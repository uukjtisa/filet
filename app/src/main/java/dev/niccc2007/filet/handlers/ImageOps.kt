package dev.niccc2007.filet.handlers

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The image editor's arithmetic, with no Android in it.
 *
 * Every operation here is a pure function over a pixel buffer, which is what makes the
 * editor checkable: `ImageOpsTest` runs on the JVM and compares actual pixels, so a flipped
 * axis or an off-by-one crop fails a build rather than quietly producing a ruined photo.
 * The Compose layer in `ImageEditor.kt` does nothing but move bytes in and out of here.
 *
 * Buffers are packed ARGB, the same layout `Bitmap.getPixels` hands out, so the adapter is a
 * copy in and a copy out with no conversion in between.
 */
class Pixels(val width: Int, val height: Int, val argb: IntArray) {

    init {
        require(width > 0 && height > 0) { "empty image: ${width}x$height" }
        require(argb.size == width * height) { "buffer is ${argb.size}, expected ${width * height}" }
    }

    operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    companion object {
        /** A buffer filled by a function of the coordinates. Used by the tests and by stretch. */
        fun of(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): Pixels {
            val out = IntArray(width * height)
            var i = 0
            for (y in 0 until height) for (x in 0 until width) out[i++] = pixel(x, y)
            return Pixels(width, height, out)
        }
    }
}

/** Which way round. Named rather than a degree count so a call site cannot pass 45. */
enum class Turn(val quarterTurnsClockwise: Int) { LEFT(3), RIGHT(1), HALF(2) }

/**
 * Rotate in quarter turns.
 *
 * A left turn is three right turns and is written that way on purpose: one mapping to get
 * right rather than two that have to agree with each other.
 */
fun Pixels.rotated(turn: Turn): Pixels {
    var out = this
    repeat(turn.quarterTurnsClockwise) { out = out.rotatedRight() }
    return out
}

private fun Pixels.rotatedRight(): Pixels =
    // Clockwise, written as the inverse map because that is the direction the fill runs in:
    // the source pixel at (sx, sy) lands at (height-1-sy, sx), so reading backwards from a
    // destination pixel (x, y) means taking the source at (y, height-1-x). Note the source
    // column comes from the destination ROW - getting that the wrong way round still type
    // checks and still fills the buffer, it just indexes out of bounds on a non-square image.
    Pixels.of(height, width) { x, y -> this[y, height - 1 - x] }

fun Pixels.flipped(horizontal: Boolean): Pixels =
    if (horizontal) Pixels.of(width, height) { x, y -> this[width - 1 - x, y] }
    else Pixels.of(width, height) { x, y -> this[x, height - 1 - y] }

/**
 * Invert the colour channels and leave alpha alone.
 *
 * Inverting alpha as well is the classic bug here: it turns a transparent PNG background
 * opaque and an opaque subject invisible, which looks like a decode failure rather than an
 * effect.
 */
fun Pixels.inverted(): Pixels {
    val out = IntArray(argb.size)
    for (i in argb.indices) {
        val p = argb[i]
        out[i] = (p and ALPHA) or (p.inv() and RGB)
    }
    return Pixels(width, height, out)
}

/** Greyscale by luminance, not by averaging - the eye weighs green far more than blue. */
fun Pixels.greyscale(): Pixels {
    val out = IntArray(argb.size)
    for (i in argb.indices) {
        val p = argb[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val y = ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
        out[i] = (p and ALPHA) or (y shl 16) or (y shl 8) or y
    }
    return Pixels(width, height, out)
}

/** A rectangle in image pixels: always inside the image, always at least one pixel. */
data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right get() = left + width
    val bottom get() = top + height
}

/**
 * Turn two dragged corners into a rectangle that can actually be cropped.
 *
 * Three separate things go wrong if this is done at the call site, and all three have to be
 * handled before the rectangle is used: the corners arrive in whatever order the finger made
 * them, a drag that leaves the image gives coordinates outside it, and a tap rather than a
 * drag gives a rectangle of zero area that would crash the allocation.
 */
fun cropRect(x0: Float, y0: Float, x1: Float, y1: Float, imageWidth: Int, imageHeight: Int): CropRect {
    val l = min(x0, x1).roundToInt().coerceIn(0, imageWidth - 1)
    val t = min(y0, y1).roundToInt().coerceIn(0, imageHeight - 1)
    val r = max(x0, x1).roundToInt().coerceIn(0, imageWidth)
    val b = max(y0, y1).roundToInt().coerceIn(0, imageHeight)
    return CropRect(l, t, max(1, r - l), max(1, b - t))
}

/** @throws IllegalArgumentException if the rectangle is not inside the image. Use [cropRect]. */
fun Pixels.cropped(rect: CropRect): Pixels {
    require(rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height) {
        "crop $rect is outside ${width}x$height"
    }
    return Pixels.of(rect.width, rect.height) { x, y -> this[rect.left + x, rect.top + y] }
}

/**
 * Resample to a new size, bilinearly.
 *
 * Bilinear rather than nearest because the visible use is stretching a small image up, and
 * nearest on an upscale produces hard blocks that read as a broken filter. The corner pixels
 * land exactly on the corner samples, which is what the test pins.
 */
fun Pixels.stretched(newWidth: Int, newHeight: Int): Pixels {
    require(newWidth > 0 && newHeight > 0) { "empty target: ${newWidth}x$newHeight" }
    if (newWidth == width && newHeight == height) return this
    // Map so that source 0 and source (size-1) land on target 0 and target (size-1). Dividing
    // by the target size instead would drift the far edge by half a pixel per axis.
    val sx = if (newWidth == 1) 0f else (width - 1).toFloat() / (newWidth - 1)
    val sy = if (newHeight == 1) 0f else (height - 1).toFloat() / (newHeight - 1)
    return Pixels.of(newWidth, newHeight) { x, y ->
        val fx = x * sx
        val fy = y * sy
        val x0 = fx.toInt().coerceAtMost(width - 1)
        val y0 = fy.toInt().coerceAtMost(height - 1)
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        blend(this[x0, y0], this[x1, y0], this[x0, y1], this[x1, y1], fx - x0, fy - y0)
    }
}

private fun blend(tl: Int, tr: Int, bl: Int, br: Int, dx: Float, dy: Float): Int {
    var out = 0
    for (shift in intArrayOf(24, 16, 8, 0)) {
        val a = (tl shr shift) and 0xFF
        val b = (tr shr shift) and 0xFF
        val c = (bl shr shift) and 0xFF
        val d = (br shr shift) and 0xFF
        val top = a + (b - a) * dx
        val bottom = c + (d - c) * dx
        val v = (top + (bottom - top) * dy).roundToInt().coerceIn(0, 255)
        out = out or (v shl shift)
    }
    return out
}

/**
 * A name for the edited copy, which is never the name of the original.
 *
 * An image editor that writes over its input is a data-loss bug, not a feature, and it is the
 * one mistake in this file that cannot be undone by tapping again. So the save path does not
 * get to choose: it asks for a name here, and the answer is always a new one.
 *
 * @param extension without the dot, to override the original's. The editor re-encodes, and a
 *   JPEG written under a `.heic` name is a file nothing will open. Null keeps what was there.
 * @param taken says whether a name already exists in the destination folder. Called until it
 *   says no, so a folder full of `photo-edit-1..40.png` still gets a fresh name.
 */
fun editedName(original: String, extension: String? = null, taken: (String) -> Boolean): String {
    val dot = original.lastIndexOf('.')
    val stem = if (dot > 0) original.substring(0, dot) else original
    val ext = when {
        extension != null -> ".${extension.removePrefix(".")}"
        dot > 0 -> original.substring(dot)
        else -> ""
    }
    // Strip an edit marker the stem already carries, so editing "photo-edit.png" again gives
    // "photo-edit-2.png" rather than "photo-edit-edit.png" growing a suffix per save.
    val base = ALREADY_EDITED.matchEntire(stem)?.groupValues?.get(1) ?: stem
    var n = 1
    while (true) {
        val candidate = if (n == 1) "$base$EDIT_SUFFIX$ext" else "$base$EDIT_SUFFIX-$n$ext"
        if (candidate != original && !taken(candidate)) return candidate
        n++
        require(n < 10_000) { "no free name for $original" }
    }
}

private const val EDIT_SUFFIX = "-edit"
private val ALREADY_EDITED = Regex("""^(.*)-edit(?:-\d+)?$""")
private const val ALPHA = 0xFF shl 24
private const val RGB = 0x00FFFFFF

/**
 * Where a "fit inside, keep the shape" image actually lands in its box.
 *
 * The crop rectangle and the drawing strokes both live in image pixels while the finger lives
 * in view pixels, and this is the only conversion between them. It is worth its own function
 * and its own test because getting it slightly wrong does not look broken - the crop just
 * takes a rectangle a few pixels away from the one that was drawn, which is the kind of bug
 * that gets shipped.
 */
data class FitBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    /** Zero until the box has been measured. [ready] is the readable way to ask. */
    val scale: Float,
) {
    val ready: Boolean get() = scale > 0f
}

fun fitInside(boxWidth: Float, boxHeight: Float, imageWidth: Int, imageHeight: Int): FitBox {
    // Compose reports 0x0 for a frame before layout lands, and gesture handlers run against
    // whatever that frame left behind. A zero scale says "not yet" and every conversion below
    // returns a harmless zero rather than a coordinate derived from a box that does not exist.
    if (boxWidth <= 0f || boxHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) {
        return FitBox(0f, 0f, 0f, 0f, 0f)
    }
    val scale = min(boxWidth / imageWidth, boxHeight / imageHeight)
    val w = imageWidth * scale
    val h = imageHeight * scale
    return FitBox((boxWidth - w) / 2f, (boxHeight - h) / 2f, w, h, scale)
}

/**
 * A view coordinate as an image pixel, clamped to the image.
 *
 * Clamped rather than rejected: a finger that slides off the picture while dragging a crop
 * corner should pin that corner to the edge, not drop the drag.
 */
fun FitBox.toImageX(viewX: Float, imageWidth: Int): Float =
    if (scale <= 0f) 0f else ((viewX - left) / scale).coerceIn(0f, imageWidth.toFloat())

fun FitBox.toImageY(viewY: Float, imageHeight: Int): Float =
    if (scale <= 0f) 0f else ((viewY - top) / scale).coerceIn(0f, imageHeight.toFloat())

/** And back again, for drawing the crop frame over the picture. */
fun FitBox.toViewX(imageX: Float): Float = left + imageX * scale

fun FitBox.toViewY(imageY: Float): Float = top + imageY * scale

/**
 * How far a pinch-zoomed image may be panned before it is pulled back.
 *
 * Shared with the viewer so the limit is one rule rather than a guess in each gesture handler:
 * at 1x there is nothing to pan and the offset is zero; past that, the image may be moved by
 * exactly the amount of it that has been pushed off screen.
 */
fun clampPan(offset: Float, scale: Float, viewportSize: Float): Float {
    if (scale <= ZoomView.FLAT) return 0f
    val slack = viewportSize * (scale - 1f) / 2f
    return offset.coerceIn(-slack, slack)
}

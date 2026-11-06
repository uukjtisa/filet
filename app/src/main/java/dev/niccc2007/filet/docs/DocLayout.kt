package dev.niccc2007.filet.docs

import android.os.Build
import dev.niccc2007.filet.vfs.types.FileTypes
import dev.niccc2007.filet.vfs.types.TypeKind
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The decisions the document screen makes that can be wrong, kept apart from Compose so they
 * can be tested without a phone.
 */
internal object DocLayout {

    /** Where a block sits in a slide's card. */
    enum class Frame { NONE, TOP, MIDDLE, BOTTOM, ONLY }

    /**
     * Which blocks belong to which slide card.
     *
     * A deck is a flat list - a [Block.Slide] marker, the slide's blocks, its notes, the next
     * marker - because the model is shared with formats that have no slides. The screen still
     * has to draw each slide as one separate card, so this recovers the grouping: a card opens
     * on a marker and closes before the next one, or at the end of the document.
     */
    fun slideFrames(blocks: List<Block>): List<Frame> {
        val out = MutableList(blocks.size) { Frame.NONE }
        var i = 0
        while (i < blocks.size) {
            if (blocks[i] !is Block.Slide) { i++; continue }
            var end = i + 1
            while (end < blocks.size && blocks[end] !is Block.Slide) end++
            val last = end - 1
            for (k in i..last) {
                out[k] = when {
                    i == last -> Frame.ONLY
                    k == i -> Frame.TOP
                    k == last -> Frame.BOTTOM
                    else -> Frame.MIDDLE
                }
            }
            i = end
        }
        return out
    }

    /**
     * Whether text in [argb] can be read on [ground], by the WCAG contrast ratio.
     *
     * Bug identified here before it shipped: a document's colours were chosen for white paper.
     * Black body text set explicitly as `000000` - which Word does constantly - drawn on the
     * dark theme is invisible, and a navy heading is very nearly so. A colour that fails this
     * is dropped and the screen's own text colour is used instead, which keeps every colour
     * that was a choice about emphasis and loses only the ones that were about paper.
     *
     * 3:1 rather than 4.5:1: this is deciding whether to keep an author's colour at all, not
     * grading body text, and a red that is plainly legible on dark grey sits around 3.5.
     */
    fun readable(argb: Long, ground: Long): Boolean = contrast(argb, ground) >= 3.0

    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** Black or white, whichever reads on a highlighter of [argb]. */
    fun inkOn(argb: Long): Long =
        if (contrast(0xFF000000L, argb) >= contrast(0xFFFFFFFFL, argb)) 0xFF000000L else 0xFFFFFFFFL

    /**
     * How wide a picture is drawn, in dp, from the size the document places it at.
     *
     * 9525 EMU is one CSS pixel at 96 dpi, which is the scale Word and PowerPoint use for "100%",
     * and a CSS pixel is near enough a dp. 0 means the file did not say and the picture takes the
     * width; a picture is never drawn larger than the column.
     */
    fun pictureWidthDp(emuWidth: Long): Float = if (emuWidth <= 0) 0f else emuWidth / 9525f

    /** The power-of-two step that brings a picture down to at most [maxPx] wide when decoded. */
    fun sampleSize(width: Int, maxPx: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxPx) sample *= 2
        return sample
    }

    /**
     * Whether a picture in a document is one Android's decoder cannot draw, so it is named
     * instead of drawn: a vector format (SVG, EMF, WMF, EPS), or one this phone has no decoder
     * for (TIFF, PICT, HEIC before Android 9). Both are the file type table's to say.
     */
    fun undrawable(format: String, sdk: Int = Build.VERSION.SDK_INT): Boolean {
        val type = FileTypes.of(format) ?: return false
        return type.kind == TypeKind.IMAGE && (type.vector || !FileTypes.decodesOn(type, sdk))
    }
}

/**
 * The document reader's zoom, as arithmetic.
 *
 * ## The bounds
 *
 * [MIN] 0.75: body text is 13 sp, and below about 10 sp it stops being reading and becomes
 * squinting - zooming out only exists to see more of a table or a slide at once. [MAX] 3.0: at
 * that scale a phone column holds four or five words a line, which is the most a reflowed
 * document can still be read at; past it every line is a word and the gesture has stopped
 * helping.
 *
 * ## Double-tap
 *
 * Steps through [STEPS] and then back to 1. Steps, not a toggle, because a table read at 1.5x is
 * the common case and a toggle straight to 2.5x overshoots it; back to 1 from anywhere past the
 * last step, so a double-tap always has an obvious way home.
 */
internal object DocZoom {
    const val MIN = 0.75f
    const val MAX = 3.0f
    val STEPS = floatArrayOf(1.5f, 2.25f)

    fun clamp(z: Float): Float = z.coerceIn(MIN, MAX)

    /** One pinch frame: the current zoom times the frame's ratio, kept in bounds. */
    fun pinch(current: Float, ratio: Float): Float = clamp(current * ratio)

    /** The next double-tap level. */
    fun doubleTap(current: Float): Float = STEPS.firstOrNull { it > current + 0.01f } ?: 1f

    /**
     * Where an item's top must move so the content under [focalY] stays under it.
     *
     * The point under the fingers sits `focalY - itemTop` into the item. Scaled by [ratio] it
     * is that far times the ratio into the item, so the item's top goes to `focalY` minus that.
     * Getting this wrong is what makes a pinch feel broken: the text slides away from under the
     * fingers as it grows, and the reader chases it.
     *
     * Exact for content that scales uniformly. Reflowed text changes its line count as it
     * grows, so for a paragraph this is the right first answer and the residue is a line.
     */
    fun anchoredTop(focalY: Float, itemTop: Float, ratio: Float): Float = focalY - (focalY - itemTop) * ratio
}

/**
 * A pinch, gathered between frames and applied once per frame.
 *
 * ## The freeze this replaces
 *
 * Bug identified here: the pinch wrote the zoom and launched a scroll for EVERY touch event, and a
 * phone sends several per frame. Each scroll is a `scrollToItem` and a `scrollBy`, and each of
 * those forces the list to measure again on the spot, at the new density - so one frame paid for
 * three or four full layouts of every visible block and the main thread fell behind the fingers.
 * The pinch felt stuck, then jumped.
 *
 * Now a touch event only multiplies its ratio in here. Once a frame the reader [take]s what has
 * gathered, sets the zoom once and scrolls once. Ratios multiply, so nothing is lost by gathering
 * them, and the focal point is the latest one, which is where the fingers are now.
 */
internal class PinchFrame {
    private var ratio = 1f
    private var focal = Float.NaN
    private var pending = false
    private var moved = false

    /** Two fingers are down. */
    var active = false
        private set

    fun begin() { active = true }

    fun end() { active = false }

    /**
     * True once, after a pinch that changed the zoom has ended and its last frame is applied:
     * the moment to remember the size. Saving on every frame would write the settings sixty
     * times a second; saving on lift alone would miss the frame still gathering.
     */
    fun settled(): Boolean {
        if (active || pending || !moved) return false
        moved = false
        return true
    }

    /** One touch event's zoom ratio, around [focalY]. */
    fun add(eventRatio: Float, focalY: Float) {
        if (eventRatio.isNaN() || eventRatio <= 0f) return
        ratio *= eventRatio
        focal = focalY
        pending = true
    }

    /**
     * What gathered since the last frame, applied to [current]: the new zoom and the ratio it
     * actually moved by, which the clamp can make smaller than what the fingers did. Null when
     * nothing gathered, or when the zoom is pinned at a bound and the fingers push against it.
     */
    fun take(current: Float): Step? {
        if (!pending) return null
        val after = DocZoom.pinch(current, ratio)
        val f = focal
        ratio = 1f
        pending = false
        if (after == current) return null
        moved = true
        return Step(after, after / current, f)
    }

    data class Step(val zoom: Float, val ratio: Float, val focalY: Float)
}

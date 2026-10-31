package dev.niccc2007.filet.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/**
 * A brightness plane: one byte a pixel, [stride] bytes a row. What a camera hands over as its Y
 * plane, and what a photo becomes before it is searched.
 */
class Luma(val data: ByteArray, val width: Int, val height: Int, val stride: Int = width) {

    val longest: Int get() = maxOf(width, height)

    /**
     * This plane at [side] pixels on its longest edge, each pixel the AVERAGE of the ones it
     * covers. Itself when it is no bigger than that already.
     *
     * An average and not a pick of every nth pixel, because the average is the point: the noise
     * that stops a code reading - the interference between a screen's pixel grid and the
     * camera's, the speckle of glare on a printed sticker - is finer than a module of the code,
     * and averaging is what removes detail finer than the new pixel. Sampling would keep the
     * noise and drop the rest.
     *
     * @param scratch reused for the result when given, so a camera frame does not allocate.
     */
    fun shrunkTo(side: Int, scratch: Scratch? = null): Luma {
        if (side >= longest || side <= 0) return this
        val w = maxOf(1, (width.toLong() * side / longest).toInt())
        val h = maxOf(1, (height.toLong() * side / longest).toInt())
        val out = scratch?.take(w * h) ?: ByteArray(w * h)
        val xs = IntArray(w + 1) { (it.toLong() * width / w).toInt() }
        for (y in 0 until h) {
            val y0 = (y.toLong() * height / h).toInt()
            val y1 = maxOf(y0 + 1, ((y + 1).toLong() * height / h).toInt())
            for (x in 0 until w) {
                val x0 = xs[x]
                val x1 = maxOf(x0 + 1, xs[x + 1])
                var sum = 0
                for (yy in y0 until y1) {
                    val row = yy * stride
                    for (xx in x0 until x1) sum += data[row + xx].toInt() and 0xFF
                }
                out[y * w + x] = (sum / ((x1 - x0) * (y1 - y0))).toByte()
            }
        }
        return Luma(out, w, h)
    }

    /** A buffer kept between calls, grown when a bigger plane needs it. */
    class Scratch {
        private var bytes = ByteArray(0)
        fun take(size: Int): ByteArray {
            if (bytes.size < size) bytes = ByteArray(size)
            return bytes
        }
    }

    companion object {
        /** The brightness zxing itself takes from a colour pixel: (red + 2 green + blue) / 4. */
        fun of(argb: Int): Byte =
            (((argb shr 16) and 0xFF) + 2 * ((argb shr 8) and 0xFF) + (argb and 0xFF)).div(4).toByte()
    }
}

/** One attempt at a code: the picture at [side] pixels on its longest edge, through one of zxing's two binarizers. */
data class QrPass(val side: Int, val global: Boolean)

/**
 * The attempts made at a picture, in order, and the reading of them.
 *
 * ## Why more than one
 *
 * Bug identified here: the decoder read every picture once, at the size it was loaded, through
 * one binarizer - and a photo of a code on a laptop screen and a photo of a payment sticker under
 * shop lighting both failed at that size while reading in a few milliseconds at half of it. The
 * fault was never the code. A screen photographed up close is covered in moire, and glare on a
 * printed code is speckle; both are finer than a module, so at full size the binarizer sees them
 * as black and white detail, cuts the finder squares into pieces and finds nothing. Shrunk with
 * an average, that detail is gone and the code is plain.
 *
 * There is no one size that is right. Measured on those two photos, the screen read at every
 * size from 1024 down and the sticker read at 1280, 720 and 640 but not at 1024 or 800 - where it
 * reads depends on how the noise happens to line up with the new pixels. So a photo is tried at
 * a falling ladder of sizes, as zxing-cpp does with its `tryDownscale`, and at each size through
 * both binarizers: the hybrid one judges each patch by its neighbours, which survives a shadow
 * across the code; the global one judges each row by the whole, which survives speckle the
 * hybrid one is fooled by. They fail on different pictures, which is the reason to have both.
 *
 * The picture as loaded goes first, through the hybrid binarizer, so a code that read before
 * still reads on the first attempt and costs what it always did. Each shrunk attempt is a
 * fraction of that cost, so a photo with no code in it pays roughly half as much again to be
 * sure.
 */
object QrLadder {

    /** The sizes a photo is also tried at, below the one it was loaded at. */
    val PHOTO_SIDES = listOf(1024, 800, 640, 480, 360)

    fun photo(longest: Int): List<QrPass> = buildList {
        add(QrPass(longest, global = false))
        add(QrPass(longest, global = true))
        for (side in PHOTO_SIDES) if (side < longest) {
            add(QrPass(side, global = false))
            add(QrPass(side, global = true))
        }
    }

    /**
     * What one camera frame is tried as: the frame as it is through the hybrid binarizer, which
     * is what the scanner always did, and one other attempt taken in turn from [FRAME_TURNS].
     *
     * Not the whole ladder on every frame. The next frame is tens of milliseconds away and the
     * analyser keeps only the latest, so a frame that takes long to search is a frame that makes
     * the ones after it get dropped. Two attempts a frame, with the second rotating, tries every
     * attempt within four frames - well under a second - at little more than the old cost.
     */
    fun frame(index: Long, longest: Int): List<QrPass> {
        val (divisor, global) = FRAME_TURNS[Math.floorMod(index, FRAME_TURNS.size.toLong()).toInt()]
        return listOf(QrPass(longest, global = false), QrPass(longest / divisor, global))
    }

    /** The second attempt of each frame, in turn: (the frame divided by, global binarizer). */
    val FRAME_TURNS = listOf(2 to false, 1 to true, 2 to true, 3 to false)

    private val PHOTO_HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        // White on black: plenty of payment and Wi-Fi stickers are printed inverted.
        DecodeHintType.ALSO_INVERTED to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * A camera frame is searched without TRY_HARDER. A frame that does not read easily is
     * cheaper to skip than to labour over: the next one is moments away, and labouring holds the
     * analyser while the frames that WOULD have read are dropped.
     */
    private val FRAME_HINTS = PHOTO_HINTS - DecodeHintType.TRY_HARDER

    /**
     * The text of the first code found by [passes] over [luma], or null.
     *
     * @param thorough a photo, searched with TRY_HARDER; otherwise a camera frame.
     * @param keepGoing asked between attempts, so a search nobody is waiting for any more stops.
     */
    fun read(
        luma: Luma,
        passes: List<QrPass>,
        thorough: Boolean,
        scratch: Luma.Scratch? = null,
        keepGoing: () -> Boolean = { true },
    ): String? {
        val reader = MultiFormatReader().apply { setHints(if (thorough) PHOTO_HINTS else FRAME_HINTS) }
        var plane = luma
        for (pass in passes) {
            if (!keepGoing()) return null
            // The two binarizers at one size share one shrink.
            if (plane.longest != minOf(pass.side, luma.longest)) plane = luma.shrunkTo(pass.side, scratch)
            val source = PlanarYUVLuminanceSource(plane.data, plane.stride, plane.height, 0, 0, plane.width, plane.height, false)
            val bits = BinaryBitmap(if (pass.global) GlobalHistogramBinarizer(source) else HybridBinarizer(source))
            val text = try {
                reader.decodeWithState(bits).text
            } catch (_: Exception) {
                // NotFound, Checksum and Format all mean the same here: not at this size.
                null
            } finally {
                reader.reset()
            }
            if (text != null) return text
        }
        return null
    }
}

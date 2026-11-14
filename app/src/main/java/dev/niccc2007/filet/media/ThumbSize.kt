package dev.niccc2007.filet.media

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * What size a preview is decoded at. Pure, so every rule here is a test.
 *
 * Bug identified here: gallery and list thumbnails looked low-resolution next to the phone's own
 * gallery, and three things stacked to make them so:
 *
 * - **The size was a guess at the screen.** A slot was decoded at 2.5 pixels per dp, capped at
 *   320. This phone draws 2.75 pixels per dp, so every picture was stretched before it was shown.
 *   [forSlot] reads the real density.
 * - **The wrong edge was measured.** The longest edge was brought down to the slot, and the tile
 *   then cropped the picture to a square - so the square was filled from the SHORT edge, which for
 *   a phone photo is three-quarters of the slot or less, and was stretched again. A square preview
 *   is now sized on its short edge ([scaled]) and cropped once, at decode.
 * - **Half the colour was thrown away.** Previews were decoded as RGB_565, which bands every sky
 *   and every face into steps. That choice belongs to Thumbnails, and is now ARGB_8888.
 */
internal object ThumbSize {

    /** The step decode sizes are rounded up to, so slots a few dp apart share one cached picture. */
    const val STEP = 32

    const val MIN = 48

    /** The largest preview a slot gets; past this the slot is a viewer, not a thumbnail. */
    const val MAX = 512

    /** The decode size for a slot [slotDp] wide on a screen of [density] pixels per dp. */
    fun forSlot(slotDp: Float, density: Float): Int {
        val raw = slotDp * density.coerceAtLeast(1f)
        val stepped = (ceil(raw / STEP).toInt() * STEP)
        return stepped.coerceIn(MIN, MAX)
    }

    /**
     * The power of two to sample by so the measured edge stays at or above [px]: the short edge
     * for a [square] preview, which is cropped from the middle, the long one for a whole picture.
     * `inSampleSize` only honours powers of two, so overshooting and scaling once afterwards is
     * both faster and sharper than asking for an exact size.
     */
    fun sample(width: Int, height: Int, px: Int, square: Boolean): Int {
        if (width <= 0 || height <= 0 || px <= 0) return 1
        var edge = if (square) minOf(width, height) else maxOf(width, height)
        var sample = 1
        while (edge / 2 >= px) {
            edge /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * The size to scale a [width] x [height] picture to before cropping: the short edge at [px]
     * for a [square] preview, the long edge at [px] otherwise. Never larger than it already is -
     * a small picture scaled up gains nothing and costs memory.
     */
    fun scaled(width: Int, height: Int, px: Int, square: Boolean): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val edge = if (square) minOf(width, height) else maxOf(width, height)
        if (edge <= px) return width to height
        val ratio = px.toFloat() / edge
        return (width * ratio).roundToInt().coerceAtLeast(1) to (height * ratio).roundToInt().coerceAtLeast(1)
    }

    /**
     * The box to ask the system for, so that a picture of [width] x [height] fitted inside it
     * still reaches [px] on the edge that matters: the short one for a [square] preview, which is
     * cropped from the middle, the long one otherwise. Zero when the shape is so long that the box
     * would be past twice [MAX] - the system would hand back the whole file to decode, which is
     * the work the system preview was meant to save. Unknown dimensions ask for [px] and let
     * [covers] judge what comes back.
     */
    fun boxFor(width: Int, height: Int, px: Int, square: Boolean): Int {
        if (width <= 0 || height <= 0 || !square) return px
        val long = maxOf(width, height)
        val short = minOf(width, height)
        val box = ceil(px.toDouble() * long / short).toInt()
        return if (box > MAX * 2) 0 else box
    }

    /**
     * Whether a picture [width] x [height] fills a [px] slot without being stretched more than a
     * tenth: on its short edge for a [square] preview, its long edge otherwise. A tenth is under
     * what shows on a phone screen; past it the preview reads as soft beside the gallery's.
     */
    fun covers(width: Int, height: Int, px: Int, square: Boolean): Boolean {
        if (width <= 0 || height <= 0) return false
        val edge = if (square) minOf(width, height) else maxOf(width, height)
        return edge * 10 >= px * 9
    }

    /** The centred square to keep from a [width] x [height] picture: left, top and side. */
    fun crop(width: Int, height: Int): Triple<Int, Int, Int> {
        val side = minOf(width, height)
        return Triple((width - side) / 2, (height - side) / 2, side)
    }
}

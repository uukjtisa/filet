package dev.niccc2007.filet.playback

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The colour the player borrows from whatever is playing, with a floor under it.
 *
 * ## Why not just average the artwork
 *
 * Averaging a picture gives mud. Average a room lit green and you get a grey-green that is
 * neither the green nor usefully neutral, and on a dark panel it is invisible. The pixels
 * that carry the identity of an image are the saturated ones, and there are usually few of
 * them, so an unweighted mean lets the shadows outvote them every time.
 *
 * So the hue is a **circular mean weighted towards saturated, non-black pixels**. Circular
 * because hue wraps: the ordinary mean of 350 and 10 is 180, which is the exact opposite of
 * the answer.
 *
 * ## Why there is a contrast floor, and why it is not optional
 *
 * The colour is used to fill the play button and to tint the active controls. A dark track
 * sampled honestly produces a dark colour, and a dark button on a dark panel is a control
 * nobody can see - which is worse than the fixed accent it replaced. So the result is
 * lightened until it clears [MIN_RATIO] against the panel it will sit on, and the glyph
 * drawn ON it is black or white by whichever of the two wins against it.
 *
 * That is the whole bargain: the colour may be adjusted away from the artwork, and it may
 * not be unreadable.
 *
 * Pure, and tested, because every one of those decisions is wrong in a way that only shows
 * up on somebody else's album art.
 */
object ArtPalette {

    /** The readable-text threshold. More than enough for a filled control. */
    const val MIN_RATIO = 4.5

    /** Below this the sample is not a colour, it is noise, and the theme accent is better. */
    const val MIN_WEIGHT = 0.004

    /** What the player's controls sit on, for the contrast test. */
    const val PANEL_R = 0x16
    const val PANEL_G = 0x16
    const val PANEL_B = 0x14

    /**
     * @param colour the tint for filled and active controls, as 0xRRGGBB.
     * @param onColour what to draw on top of [colour]: black or white.
     * @param usable false when the artwork had no colour worth taking, so the caller keeps
     *   the theme accent rather than being handed a guess.
     */
    data class Result(val colour: Int, val onColour: Int, val usable: Boolean)

    val NONE = Result(0, 0, usable = false)

    /**
     * Sample a already-downscaled grid of pixels.
     *
     * Takes packed 0xRRGGBB ints rather than a Bitmap so the arithmetic is testable on a
     * plain JVM. The caller downscales - a 24x24 is plenty and costs nothing next to
     * decoding the artwork in the first place.
     */
    fun of(pixels: IntArray): Result {
        if (pixels.isEmpty()) return NONE
        var x = 0.0
        var y = 0.0
        var satSum = 0.0
        var weight = 0.0
        for (p in pixels) {
            val (h, sat, l) = hsl(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)
            // Saturation decides how much a pixel counts, and lightness gates it, so the
            // handful of lit pixels in a dark frame choose the hue instead of the shadows.
            val w = sat.pow(1.4) * min(1.0, l * 2.4)
            if (w <= 0.0) continue
            val rad = Math.toRadians(h)
            x += cos(rad) * w
            y += sin(rad) * w
            satSum += sat * w
            weight += w
        }
        if (weight < MIN_WEIGHT * pixels.size) return NONE

        var hue = Math.toDegrees(atan2(y / weight, x / weight))
        if (hue < 0) hue += 360.0
        // Pushed up, because a sampled saturation is always lower than the colour somebody
        // would have picked by eye - and clamped, because a fully saturated control looks
        // like a warning rather than a theme.
        val sat = (satSum / weight * 1.5).coerceIn(0.42, 0.82)

        var light = 0.62
        var rgb = fromHsl(hue, sat, light)
        var guard = 0
        while (contrast(rgb, panel()) < MIN_RATIO && light < 0.93 && guard++ < 64) {
            light += 0.02
            rgb = fromHsl(hue, sat, light)
        }
        val on = if (contrast(rgb, 0x000000) >= contrast(rgb, 0xFFFFFF)) 0x12110F else 0xFFFFFF
        return Result(rgb, on, usable = true)
    }

    /**
     * The same hue, as a GROUND rather than as a control.
     *
     * [of] lightens until the colour clears 4.5:1 against a dark panel, because what it
     * produces fills a 68dp play button that has to be findable. Painting a whole
     * notification card with that is a slab of neon - reported in exactly those words - and
     * the reason is that the two jobs want opposite lightnesses: a small shape on a dark
     * screen has to be brighter than its ground, and a large area that text sits ON has to
     * be darker than the text.
     *
     * So this takes the hue and the saturation the sampler found and re-renders them at
     * [GROUND_L], which is dim enough that the system's own "is this background dark"
     * test picks white text over it, and saturated enough that the card is still obviously
     * the colour of the track rather than grey.
     *
     * Returns 0 for an unusable sample, so a caller can leave the card alone.
     */
    fun ground(result: Result): Int {
        if (!result.usable) return 0
        val (h, s, _) = hsl(
            result.colour shr 16 and 0xFF,
            result.colour shr 8 and 0xFF,
            result.colour and 0xFF,
        )
        // Saturation is pulled DOWN as well as the lightness. A deep colour at the
        // control's 0.82 saturation reads as an alert; big areas want less of it.
        return fromHsl(h, (s * 0.72).coerceIn(0.22, 0.60), GROUND_L)
    }

    /**
     * How dark a card's ground is.
     *
     * 0.19 puts every hue's luminance well under the 0.5 the platform splits light and dark
     * text on, so white text is chosen on a yellow track as surely as on a blue one - and a
     * notification whose text colour flips between tracks is the thing this avoids.
     */
    const val GROUND_L = 0.19

    /**
     * The same colour, a little further round the wheel.
     *
     * The living glow on screen is three lights drifting over each other, which a
     * notification cannot do - the shade draws a flat card from one colour. So the card's
     * colour itself is what moves: the hue swings [DRIFT_DEG] either side of the sampled one
     * and the lightness breathes, and the service re-posts the card every few seconds at the
     * next phase. Slow enough that nobody watching sees it change, obvious enough that a
     * glance five minutes apart is a different colour.
     *
     * The lightness swing is on a different period from the hue - an irrational ratio, so the
     * two never arrive together. Both at once reads as a pulse, which is a notification
     * demanding attention rather than a colour that is alive.
     *
     * Bounded on purpose. [GROUND_L] is dark enough that the platform picks white text over
     * every hue, and a drift that could lift it past 0.5 would flip the text colour mid-track.
     *
     * @param phase 0 to 1 is one full out-and-back.
     */
    fun drift(rgb: Int, phase: Double): Int {
        val (h, s, l) = hsl(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
        val t = phase * 2.0 * Math.PI
        val hue = ((h + DRIFT_DEG * sin(t)) % 360.0 + 360.0) % 360.0
        val light = (l * (1.0 + DRIFT_L * sin(t * 1.618))).coerceIn(0.06, 0.44)
        return fromHsl(hue, s, light)
    }

    /** How far the drifting card's hue travels either side of the artwork's own. */
    const val DRIFT_DEG = 16.0

    /** How far its lightness breathes, as a fraction of itself. */
    const val DRIFT_L = 0.12

    fun panel(): Int = (PANEL_R shl 16) or (PANEL_G shl 8) or PANEL_B

    /** WCAG relative luminance. */
    fun luminance(rgb: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(rgb shr 16 and 0xFF) +
            0.7152 * channel(rgb shr 8 and 0xFF) +
            0.0722 * channel(rgb and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun hsl(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
        val rf = r / 255.0
        val gf = g / 255.0
        val bf = b / 255.0
        val mx = maxOf(rf, gf, bf)
        val mn = minOf(rf, gf, bf)
        val d = mx - mn
        var h = 0.0
        if (d > 0.0) {
            h = when (mx) {
                rf -> ((gf - bf) / d) % 6
                gf -> (bf - rf) / d + 2
                else -> (rf - gf) / d + 4
            } * 60.0
            if (h < 0) h += 360.0
        }
        val l = (mx + mn) / 2
        val s = if (d == 0.0) 0.0 else d / (1 - abs(2 * l - 1))
        return Triple(h, s, l)
    }

    private fun fromHsl(h: Double, s: Double, l: Double): Int {
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60.0) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun byte(v: Double) = ((v + m) * 255).toInt().coerceIn(0, 255)
        return (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    /**
     * The same answer, taken straight off a bitmap.
     *
     * Here rather than in the composable so the notification can reach it: the shade is built
     * in a Service with no composition, and the one thing worse than an uncoloured card is a
     * card coloured by a second implementation that drifts from the screen's.
     *
     * A 24x24 downscale and a pass over 576 pixels - this is looking for the dominant hue,
     * not for detail. It is NOT safe to call on a hot path and is not meant to be: the one
     * caller rebuilds the notification when the track changes.
     */
    fun ofBitmap(bitmap: android.graphics.Bitmap): Result {
        val small = runCatching {
            android.graphics.Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        }.getOrNull() ?: return NONE
        val px = IntArray(24 * 24)
        runCatching { small.getPixels(px, 0, 24, 0, 0, 24, 24) }.onFailure { return NONE }
        if (small !== bitmap) small.recycle()
        return of(IntArray(px.size) { px[it] and 0xFFFFFF })
    }
}

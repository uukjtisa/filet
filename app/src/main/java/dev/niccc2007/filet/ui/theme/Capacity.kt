package dev.niccc2007.filet.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The colour of a capacity bar: one solid colour, picked by how full the drive is.
 *
 * ## What it replaces
 *
 * The bar used to be the accent colour, turning into the warning colour past 85%. Two states,
 * so a drive at 20% and a drive at 80% looked identical and the only reading on offer was the
 * length of the fill. Length is hard to judge at five pixels tall across tiles of different
 * widths, and nothing is harder to compare than two bars of different lengths side by side.
 *
 * Now the whole bar is one colour drawn from a spectrum - cool blue with room to spare, through
 * teal and gold, to red when the drive is out of room. **The colour is the reading**, it needs
 * no comparison against anything, and the same percentage is the same colour on every tile
 * forever.
 *
 * A gradient along the bar was tried first and is the wrong answer: the eye reads a gradient as
 * decoration, and a bar whose own left end is a different colour from its right end invites the
 * question of which end to believe.
 *
 * ## Why this does not follow the accent palette
 *
 * Filet has four accent palettes and this ignores all of them. Fullness is semantic colour, in
 * the same family as the good and bad already in the theme: blue-to-red means one thing to
 * everybody, and rendering a capacity gauge in the monochrome palette's greys would turn a
 * warning into decoration. The bar is the one place in the app where the colour is data.
 */
object Capacity {

    /**
     * The stops, cool to hot.
     *
     * Five rather than two so the middle of the range says something, and placed where the
     * meaning changes rather than at even intervals: nothing is wrong until about two thirds,
     * the last tenth is where a copy starts failing, and the stops bunch up accordingly.
     */
    val STOPS: List<Pair<Float, Color>> = listOf(
        0.00f to Color(0xFF3E8FD8), // blue    - empty, nothing to think about
        0.38f to Color(0xFF35B5A6), // teal    - comfortable
        0.58f to Color(0xFF7FC14F), // green   - healthy
        0.74f to Color(0xFFE0C248), // yellow  - worth knowing
        0.84f to Color(0xFFE58A33), // orange  - getting tight
        0.90f to Color(0xFFD63B2C), // red     - critical
        1.00f to Color(0xFFA81D16), // deep red - out of room
    )

    // Where the bands sit is the whole decision, and the first attempt put them wrong: red was
    // the colour AT 100% only, so a drive at 90% - 23 GB left of 224, which is the point where
    // a copy starts failing - still drew the same orange the old two-state bar used. The gauge
    // agreed with itself and disagreed with the situation.
    //
    // Red now starts at 90% and deepens from there, and the useful range is spread across the
    // middle rather than bunched at the top: most drives live between a third and three
    // quarters full, and that is where a gauge has to be able to tell two drives apart.

    /**
     * The colour at a given fullness, interpolated between the stops.
     *
     * Linear in each channel. A perceptual blend would be more correct and is not worth a
     * colour-space conversion for a four-pixel bar; the stops are close enough together that
     * linear never passes through a muddy midpoint.
     */
    fun colourAt(fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        for (i in 0 until STOPS.size - 1) {
            val (lo, a) = STOPS[i]
            val (hi, b) = STOPS[i + 1]
            if (f in lo..hi) {
                val t = if (hi == lo) 0f else (f - lo) / (hi - lo)
                return Color(
                    red = a.red + (b.red - a.red) * t,
                    green = a.green + (b.green - a.green) * t,
                    blue = a.blue + (b.blue - a.blue) * t,
                    alpha = 1f,
                )
            }
        }
        return STOPS.last().second
    }
}

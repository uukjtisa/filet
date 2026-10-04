package dev.niccc2007.filet.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capacity spectrum.
 *
 * A colour that is data gets tested like data. The property that matters is not which blue it
 * starts on - it is that the thing is monotonic: as a drive fills, the bar must get warmer at
 * every step and never cool off halfway, because a gauge that dips back toward calm in the
 * middle is worse than one colour.
 */
class CapacityTest {

    @Test
    fun `the stops run from empty to full, in order, with no gaps`() {
        val stops = Capacity.STOPS
        assertEquals(0f, stops.first().first, 0f)
        assertEquals(1f, stops.last().first, 0f)
        for (i in 0 until stops.size - 1) {
            assertTrue("stop $i is not before stop ${i + 1}", stops[i].first < stops[i + 1].first)
        }
    }

    @Test
    fun `asking at a stop gives that stop's colour`() {
        for ((at, colour) in Capacity.STOPS) {
            assertEquals("at $at", colour, Capacity.colourAt(at))
        }
    }

    /** Hue in degrees: blue is about 210, green about 100, yellow about 50, red about 0. */
    private fun hue(c: androidx.compose.ui.graphics.Color): Float {
        val max = maxOf(c.red, c.green, c.blue)
        val min = minOf(c.red, c.green, c.blue)
        val d = max - min
        if (d < 1e-6f) return 0f
        val h = when (max) {
            c.red -> ((c.green - c.blue) / d).let { if (it < 0) it + 6 else it }
            c.green -> (c.blue - c.red) / d + 2
            else -> (c.red - c.green) / d + 4
        }
        return h * 60f
    }

    @Test
    fun `the hue falls all the way from blue to red and never climbs back`() {
        // The property that actually defines a blue-to-red ramp, and the second metric this
        // test has used. The first compared red against blue, which looks like "warmth" and is
        // not: a true alarm red has LESS red in it than a saturated orange does, so that metric
        // rejected the correct colour and would have kept the bar orange at the top forever.
        // Hue is the thing being ramped, so hue is the thing to assert.
        var previous = 400f
        var f = 0f
        while (f <= 1.0001f) {
            val h = hue(Capacity.colourAt(f))
            assertTrue("hue climbed back at $f ($h after $previous)", h <= previous + 0.5f)
            previous = h
            f += 0.01f
        }
    }

    @Test
    fun `empty is blue and full is red`() {
        assertTrue("an empty drive should read blue", hue(Capacity.colourAt(0f)) > 180f)
        assertTrue("a full drive should read red", hue(Capacity.colourAt(1f)) < 15f)
    }

    @Test
    fun `a drive that is nearly out of room reads red, not orange`() {
        // The report this band exists to answer: at 90% - about 23 GB left of 224, which is
        // where a large copy starts failing - the bar used to draw the same orange the old
        // two-state version used, so the extra colours changed nothing at the only point where
        // the colour matters.
        assertTrue("90% must be red", hue(Capacity.colourAt(0.90f)) < 15f)
        assertTrue("95% must be red", hue(Capacity.colourAt(0.95f)) < 15f)
        // And the bands below it must still be distinguishable rather than all orange.
        assertTrue("half full should be green", hue(Capacity.colourAt(0.50f)) in 90f..190f)
        assertTrue("three quarters should be yellow", hue(Capacity.colourAt(0.75f)) in 30f..70f)
    }

    @Test
    fun `a fraction outside the range is clamped rather than extrapolated`() {
        // A free-space figure larger than the total is a thing a network share can report, and
        // extrapolating past the last stop gives a colour outside the spectrum entirely.
        assertEquals(Capacity.colourAt(0f), Capacity.colourAt(-3f))
        assertEquals(Capacity.colourAt(1f), Capacity.colourAt(4f))
    }

    @Test
    fun `the colour between two stops is between the two colours`() {
        val mid = Capacity.colourAt(0.575f) // halfway between the 0.45 and 0.70 stops
        val lo = Capacity.STOPS[1].second
        val hi = Capacity.STOPS[2].second
        assertTrue(mid.red in minOf(lo.red, hi.red)..maxOf(lo.red, hi.red))
        assertTrue(mid.blue in minOf(lo.blue, hi.blue)..maxOf(lo.blue, hi.blue))
    }

    @Test
    fun `every stop is fully opaque`() {
        // The ghost applies its own alpha. A stop that arrived part-transparent would make the
        // filled part of the bar show the track through it and read as a rendering fault.
        for ((at, c) in Capacity.STOPS) assertEquals("at $at", 1f, c.alpha, 0f)
    }
}

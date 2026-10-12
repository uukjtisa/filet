package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtPaletteTest {

    private fun grid(vararg colours: Int, each: Int = 64) =
        IntArray(colours.size * each) { colours[it / each] }

    private fun hueOf(rgb: Int): Double {
        val r = (rgb shr 16 and 0xFF) / 255.0
        val g = (rgb shr 8 and 0xFF) / 255.0
        val b = (rgb and 0xFF) / 255.0
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        if (d == 0.0) return -1.0
        var h = when (mx) {
            r -> ((g - b) / d) % 6
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60.0
        if (h < 0) h += 360.0
        return h
    }

    @Test
    fun `nothing in, nothing claimed`() {
        assertFalse(ArtPalette.of(IntArray(0)).usable)
    }

    @Test
    fun `a picture with no colour is refused rather than guessed at`() {
        // Pure greys have no hue to borrow. Handing back a tinted grey would be inventing a
        // colour the artwork does not have, and the theme accent is the honest answer.
        assertFalse(ArtPalette.of(grid(0x000000, 0x404040, 0x808080, 0xFFFFFF)).usable)
    }

    @Test
    fun `the result always clears the contrast floor against the panel`() {
        // Every hue, including the ones that are naturally dark against a dark panel.
        var h = 0
        while (h < 360) {
            val rgb = java.awt.Color.getHSBColor(h / 360f, 0.9f, 0.45f).rgb and 0xFFFFFF
            val r = ArtPalette.of(grid(rgb))
            assertTrue("hue $h gave nothing", r.usable)
            val ratio = ArtPalette.contrast(r.colour, ArtPalette.panel())
            assertTrue("hue $h gave only $ratio:1", ratio >= ArtPalette.MIN_RATIO)
            h += 15
        }
    }

    @Test
    fun `a nearly black frame still yields a visible colour`() {
        // The case that started this: a clip shot in a dark room with a few lit pixels. The
        // honest average is near-black, and a near-black play button on a near-black panel
        // is the control nobody can see.
        val dark = IntArray(576) { 0x060805 }
        dark[10] = 0x27E03A
        dark[11] = 0x1FC032
        val r = ArtPalette.of(dark)
        assertTrue(r.usable)
        assertTrue(ArtPalette.contrast(r.colour, ArtPalette.panel()) >= ArtPalette.MIN_RATIO)
        val hue = hueOf(r.colour)
        assertTrue("took $hue, expected a green", hue in 90.0..160.0)
    }

    @Test
    fun `hue is averaged round the circle, not across it`() {
        // 350 and 10 are both red. An ordinary mean says 180, which is cyan - the exact
        // opposite - so the circular mean is not a nicety.
        val a = java.awt.Color.getHSBColor(350 / 360f, 0.9f, 0.7f).rgb and 0xFFFFFF
        val b = java.awt.Color.getHSBColor(10 / 360f, 0.9f, 0.7f).rgb and 0xFFFFFF
        val hue = hueOf(ArtPalette.of(grid(a, b)).colour)
        assertTrue("took $hue, expected a red near 0/360", hue < 30.0 || hue > 330.0)
    }

    @Test
    fun `the few saturated pixels outvote the many dull ones`() {
        val px = IntArray(576) { 0x2A2A28 }          // flat near-grey
        for (i in 0 until 20) px[i] = 0x1050FF        // a slice of strong blue
        val hue = hueOf(ArtPalette.of(px).colour)
        assertTrue("took $hue, expected a blue", hue in 190.0..260.0)
    }

    @Test
    fun `the glyph on the button is whichever of black or white wins`() {
        val yellow = ArtPalette.of(grid(java.awt.Color.getHSBColor(55 / 360f, 0.95f, 0.95f).rgb and 0xFFFFFF))
        assertEquals("a pale yellow needs dark text", 0x12110F, yellow.onColour)

        val deep = ArtPalette.of(grid(java.awt.Color.getHSBColor(250 / 360f, 0.95f, 0.30f).rgb and 0xFFFFFF))
        // It may have been lightened to pass the floor, so the assertion is the RULE rather
        // than a fixed answer: whatever came out, the glyph is the better of the two.
        val best = if (ArtPalette.contrast(deep.colour, 0x000000) >=
            ArtPalette.contrast(deep.colour, 0xFFFFFF)
        ) 0x12110F else 0xFFFFFF
        assertEquals(best, deep.onColour)
    }

    // ── the living glow's share of this: a card colour that drifts between posts ──

    @Test
    fun `drift swings the hue either side and returns through the middle`() {
        val ground = ArtPalette.ground(ArtPalette.of(grid(0x2F6FD0)))
        val middle = hueOf(ArtPalette.drift(ground, 0.0))
        val out = hueOf(ArtPalette.drift(ground, 0.25))
        val back = hueOf(ArtPalette.drift(ground, 0.75))
        // A quarter in is one end of the swing, three quarters is the other, and half way
        // round is back through the middle - which is what makes it weather rather than a
        // jump at the loop point.
        assertTrue("the hue never moved", out != middle)
        assertEquals(middle, hueOf(ArtPalette.drift(ground, 0.5)), 1.0)
        assertTrue("the swing only went one way", (out - middle) * (back - middle) < 0)
    }

    @Test
    fun `drift keeps a grey grey`() {
        // There is no hue to rotate in a colour with no saturation, so the only thing that
        // can move is the lightness - and it must not tint on the way.
        val grey = ArtPalette.drift(0x404040, 0.3)
        val r = grey shr 16 and 0xFF
        assertEquals(r, grey shr 8 and 0xFF)
        assertEquals(r, grey and 0xFF)
    }

    @Test
    fun `drift stays dark enough for white text on every hue`() {
        // The whole reason the swing is bounded: the platform picks the text colour from how
        // dark the card is, and a card whose text flipped mid-track would be the bug.
        for (hue in 0 until 360 step 15) {
            val base = ArtPalette.ground(
                ArtPalette.of(grid(java.awt.Color.getHSBColor(hue / 360f, 0.8f, 0.7f).rgb and 0xFFFFFF)),
            )
            for (step in 0 until 16) {
                val rgb = ArtPalette.drift(base, step / 16.0)
                assertTrue(
                    "hue $hue step $step came out too light",
                    ArtPalette.luminance(rgb) < 0.5,
                )
            }
        }
    }

    @Test
    fun `saturation is clamped so a control never looks like a warning`() {
        val screaming = ArtPalette.of(grid(0xFF0000))
        val r = screaming.colour shr 16 and 0xFF
        val g = screaming.colour shr 8 and 0xFF
        val b = screaming.colour and 0xFF
        // Not pure red: something is always mixed in by the clamp and the lightening.
        assertTrue("got a fully saturated primary", minOf(g, b) > 10 || r < 250)
    }
}

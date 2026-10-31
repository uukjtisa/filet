package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shrink arithmetic, so it is checked rather than eyeballed inside a split screen. */
class SplitFitTest {

    @Test fun `a pane as wide as the design is not shrunk`() {
        assertEquals(1f, SplitFit.scaleFor(SplitFit.DESIGNED_FOR_DP), 0f)
        assertEquals(1f, SplitFit.scaleFor(SplitFit.DESIGNED_FOR_DP + 200f), 0f)
        assertFalse(SplitFit.shrinks(1080f))
    }

    @Test fun `a pane narrower than the design shrinks in proportion`() {
        // A 720dp tablet split in two gives 360dp panes, which need nothing. A 600dp foldable
        // gives 300dp, which shrinks in proportion.
        assertEquals(1f, SplitFit.scaleFor(360f), 0f)
        assertEquals(300f / 320f, SplitFit.scaleFor(300f), 0.0001f)
        assertTrue(SplitFit.shrinks(300f))
    }

    @Test fun `a common phone split in two fits whole`() {
        // A 411dp phone split down the middle: 6dp of divider, 202.5dp a side, laid out at the
        // 320dp floor - 0.63, above the floor, so nothing clips.
        val half = (411f - 6f) / 2f
        assertEquals(half / 320f, SplitFit.scaleFor(half), 0.0001f)
        assertTrue(SplitFit.scaleFor(half) > SplitFit.FLOOR)
        // 390dp, the narrowest common width, lands exactly on the floor and still fits whole.
        assertEquals((390f - 6f) / 2f / 320f, SplitFit.scaleFor((390f - 6f) / 2f), 0.0001f)
    }

    @Test fun `a small phone split in two lands on the floor, so it still clips a little`() {
        // A 360dp phone gives 177dp panes, and 177/320 is 0.55 - below the floor. Capped there,
        // the pane lays out at 295dp and loses a few edges, which is the deliberate trade.
        assertEquals(SplitFit.FLOOR, SplitFit.scaleFor(177f), 0f)
        assertTrue(177f / 320f < SplitFit.FLOOR)
    }

    @Test fun `it never shrinks past the readable floor`() {
        // Past the floor a pane keeps fitting and stops being readable, which trades one
        // complaint for a worse one. It clips a little instead.
        assertEquals(SplitFit.FLOOR, SplitFit.scaleFor(100f), 0f)
        assertEquals(SplitFit.FLOOR, SplitFit.scaleFor(1f), 0f)
    }

    @Test fun `turning it off leaves the layout alone at any width`() {
        for (w in listOf(1f, 100f, 195f, 360f, 1080f)) {
            assertEquals(w.toString(), 1f, SplitFit.scaleFor(w, SplitFit.Mode.NONE), 0f)
        }
    }

    @Test fun `a nonsense width is not a crash`() {
        // BoxWithConstraints can report 0 for a frame, and a zero or infinite scale would take
        // the whole pane out rather than making it smaller.
        assertEquals(1f, SplitFit.scaleFor(0f), 0f)
        assertEquals(1f, SplitFit.scaleFor(-5f), 0f)
        assertEquals(1f, SplitFit.scaleFor(Float.NaN), 0f)
        assertEquals(1f, SplitFit.scaleFor(Float.POSITIVE_INFINITY), 0f)
    }
}

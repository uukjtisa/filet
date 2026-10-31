package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pulling past either end, tested as a drag: deltas go in, a refresh comes out or does not. */
class EdgePullTest {

    private val t = 72f

    /** Drag [by] in one go while the list sits at the given end, then let go. */
    private fun drag(
        start: PullState = PullState(),
        by: List<Float>,
        atTop: Boolean = false,
        atBottom: Boolean = false,
    ): Pair<PullState, Boolean> {
        var s = start
        for (d in by) s = EdgePull.onScroll(s, d, atTop, atBottom)
        return EdgePull.onRelease(s, t)
    }

    // ── the two ends ───────────────────────────────────────────────────────────────────

    @Test
    fun `pulling down far enough at the top refreshes`() {
        assertTrue(drag(by = listOf(40f, 40f), atTop = true).second)
    }

    @Test
    fun `pulling up far enough at the bottom refreshes`() {
        // The end that matters on a long listing, and the one a top-only implementation
        // silently leaves out.
        assertTrue(drag(by = listOf(-40f, -40f), atBottom = true).second)
    }

    @Test
    fun `a short pull does nothing`() {
        assertFalse(drag(by = listOf(20f), atTop = true).second)
        assertFalse(drag(by = listOf(-71f), atBottom = true).second)
    }

    @Test
    fun `exactly the threshold counts`() {
        assertTrue(drag(by = listOf(72f), atTop = true).second)
    }

    // ── things that must NOT count ─────────────────────────────────────────────────────

    @Test
    fun `scrolling in the middle of the list never arms anything`() {
        val (state, fired) = drag(by = listOf(200f, 200f), atTop = false, atBottom = false)
        assertFalse(fired)
        assertNull(state.edge)
    }

    @Test
    fun `pulling the wrong way at an end does nothing`() {
        // At the top, dragging up is ordinary scrolling into the list.
        assertFalse(drag(by = listOf(-200f), atTop = true).second)
        // At the bottom, dragging down is ordinary scrolling back up it.
        assertFalse(drag(by = listOf(200f), atBottom = true).second)
    }

    @Test
    fun `pushing back towards the middle resets the distance`() {
        var s = PullState()
        s = EdgePull.onScroll(s, 60f, atTop = true, atBottom = false)
        // One nudge the other way, and the 60 already earned is gone.
        s = EdgePull.onScroll(s, -10f, atTop = true, atBottom = false)
        s = EdgePull.onScroll(s, 60f, atTop = true, atBottom = false)
        assertFalse("two separate nudges must not add up", EdgePull.onRelease(s, t).second)
    }

    @Test
    fun `distance earned at one end does not carry to the other`() {
        var s = PullState()
        s = EdgePull.onScroll(s, 60f, atTop = true, atBottom = false)
        s = EdgePull.onScroll(s, -20f, atTop = false, atBottom = true)
        assertEquals(PullEdge.BOTTOM, s.edge)
        assertEquals(20f, s.distance, 0.01f)
        assertFalse(EdgePull.onRelease(s, t).second)
    }

    // ── firing exactly once ────────────────────────────────────────────────────────────

    @Test
    fun `letting go clears the pull so a second release does nothing`() {
        var s = PullState()
        s = EdgePull.onScroll(s, 100f, atTop = true, atBottom = false)
        val (after, fired) = EdgePull.onRelease(s, t)
        assertTrue(fired)
        assertFalse("the same gesture must not refresh twice", EdgePull.onRelease(after, t).second)
    }

    @Test
    fun `a spent pull cannot re-arm without letting go`() {
        val spent = PullState(PullEdge.TOP, 400f, spent = true)
        assertFalse(spent.armed(t))
        assertFalse(EdgePull.onRelease(spent, t).second)
    }

    // ── what the indicator reads ───────────────────────────────────────────────────────

    @Test
    fun `armed only past the line, and only while an end is held`() {
        assertFalse(PullState(PullEdge.TOP, 71f).armed(t))
        assertTrue(PullState(PullEdge.TOP, 72f).armed(t))
        assertFalse(PullState(null, 500f).armed(t))
    }

    @Test
    fun `distance is always positive whichever way it was pulled`() {
        val s = EdgePull.onScroll(PullState(), -50f, atTop = false, atBottom = true)
        assertEquals(50f, s.distance, 0.01f)
    }

    // ── the indicator: Chrome-style, rides out slower than the finger ────────────────

    @Test
    fun `the threshold is a deliberate pull, not the tail of a flick`() {
        assertTrue(EdgePull.THRESHOLD_DP >= 120f)
    }

    @Test
    fun `progress fills to one at the threshold and stops there`() {
        assertEquals(0f, EdgePull.progress(0f, 100f), 0.001f)
        assertEquals(0.5f, EdgePull.progress(50f, 100f), 0.001f)
        assertEquals(1f, EdgePull.progress(100f, 100f), 0.001f)
        assertEquals(1f, EdgePull.progress(900f, 100f), 0.001f)
    }

    @Test
    fun `the indicator moves slower than the finger and never passes its cap`() {
        val cap = 96f
        var last = 0f
        for (d in listOf(1f, 10f, 50f, 150f, 400f, 5000f)) {
            val o = EdgePull.indicatorOffset(d, cap)
            assertTrue("never decreases", o >= last)
            assertTrue("never faster than half the finger", o <= d * 0.5f + 0.001f)
            assertTrue("never past the cap", o <= cap)
            last = o
        }
        assertEquals(0f, EdgePull.indicatorOffset(0f, cap), 0.001f)
    }
}

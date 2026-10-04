package dev.niccc2007.filet.handlers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerChromeTest {

    private val idle = ViewerChrome.IDLE_MS

    @Test
    fun `controls retract once nothing has been touched for a while`() {
        assertTrue(ViewerChrome.shouldRetract(now = 10_000, lastTouchedAt = 10_000 - idle, interacting = false))
    }

    @Test
    fun `a recent touch keeps them up`() {
        assertFalse(ViewerChrome.shouldRetract(now = 10_000, lastTouchedAt = 9_500, interacting = false))
    }

    @Test
    fun `they do not retract while something is being used`() {
        // Controls that vanish mid-drag take the thing being dragged with them.
        assertFalse(ViewerChrome.shouldRetract(now = 10_000, lastTouchedAt = 0, interacting = true))
    }

    @Test
    fun `retracting does not depend on anything playing`() {
        // The whole correction: a paused frame being studied and a photograph being looked at are
        // the cases where the controls are MOST in the way, and a rule written as "hide while
        // playing" keeps them up for exactly those.
        val longAgo = 0L
        assertTrue(ViewerChrome.shouldRetract(now = idle, lastTouchedAt = longAgo, interacting = false))
    }

    @Test
    fun `a clock that jumps backwards does not slam them shut`() {
        // A corrected device clock, or a touch sampled after the comparison, reads as a negative
        // idle time. Treated as "just touched" rather than as a very long time ago.
        assertFalse(ViewerChrome.shouldRetract(now = 1_000, lastTouchedAt = 9_000, interacting = false))
    }

    @Test
    fun `the boundary belongs to retracting`() {
        assertTrue(ViewerChrome.shouldRetract(now = idle, lastTouchedAt = 0, interacting = false))
        assertFalse(ViewerChrome.shouldRetract(now = idle - 1, lastTouchedAt = 0, interacting = false))
    }
}

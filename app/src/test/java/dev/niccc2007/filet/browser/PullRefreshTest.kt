package dev.niccc2007.filet.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pulling down when already at the top refreshes the tab.
 *
 * The ask was the top edge specifically. `EdgePullTest` covers the mechanism as a whole,
 * including the bottom edge added later; this one holds the original promise on its own, so
 * that reworking the general case cannot quietly drop the case that was asked for.
 */
class PullRefreshTest {

    private val t = EdgePull.THRESHOLD_DP

    private fun pullDownAtTop(by: Float): Boolean {
        var s = PullState()
        s = EdgePull.onScroll(s, by, atTop = true, atBottom = false)
        return EdgePull.onRelease(s, t).second
    }

    @Test
    fun `pulling down at the top refreshes`() {
        assertTrue(pullDownAtTop(t + 1))
    }

    @Test
    fun `a nudge at the top does not`() {
        assertFalse(pullDownAtTop(t / 4))
    }

    @Test
    fun `scrolling down from the middle never refreshes`() {
        // The distinction the gesture turns on: only what the list could not consume counts,
        // so an ordinary scroll that merely began at the top is not a pull.
        var s = PullState()
        s = EdgePull.onScroll(s, 500f, atTop = false, atBottom = false)
        assertFalse(EdgePull.onRelease(s, t).second)
    }

    @Test
    fun `scrolling up while at the top is ordinary scrolling`() {
        var s = PullState()
        s = EdgePull.onScroll(s, -500f, atTop = true, atBottom = false)
        assertFalse(EdgePull.onRelease(s, t).second)
    }

    @Test
    fun `one pull is one refresh`() {
        var s = PullState()
        s = EdgePull.onScroll(s, t * 4, atTop = true, atBottom = false)
        val (after, fired) = EdgePull.onRelease(s, t)
        assertTrue(fired)
        assertFalse("holding past the line must not refresh again", EdgePull.onRelease(after, t).second)
    }
}

package dev.niccc2007.filet.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A finished tab swipe lets go only once the tab it went to is the current pane.
 *
 * Bug identified here: the old tab flashed when the finger lifted, because the motion reset a
 * frame or two before the switch reached the screen. These pin the order that removes it.
 */
class SwipeHoldTest {

    @Test
    fun `a held swipe waits while the old tab is still current`() {
        assertFalse(SwipeHold.settled(holding = true, targetId = 5, currentId = 6))
    }

    @Test
    fun `a held swipe lets go in the composition where the target is current`() {
        assertTrue(SwipeHold.settled(holding = true, targetId = 5, currentId = 5))
    }

    @Test
    fun `nothing settles without a hold or without a target`() {
        // A live drag shows the target beside the current pane; that is not the end of anything.
        assertFalse(SwipeHold.settled(holding = false, targetId = 5, currentId = 5))
        assertFalse(SwipeHold.settled(holding = true, targetId = null, currentId = 5))
    }
}

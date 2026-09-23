package dev.niccc2007.filet.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tap on the split divider swaps the panes. A hold or a drag does not.
 *
 * The asymmetry is deliberate and every case here leans the same way: a missed tap costs a
 * second press, a false tap swaps the panes out from under somebody who meant to resize.
 */
class DividerTapTest {

    @Test
    fun `a quick press that does not move is a tap`() {
        assertTrue(DividerTap.isTap(durationMs = 80, travelDp = 0f))
    }

    @Test
    fun `a thumb that wobbles slightly is still a tap`() {
        // Zero tolerance means the tap essentially never fires on a real touchscreen.
        assertTrue(DividerTap.isTap(durationMs = 120, travelDp = 6f))
    }

    @Test
    fun `a press held still is NOT a tap`() {
        // A tap-hold must not register as a tap. A finger resting on a drag handle is the
        // beginning of a drag that has not moved yet.
        assertFalse(DividerTap.isTap(durationMs = 600, travelDp = 0f))
        assertFalse(DividerTap.isTap(durationMs = 221, travelDp = 0f))
    }

    @Test
    fun `a quick flick that moves is a drag, not a tap`() {
        assertFalse(DividerTap.isTap(durationMs = 60, travelDp = 40f))
    }

    @Test
    fun `a drag out and back is a drag`() {
        // Travel is the furthest point reached, not the endpoint. Measuring where the finger
        // ended up calls this a tap and resizes the split to exactly where it already was.
        assertFalse(DividerTap.isTap(durationMs = 200, travelDp = 60f))
    }

    @Test
    fun `the boundaries are inclusive on the tap side`() {
        assertTrue(DividerTap.isTap(DividerTap.MAX_DURATION_MS, DividerTap.MAX_TRAVEL_DP))
        assertFalse(DividerTap.isTap(DividerTap.MAX_DURATION_MS + 1, DividerTap.MAX_TRAVEL_DP))
        assertFalse(DividerTap.isTap(DividerTap.MAX_DURATION_MS, DividerTap.MAX_TRAVEL_DP + 0.1f))
    }

    @Test
    fun `nonsense input is not a tap`() {
        assertFalse(DividerTap.isTap(-1, 0f))
        assertFalse(DividerTap.isTap(100, Float.NaN))
    }

    @Test
    fun `the hold threshold is below the platform long-press`() {
        // The divider has no long-press action, so anything slow can only be a drag that has
        // not started moving. Waiting the full 500ms would let a slow deliberate drag swap.
        assertTrue(DividerTap.MAX_DURATION_MS < 500)
    }
}

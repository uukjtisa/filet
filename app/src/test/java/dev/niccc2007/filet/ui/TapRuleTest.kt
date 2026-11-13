package dev.niccc2007.filet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What counts as a tap in the viewers, so a pan can never be read as half of a double tap. */
class TapRuleTest {

    private val slop = 22f
    private val longPress = 400L

    @Test fun `a press that stays put and lets go is a tap`() {
        assertTrue(TapRule.isTap(farthestPx = 0f, slopPx = slop, heldMs = 90, longPressMs = longPress))
        assertTrue(TapRule.isTap(farthestPx = slop, slopPx = slop, heldMs = 90, longPressMs = longPress))
    }

    @Test fun `the end of a slide is not a tap, however quick and wherever it lifts`() {
        // The defect: a sideways pan on a zoomed page lifted, and counted as the first tap.
        assertFalse(TapRule.isTap(farthestPx = 340f, slopPx = slop, heldMs = 120, longPressMs = longPress))
        // A slide that came back to where it started still travelled.
        assertFalse(TapRule.isTap(farthestPx = slop + 1f, slopPx = slop, heldMs = 60, longPressMs = longPress))
    }

    @Test fun `a held press is not a tap`() {
        assertFalse(TapRule.isTap(farthestPx = 0f, slopPx = slop, heldMs = longPress, longPressMs = longPress))
    }

    @Test fun `two taps pair only when close in time and place`() {
        val pair = 100f * 2.75f
        assertTrue(TapRule.pairs(gapMs = 120, apartPx = 30f, minGapMs = 40, maxGapMs = 300, pairSlopPx = pair))
        // A bounce of the same press, reported twice.
        assertFalse(TapRule.pairs(gapMs = 10, apartPx = 0f, minGapMs = 40, maxGapMs = 300, pairSlopPx = pair))
        assertFalse(TapRule.pairs(gapMs = 301, apartPx = 30f, minGapMs = 40, maxGapMs = 300, pairSlopPx = pair))
        // Quick, but on the far side of the page.
        assertFalse(TapRule.pairs(gapMs = 120, apartPx = pair + 1f, minGapMs = 40, maxGapMs = 300, pairSlopPx = pair))
    }
}

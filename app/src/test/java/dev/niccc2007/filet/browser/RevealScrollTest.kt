package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Go to containing folder" has to put the file in front of you.
 *
 * Reported as the tab opening somewhere else instead of where the file is. Opening the folder
 * and selecting the row is only half of it: the listing starts at the top, so a file far down
 * was selected and off screen.
 */
class RevealScrollTest {

    /** Where the target ends up on screen, given what firstVisibleFor decided. */
    private fun rowFromTop(target: Int, rows: Int, total: Int): Int =
        target - RevealScroll.firstVisibleFor(target, rows, total)

    // ── the report ──

    @Test
    fun `a file far down the list is brought to the middle`() {
        // 800 rows down in a thousand-row folder, on a screen holding twenty.
        val at = rowFromTop(target = 800, rows = 20, total = 1000)
        assertTrue("landed $at rows from the top", at in 8..11)
    }

    @Test
    fun `it is centred rather than merely on screen`() {
        // A row shoved to the bottom edge counts as scrolled-into-view and still has to be
        // hunted for, which is the complaint.
        for (target in listOf(50, 120, 400, 777)) {
            val at = rowFromTop(target, rows = 21, total = 1000)
            assertEquals("target $target", 10, at)
        }
    }

    // ── the ends, which is where centring cannot be had ──

    @Test
    fun `a file near the start does not scroll above the first row`() {
        assertEquals(0, RevealScroll.firstVisibleFor(targetIndex = 2, rowsOnScreen = 20, total = 1000))
    }

    @Test
    fun `a file near the end does not scroll past the last row`() {
        // Centring row 995 of 1000 would need rows that do not exist; the listing settles at
        // its own end instead of leaving a gap below.
        val first = RevealScroll.firstVisibleFor(targetIndex = 995, rowsOnScreen = 20, total = 1000)
        assertEquals(980, first)
        assertTrue("still on screen", 995 - first in 0..19)
    }

    @Test
    fun `the very last row is visible`() {
        val total = 1000
        val first = RevealScroll.firstVisibleFor(total - 1, rowsOnScreen = 20, total = total)
        assertTrue(total - 1 - first in 0..19)
    }

    @Test
    fun `the first row needs no scrolling`() {
        assertEquals(0, RevealScroll.firstVisibleFor(0, 20, 1000))
    }

    // ── degenerate cases that must not crash or scroll somewhere silly ──

    @Test
    fun `a folder shorter than the screen never scrolls`() {
        for (target in 0..4) {
            assertEquals(0, RevealScroll.firstVisibleFor(target, rowsOnScreen = 20, total = 5))
        }
    }

    @Test
    fun `an empty folder scrolls nowhere`() {
        assertEquals(0, RevealScroll.firstVisibleFor(3, 20, 0))
    }

    @Test
    fun `an unmeasured viewport puts the target at the top rather than guessing`() {
        assertEquals(40, RevealScroll.firstVisibleFor(40, rowsOnScreen = 0, total = 100))
        assertEquals(40, RevealScroll.firstVisibleFor(40, rowsOnScreen = 1, total = 100))
    }

    @Test
    fun `a negative index is treated as the top`() {
        assertEquals(0, RevealScroll.firstVisibleFor(-3, 20, 100))
    }

    @Test
    fun `the result is always a real row`() {
        for (total in listOf(1, 5, 50, 1000)) {
            for (rows in listOf(0, 1, 7, 20, 60)) {
                for (target in listOf(0, 1, total / 2, total - 1)) {
                    val first = RevealScroll.firstVisibleFor(target, rows, total)
                    assertTrue("$total/$rows/$target gave $first", first in 0 until maxOf(1, total))
                }
            }
        }
    }

    // ── how many rows fit ──

    @Test
    fun `rows are counted whole`() {
        // Half a row at the bottom is not somewhere a file can be said to be.
        assertEquals(10, RevealScroll.rowsOnScreen(viewportPx = 1050, rowPx = 100))
    }

    @Test
    fun `an unmeasured viewport counts no rows`() {
        assertEquals(0, RevealScroll.rowsOnScreen(0, 100))
        assertEquals(0, RevealScroll.rowsOnScreen(1000, 0))
    }

    // ── leaving it alone when it is already there ──

    @Test
    fun `a file already in the middle is not jumped`() {
        // Scrolling something already in front of somebody loses their place for nothing.
        assertTrue(RevealScroll.alreadyShown(targetIndex = 15, firstVisible = 10, rowsOnScreen = 20))
    }

    @Test
    fun `a file at the very edge does not count as shown`() {
        // Half under the toolbar is visible and not useful.
        assertFalse(RevealScroll.alreadyShown(targetIndex = 10, firstVisible = 10, rowsOnScreen = 20))
        assertFalse(RevealScroll.alreadyShown(targetIndex = 29, firstVisible = 10, rowsOnScreen = 20))
    }

    @Test
    fun `a file off screen is not shown`() {
        assertFalse(RevealScroll.alreadyShown(targetIndex = 500, firstVisible = 10, rowsOnScreen = 20))
        assertFalse(RevealScroll.alreadyShown(targetIndex = 0, firstVisible = 10, rowsOnScreen = 20))
    }

    @Test
    fun `nothing is shown in an unmeasured viewport`() {
        assertFalse(RevealScroll.alreadyShown(5, 0, rowsOnScreen = 0))
    }
}

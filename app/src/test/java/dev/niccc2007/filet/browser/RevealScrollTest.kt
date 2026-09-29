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

    // ── whether to act at all (RevealScroll.act) ──

    @Test
    fun `a chunked listing that has not finished is waited for`() {
        // THE REGRESSION, as a test. "Go to containing folder" stopped scrolling the moment
        // listings began arriving in chunks: the effect fired on chunk one, the target was not
        // among the first 120 entries, and the old code read that as "not in this listing" and
        // dropped the request. Nothing scrolled, ever, for any file outside the first chunk.
        assertEquals(
            RevealScroll.Act.WAIT,
            RevealScroll.act(settled = false, rowCount = 120, targetIndex = -1),
        )
    }

    @Test
    fun `a hit in an unfinished listing scrolls at once and keeps the request`() {
        // Found in chunk one: go there NOW, and keep the request so later chunks can correct
        // the position. Waiting for the whole listing was the first fix and it was visible -
        // a person can tap during the pause.
        assertEquals(
            RevealScroll.Act.SCROLL_AGAIN,
            RevealScroll.act(settled = false, rowCount = 120, targetIndex = 44),
        )
    }

    @Test
    fun `a settled listing holding the target scrolls`() {
        assertEquals(
            RevealScroll.Act.SCROLL_DONE,
            RevealScroll.act(settled = true, rowCount = 1242, targetIndex = 980),
        )
    }

    @Test
    fun `the first row is a hit and not an absence`() {
        // Index 0 is falsy in several languages and this has to be one of them that it is not.
        assertEquals(
            RevealScroll.Act.SCROLL_DONE,
            RevealScroll.act(settled = true, rowCount = 10, targetIndex = 0),
        )
    }

    @Test
    fun `a settled listing without the target gives up`() {
        // Hidden by the hidden-files filter, or deleted since the row was drawn. Holding the
        // request would make the NEXT folder jump for no reason.
        assertEquals(
            RevealScroll.Act.GIVE_UP,
            RevealScroll.act(settled = true, rowCount = 1242, targetIndex = -1),
        )
    }

    @Test
    fun `a settled empty listing gives up rather than waiting forever`() {
        // An unreadable folder, or one that really is empty. Either way the read is over.
        assertEquals(
            RevealScroll.Act.GIVE_UP,
            RevealScroll.act(settled = true, rowCount = 0, targetIndex = -1),
        )
    }

    @Test
    fun `an unfinished empty listing is still waited for`() {
        // The very first firing, before any chunk. Giving up here is how the request died
        // before the folder had produced a single row.
        assertEquals(
            RevealScroll.Act.WAIT,
            RevealScroll.act(settled = false, rowCount = 0, targetIndex = -1),
        )
    }

    @Test
    fun `the old rule is what broke it`() {
        // The negative control, stated as the code that was there: act as soon as there are
        // rows, and treat a miss as final.
        val oldRule = { rowCount: Int, targetIndex: Int ->
            when {
                rowCount <= 0 -> RevealScroll.Act.WAIT
                targetIndex < 0 -> RevealScroll.Act.GIVE_UP
                else -> RevealScroll.Act.SCROLL_DONE
            }
        }
        // Chunk one of a camera folder, target not in it.
        assertEquals(RevealScroll.Act.GIVE_UP, oldRule(120, -1))
        assertEquals(
            RevealScroll.Act.WAIT,
            RevealScroll.act(settled = false, rowCount = 120, targetIndex = -1),
        )
    }

    @Test
    fun `a finger on the list cancels the reveal outright`() {
        // Their scroll wins immediately. A reveal is what the app was asked to do a moment ago;
        // a drag is what is being asked for now, and yanking the list out from under it is the
        // worst thing this could do.
        assertEquals(
            RevealScroll.Act.GIVE_UP,
            RevealScroll.act(settled = false, rowCount = 400, targetIndex = 12, userTookOver = true),
        )
        assertEquals(
            RevealScroll.Act.GIVE_UP,
            RevealScroll.act(settled = true, rowCount = 400, targetIndex = 12, userTookOver = true),
        )
    }

    @Test
    fun `a hit keeps the request until the listing is final`() {
        // SCROLL_AGAIN means the row is shown now and the position may still be corrected;
        // SCROLL_DONE is the only one that releases it. Releasing on the first hit would leave
        // the row wherever the partial list put it when the rest arrived and moved it.
        assertEquals(
            RevealScroll.Act.SCROLL_AGAIN,
            RevealScroll.act(settled = false, rowCount = 120, targetIndex = 3),
        )
        assertEquals(
            RevealScroll.Act.SCROLL_DONE,
            RevealScroll.act(settled = true, rowCount = 1242, targetIndex = 3),
        )
    }
}

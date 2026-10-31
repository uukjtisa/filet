package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a result's label means. SEARCH.md §14.3.
 *
 * The defect: Confirming was tied to whether an index pass had re-read the row, so a row already
 * checked against the filesystem still read Confirming for as long as a crawl ran - minutes, with
 * a crawl started on every open.
 */
class HitStateTest {

    @Test
    fun `a row that passes its check becomes available`() {
        assertEquals(HitState.AVAILABLE, afterCheck(exists = true))
    }

    @Test
    fun `a row whose file is gone is removed, not relabelled`() {
        // A third state for "might be gone" is the reader doing the checking. The row leaves.
        assertNull(afterCheck(exists = false))
    }

    @Test
    fun `only a checked row can be acted on`() {
        assertTrue(actionable(HitState.AVAILABLE))
        assertFalse(actionable(HitState.CONFIRMING))
    }

    @Test
    fun `there are exactly two states`() {
        assertEquals(2, HitState.entries.size)
    }

    @Test
    fun `both states are drawable`() {
        for (s in HitState.entries) assertTrue(s.name, s.label.isNotBlank())
    }

    @Test
    fun `the old rule kept a checked row confirming for as long as a crawl ran`() {
        // The negative control, written as the rule that was there. A row the pane had already
        // checked - generation behind the running pass - still read Confirming under it, and
        // nothing about the row could change that until the crawl reached it.
        val old = { crawlRunning: Boolean, rowGen: Long, currentGen: Long ->
            if (crawlRunning && rowGen < currentGen) HitState.CONFIRMING else HitState.AVAILABLE
        }
        assertEquals(HitState.CONFIRMING, old(true, 6, 7))
        // Under the new rule the same row's own check decides it, crawl or no crawl.
        assertEquals(HitState.AVAILABLE, afterCheck(exists = true))
    }
}

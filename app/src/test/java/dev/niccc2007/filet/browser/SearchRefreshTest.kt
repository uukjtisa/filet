package dev.niccc2007.filet.browser

import dev.niccc2007.filet.index.IndexStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an open search asks the index again.
 *
 * Reported as results failing to appear at the end of a pass unless the query was retyped. The
 * risk in fixing it is the opposite fault - a list that re-queries continuously moves while it is
 * being read - so most of these cases are about NOT re-asking.
 */
class SearchRefreshTest {

    private fun st(running: Boolean, files: Long = 1000) =
        IndexStatus(running = running, files = files, available = true)

    // ── the reported case ──

    @Test
    fun `a pass ending asks again`() {
        // The whole report: rows written during the crawl were never fetched, and retyping was
        // the only way to re-run the query.
        assertTrue(SearchRefresh.shouldRerun(st(running = true), st(running = false)))
    }

    @Test
    fun `the index growing between passes asks again`() {
        // A background pass finished between two ticks.
        assertTrue(
            SearchRefresh.shouldRerun(st(running = false, files = 1000), st(running = false, files = 1400)),
        )
    }

    // ── and everything that must NOT ──

    @Test
    fun `a running pass does not ask on every tick`() {
        // The instability this has to avoid. A crawl writes continuously, and a list that
        // re-queries with it moves under a thumb already reaching for a row.
        assertFalse(SearchRefresh.shouldRerun(st(running = true, files = 1000), st(running = true, files = 1400)))
    }

    @Test
    fun `a pass starting does not ask`() {
        // Nothing new has been written yet; the answer on screen is as good as it was.
        assertFalse(SearchRefresh.shouldRerun(st(running = false), st(running = true)))
    }

    @Test
    fun `nothing changing does not ask`() {
        assertFalse(SearchRefresh.shouldRerun(st(running = false, files = 1000), st(running = false, files = 1000)))
        assertFalse(SearchRefresh.shouldRerun(st(running = true, files = 1000), st(running = true, files = 1000)))
    }

    @Test
    fun `an index that shrank does not ask`() {
        // A sweep removed rows. Nothing was added, so the answer can only get shorter, and
        // re-running to drop rows out from under a reader is the instability again.
        assertFalse(
            SearchRefresh.shouldRerun(st(running = false, files = 1400), st(running = false, files = 1000)),
        )
    }

    @Test
    fun `the first observation never asks`() {
        // With no previous status there is no edge, and the search has just run anyway.
        assertFalse(SearchRefresh.shouldRerun(null, st(running = false)))
    }

    @Test
    fun `no index at all never asks`() {
        assertFalse(SearchRefresh.shouldRerun(st(running = true), null))
        assertFalse(SearchRefresh.shouldRerun(null, null))
    }

    @Test
    fun `the end of a pass fires once, not on every tick after it`() {
        // Edges, not levels. Two ticks after the pass ended must be silent, or the fix for
        // "never updates" becomes "never stops updating".
        var seen: IndexStatus? = st(running = true)
        val after = st(running = false)
        assertTrue(SearchRefresh.shouldRerun(seen, after))
        seen = after
        assertFalse(SearchRefresh.shouldRerun(seen, after))
        assertFalse(SearchRefresh.shouldRerun(seen, st(running = false)))
    }

    // ── badges are a separate question ──

    @Test
    fun `badges settle whenever nothing is running`() {
        assertTrue(SearchRefresh.shouldSettleBadges(st(running = false)))
        assertFalse(SearchRefresh.shouldSettleBadges(st(running = true)))
        assertFalse(SearchRefresh.shouldSettleBadges(null))
    }

    @Test
    fun `settling and re-asking are not the same event`() {
        // The fault, stated as a property. Settling happens on every tick while idle; asking
        // happens once, on the edge. The old code did both in one branch and then returned,
        // which is how the re-ask was lost.
        val idle = st(running = false)
        assertTrue(SearchRefresh.shouldSettleBadges(idle))
        assertFalse(SearchRefresh.shouldRerun(idle, idle))
    }
}

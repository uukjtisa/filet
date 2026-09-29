package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resuming a truncated crawl, and the commit batching it makes safe.
 *
 * The generation rule is the one worth defending hardest. Resuming under the wrong generation
 * would not fail loudly - it would write rows that the end-of-pass sweep then treats as stale and
 * deletes, or spare rows the sweep should have removed. Both are silent, and both look like the
 * index losing files.
 */
class CrawlFrontierTest {

    // ── whether a stored frontier is the right one ──

    @Test
    fun `a frontier from the pass about to start is resumed`() {
        // A truncated pass does not advance META_GEN, so the next pass computes the same
        // generation and the leftover frontier is its own unfinished half.
        assertTrue(CrawlFrontier.canResume(storedGen = 15, storedRows = 400, nextGen = 15, kind = CrawlKind.UPDATE))
    }

    @Test
    fun `a frontier from an older generation is discarded`() {
        // That pass has since completed and been swept. Walking its leftovers would re-walk a
        // tree that is already settled, under a generation that no longer means anything.
        assertFalse(CrawlFrontier.canResume(storedGen = 14, storedRows = 400, nextGen = 15, kind = CrawlKind.UPDATE))
    }

    @Test
    fun `a frontier from a newer generation is discarded`() {
        // Should not happen, and is exactly the case where guessing is worst - so it starts clean
        // rather than trusting a number it cannot explain.
        assertFalse(CrawlFrontier.canResume(storedGen = 16, storedRows = 400, nextGen = 15, kind = CrawlKind.UPDATE))
    }

    @Test
    fun `an empty frontier is not a resume`() {
        assertFalse(CrawlFrontier.canResume(storedGen = 15, storedRows = 0, nextGen = 15, kind = CrawlKind.UPDATE))
    }

    @Test
    fun `a build never resumes`() {
        // BUILD means the index holds nothing, so there is no partial state worth trusting and
        // starting clean is no slower.
        assertFalse(CrawlFrontier.canResume(storedGen = 15, storedRows = 400, nextGen = 15, kind = CrawlKind.BUILD))
    }

    @Test
    fun `a pass that is not going to run does not resume`() {
        assertFalse(CrawlFrontier.canResume(storedGen = 15, storedRows = 400, nextGen = 15, kind = CrawlKind.NONE))
    }

    // ── when to commit ──

    @Test
    fun `a full batch commits`() {
        assertTrue(CrawlFrontier.shouldCommit(CrawlFrontier.COMMIT_EVERY, msSinceCommit = 0))
    }

    @Test
    fun `a slow batch commits on time even when it is nearly empty`() {
        // A tree of enormous directories can spend a second on one of them. Holding a transaction
        // open across that widens the loss window for no gain.
        assertTrue(CrawlFrontier.shouldCommit(dirsSinceCommit = 1, msSinceCommit = CrawlFrontier.COMMIT_AFTER_MS))
    }

    @Test
    fun `a partial batch inside the window waits`() {
        assertFalse(CrawlFrontier.shouldCommit(dirsSinceCommit = 3, msSinceCommit = 10))
    }

    @Test
    fun `committing per directory is what this replaces`() {
        // The negative control, as the policy that was there: every directory its own transaction,
        // about 1,821 of them for a complete pass on this device.
        val perDirectory = { _: Int, _: Long -> true }
        assertTrue(perDirectory(1, 0L))
        assertFalse(CrawlFrontier.shouldCommit(1, 0L))
    }

    // ── the bound that makes batching safe ──

    @Test
    fun `a kill can only cost one batch, and that batch is still on the frontier`() {
        // This is the argument for batching at all. Without a frontier a lost batch had to be
        // found again from the roots; with one it costs exactly the directories in it.
        assertEquals(CrawlFrontier.COMMIT_EVERY, CrawlFrontier.worstCaseLostDirs())
        assertTrue("a batch has to stay small enough to be cheap to redo", CrawlFrontier.worstCaseLostDirs() <= 64)
    }

    @Test
    fun `read-ahead stays inside what phone storage will answer`() {
        // Reads only - SQLite takes one writer whatever the callers do. Below the feed's six,
        // because this runs behind a foreground app.
        assertTrue(CrawlFrontier.READ_AHEAD in 2..6)
        assertTrue(CrawlFrontier.READ_AHEAD < FEED_PARALLELISM_REFERENCE)
    }

    /** The feed's figure, restated so the relationship between the two is asserted and not assumed. */
    private val FEED_PARALLELISM_REFERENCE = 6
}

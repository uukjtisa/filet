package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reported: the indexing never stops.
 *
 * The mechanism, and it is a loop with no exit. A background pass gets a budget; a truncated
 * pass walks from the roots again next time rather than resuming; the last-run time is only
 * recorded after a complete pass. So on a tree bigger than one budget the pass never
 * completes, the time stays at zero, the index reads as permanently stale, and the next launch
 * starts another one that will also not complete.
 *
 * And the instruction under it: update the index, do not rebuild it. Only build when there is
 * genuinely nothing there.
 */
class CrawlPlanTest {

    private val STALE = 6L * 60 * 60 * 1000
    private val RETRY = 30L * 60 * 1000
    private val NOW = 1_800_000_000_000L

    // ── the loop ──

    @Test
    fun `a pass that did not complete is not retried immediately`() {
        // The exact loop. Rows are held, a pass was attempted a minute ago and did not finish,
        // so the complete time is still zero. Without the attempt time this asks for another
        // pass, and will again a minute later, for ever.
        val kind = crawlKind(
            filesHeld = 26_612, lastCompleteAt = 0, lastAttemptAt = NOW - 60_000,
            now = NOW, staleAfterMs = STALE, retryAfterMs = RETRY,
        )
        assertEquals(CrawlKind.NONE, kind)
    }

    @Test
    fun `it is retried once enough time has passed`() {
        val kind = crawlKind(
            filesHeld = 26_612, lastCompleteAt = 0, lastAttemptAt = NOW - RETRY - 1,
            now = NOW, staleAfterMs = STALE, retryAfterMs = RETRY,
        )
        assertEquals(CrawlKind.UPDATE, kind)
    }

    @Test
    fun `an index that holds rows is never rebuilt`() {
        // The instruction, as a property: whatever the times say, holding rows means update.
        for (complete in listOf(0L, NOW - STALE - 1, NOW)) {
            for (attempt in listOf(0L, NOW - RETRY - 1)) {
                val kind = crawlKind(1, complete, attempt, NOW, STALE, RETRY)
                assertTrue("$complete/$attempt gave $kind", kind != CrawlKind.BUILD)
            }
        }
    }

    // ── building ──

    @Test
    fun `an empty index is built`() {
        assertEquals(CrawlKind.BUILD, crawlKind(0, 0, 0, NOW, STALE, RETRY))
    }

    @Test
    fun `an empty index is built even if one was just attempted`() {
        // Waiting does not make an empty index more useful, and a first build is exactly the
        // case that gets truncated - so the retry guard must not apply to it.
        assertEquals(CrawlKind.BUILD, crawlKind(0, 0, NOW - 1, NOW, STALE, RETRY))
    }

    // ── staleness ──

    @Test
    fun `a fresh complete index is left alone`() {
        assertEquals(CrawlKind.NONE, crawlKind(30_000, NOW - 1000, NOW - 1000, NOW, STALE, RETRY))
    }

    @Test
    fun `a stale complete index is updated`() {
        assertEquals(
            CrawlKind.UPDATE,
            crawlKind(30_000, NOW - STALE - 1, NOW - STALE - 1, NOW, STALE, RETRY),
        )
    }

    @Test
    fun `nothing runs twice in a row without time passing`() {
        // The property that actually ends the loop, stated over a run of launches: repeatedly
        // asking within the retry window must produce work exactly once.
        var attempt = 0L
        var started = 0
        for (t in 0 until 20) {
            val now = NOW + t * 60_000
            val kind = crawlKind(26_612, 0, attempt, now, STALE, RETRY)
            if (kind != CrawlKind.NONE) { started++; attempt = now }
        }
        assertEquals("twenty launches over twenty minutes", 1, started)
    }

    // ── skipping unchanged directories, which is what makes an update finish ──

    @Test
    fun `a directory whose time has not moved is skipped`() {
        assertTrue(dirUnchanged(recordedMtime = 1_700_000_000, actualMtime = 1_700_000_000))
    }

    @Test
    fun `a directory whose time moved is visited`() {
        assertFalse(dirUnchanged(1_700_000_000, 1_700_000_001))
    }

    @Test
    fun `an unknown time is never treated as unchanged`() {
        // A filesystem that does not report directory times would otherwise let every
        // directory be skipped for ever, which is an index that stops updating silently.
        assertFalse(dirUnchanged(0, 0))
        assertFalse(dirUnchanged(0, 1_700_000_000))
        assertFalse(dirUnchanged(1_700_000_000, 0))
    }
}

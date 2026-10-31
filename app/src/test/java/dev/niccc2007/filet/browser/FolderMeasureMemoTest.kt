package dev.niccc2007.filet.browser

import dev.niccc2007.filet.index.FakeIndex
import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The index's figure for a folder: derived once per crawl generation, and never final.
 *
 * Two defects meet in this one lookup.
 *
 * Bug identified here: every lookup streamed every indexed row under the folder through a tally,
 * and a list row asks on every appearance - so the folder total on a row cost a full subtree
 * aggregation per folder per scroll, while the details sheet, asking once, felt instant.
 *
 * The second is worse, because it was wrong rather than slow: the figure it produced was handed
 * out as a TOTAL. The index holds only what its crawl has reached, so its sum under a folder is a
 * floor. These pin the cache that fixed the first and the floor that fixed the second, plus the
 * two things that must still get past the cache: a new crawl, and time passing while one runs.
 */
class FolderMeasureMemoTest {

    private val folder = VPath.of("file", "/storage/emulated/0/DCIM")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var clock = 1_000_000L

    @After fun tearDown() = scope.cancel()

    private fun measure(index: FakeIndex) =
        FolderMeasure(Vfs(emptyList()), index, JobLedger(), scope, now = { clock })

    @Test fun `asking forty times between passes reads the index once`() = runBlocking {
        val index = FakeIndex(rows = 12)
        val m = measure(index)
        repeat(40) { assertEquals(1_200L, m.cached(folder)?.tally?.bytes) }
        assertEquals(1, index.reads)
    }

    @Test fun `a new crawl generation is never served the old figure`() = runBlocking {
        val index = FakeIndex(rows = 12)
        val m = measure(index)
        m.cached(folder)
        index.rows = 30
        index.state.value = index.state.value.copy(writingGen = 2, lastRunAt = 900L)
        assertEquals(3_000L, m.cached(folder)?.tally?.bytes)
        assertEquals(2, index.reads)
    }

    @Test fun `while a crawl runs the figure is held briefly and then re-read`() = runBlocking {
        // During a pass the rows under the folder are exactly what is changing, so the
        // generation alone would hold a stale figure for minutes.
        val index = FakeIndex(rows = 12)
        index.state.value = index.state.value.copy(running = true)
        val m = measure(index)
        m.cached(folder)
        clock += FolderMeasure.WHILE_CRAWLING_MS - 1
        m.cached(folder)
        assertEquals("inside the window it is a map lookup", 1, index.reads)
        clock += 2
        m.cached(folder)
        assertEquals("past it, it is read again", 2, index.reads)
    }

    @Test fun `between passes the figure is held however long it has been`() = runBlocking {
        val index = FakeIndex(rows = 12)
        val m = measure(index)
        m.cached(folder)
        clock += 10 * 60 * 60 * 1000L
        m.cached(folder)
        assertEquals(1, index.reads)
    }

    @Test fun `a folder the index does not hold is remembered as not held`() = runBlocking {
        // Establishing "nothing here" costs the same full read as a real figure, and it is the
        // answer for every folder on a device with indexing off.
        val index = FakeIndex(rows = null)
        val m = measure(index)
        repeat(10) { assertEquals(null, m.cached(folder)) }
        assertEquals(1, index.reads)
    }

    // ── what a row is told ────────────────────────────────────────────────────────────────

    @Test fun `nothing known is Unknown`() = runBlocking {
        assertEquals(FolderTotal.Unknown, measure(FakeIndex(rows = null)).total(folder))
    }

    @Test fun `an index figure is a floor, never a total, however old the crawl is`() = runBlocking {
        // THE defect this round. The index holds only what its crawl has reached, so its sum
        // under a folder is "at least this much" - 703 MB under a 39 GB Download on a real
        // phone. It was returned as Settled, which the row prints bare and final. No amount of
        // elapsed time turns the indexed part into the folder, so time must not change this.
        val m = measure(FakeIndex(rows = 12))
        assertEquals(FolderTotal.AtLeast(1_200L), m.total(folder))
        clock += 10L * 24 * 60 * 60 * 1000
        assertEquals(FolderTotal.AtLeast(1_200L), m.total(folder))
        assertTrue(m.total(folder).isFloor())
    }

    @Test fun `a folder the crawl reached no files under says nothing, not zero`() = runBlocking {
        // Six folders on a real phone read "0 B" with directories plainly inside them: the
        // crawl had indexed the directory rows and never opened them. "Nothing seen" is not
        // "nothing here", and on a row somebody may delete from, the second is the dangerous
        // one. The item count beside the name is what answers emptiness honestly.
        assertEquals(FolderTotal.Unknown, measure(FakeIndex(rows = 0)).total(folder))
    }
}

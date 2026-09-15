package dev.niccc2007.filet.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The expand that took seconds on a long list.
 *
 * Nic: *"expand button for the new files section in the home tab takes so long to render
 * especially if I'm tracking a whole 1k plus list.. fix that please? isn't the load as you
 * scroll a thing?"*
 *
 * "Load as you scroll" was already there - the list is a `LazyColumn` with a `shown` window
 * that grows as it is scrolled. What was NOT paged is everything that happens before the first
 * row can be drawn, and that is where the wait came from:
 *
 *  - every path's first-seen date was **recorded and the whole store re-serialised to JSON and
 *    written to preferences**, for the entire tracked set, inside a `LaunchedEffect` - which
 *    runs on the main dispatcher, so a thousand-file write blocked the frame;
 *  - then the full list was mapped and grouped before the first of twenty visible rows.
 *
 * These tests hold the two properties that keep that from coming back. They are about work
 * done, not wall-clock, because a timing assertion on a build machine is a flake generator.
 */
class FileHistoryPagingTest {

    private val zone = TimeZone.getTimeZone("UTC")

    private fun entries(n: Int): List<HistoryEntry> = (0 until n).map {
        HistoryEntry(
            path = "/storage/emulated/0/Download/file$it.bin",
            name = "file$it.bin",
            bytes = it * 97L,
            // Spread across about three months so the grouping has real day buckets to build
            // rather than one enormous group, which is the cheap case.
            firstSeen = 1_700_000_000_000L + it * 3_600_000L,
            lastChanged = 1_700_000_000_000L + it * 3_600_000L,
            origin = "Download",
        )
    }

    // ── the write storm on the main thread ──

    @Test
    fun `recording a thousand files writes the store once, not once per file`() {
        var writes = 0
        var blob: String? = null
        val store = FirstSeenStore({ blob }, { blob = it; writes++ })

        val paths = entries(1200).associate { it.path to it.lastChanged }
        store.record(paths, now = 1_700_000_000_000L)

        assertEquals("one pass over the list is one write", 1, writes)
    }

    @Test
    fun `opening the screen again with nothing new writes nothing at all`() {
        // The expand is re-entered constantly - every time the feed refreshes, every time the
        // tab is switched back to. If each of those re-serialised a thousand-key JSON object on
        // the main thread, the screen would stutter forever and not only on first open.
        var writes = 0
        var blob: String? = null
        val store = FirstSeenStore({ blob }, { blob = it; writes++ })

        val paths = entries(1200).associate { it.path to it.lastChanged }
        store.record(paths, now = 1_700_000_000_000L)
        val afterFirst = writes

        repeat(5) { store.record(paths, now = 1_700_000_000_100L) }
        store.prune(paths.keys)

        assertEquals("a re-open with no new files must not write", afterFirst, writes)
    }

    @Test
    fun `a single genuinely new file still gets recorded`() {
        // The positive control for the test above. A store that never wrote again would pass it
        // and would silently stop tracking anything.
        var writes = 0
        var blob: String? = null
        val store = FirstSeenStore({ blob }, { blob = it; writes++ })

        val first = entries(50).associate { it.path to it.lastChanged }
        store.record(first, now = 1_700_000_000_000L)
        val before = writes

        store.record(first + ("/storage/emulated/0/Download/brand-new.bin" to 0L), now = 1_700_000_100_000L)
        assertTrue("a new path must be persisted", writes > before)
    }

    // ── the preparation that happens before the first row ──

    @Test
    fun `grouping scales with the list rather than with its square`() {
        // Not a stopwatch on absolute time - that flakes on a loaded build machine. This is the
        // SHAPE of the cost: ten times the input must not be a hundred times the work. A
        // grouping that re-scanned the list per day bucket would fail here and would be exactly
        // the behaviour that made a 1k list feel different from a 100 one.
        fun timeFor(n: Int): Long {
            val data = entries(n)
            repeat(3) { FileHistory.group(data, HistorySort.FIRST_SEEN, 1_700_400_000_000L, zone) }
            val t0 = System.nanoTime()
            repeat(5) { FileHistory.group(data, HistorySort.FIRST_SEEN, 1_700_400_000_000L, zone) }
            return System.nanoTime() - t0
        }

        val small = timeFor(500).coerceAtLeast(1)
        val large = timeFor(5000)
        val ratio = large.toDouble() / small

        assertTrue(
            "10x the entries cost ${"%.1f".format(ratio)}x the time - that is not linear",
            ratio < 40.0,
        )
    }

    @Test
    fun `every entry survives grouping, so paging cannot silently drop rows`() {
        val data = entries(1500)
        val groups = FileHistory.group(data, HistorySort.FIRST_SEEN, 1_700_400_000_000L, zone)
        assertEquals(data.size, groups.sumOf { it.entries.size })
    }
}

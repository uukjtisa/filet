package dev.niccc2007.filet.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The preparation in front of the first row of New files.
 *
 * The tab holds up to ten thousand rows now, device-wide, so what happens before the first of
 * twenty visible rows is drawn has to scale with the list rather than with its square, and
 * must not drop rows. About work done, not wall-clock, because a timing assertion on a build
 * machine is a flake generator.
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
            added = 1_700_000_000_000L + it * 3_600_000L,
            modified = 1_700_000_000_000L + it * 3_600_000L,
            origin = "Download",
        )
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
            repeat(3) { FileHistory.group(data, HistorySort.DATE_ADDED, 1_700_400_000_000L, zone) }
            val t0 = System.nanoTime()
            repeat(5) { FileHistory.group(data, HistorySort.DATE_ADDED, 1_700_400_000_000L, zone) }
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
        val groups = FileHistory.group(data, HistorySort.DATE_ADDED, 1_700_400_000_000L, zone)
        assertEquals(data.size, groups.sumOf { it.entries.size })
    }
}

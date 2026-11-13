package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/** The Carry on page's own decisions: time left, the day headings, and what Undo puts back. */
class CarryOnPageTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L

    /** 21:00 on 13 November 2026, UTC. */
    private val now = 1_794_603_600_000L

    private fun point(path: String, at: Long = 0L, pos: Long = 60_000L, dur: Long = 600_000L) =
        ResumeStore.Point(path, path, false, pos, dur, at)

    @Test
    fun `time left is in minutes, then hours and minutes, rounded`() {
        assertEquals("12 min left", resumeLeft(point("a", pos = 0L, dur = 12 * 60_000L)))
        // 12:29 left rounds down, 12:30 rounds up, as the mock's Math.round does.
        assertEquals("12 min left", resumeLeft(point("a", pos = 31_000L, dur = 13 * 60_000L)))
        assertEquals("13 min left", resumeLeft(point("a", pos = 30_000L, dur = 13 * 60_000L)))
        assertEquals("1 h 5 min left", resumeLeft(point("a", pos = 0L, dur = 65 * 60_000L)))
        assertEquals("2 h 0 min left", resumeLeft(point("a", pos = 0L, dur = 120 * 60_000L)))
    }

    @Test
    fun `the last half minute is under a minute, and an unknown length says nothing`() {
        assertEquals("under a minute", resumeLeft(point("a", pos = 571_000L, dur = 600_000L)))
        assertEquals("under a minute", resumeLeft(point("a", pos = 600_000L, dur = 600_000L)))
        // A position past a length that was measured short.
        assertEquals("under a minute", resumeLeft(point("a", pos = 700_000L, dur = 600_000L)))
        assertNull(resumeLeft(point("a", dur = 0L)))
    }

    @Test
    fun `rows group under their day, newest first, with the headings DayBuckets draws`() {
        val rows = listOf(
            point("today-1", at = now - 60_000L),
            point("today-2", at = now - 3 * 3_600_000L),
            point("yesterday", at = now - day),
            point("last-week", at = now - 6 * day),
            point("spring", at = now - 200 * day),
        )
        val days = resumeDays(rows, now, utc)
        assertEquals(4, days.size)
        assertEquals("Today", days[0].first)
        assertEquals("Yesterday", days[1].first)
        // Six days back is a day of its own, under its date.
        assertEquals(dev.niccc2007.filet.ui.tabs.DayBuckets.label("d06", now - 6 * day, utc), days[2].first)
        assertEquals("Earlier this year", days[3].first)
        assertEquals(listOf("today-1", "today-2"), days[0].second.map { it.path })
        assertEquals(listOf("yesterday"), days[1].second.map { it.path })
        assertEquals(listOf("last-week"), days[2].second.map { it.path })
        assertEquals(listOf("spring"), days[3].second.map { it.path })
        // Nothing is dropped or repeated.
        assertEquals(rows.map { it.path }, days.flatMap { it.second }.map { it.path })
    }

    @Test
    fun `nothing to group is no headings`() {
        assertEquals(emptyList<Pair<String, List<ResumeStore.Point>>>(), resumeDays(emptyList(), now, utc))
    }

    @Test
    fun `undo puts the forgotten rows back as they were`() {
        val kept = listOf(point("b", at = 20L))
        val gone = listOf(point("a", at = 30L, pos = 90_000L), point("c", at = 10L, pos = 5_000L))
        val back = ResumeStore.restored(kept, gone)
        assertEquals(setOf("a", "b", "c"), back.map { it.path }.toSet())
        // Their own times and positions, so they land where they were once sorted.
        assertEquals(gone[0], back.first { it.path == "a" })
        assertEquals(listOf("a", "b", "c"), back.sortedByDescending { it.at }.map { it.path })
    }

    @Test
    fun `a row played again after the forget keeps its newer place on undo`() {
        val replayed = point("a", at = 99L, pos = 400_000L)
        val back = ResumeStore.restored(listOf(replayed), listOf(point("a", at = 30L, pos = 90_000L)))
        assertEquals(listOf(replayed), back)
    }

    @Test
    fun `forget all then undo is the list it was`() {
        val all = listOf(point("a", at = 3L), point("b", at = 2L), point("c", at = 1L))
        assertEquals(all, ResumeStore.restored(emptyList(), all))
    }
}

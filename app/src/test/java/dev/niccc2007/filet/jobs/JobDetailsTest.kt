package dev.niccc2007.filet.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The opened Activity row and Copy details are one list of facts. */
class JobDetailsTest {

    private val done = Job(
        id = 7L, title = "Copy 3 items", state = JobState.DONE,
        startedAt = 1_000_000L, endedAt = 1_000_000L + 125_000L,
        opened = "local:///storage/emulated/0/Download", kind = JobKind.COPY,
    )

    @Test
    fun `a job with no paths still has its state, times, duration, result and source`() {
        val labels = JobText.rows(done, now = 0L).map { it.label }
        assertEquals(listOf("State", "Started", "Ended", "Duration", "Result", "Source"), labels)
    }

    @Test
    fun `copy details is the title and then exactly the rows`() {
        val rows = JobText.rows(done, now = 0L)
        val text = JobText.details(done, now = 0L).lines()
        assertEquals("Copy 3 items", text.first())
        assertEquals(rows.map { "${it.label}: ${it.value}" }, text.drop(1))
    }

    @Test
    fun `durations read in the largest units that matter`() {
        assertEquals("2 min 5 s", JobText.took(done, now = 0L))
        assertEquals("under a second", JobText.took(done.copy(endedAt = done.startedAt + 400), now = 0L))
        assertEquals("1 h 1 min", JobText.took(done.copy(endedAt = done.startedAt + 3_660_000), now = 0L))
        // Still running: measured to now.
        val running = done.copy(state = JobState.RUNNING, endedAt = 0L)
        assertEquals("42 s", JobText.took(running, now = done.startedAt + 42_000))
    }

    @Test
    fun `paths are marked so the sheet can shorten them`() {
        val rows = JobText.rows(done.copy(from = "local:///a", to = "local:///b"), now = 0L)
        assertTrue(rows.filter { it.path }.map { it.label } == listOf("From", "To", "Result"))
    }
}

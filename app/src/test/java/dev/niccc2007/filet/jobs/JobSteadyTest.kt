package dev.niccc2007.filet.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A running job's opened card keeps one height while its values change. See [JobText.isLive]. */
class JobSteadyTest {

    private val running = Job(id = 1L, title = "Compress 2 folders", state = JobState.RUNNING, startedAt = 1_000L)

    private fun labels(job: Job, steady: Boolean) = JobText.rows(job, now = 5_000L, steady = steady).map { it.label }

    @Test
    fun `Speed, Left and Detail hold their place with a dash while nothing is known yet`() {
        val rows = JobText.rows(running, now = 5_000L, steady = true).associate { it.label to it.value }
        assertEquals(JobText.STILL, rows["Speed"])
        assertEquals(JobText.STILL, rows["Left"])
        assertEquals(JobText.STILL, rows["Detail"])
    }

    @Test
    fun `the rows are the same set whether the rate is known or not, so nothing pops in`() {
        val unknown = labels(running, steady = true)
        val known = labels(running.copy(bytesPerSec = 4_000_000.0, detail = "photos/a-very-long-name.jpg"), steady = true)
        assertEquals(unknown, known)
    }

    @Test
    fun `Copy details is unchanged - no dashes for what is not known`() {
        val copy = labels(running, steady = false)
        assertFalse("Speed" in copy)
        assertFalse("Left" in copy)
        assertFalse("Detail" in copy)
    }

    @Test
    fun `a finished job has no held rows`() {
        val done = running.copy(state = JobState.DONE, endedAt = 9_000L)
        assertEquals(labels(done, steady = false), labels(done, steady = true))
    }

    @Test
    fun `the values that change while running are one line, the paths still wrap`() {
        val job = running.copy(detail = "x")
        val rows = JobText.rows(job, now = 5_000L, steady = true).associateBy { it.label }
        assertTrue(JobText.isLive(job, rows.getValue("Detail")))
        assertTrue(JobText.isLive(job, rows.getValue("Speed")))
        assertFalse(JobText.isLive(job, rows.getValue("Source")))
        assertFalse(JobText.isLive(job, JobText.Row("From", "/storage/emulated/0/a", path = true)))
        // Finished, nothing moves any more, and everything is read in full.
        val done = job.copy(state = JobState.DONE)
        assertFalse(JobText.isLive(done, rows.getValue("Detail")))
    }
}

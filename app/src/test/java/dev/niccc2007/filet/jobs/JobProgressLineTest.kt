package dev.niccc2007.filet.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The line under a running job's bar: how far, how much, how long, and nothing made up. */
class JobProgressLineTest {

    private val start = 1_000_000L

    private fun job(
        progress: Float? = null,
        doneBytes: Long? = null,
        totalBytes: Long? = null,
        rate: Double? = null,
        doneItems: Int? = null,
        totalItems: Int? = null,
        state: JobState = JobState.RUNNING,
        paused: Boolean = false,
    ) = Job(
        id = 1, title = "Compressing 3 item(s)", state = state, progress = progress, startedAt = start,
        doneBytes = doneBytes, totalBytes = totalBytes, bytesPerSec = rate,
        doneItems = doneItems, totalItems = totalItems, paused = paused,
    )

    @Test fun `a job that knows its bytes and speed says all four`() {
        val mb = 1024L * 1024
        val line = JobText.progressLine(job(0.42f, 420 * mb, 1000 * mb, rate = 10.0 * mb), start + 42_000)
        // 580 MB left at 10 MB/s: 58 s, rounded to the next 5 s.
        assertEquals("42% · 420 MB of 1000 MB · 10.0 MB/s · about 60 s left", line)
    }

    @Test fun `without a speed the time left comes from how far it got and how long that took`() {
        // A quarter done in 30 s: 90 s to go.
        assertEquals("25% · about 2 min left", JobText.progressLine(job(0.25f), start + 30_000))
    }

    @Test fun `a job that cannot measure itself says what it can and no percentage`() {
        assertEquals("3 of 10 items", JobText.progressLine(job(doneItems = 3, totalItems = 10), start + 5_000))
        assertNull(JobText.progressLine(job(), start + 5_000))
    }

    @Test fun `a paused job says so, and a finished one has no line`() {
        assertEquals("40% · paused", JobText.progressLine(job(0.4f, paused = true), start + 5_000))
        assertNull(JobText.progressLine(job(1f, state = JobState.DONE), start + 5_000))
    }

    @Test fun `the percentage never reads 100 before the job is done`() {
        assertEquals("99%", JobText.percent(0.999f))
        assertEquals("0%", JobText.percent(-1f))
    }
}

package dev.niccc2007.filet.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** The pure half of Activity: what is kept, how a day is named, speed and time left, and the stored form. */
class JobHistoryTest {

    private val zone = ZoneId.of("Asia/Manila")
    private val day = 24L * 60 * 60 * 1000
    // 2026-10-28 12:00 in Manila.
    private val noon = 1_793_160_000_000L

    private fun done(id: Long, endedAt: Long, title: String = "Copying") =
        Job(id, title, state = JobState.DONE, startedAt = endedAt - 1_000, endedAt = endedAt)

    @Test
    fun `pruning keeps what is running whatever its age, and cuts old finished jobs`() {
        val running = Job(1, "Indexing storage", startedAt = noon - 40 * day)
        val fresh = done(2, noon - day)
        val stale = done(3, noon - 9 * day)
        assertEquals(listOf(1L, 2L), JobHistory.prune(listOf(running, fresh, stale), noon, 7).map { it.id })
        assertEquals(3, JobHistory.prune(listOf(running, fresh, stale), noon, 0).size)
        assertEquals(JobHistory.CAP, JobHistory.prune(List(400) { done(it.toLong(), noon) }, noon, 0).size)
    }

    @Test
    fun `a day is today, yesterday, or its date`() {
        assertEquals("Today", JobHistory.dayOf(noon - 3_600_000, noon, zone, Locale.US))
        assertEquals("Yesterday", JobHistory.dayOf(noon - day, noon, zone, Locale.US))
        assertEquals("Sun 25 Oct", JobHistory.dayOf(noon - 3 * day, noon, zone, Locale.US))
        assertEquals("28 Oct 2025", JobHistory.dayOf(noon - 365 * day, noon, zone, Locale.US))
    }

    @Test
    fun `jobs are grouped under their day in the order they came`() {
        val groups = JobHistory.byDay(listOf(done(1, noon), done(2, noon - 60_000), done(3, noon - day)), noon, zone)
        assertEquals(listOf("Today", "Yesterday"), groups.map { it.first })
        assertEquals(listOf(1L, 2L), groups[0].second.map { it.id })
    }

    @Test
    fun `the speed follows the copy and ignores the next file starting from zero`() {
        val first = JobRate.update(null, 0, 0, 5_000_000, 1_000)!!
        assertEquals(5_000_000.0, first, 1.0)
        // A slower second: the smoothed speed moves toward it, not all the way.
        val second = JobRate.update(first, 5_000_000, 1_000, 6_000_000, 2_000)!!
        assertTrue(second < first && second > 1_000_000.0)
        // A count that went down is a new file, not a negative speed.
        assertEquals(second, JobRate.update(second, 6_000_000, 2_000, 100, 2_100)!!, 0.0)
        assertEquals(second, JobRate.update(second, 6_000_000, 2_000, 7_000_000, 2_000)!!, 0.0)
    }

    @Test
    fun `time left comes from bytes and speed, or from how far it has got`() {
        val byBytes = Job(1, "Copying", doneBytes = 40_000_000, totalBytes = 100_000_000, bytesPerSec = 10_000_000.0)
        assertEquals(6_000L, JobRate.etaMs(byBytes, 0))
        val byFraction = Job(2, "Extracting", progress = 0.25f, startedAt = 0)
        assertEquals(30_000L, JobRate.etaMs(byFraction, 10_000))
        // Too early to say anything honest.
        assertNull(JobRate.etaMs(Job(3, "Extracting", progress = 0.01f, startedAt = 0), 10_000))
        assertNull(JobRate.etaMs(Job(4, "Extracting", progress = 0.5f, startedAt = 0), 500))
    }

    @Test
    fun `the bar's ring averages what can say, turns for what cannot, and is absent when idle`() {
        assertNull(JobRate.overall(listOf(done(1, noon))))
        assertEquals(-1f, JobRate.overall(listOf(Job(1, "Indexing storage"))))
        assertEquals(0.5f, JobRate.overall(listOf(Job(1, "a", progress = 0.25f), Job(2, "b", progress = 0.75f), Job(3, "c"))))
    }

    @Test
    fun `the words`() {
        assertEquals("a few seconds left", JobText.eta(4_000))
        assertEquals("about 25 s left", JobText.eta(22_000))
        assertEquals("about 3 min left", JobText.eta(170_000))
        assertEquals("about 1 h 5 min left", JobText.eta(3_900_000))
        assertEquals("1.5 MB", JobText.bytes(1_572_864))
        assertEquals("512 B", JobText.bytes(512))
        val j = Job(1, "Copying", doneItems = 12, totalItems = 40, doneBytes = 1_073_741_824, totalBytes = 3_221_225_472)
        assertEquals("12 of 40 items", JobText.items(j))
        assertEquals("1.0 GB of 3.0 GB", JobText.amount(j))
        assertEquals("1.0 GB so far", JobText.amount(j.copy(totalBytes = null)))
    }

    @Test
    fun `the stored form round trips and drops what it cannot read`() {
        val jobs = listOf(
            Job(
                7, "Extracting", "photos.zip", JobState.DONE, 1f, "12 file(s)", 100, 200,
                opened = "local:///x", kind = JobKind.EXTRACT, from = "a", to = "b",
                doneItems = 12, totalItems = 12, doneBytes = 5, totalBytes = 5,
            ),
            Job(8, "Deleting 1 item(s)", state = JobState.FAILED, error = "permission denied"),
        )
        val back = JobCodec.decode(JobCodec.encode(jobs))
        assertEquals(jobs.map { it.copy(bytesPerSec = null) }, back)
        assertEquals(1, JobCodec.decode("""[{"id":1,"title":"x","state":"DONE"},{"title":"no id"},"junk"]""").size)
        assertTrue(JobCodec.decode("not json").isEmpty())
        // A kind this build does not know falls back to the title.
        assertEquals(JobKind.COPY, JobCodec.decode("""[{"id":1,"title":"Copying 2 item(s)","state":"DONE","kind":"TELEPORT"}]""").single().kind)
    }

    @Test
    fun `a job left running at a restart is a failure with the reason, and offers nothing it cannot do`() {
        val back = JobHistory.afterRestart(listOf(Job(1, "Copying", cancellable = true, paused = true, canRunAgain = true, startedAt = 50)))
        val j = back.single()
        assertEquals(JobState.FAILED, j.state)
        assertEquals(JobHistory.INTERRUPTED, j.error)
        assertEquals(50L, j.endedAt)
        assertTrue(!j.cancellable && !j.paused && !j.canRunAgain)
    }
}

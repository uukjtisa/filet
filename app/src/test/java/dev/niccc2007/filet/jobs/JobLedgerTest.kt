package dev.niccc2007.filet.jobs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Activity tab is drawn from: endings that stay ended, pauses that hold, a history that survives. */
class JobLedgerTest {

    private class MemoryStore : JobStore {
        var text: String? = null
        override fun load() = text
        override fun save(text: String) { this.text = text }
    }

    private fun job(l: JobLedger, id: Long) = l.jobs.value.first { it.id == id }

    @Test
    fun `a stopped job stays stopped when its work reports finishing`() {
        // The op's own finally reports what it managed after a Stop. That report used to flip the
        // row the person had just stopped back to "done".
        val l = JobLedger()
        val id = l.start("Copying 3 item(s)", cancel = Job())
        l.requestCancel(id)
        l.finish(id, "3 item(s)")
        l.fail(id, "late")
        assertEquals(JobState.CANCELLED, job(l, id).state)
    }

    @Test
    fun `a paused job holds at its checkpoint until resumed`() = runBlocking {
        val l = JobLedger()
        val id = l.start("Copying", pausable = true)
        l.pause(id)
        assertTrue(job(l, id).paused)
        var passed = false
        val worker = launch { l.checkpoint(id); passed = true }
        delay(50)
        assertFalse(passed)
        l.resume(id)
        withTimeout(1_000) { worker.join() }
        assertTrue(passed)
        assertFalse(job(l, id).paused)
    }

    @Test
    fun `a pause is refused for work that has no checkpoint`() {
        // A Pause that could not pause anything is the same lie as a Stop that could not stop.
        val l = JobLedger()
        val id = l.start("Saving")
        l.pause(id)
        assertFalse(job(l, id).paused)
    }

    @Test
    fun `stopping a paused job ends it rather than leaving it held`() = runBlocking {
        val l = JobLedger()
        var threw = false
        val worker = launch(start = CoroutineStart.LAZY) {}
        val work = launch {
            val id = l.start("Copying", pausable = true, cancel = coroutineContext[Job])
            l.pause(id)
            try {
                l.checkpoint(id)
            } catch (e: CancellationException) {
                threw = true
                throw e
            }
        }
        delay(50)
        l.requestCancel(l.jobs.value.first().id)
        withTimeout(1_000) { work.join() }
        worker.cancel()
        assertTrue(threw)
        assertEquals(JobState.CANCELLED, l.jobs.value.first().state)
    }

    @Test
    fun `a held copy thread is let go on resume and on stop`() {
        val l = JobLedger()
        val id = l.start("Copying", pausable = true, cancel = Job())
        l.pause(id)
        val t = Thread { l.holdIfPaused(id) }.apply { name = "copy"; start() }
        Thread.sleep(50)
        assertTrue(t.isAlive)
        l.resume(id)
        t.join(1_000)
        assertFalse(t.isAlive)

        val id2 = l.start("Copying", pausable = true, cancel = Job())
        l.pause(id2)
        val t2 = Thread { l.holdIfPaused(id2) }.apply { name = "copy"; start() }
        Thread.sleep(50)
        l.requestCancel(id2)
        t2.join(1_000)
        assertFalse(t2.isAlive)
    }

    @Test
    fun `finished jobs survive a restart, and one left running is told as interrupted`() {
        val store = MemoryStore()
        val first = JobLedger(store)
        val done = first.start("Extracting", "photos.zip", opened = "local:///storage/emulated/0/photos")
        first.finish(done, "12 file(s)")
        first.start("Copying 40 item(s)")

        val second = JobLedger(store)
        val back = second.jobs.value
        assertEquals(2, back.size)
        val extracted = back.first { it.title == "Extracting" }
        assertEquals(JobState.DONE, extracted.state)
        assertEquals("local:///storage/emulated/0/photos", extracted.opened)
        val copying = back.first { it.title.startsWith("Copying") }
        assertEquals(JobState.FAILED, copying.state)
        assertEquals(JobHistory.INTERRUPTED, copying.error)
        // New ids carry on past the old ones rather than reusing them.
        assertTrue(second.start("Next") > back.maxOf { it.id })
    }

    @Test
    fun `finished jobs older than the setting are let go, running ones never`() {
        val store = MemoryStore()
        var clock = 1_000_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val l = JobLedger(store, keepDays = { 7 }, now = { clock })
        val old = l.start("Deleting 1 item(s)")
        l.finish(old)
        clock += 8 * day
        l.start("Indexing storage")
        val reread = JobLedger(store, keepDays = { 7 }, now = { clock })
        assertEquals(listOf("Indexing storage"), reread.jobs.value.map { it.title })
        // Forever keeps it.
        val forever = MemoryStore()
        val f = JobLedger(forever, keepDays = { 0 }, now = { clock - 8 * day })
        f.finish(f.start("Old"))
        assertEquals(1, JobLedger(forever, keepDays = { 0 }, now = { clock }).jobs.value.size)
    }

    @Test
    fun `remove and clear take finished jobs and leave running ones`() {
        val l = JobLedger()
        val running = l.start("Copying")
        val finished = l.start("Moving")
        l.finish(finished)
        l.remove(running)
        assertEquals(2, l.jobs.value.size)
        l.remove(finished)
        assertEquals(listOf(running), l.jobs.value.map { it.id })
        val another = l.start("Deleting")
        l.fail(another, "denied")
        l.clearFinished()
        assertEquals(listOf(running), l.jobs.value.map { it.id })
    }

    @Test
    fun `run again calls what the work left, and is not offered after a restart`() {
        val store = MemoryStore()
        val l = JobLedger(store)
        var ran = 0
        val id = l.start("Copying", runAgain = { ran++ })
        l.fail(id, "1 failed")
        assertTrue(job(l, id).canRunAgain)
        assertTrue(l.runAgain(id))
        assertEquals(1, ran)
        assertFalse(JobLedger(store).jobs.value.first().canRunAgain)
    }

    @Test
    fun `speed comes from the bytes and the clock`() {
        var clock = 0L
        val l = JobLedger(now = { clock })
        val id = l.start("Copying")
        l.progress(id, 0f, "a", doneBytes = 0)
        assertNull(job(l, id).bytesPerSec)
        clock = 1_000
        l.progress(id, 0.5f, "a", doneBytes = 10_000_000)
        assertEquals(10_000_000.0, job(l, id).bytesPerSec!!, 1.0)
    }

    @Test
    fun `waiting is still running, and progress starts it`() {
        val l = JobLedger()
        val id = l.start("Measuring beats", waiting = true)
        assertTrue(job(l, id).running)
        assertEquals(JobState.WAITING, job(l, id).state)
        assertEquals(1, l.activeCount)
        l.progress(id, 0.1f)
        assertEquals(JobState.RUNNING, job(l, id).state)
    }
}

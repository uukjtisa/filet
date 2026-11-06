package dev.niccc2007.filet.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Preparing the record…" lasts as long as the read, and not a moment past it. */
class RecordReadTest {

    private class Flag {
        val seen = ArrayList<Boolean>()
        var now = false
        val set: (Boolean) -> Unit = { seen += it; now = it }
    }

    @Test
    fun `a read that comes back with nothing ends the note - the reported bug`() = runBlocking {
        val f = Flag()
        val got = RecordRead.during(true, f.set) {
            assertTrue("on while it runs", f.now)
            null as String?
        }
        assertNull(got)
        assertEquals(listOf(true, false), f.seen)
    }

    @Test
    fun `a read that finishes ends it and hands the samples through`() = runBlocking {
        val f = Flag()
        assertEquals("pcm", RecordRead.during(true, f.set) { "pcm" })
        assertEquals(listOf(true, false), f.seen)
    }

    @Test
    fun `a read that throws ends it`() {
        val f = Flag()
        val thrown = runCatching { runBlocking { RecordRead.during(true, f.set) { error("decoder") } } }
        assertTrue(thrown.isFailure)
        assertEquals(listOf(true, false), f.seen)
    }

    @Test
    fun `a track change mid-read ends it`() = runBlocking {
        val f = Flag()
        val never = CompletableDeferred<String>()
        val job = launch(Dispatchers.Unconfined) {
            RecordRead.during(true, f.set) { never.await() }
        }
        yield()
        assertTrue(f.now)
        job.cancel(CancellationException("the track changed"))
        job.join()
        assertEquals(listOf(true, false), f.seen)
    }

    @Test
    fun `a read for the beat alone says nothing about the record`() = runBlocking {
        val f = Flag()
        RecordRead.during(false, f.set) { "pcm" }
        assertEquals(emptyList<Boolean>(), f.seen)
    }

    // ── the line itself ──

    private val disc = true

    @Test
    fun `a map ready from the cache and no read running shows nothing - the screenshots`() {
        assertNull(RecordRead.note(pulse = true, ready = TrackReady.READY, disc = disc, readingRecord = false))
        assertNull(RecordRead.note(pulse = false, ready = TrackReady.IDLE, disc = disc, readingRecord = false))
    }

    @Test
    fun `the record's line shows only while its read runs, and only on a face with a disc`() {
        assertEquals(
            "Preparing the record…",
            RecordRead.note(pulse = true, ready = TrackReady.READY, disc = disc, readingRecord = true),
        )
        assertNull(RecordRead.note(pulse = true, ready = TrackReady.READY, disc = false, readingRecord = true))
    }

    @Test
    fun `the beat's line wins over the record's`() {
        assertEquals(
            "Reading the beat…",
            RecordRead.note(pulse = true, ready = TrackReady.READING, disc = disc, readingRecord = true),
        )
        // The pulse off: the beat is not being waited for, so the record speaks.
        assertEquals(
            "Preparing the record…",
            RecordRead.note(pulse = false, ready = TrackReady.READING, disc = disc, readingRecord = true),
        )
    }
}

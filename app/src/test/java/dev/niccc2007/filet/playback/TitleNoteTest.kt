package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The line under a song's title says only what is being waited for, and the record never is. */
class TitleNoteTest {

    @Test
    fun `the beat's line shows while the beat is read, when the beat is wanted`() {
        assertEquals("Reading the beat…", TitleNote.note(pulse = true, ready = TrackReady.READING))
        assertNull("the pulse is off, so nothing waits for the beat", TitleNote.note(pulse = false, ready = TrackReady.READING))
    }

    @Test
    fun `a map ready from the cache shows nothing - the reported bug`() {
        // The case behind the fault: the map stored, the samples not, the decode running for the
        // disc. That decode is a preload the record does not wait for, so it says nothing.
        assertNull(TitleNote.note(pulse = true, ready = TrackReady.READY))
        assertNull(TitleNote.note(pulse = false, ready = TrackReady.IDLE))
    }

    @Test
    fun `a map already partly in hand is not announced as missing`() {
        // The wave is already running on the opening's map, so "reading" would be untrue.
        assertNull(TitleNote.note(pulse = true, ready = TrackReady.PARTIAL))
    }

    @Test
    fun `no state of the read names the record`() {
        for (pulse in listOf(true, false)) {
            for (ready in TrackReady.entries) {
                val line = TitleNote.note(pulse, ready)
                assertTrue("$pulse $ready: $line", line == null || !line.contains("record", ignoreCase = true))
            }
        }
    }
}

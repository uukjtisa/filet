package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Carry on's tag for a row played to its end, and the rows that get none. */
class FinishedTagTest {

    private fun point(at: Long, length: Long) = ResumeStore.Point("local:///x.mp4", "x.mp4", true, at, length, 0L)

    @Test fun `played to the end reads 100% FINISHED`() {
        assertEquals("100% FINISHED", finishedTag(point(at = 60_000L, length = 60_000L)))
    }

    @Test fun `99_9% reads 99, never claiming the end it did not reach`() {
        assertEquals("99% FINISHED", finishedTag(point(at = 99_900L, length = 100_000L)))
    }

    @Test fun `part way has no tag, its percentage stays`() {
        assertNull(finishedTag(point(at = 50_000L, length = 100_000L)))
        assertEquals("50%", resumePercent(point(at = 50_000L, length = 100_000L)))
    }

    @Test fun `an unknown length is never tagged`() {
        assertNull(finishedTag(point(at = 50_000L, length = 0L)))
    }
}

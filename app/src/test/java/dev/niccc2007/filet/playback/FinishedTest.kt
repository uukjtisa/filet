package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where Carry on's "Finished" starts, its percentage, and where a finished row opens. */
class FinishedTest {

    @Test fun `99 percent and the end itself are finished`() {
        assertTrue(Finished.at(positionMs = 99_000L, durationMs = 100_000L))
        assertTrue(Finished.at(positionMs = 100_000L, durationMs = 100_000L))
    }

    @Test fun `just short of 99 percent is not`() {
        assertFalse(Finished.at(positionMs = 98_999L, durationMs = 100_000L))
    }

    @Test fun `a film's credits count and a song's last second counts`() {
        // Two hours: the last 72 seconds.
        assertTrue(Finished.at(positionMs = 7_128_000L, durationMs = 7_200_000L))
        // Three minutes: the last 1.8 seconds.
        assertTrue(Finished.at(positionMs = 178_200L, durationMs = 180_000L))
        assertFalse(Finished.at(positionMs = 175_000L, durationMs = 180_000L))
    }

    @Test fun `an unknown length is never finished`() {
        assertFalse(Finished.at(positionMs = 50_000L, durationMs = 0L))
        assertFalse(Finished.at(positionMs = 50_000L, durationMs = -1L))
    }

    @Test fun `the percentage floors, so 99_9 reads 99`() {
        assertEquals(99, Finished.percent(positionMs = 99_900L, durationMs = 100_000L))
        assertEquals(100, Finished.percent(positionMs = 100_000L, durationMs = 100_000L))
    }

    @Test fun `the percentage stays in range`() {
        assertEquals(100, Finished.percent(positionMs = 101_000L, durationMs = 100_000L))
        assertEquals(0, Finished.percent(positionMs = -5L, durationMs = 100_000L))
        assertEquals(0, Finished.percent(positionMs = 50_000L, durationMs = 0L))
    }

    @Test fun `a finished row opens at the start, any other where it stopped`() {
        assertEquals(0L, Finished.startAt(positionMs = 99_500L, durationMs = 100_000L))
        assertEquals(42_000L, Finished.startAt(positionMs = 42_000L, durationMs = 100_000L))
        assertEquals(42_000L, Finished.startAt(positionMs = 42_000L, durationMs = 0L))
    }
}

package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.playback.ResumeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #53: Carry on for videos, and the preview's Play from - one record for both. */
class VideoResumeTest {

    private val tenMinutes = 10 * 60_000L

    // ── what the pill offers ──

    @Test
    fun `a stop at 0 09 is saved, so it survives a scroll away and back`() {
        assertTrue(ResumeStore.keeps(9_000L))
        // The screen that stopped it is gone; the preview built on coming back reads the store.
        val from = VideoResume.playFrom(savedMs = 9_000L, savedDurationMs = tenMinutes, stoppedAtMs = 0L)
        assertEquals(9_000L, from)
        assertEquals("Play from 0:09", VideoResume.label(from))
    }

    @Test
    fun `the saved place wins over this visit's stop when both are there`() {
        // Left at 3:00 here, carried on to 8:00 as music elsewhere: 8:00 is the later word.
        assertEquals(480_000L, VideoResume.playFrom(480_000L, tenMinutes, stoppedAtMs = 180_000L))
    }

    @Test
    fun `a finished saved place plays from the start`() {
        // Kept in Carry on, tagged finished; carrying on at the end would end at once.
        assertEquals(0L, VideoResume.playFrom(tenMinutes, tenMinutes, stoppedAtMs = 0L))
        assertEquals("99% is finished", 0L, VideoResume.playFrom(594_000L, tenMinutes, stoppedAtMs = 0L))
        assertEquals("98% is not", 588_000L, VideoResume.playFrom(588_000L, tenMinutes, stoppedAtMs = 0L))
    }

    @Test
    fun `a place with no known length is carried on from`() {
        assertEquals(4_000L, VideoResume.playFrom(4_000L, savedDurationMs = 0L, stoppedAtMs = 0L))
    }

    @Test
    fun `with nothing saved, this visit's stop still carries on`() {
        // A write that failed: Back then Play still carries on for as long as the screen is up.
        assertEquals(5_000L, VideoResume.playFrom(savedMs = null, savedDurationMs = 0L, stoppedAtMs = 5_000L))
    }

    @Test
    fun `nothing saved and nothing stopped plays from the start`() {
        assertEquals(0L, VideoResume.playFrom(null, 0L, 0L))
        assertEquals(0L, VideoResume.playFrom(0L, tenMinutes, 0L))
    }

    @Test
    fun `the pill says Play from and the time`() {
        assertEquals("Play from 0:09", VideoResume.label(9_000L))
        assertEquals("Play from 1:02:03", VideoResume.label(3_723_000L))
    }

    // ── taking over from the music player ──

    @Test
    fun `taking over while the music player is still opening the file uses the saved place`() {
        // Carry on asked for 12:34; the music player reads 0 until it has sought.
        assertEquals(754_000L, VideoResume.takeOverAt(liveMs = 0L, savedMs = 754_000L, savedDurationMs = 3_600_000L))
    }

    @Test
    fun `taking over a finished video starts it again`() {
        assertEquals(0L, VideoResume.takeOverAt(liveMs = 0L, savedMs = 3_600_000L, savedDurationMs = 3_600_000L))
    }

    @Test
    fun `taking over a video playing as music uses where the music has got to`() {
        assertEquals(761_500L, VideoResume.takeOverAt(liveMs = 761_500L, savedMs = 754_000L, savedDurationMs = 3_600_000L))
        assertEquals(42_000L, VideoResume.takeOverAt(liveMs = 42_000L, savedMs = null, savedDurationMs = 0L))
    }

    @Test
    fun `nothing saved and nothing playing takes over at the start`() {
        assertEquals(0L, VideoResume.takeOverAt(liveMs = 0L, savedMs = null, savedDurationMs = 0L))
    }
}

package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.playback.ResumeStore
import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #53: Carry on for videos, and the preview's Play from - one record for both. */
class VideoResumeTest {

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

package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.playback.ResumeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #53: Carry on for videos, and the preview's Play from. */
class VideoResumeTest {

    @Test
    fun `the saved place is what Play carries on from`() {
        assertEquals(9_000L, VideoResume.playFrom(savedMs = 9_000L, stoppedAtMs = 0L))
        // Scrolled away and back: the screen's own stop is gone, the saved place is not.
        assertEquals(125_000L, VideoResume.playFrom(savedMs = 125_000L, stoppedAtMs = 0L))
    }

    @Test
    fun `the saved place wins over this visit's stop when both are there`() {
        // Left at 3:00 here, carried on to 8:00 as music elsewhere: 8:00 is the later word.
        assertEquals(480_000L, VideoResume.playFrom(savedMs = 480_000L, stoppedAtMs = 180_000L))
    }

    @Test
    fun `what the store will not keep still carries on for this visit`() {
        // A ten-second clip stopped at 0:05 is never saved; Back and Play still carry on.
        assertFalse(ResumeStore.worthKeeping(5_000L, 10_000L))
        assertEquals(5_000L, VideoResume.playFrom(savedMs = null, stoppedAtMs = 5_000L))
        // Nor a stop in the last twenty seconds.
        assertFalse(ResumeStore.worthKeeping(590_000L, 600_000L))
        assertEquals(590_000L, VideoResume.playFrom(savedMs = null, stoppedAtMs = 590_000L))
    }

    @Test
    fun `nothing saved and nothing stopped plays from the start`() {
        assertEquals(0L, VideoResume.playFrom(savedMs = null, stoppedAtMs = 0L))
        assertEquals(0L, VideoResume.playFrom(savedMs = 0L, stoppedAtMs = 0L))
    }

    @Test
    fun `a video stopped halfway is worth keeping from fifteen seconds long`() {
        for (d in listOf(15_000L, 40_000L, 10 * 60_000L, 2 * 60 * 60_000L)) {
            assertTrue("halfway through $d ms", ResumeStore.worthKeeping(d / 2, d))
        }
        assertFalse("under fifteen seconds is never kept", ResumeStore.worthKeeping(7_000L, 14_000L))
    }

    @Test
    fun `taking over while the music player is still opening the file uses the saved place`() {
        // Carry on asked for 12:34; the music player reads 0 until it has sought.
        assertEquals(754_000L, VideoResume.takeOverAt(liveMs = 0L, savedMs = 754_000L))
    }

    @Test
    fun `taking over a video playing as music uses where the music has got to`() {
        assertEquals(761_500L, VideoResume.takeOverAt(liveMs = 761_500L, savedMs = 754_000L))
        assertEquals(42_000L, VideoResume.takeOverAt(liveMs = 42_000L, savedMs = null))
    }

    @Test
    fun `Carry on from the start takes over at the start`() {
        // From the start forgets the saved place before asking the music player to play.
        assertEquals(0L, VideoResume.takeOverAt(liveMs = 0L, savedMs = null))
    }

    @Test
    fun `the pill says Play from and the time`() {
        assertEquals("Play from 0:09", VideoResume.label(9_000L))
        assertEquals("Play from 1:02:03", VideoResume.label(3_723_000L))
    }
}

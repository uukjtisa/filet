package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.playback.ResumeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #53: Carry on for videos, and the preview's Play from. */
class VideoResumeTest {

    private val path = "/storage/emulated/0/Movies/clip.mp4"

    // ── what the pill offers ──

    @Test
    fun `a stop at 0 09 survives a scroll away and back`() {
        // Too early for Carry on to list, so the saved place is gone...
        assertFalse(ResumeStore.worthKeeping(9_000L, 10 * 60_000L))
        val stops = LastStops()
        stops.note(path, 9_000L)
        // ...and the screen that noted it is gone too. The preview built on coming back reads:
        val from = VideoResume.playFrom(savedMs = null, lastStopMs = stops.of(path))
        assertEquals(9_000L, from)
        assertEquals("Play from 0:09", VideoResume.label(from))
    }

    @Test
    fun `playing to the end clears the last stop`() {
        val stops = LastStops()
        stops.note(path, 9_000L)
        stops.clear(path)
        assertNull(stops.of(path))
        assertEquals(0L, VideoResume.playFrom(savedMs = null, lastStopMs = stops.of(path)))
    }

    @Test
    fun `the saved place wins over the last stop when both are there`() {
        // Left at 3:00 here, carried on to 8:00 as music elsewhere: 8:00 is the later word.
        assertEquals(480_000L, VideoResume.playFrom(savedMs = 480_000L, lastStopMs = 180_000L))
    }

    @Test
    fun `nothing saved and nothing stopped plays from the start`() {
        assertEquals(0L, VideoResume.playFrom(savedMs = null, lastStopMs = null))
        assertEquals(0L, VideoResume.playFrom(savedMs = 0L, lastStopMs = 0L))
    }

    @Test
    fun `what Carry on will not list is still where the pill carries on from`() {
        // A ten-second clip stopped at 0:05, and a stop in the last twenty seconds.
        assertFalse(ResumeStore.worthKeeping(5_000L, 10_000L))
        assertEquals(5_000L, VideoResume.playFrom(savedMs = null, lastStopMs = 5_000L))
        assertFalse(ResumeStore.worthKeeping(590_000L, 600_000L))
        assertEquals(590_000L, VideoResume.playFrom(savedMs = null, lastStopMs = 590_000L))
    }

    @Test
    fun `a video stopped halfway is listed from fifteen seconds long`() {
        for (d in listOf(15_000L, 40_000L, 10 * 60_000L, 2 * 60 * 60_000L)) {
            assertTrue("halfway through $d ms", ResumeStore.worthKeeping(d / 2, d))
        }
        assertFalse("under fifteen seconds is never listed", ResumeStore.worthKeeping(7_000L, 14_000L))
    }

    @Test
    fun `the pill says Play from and the time`() {
        assertEquals("Play from 0:09", VideoResume.label(9_000L))
        assertEquals("Play from 1:02:03", VideoResume.label(3_723_000L))
    }

    // ── the last stops ──

    @Test
    fun `a later stop replaces the earlier one`() {
        val stops = LastStops()
        stops.note(path, 9_000L)
        stops.note(path, 64_000L)
        assertEquals(64_000L, stops.of(path))
        assertEquals(1, stops.all.value.size)
    }

    @Test
    fun `a stop at the very start is no place`() {
        val stops = LastStops()
        stops.note(path, 9_000L)
        stops.note(path, 0L)
        assertNull(stops.of(path))
    }

    @Test
    fun `only the most recent stops are held`() {
        val stops = LastStops()
        for (i in 0..LastStops.MAX) stops.note("/v/$i.mp4", 1_000L + i)
        assertEquals(LastStops.MAX, stops.all.value.size)
        assertNull("the oldest went", stops.of("/v/0.mp4"))
        assertEquals(1_000L + LastStops.MAX, stops.of("/v/${LastStops.MAX}.mp4"))
    }

    @Test
    fun `stopping a video again makes it the newest, so the cap does not drop it`() {
        val stops = LastStops()
        for (i in 0 until LastStops.MAX) stops.note("/v/$i.mp4", 1_000L)
        stops.note("/v/0.mp4", 2_000L)
        stops.note("/v/new.mp4", 1_000L)
        assertEquals(2_000L, stops.of("/v/0.mp4"))
        assertNull(stops.of("/v/1.mp4"))
    }

    // ── taking over from the music player ──

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
}

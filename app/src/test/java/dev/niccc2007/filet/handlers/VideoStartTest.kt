package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.handlers.VideoStart.BackStep
import dev.niccc2007.filet.handlers.VideoStart.Face
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a video plays at once, when it waits under its preview, and what Back does to it. */
class VideoStartTest {

    @Test
    fun `an opened video waits for a tap, whatever the next-video setting`() {
        assertEquals(Face.PREVIEW, VideoStart.opensOn(takingOver = false, arrived = false, nextPlays = false))
        assertEquals(Face.PREVIEW, VideoStart.opensOn(takingOver = false, arrived = false, nextPlays = true))
    }

    @Test
    fun `a video stepped or swiped to follows the next-video setting`() {
        assertEquals(Face.PREVIEW, VideoStart.opensOn(takingOver = false, arrived = true, nextPlays = false))
        assertEquals(Face.PLAYING, VideoStart.opensOn(takingOver = false, arrived = true, nextPlays = true))
    }

    @Test
    fun `a video taken back from the music player carries on playing`() {
        for (arrived in listOf(false, true)) {
            for (nextPlays in listOf(false, true)) {
                assertEquals(Face.PLAYING, VideoStart.opensOn(takingOver = true, arrived = arrived, nextPlays = nextPlays))
            }
        }
    }

    @Test
    fun `back steps from playing to the preview, and from the preview out`() {
        // Opened: the preview. Played. Back: the preview again. Back: out.
        var face = VideoStart.opensOn(takingOver = false, arrived = false, nextPlays = false)
        assertEquals(Face.PREVIEW, face)
        face = Face.PLAYING
        assertEquals(BackStep.TO_PREVIEW, VideoStart.back(face, handingOver = false))
        face = Face.PREVIEW
        assertEquals(BackStep.LEAVE, VideoStart.back(face, handingOver = false))
        // A video that opened playing still stops at its preview first.
        assertEquals(BackStep.TO_PREVIEW, VideoStart.back(VideoStart.opensOn(true, false, false), handingOver = false))
    }

    @Test
    fun `back while the sound is being handed to the music player leaves with it`() {
        assertEquals(BackStep.LEAVE, VideoStart.back(Face.PLAYING, handingOver = true))
        assertEquals(BackStep.LEAVE, VideoStart.back(Face.PREVIEW, handingOver = true))
    }

    @Test
    fun `the page a run opened on is opened until the run moves off it`() {
        assertFalse(VideoStart.arrived(page = 3, opened = 3, movedOff = false))
        assertTrue(VideoStart.arrived(page = 4, opened = 3, movedOff = false))
        assertTrue(VideoStart.arrived(page = 2, opened = 3, movedOff = false))
        // Back on it after moving away is arriving.
        assertTrue(VideoStart.arrived(page = 3, opened = 3, movedOff = true))
    }
}

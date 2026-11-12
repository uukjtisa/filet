package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoLadderTest {

    @Test fun `the phone's player falls to Media3, then FFmpeg, then nothing`() {
        assertEquals(VideoRung.MEDIA3, VideoLadder.next(VideoRung.PLATFORM, ffmpeg = true))
        assertEquals(VideoRung.FFMPEG, VideoLadder.next(VideoRung.MEDIA3, ffmpeg = true))
        assertNull(VideoLadder.next(VideoRung.FFMPEG, ffmpeg = true))
    }

    @Test fun `without FFmpeg in the build Media3 is the last rung`() {
        assertEquals(VideoRung.MEDIA3, VideoLadder.next(VideoRung.PLATFORM, ffmpeg = false))
        assertNull(VideoLadder.next(VideoRung.MEDIA3, ffmpeg = false))
    }

    @Test fun `started in Filet's player, the phone's player is the last try`() {
        val start = VideoRung.MEDIA3
        assertEquals(VideoRung.FFMPEG, VideoLadder.next(VideoRung.MEDIA3, ffmpeg = true, start = start))
        assertEquals(VideoRung.PLATFORM, VideoLadder.next(VideoRung.FFMPEG, ffmpeg = true, start = start))
        assertNull(VideoLadder.next(VideoRung.PLATFORM, ffmpeg = true, start = start))
        assertEquals(VideoRung.PLATFORM, VideoLadder.next(VideoRung.MEDIA3, ffmpeg = false, start = start))
    }

    @Test fun `FFmpeg already drawing the picture is not stepped to again`() {
        assertNull(VideoLadder.next(VideoRung.MEDIA3, ffmpeg = true, pictureByFfmpeg = true))
        assertEquals(
            VideoRung.PLATFORM,
            VideoLadder.next(VideoRung.MEDIA3, ffmpeg = true, pictureByFfmpeg = true, start = VideoRung.MEDIA3),
        )
    }

    @Test fun `no order tries a rung twice`() {
        for (start in VideoRung.entries) {
            val order = VideoLadder.order(start)
            assertEquals(order.distinct(), order)
            assertEquals(start, order.first())
        }
    }

    @Test fun `a video starts on the rung of the engine chosen, and the menu ticks it`() {
        for (kind in EngineKind.entries) {
            assertEquals(kind, VideoLadder.engineOf(VideoLadder.first(kind)))
        }
        assertEquals(VideoRung.PLATFORM, VideoLadder.first(EngineKind.PLATFORM))
        assertEquals(VideoRung.MEDIA3, VideoLadder.first(EngineKind.MEDIA3))
        assertEquals(EngineKind.MEDIA3, VideoLadder.engineOf(VideoRung.FFMPEG))
    }

    @Test fun `every rung reached by falling says so`() {
        for (r in VideoRung.entries) assert(VideoLadder.notice(r).isNotBlank())
    }
}

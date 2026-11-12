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

    @Test fun `a video starts on the rung of the engine chosen`() {
        assertEquals(VideoRung.PLATFORM, VideoLadder.first(EngineKind.PLATFORM))
        assertEquals(VideoRung.MEDIA3, VideoLadder.first(EngineKind.MEDIA3))
    }

    @Test fun `every rung reached by falling says so`() {
        for (r in VideoRung.entries) assert(VideoLadder.notice(r).isNotBlank())
    }
}

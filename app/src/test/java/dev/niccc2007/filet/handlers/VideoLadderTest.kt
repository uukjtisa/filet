package dev.niccc2007.filet.handlers

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLadderTest {

    @Test fun `a failure in Filet's player offers FFmpeg, then the phone's player`() {
        assertEquals(listOf(VideoRung.FFMPEG, VideoRung.PLATFORM), VideoLadder.offers(VideoRung.MEDIA3, ffmpeg = true))
    }

    @Test fun `without FFmpeg in the build, only the phone's player is offered`() {
        assertEquals(listOf(VideoRung.PLATFORM), VideoLadder.offers(VideoRung.MEDIA3, ffmpeg = false))
    }

    @Test fun `FFmpeg already drawing the picture is not offered again`() {
        assertEquals(listOf(VideoRung.PLATFORM), VideoLadder.offers(VideoRung.MEDIA3, ffmpeg = true, pictureByFfmpeg = true))
        assertEquals(listOf(VideoRung.PLATFORM), VideoLadder.offers(VideoRung.FFMPEG, ffmpeg = true))
    }

    @Test fun `a failure in the phone's player offers Filet's`() {
        assertEquals(listOf(VideoRung.MEDIA3), VideoLadder.offers(VideoRung.PLATFORM, ffmpeg = true))
    }

    @Test fun `nothing offers the player that just failed, and everything offered has a tap`() {
        for (r in VideoRung.entries) for (f in listOf(true, false)) for (p in listOf(true, false)) {
            val o = VideoLadder.offers(r, f, p)
            assertFalse("$r offered itself", r in o)
            assertEquals(o.distinct(), o)
            for (x in o) assertTrue(VideoLadder.offerLabel(x).isNotBlank())
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

    @Test fun `an error says which player failed, why, and keeps the numbers`() {
        val m = VideoLadder.failure(VideoRung.MEDIA3, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, 0)
        assertTrue(m, m.startsWith("Filet's player could not play this video: the decoder would not start"))
        assertTrue(m, m.endsWith("(error 4001/0)."))
        assertTrue(VideoLadder.failure(VideoRung.MEDIA3, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, 0).contains("no decoder here plays this format"))
        assertTrue(VideoLadder.failure(VideoRung.FFMPEG, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, 0).contains("could not be read"))
        val p = VideoLadder.failure(VideoRung.PLATFORM, 1, -1010)
        assertTrue(p, p.startsWith("The phone's player could not play this video: this format is not supported"))
        assertTrue(VideoLadder.failure(VideoRung.PLATFORM, 1, 12345).contains("(error 1/12345)"))
    }

    @Test fun `every line a tap or the menu shows names where the video is`() {
        for (r in VideoRung.entries) assertTrue(VideoLadder.tried(r, "1:02").endsWith("at 1:02"))
        for (k in EngineKind.entries) assertTrue(VideoLadder.switched(k, "1:02").endsWith("at 1:02"))
    }
}

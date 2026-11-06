package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.handlers.VideoDecode.Decoder
import dev.niccc2007.filet.handlers.VideoDecode.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A video whose picture never came, and the words for it. See VideoDecode. */
class VideoDecodeTest {

    // The phone it was found on: a Snapdragon 778G, its decoders as its own codec list states them.
    private val qti = Decoder("c2.qti.avc.decoder", hardware = true, maxWidth = 4096, maxHeight = 2176, sizeFits = false, fits = false)
    private val google = Decoder("c2.android.avc.decoder", hardware = false, maxWidth = 4080, maxHeight = 4080, sizeFits = true, fits = true, maxFps = 60.0)
    private val tall = Stream("video/avc", 2160, 3402, 60.0)

    @Test
    fun `the file that was black says what it is and where the phone stops`() {
        val w = VideoDecode.words(tall, listOf(qti, google))
        assertTrue(w, w.startsWith("The sound played, but no picture came."))
        assertTrue(w, w.contains("H.264 video at 2160 × 3402 and 60 frames a second"))
        assertTrue(w, w.contains("hardware video decoder takes pictures up to 4096 × 2176"))
        assertTrue(w, w.contains("Only its software decoder says it takes this"))
        assertTrue(w, w.endsWith("Another app may still show it."))
    }

    @Test
    fun `a type the phone has no decoder for is named`() {
        val w = VideoDecode.words(Stream("video/hevc", 3840, 2160, 30.0), emptyList())
        assertTrue(w, w.contains("this phone has no decoder for H.265 (HEVC)"))
    }

    @Test
    fun `a size it takes at a speed it does not is said as the speed`() {
        val slow = qti.copy(sizeFits = true, fits = false, maxFps = 36.0)
        val w = VideoDecode.words(tall, listOf(slow))
        assertTrue(w, w.contains("takes at most 36 frames a second at that size"))
        assertFalse(w, w.contains("software"))
    }

    @Test
    fun `when the phone says it can, the words do not blame the size`() {
        val w = VideoDecode.words(tall, listOf(qti.copy(sizeFits = true, fits = true)))
        assertTrue(w, w.contains("which this phone says it can decode, so the cause is not its size or speed"))
        val unknown = VideoDecode.words(null, emptyList())
        assertTrue(unknown, unknown.contains("could not draw this video on this phone"))
        val noRate = VideoDecode.words(tall.copy(fps = null), listOf(qti))
        assertTrue(noRate, noRate.contains("H.264 video at 2160 × 3402."))
    }

    @Test
    fun `frame rates read the way people write them`() {
        assertEquals("60", VideoDecode.fpsText(60.0))
        assertEquals("29.97", VideoDecode.fpsText(29.97))
        assertEquals("23.98", VideoDecode.fpsText(23.976))
        assertEquals("30.5", VideoDecode.fpsText(30.5))
    }

    @Test
    fun `only a file with a video track is watched, and a share is given longer`() {
        assertTrue(VideoDecode.hasVideoTrack(listOf(1, 2)))
        assertFalse("sound only", VideoDecode.hasVideoTrack(listOf(2)))
        assertFalse("tracks unreadable", VideoDecode.hasVideoTrack(emptyList()))
        assertEquals(VideoDecode.NO_PICTURE_MS, VideoDecode.windowFor(staged = false))
        assertEquals(VideoDecode.NO_PICTURE_STAGED_MS, VideoDecode.windowFor(staged = true))
        assertTrue(VideoDecode.NO_PICTURE_STAGED_MS > VideoDecode.NO_PICTURE_MS)
    }

    // ── the watch ──

    private val step = PictureWatch.TICK_MS

    private fun PictureWatch.run(times: Int, playing: Boolean = true, advanced: Boolean = true): Boolean =
        (1..times).map { tick(step, playing, advanced, seeked = false) }.any { it }

    @Test
    fun `it speaks once, when the window has run with no picture`() {
        val w = PictureWatch(3_000)
        assertFalse(w.run(5))
        assertTrue("at three seconds", w.tick(step, playing = true, advanced = true, seeked = false))
        assertTrue(w.done)
        assertFalse("never again this opening", w.run(50))
    }

    @Test
    fun `a paused video and a stalled one never trip it`() {
        val w = PictureWatch(3_000)
        assertFalse(w.run(100, playing = false))
        assertFalse(w.run(100, advanced = false))
        assertFalse(w.done)
    }

    @Test
    fun `a seek and a resume both start the count again`() {
        val seek = PictureWatch(3_000)
        assertFalse(seek.run(5))
        assertFalse(seek.tick(step, playing = true, advanced = true, seeked = true))
        assertFalse(seek.run(5))
        assertTrue(seek.run(1))

        val resume = PictureWatch(3_000)
        assertFalse(resume.run(5))
        assertFalse(resume.run(1, playing = false))
        assertFalse(resume.run(5))
        assertTrue(resume.run(1))
    }

    @Test
    fun `a drawn picture ends it for good`() {
        val w = PictureWatch(3_000)
        assertFalse(w.run(3))
        w.drawn()
        assertTrue(w.done)
        assertFalse(w.run(100))
    }

    @Test
    fun `a share waits its longer window`() {
        val w = PictureWatch(VideoDecode.windowFor(staged = true))
        assertFalse(w.run(15))
        assertTrue(w.run(1))
    }
}

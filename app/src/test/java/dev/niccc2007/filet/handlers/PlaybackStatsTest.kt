package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatsTest {

    private fun snap(
        at: Long,
        shown: Long? = null,
        dropped: Long? = null,
        offsetSum: Long? = null,
        offsetN: Long? = null,
    ) = PlaybackStats.Snapshot(
        atMs = at,
        pictureDecoder = "c2.qti.avc.decoder",
        hardware = true,
        soundDecoder = "c2.android.aac.decoder",
        mime = "video/avc",
        width = 2160,
        height = 3402,
        fps = 60f,
        bitrate = 38_400_000,
        shown = shown,
        dropped = dropped,
        offsetUsTotal = offsetSum,
        offsetCount = offsetN,
        bufferedMs = 24_100,
    )

    @Test
    fun `rates are per second over the time between the two reads`() {
        val r = PlaybackStats.rates(snap(1_000, 100, 10), snap(3_000, 200, 30))
        assertEquals(50, r.shownPerSec)
        assertEquals(10, r.droppedPerSec)
    }

    @Test
    fun `the first read has no rates yet`() {
        val r = PlaybackStats.rates(null, snap(1_000, 100, 10))
        assertNull(r.shownPerSec)
        assertNull(r.droppedPerSec)
        assertNull(r.earlyMs)
    }

    @Test
    fun `totals that went back are a new decoder, not a negative rate`() {
        val r = PlaybackStats.rates(snap(1_000, 500, 40), snap(2_000, 20, 1))
        assertNull(r.shownPerSec)
        assertNull(r.droppedPerSec)
    }

    @Test
    fun `how early frames were ready is the window's own average, and can be late`() {
        // 60 frames, 12 ms early each.
        val early = PlaybackStats.rates(snap(0, offsetSum = 0, offsetN = 0), snap(1_000, offsetSum = 720_000, offsetN = 60))
        assertEquals(12, early.earlyMs)
        // The running sum falls when frames come late: that is a late window, not a reset.
        val late = PlaybackStats.rates(snap(0, offsetSum = 720_000, offsetN = 60), snap(1_000, offsetSum = 420_000, offsetN = 120))
        assertEquals(-5, late.earlyMs)
        // No frames in the window: nothing to say.
        assertNull(PlaybackStats.rates(snap(0, offsetSum = 5, offsetN = 3), snap(1_000, offsetSum = 5, offsetN = 3)).earlyMs)
    }

    @Test
    fun `codec names are the everyday ones`() {
        assertEquals("H.264", PlaybackStats.codecName("video/avc"))
        assertEquals("H.265", PlaybackStats.codecName("video/hevc"))
        assertEquals("VP9", PlaybackStats.codecName("video/x-vnd.on2.vp9"))
        assertEquals("AV1", PlaybackStats.codecName("video/av01"))
        assertNull(PlaybackStats.codecName(null))
    }

    @Test
    fun `software decoders are told from hardware ones by name`() {
        assertTrue(PlaybackStats.softwareByName("c2.android.avc.decoder"))
        assertTrue(PlaybackStats.softwareByName("OMX.google.h264.decoder"))
        assertTrue(PlaybackStats.softwareByName("ffmpegVideo"))
        assertFalse(PlaybackStats.softwareByName("c2.qti.avc.decoder"))
        assertFalse(PlaybackStats.softwareByName("OMX.qcom.video.decoder.avc"))
    }

    @Test
    fun `the readout names the decoder, the format, the frames and the screen`() {
        val now = snap(2_000, shown = 1_480, dropped = 12, offsetSum = 720_000, offsetN = 60)
        val lines = PlaybackStats.lines(
            now,
            PlaybackStats.Rates(shownPerSec = 59, droppedPerSec = 1, earlyMs = 12),
            screenHz = 120f,
            heat = "warm",
        ).toMap()
        assertEquals("c2.qti.avc.decoder · hardware", lines["Picture"])
        assertEquals("H.264 · 2160×3402 · 60 fps · 38.4 Mb/s", lines["Video"])
        assertEquals("shown 59/s · dropped 1/s (12 of 1492)", lines["Frames"])
        assertEquals("12 ms before due", lines["Ready"])
        assertEquals("24.1 s ahead", lines["Buffer"])
        assertEquals("120 Hz · phone warm", lines["Screen"])
        assertEquals("c2.android.aac.decoder", lines["Sound"])
        assertNull("speed is only shown when it is not 1x", lines["Speed"])
    }

    @Test
    fun `a late window and a fractional rate read plainly`() {
        val now = snap(2_000, shown = 10, dropped = 0).copy(fps = 29.97f, speed = 1.5f)
        val lines = PlaybackStats.lines(now, PlaybackStats.Rates(null, null, -8), null, null).toMap()
        assertEquals("8 ms late", lines["Ready"])
        assertTrue(lines["Video"]!!.contains("29.97 fps"))
        assertEquals("shown … · dropped … (0 of 10)", lines["Frames"])
        assertEquals("1.5×", lines["Speed"])
        assertNull("no screen line when neither the rate nor the heat is known", lines["Screen"])
    }

    @Test
    fun `a decoder that would not start is named under the one drawing, two at most`() {
        val now = snap(2_000).copy(
            pictureDecoder = "c2.android.avc.decoder",
            hardware = false,
            refused = listOf("c2.qti.avc.decoder: busy", "c2.qti.avc.decoder: busy again", "c2.qti.avc.decoder.low_latency: busy"),
        )
        val lines = PlaybackStats.lines(now, PlaybackStats.Rates(null, null, null), null, null)
        assertEquals("Picture", lines[0].first)
        assertEquals(listOf("c2.qti.avc.decoder: busy", "c2.qti.avc.decoder: busy again"), lines.filter { it.first == "Refused" }.map { it.second })
        assertEquals(1, lines.indexOfFirst { it.first == "Refused" })
    }

    @Test
    fun `frames skipped before decoding get their own line, once there are any`() {
        val none = PlaybackStats.lines(snap(1_000, 10, 0), PlaybackStats.Rates(10, 0, 5), null, null).toMap()
        assertNull(none["Skipped"])
        val some = snap(2_000, 40, 2).copy(hurried = 25)
        val r = PlaybackStats.rates(snap(1_000, 10, 0).copy(hurried = 5), some)
        assertEquals(20, r.hurriedPerSec)
        val lines = PlaybackStats.lines(some, r, null, null).toMap()
        assertEquals("20/s before decoding (25 in all)", lines["Skipped"])
    }

    @Test
    fun `heat is words, and nothing when the phone is cool`() {
        assertNull(PlaybackStats.heat(0))
        assertEquals("warm", PlaybackStats.heat(1))
        assertEquals("overheating", PlaybackStats.heat(5))
    }
}

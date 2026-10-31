package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The arithmetic of the latency measurements, which is all of them that runs without a phone. */
class LatencyProbeTest {

    @Test
    fun `the output's position runs on from its timestamp at the sample rate`() {
        // Presented frame 48 000 at t = 1 s; a quarter of a second later it is 12 000 frames on.
        assertEquals(60_000.0, LatencyProbe.presentedAt(48_000, 1_000_000_000L, 1_250_000_000L, 48_000), 1e-6)
        // A timestamp taken after "now" (a race between the two clocks) runs backwards, not off.
        assertEquals(47_520.0, LatencyProbe.presentedAt(48_000, 1_010_000_000L, 1_000_000_000L, 48_000), 1e-6)
    }

    @Test
    fun `downstream is what the mixer took and the output has not yet played`() {
        // 9 600 frames taken, 0 presented at 48 kHz: 200 ms in the pipe, a Bluetooth headset.
        assertEquals(200.0, LatencyProbe.downstreamMs(9_600, 0.0, 48_000), 1e-9)
        assertEquals(40.0, LatencyProbe.downstreamMs(50_000, 48_080.0, 48_000), 1e-9)
    }

    @Test
    fun `the start gap is the first sample that moved past rounding`() {
        val samples = listOf(0L to 1000L, 5L to 1000L, 10L to 1001L, 180L to 1002L, 185L to 1007L, 190L to 1012L)
        assertEquals(185L, LatencyProbe.firstMove(samples))
        // A position that never moves has no gap to report, and neither has no sample at all.
        assertNull(LatencyProbe.firstMove(listOf(0L to 500L, 1500L to 501L)))
        assertNull(LatencyProbe.firstMove(emptyList()))
    }

    @Test
    fun `the median of an odd and an even count, and of nothing`() {
        assertEquals(3.0, LatencyProbe.median(listOf(9.0, 1.0, 3.0))!!, 0.0)
        assertEquals(2.5, LatencyProbe.median(listOf(4.0, 1.0, 2.0, 3.0))!!, 0.0)
        assertNull(LatencyProbe.median(emptyList()))
    }
}

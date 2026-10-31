package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame by frame: the file's own frames from its sample times, where a step and a jump land on
 * them, the estimate used until they are read, and the bar's readout and hold pace.
 */
class FrameStepTest {

    /** Sample times in microseconds, as the extractor gives them. */
    private fun us(vararg ms: Double) = LongArray(ms.size) { (ms[it] * 1000).toLong() }

    /** A 60-a-second run of [n] frames from [fromMs], in microseconds. */
    private fun sixty(n: Int, fromMs: Double = 0.0) = DoubleArray(n) { fromMs + it * 1000.0 / 60 }

    @Test
    fun `frames read in decoding order come out in the order they are shown`() {
        // I P B B: the P frame is stored before the two B frames shown ahead of it.
        val list = FrameList.of(us(0.0, 100.0, 33.367, 66.733))!!
        assertEquals(4, list.size)
        assertEquals(listOf(0L, 33L, 67L, 100L), (0 until list.size).map { list.timeOf(it) })
    }

    @Test
    fun `a frame stored twice, or before the start, is not a frame of its own`() {
        // A frame split over two samples, two times under a millisecond apart, and a pre-roll
        // frame an edit list puts before zero.
        val list = FrameList.of(us(-33.0, 0.0, 16.6, 16.9, 33.4, 33.4))!!
        assertEquals(listOf(0L, 17L, 33L), (0 until list.size).map { list.timeOf(it) })
        assertNull(FrameList.of(us(-50.0, -10.0)))
        assertNull(FrameList.of(LongArray(0)))
        // Only the first count are read; the rest of the buffer is unused.
        assertEquals(2, FrameList.of(us(0.0, 40.0, 0.0, 0.0), count = 2)!!.size)
    }

    @Test
    fun `the frame at a position is the one on screen then, a millisecond short of a frame included`() {
        val list = FrameList.of(us(*sixty(10)))!!
        // 16.667 ms is sought as 17, and a player may report 16 or 17 for it.
        assertEquals(1, list.at(16))
        assertEquals(1, list.at(17))
        assertEquals(1, list.at(31))
        // Frame 2 is shown from 33.3 ms, so 32 is a millisecond short of it.
        assertEquals(2, list.at(32))
        assertEquals(2, list.at(33))
        // Before the first frame is shown, the first is the one there.
        assertEquals(0, FrameList.of(us(40.0, 80.0))!!.at(0))
        assertEquals(9, list.at(60_000))
    }

    @Test
    fun `a variable-rate clip steps on its own frames, not on the header's average`() {
        // Bug identified here: a 60-a-second picture whose header averages 13.9 a second, because
        // a stretch of it ran at 10. A step on the average's grid jumped 72 ms, four frames.
        val frames = sixty(60) + DoubleArray(10) { 1000.0 + it * 100.0 } + sixty(60, 2000.0)
        val list = FrameList.of(us(*frames))!!
        var at = 0L
        repeat(59) { at = list.step(at, 1) }
        assertEquals(983L, at)
        // Into the slow stretch: each step is that stretch's own 100 ms.
        at = list.step(at, 1)
        assertEquals(1000L, at)
        at = list.step(at, 1)
        assertEquals(1100L, at)
        // And back out at sixty a second, frame by frame, with no frame met twice.
        val seen = mutableListOf<Long>()
        repeat(15) { at = list.step(at, 1); seen += at }
        assertEquals(seen.distinct(), seen)
        assertEquals(2050L, list.step(2033, 1))
        // The header's estimate would have gone 72 ms in one step from the same place.
        assertEquals(72L, FrameStep.target(0, 10_000, 13.9, 1))
    }

    @Test
    fun `a step of two, five or ten frames counts frames, and stops at the first and the last`() {
        val list = FrameList.of(us(*sixty(30)))!!
        assertEquals(list.timeOf(5), list.step(0, 5))
        assertEquals(list.timeOf(15), list.step(list.timeOf(5), 10))
        assertEquals(list.timeOf(13), list.step(list.timeOf(15), -2))
        assertEquals(list.timeOf(29), list.step(list.timeOf(25), 10))
        assertEquals(0L, list.step(list.timeOf(3), -10))
    }

    @Test
    fun `a jump is the frame nearest a second away, and always moves while it can`() {
        val list = FrameList.of(us(*sixty(180)))!!
        // From frame 30 (500 ms): 1500 ms is frame 90.
        assertEquals(1500L, list.jump(500, 1000))
        assertEquals(0L, list.jump(500, -1000))
        // Held at the ends.
        assertEquals(list.timeOf(179), list.jump(list.timeOf(170), 1000))
        // A still longer than the jump: the nearest frame is the one shown, so it goes to the next.
        val still = FrameList.of(us(0.0, 16.0, 5000.0, 5016.0))!!
        assertEquals(5000L, still.jump(16, 1000))
        assertEquals(16L, still.jump(5000, -1000))
        // The nearest of two equally near is the earlier.
        assertEquals(0, FrameList.of(us(0.0, 20.0))!!.nearest(10))
    }

    @Test
    fun `the frame count over the length is the estimate's rate, when the file says both`() {
        assertEquals(30.0, FrameStep.fpsOf("300", "10000", null), 1e-9)
        assertEquals(60.0, FrameStep.fpsOf("600", "10000", "30"), 1e-9)
        assertEquals(24.0, FrameStep.fpsOf("240", "10000", null), 1e-9)
    }

    @Test
    fun `without a count the capture rate is used, and without either thirty a second`() {
        assertEquals(24.0, FrameStep.fpsOf(null, "10000", "24"), 1e-9)
        assertEquals(30.0, FrameStep.fpsOf(null, null, null), 1e-9)
        // A header that does not make sense is not believed.
        assertEquals(30.0, FrameStep.fpsOf("2", "10000", null), 1e-9)
        assertEquals(30.0, FrameStep.fpsOf("x", "y", "NaN"), 1e-9)
        assertEquals(30.0, FrameStep.fpsOf("0", "0", "0"), 1e-9)
    }

    @Test
    fun `an estimated step lands on the grid, and a long run of steps never drifts off it`() {
        val fps = 60.0
        var at = 0L
        repeat(600) { at = FrameStep.target(at, 60_000, fps, 1) }
        // Six hundred frames at sixty a second is ten seconds, exactly.
        assertEquals(10_000L, at)
        repeat(600) { at = FrameStep.target(at, 60_000, fps, -1) }
        assertEquals(0L, at)
        // From between two frames, the step is from the nearer one.
        assertEquals(50L, FrameStep.target(40, 60_000, fps, 1))
        // Five or ten at a time is five or ten frames.
        assertEquals(83L, FrameStep.target(0, 60_000, fps, 5))
        assertEquals(167L, FrameStep.target(0, 60_000, fps, 10))
    }

    @Test
    fun `an estimated step or jump stays inside the video at both ends`() {
        assertEquals(0L, FrameStep.target(10, 5000, 30.0, -1))
        assertEquals(5000L, FrameStep.target(4990, 5000, 30.0, 1))
        // An unknown length is held only at the start.
        assertEquals(1033L, FrameStep.target(1000, 0, 30.0, 1))
        assertEquals(0L, FrameStep.jump(400, 5000, -1000))
        assertEquals(5000L, FrameStep.jump(4500, 5000, 1000))
        assertEquals(2400L, FrameStep.jump(1400, 0, 1000))
    }

    @Test
    fun `the readout is the frame's number from one and its time to the millisecond`() {
        assertEquals("#1234 · 0:41.233", FrameStep.readout(1234, 41_233, exact = true))
        assertEquals("#1 · 0:00.000", FrameStep.readout(1, 0, exact = true))
        assertEquals("≈#31 · 0:01.000", FrameStep.readout(FrameStep.numberAt(1000, 30.0), 1000, exact = false))
        assertEquals("1:02:03.004", FrameStep.preciseClock(3_723_004))
        assertEquals("12:00.050", FrameStep.preciseClock(720_050))
    }

    @Test
    fun `a held button waits before repeating, so a tap is one step, then keeps a pace frames can be seen at`() {
        assertEquals(FrameStep.HOLD_FIRST_MS, FrameStep.holdDelay(0))
        assertEquals(FrameStep.HOLD_EVERY_MS, FrameStep.holdDelay(1))
        assertEquals(FrameStep.HOLD_EVERY_MS, FrameStep.holdDelay(40))
        // A tap is well under the first wait; the pace is under ten frames a second.
        assertTrue(FrameStep.HOLD_FIRST_MS >= 300)
        assertTrue(1000 / FrameStep.HOLD_EVERY_MS in 4L..10L)
    }
}

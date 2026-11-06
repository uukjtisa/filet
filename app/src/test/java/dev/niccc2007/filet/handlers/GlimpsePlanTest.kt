package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Where a video glimpse's clips land: NxDashVids' timing. */
class GlimpsePlanTest {

    @Test
    fun `three clips of a second and a half, one in each third of the middle 70 percent`() {
        assertEquals(3, GlimpsePlan.CLIPS)
        assertEquals(1_500L, GlimpsePlan.CLIP_MS)
        for (seed in 0 until 200) {
            val duration = 60_000L + seed * 37_919L
            val clips = GlimpsePlan.of(duration, Random(seed))
            assertEquals(GlimpsePlan.CLIPS, clips.size)
            val from = (duration * 0.15).toLong()
            val to = (duration * 0.85).toLong()
            val third = (to - from) / GlimpsePlan.CLIPS
            clips.forEachIndexed { i, c ->
                assertEquals(GlimpsePlan.CLIP_MS, c.lengthMs)
                assertTrue("seed $seed clip $i starts in its third", c.startMs >= from + i * third)
                assertTrue("seed $seed clip $i ends in its third", c.endMs <= from + (i + 1) * third)
            }
            assertTrue("never overlap", clips.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
        }
    }

    @Test
    fun `a round is four and a half seconds, not twelve`() {
        assertEquals(4_500L, GlimpsePlan.of(10 * 60_000L, Random(1)).sumOf { it.lengthMs })
    }

    @Test
    fun `nothing from the opening 15 percent or the closing 15 percent of a long video`() {
        val duration = 2 * 60 * 60_000L
        for (seed in 0 until 100) {
            for (c in GlimpsePlan.of(duration, Random(seed))) {
                assertTrue(c.startMs >= duration * 15 / 100)
                assertTrue(c.endMs <= duration * 85 / 100)
            }
        }
    }

    @Test
    fun `a middle too short for the clips falls back to all but two seconds at each end`() {
        // 9 s: 15-85% is 6.3 s, under the clips' 4.5 s plus 2 s.
        assertEquals(2_000L to 7_000L, GlimpsePlan.zone(9_000L))
        // 10 s: 7 s, enough.
        assertEquals(1_500L to 8_500L, GlimpsePlan.zone(10_000L))
        for (duration in listOf(5_000L, 6_000L, 7_500L, 9_000L)) {
            for (seed in 0 until 50) {
                val clips = GlimpsePlan.of(duration, Random(seed))
                assertEquals(GlimpsePlan.CLIPS, clips.size)
                assertTrue(clips.all { it.startMs >= GlimpsePlan.EDGE_MS && it.endMs <= duration })
            }
        }
    }

    @Test
    fun `under five seconds plays whole, a two-second video included`() {
        val clips = GlimpsePlan.of(4_999L, Random(1))
        assertEquals(listOf(GlimpsePlan.Clip(0L, 4_999L)), clips)
        assertTrue(GlimpsePlan.isWhole(clips))
        // NxDashVids shows nothing under three seconds; here it still plays.
        assertEquals(listOf(GlimpsePlan.Clip(0L, 2_000L)), GlimpsePlan.of(2_000L))
        assertEquals(listOf(GlimpsePlan.Clip(0L, 800L)), GlimpsePlan.of(800L))
    }

    @Test
    fun `an unknown length has no plan`() {
        assertTrue(GlimpsePlan.of(0L).isEmpty())
        assertTrue(GlimpsePlan.of(-1L).isEmpty())
    }

    @Test
    fun `the spots move from one hold to the next`() {
        val a = GlimpsePlan.of(10 * 60_000L, Random(1)).map { it.startMs }
        val b = GlimpsePlan.of(10 * 60_000L, Random(2)).map { it.startMs }
        assertTrue(a != b)
    }
}

package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Where a video glimpse's clips land. */
class GlimpsePlanTest {

    @Test
    fun `five clips of three seconds, one inside each fifth, in order`() {
        for (seed in 0 until 200) {
            val duration = 15_000L + seed * 7_919L
            val clips = GlimpsePlan.of(duration, Random(seed))
            assertEquals(GlimpsePlan.CLIPS, clips.size)
            val fifth = duration / GlimpsePlan.CLIPS
            clips.forEachIndexed { i, c ->
                assertEquals(GlimpsePlan.CLIP_MS, c.lengthMs)
                assertTrue("seed $seed clip $i starts in its fifth", c.startMs >= i * fifth)
                assertTrue("seed $seed clip $i ends in its fifth", c.endMs <= (i + 1) * fifth)
            }
            assertTrue("never overlap", clips.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
        }
    }

    @Test
    fun `exactly fifteen seconds is five clips end to end`() {
        val clips = GlimpsePlan.of(15_000L, Random(1))
        assertEquals(listOf(0L, 3_000L, 6_000L, 9_000L, 12_000L), clips.map { it.startMs })
    }

    @Test
    fun `a short video plays whole`() {
        val clips = GlimpsePlan.of(14_999L, Random(1))
        assertEquals(listOf(GlimpsePlan.Clip(0L, 14_999L)), clips)
        assertTrue(GlimpsePlan.isWhole(clips))
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

package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Where a video glimpse's clips land: five, from the opening to the ending. */
class GlimpsePlanTest {

    @Test
    fun `five clips of 1_2 seconds, one in each fifth of 3 to 97 percent`() {
        assertEquals(5, GlimpsePlan.CLIPS)
        assertEquals(1_200L, GlimpsePlan.CLIP_MS)
        for (seed in 0 until 200) {
            val duration = 60_000L + seed * 37_919L
            val clips = GlimpsePlan.of(duration, Random(seed))
            assertEquals(GlimpsePlan.CLIPS, clips.size)
            val from = (duration * 0.03).toLong()
            val to = (duration * 0.97).toLong()
            val fifth = (to - from) / GlimpsePlan.CLIPS
            clips.forEachIndexed { i, c ->
                assertEquals(GlimpsePlan.CLIP_MS, c.lengthMs)
                assertTrue("seed $seed clip $i starts in its fifth", c.startMs >= from + i * fifth)
                assertTrue("seed $seed clip $i ends in its fifth", c.endMs <= from + (i + 1) * fifth)
            }
            assertTrue("never overlap", clips.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
        }
    }

    @Test
    fun `a round is six seconds`() {
        assertEquals(6_000L, GlimpsePlan.of(10 * 60_000L, Random(1)).sumOf { it.lengthMs })
    }

    @Test
    fun `the first clip is from the opening and the last from the ending`() {
        // The middle-70% plan never reached either, so a round was not a glimpse of the whole.
        val duration = 10 * 60_000L
        for (seed in 0 until 100) {
            val clips = GlimpsePlan.of(duration, Random(seed))
            assertTrue("seed $seed opens in the first fifth", clips.first().endMs <= duration * 218 / 1000)
            assertTrue("seed $seed ends in the last fifth", clips.last().startMs >= duration * 782 / 1000)
        }
    }

    @Test
    fun `nothing from the very first or last 3 percent`() {
        val duration = 2 * 60 * 60_000L
        for (seed in 0 until 100) {
            for (c in GlimpsePlan.of(duration, Random(seed))) {
                assertTrue(c.startMs >= duration * 3 / 100)
                assertTrue(c.endMs <= duration * 97 / 100)
            }
        }
    }

    @Test
    fun `a video too short to hold the clips apart plays whole`() {
        // 94% of 6.383 s is the first length that holds five 1.2 s clips end to end.
        assertTrue(GlimpsePlan.playsWhole(6_382L))
        assertFalse(GlimpsePlan.playsWhole(6_383L))
        assertEquals(listOf(GlimpsePlan.Clip(0L, 6_382L)), GlimpsePlan.of(6_382L, Random(1)))
        assertTrue(GlimpsePlan.isWhole(GlimpsePlan.of(5_000L)))
        for (seed in 0 until 50) {
            val clips = GlimpsePlan.of(6_383L, Random(seed))
            assertEquals(GlimpsePlan.CLIPS, clips.size)
            assertTrue(clips.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
            assertTrue(clips.all { it.startMs >= 0L && it.endMs <= 6_383L })
        }
    }

    @Test
    fun `under three seconds still plays whole, where NxDashVids shows nothing`() {
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

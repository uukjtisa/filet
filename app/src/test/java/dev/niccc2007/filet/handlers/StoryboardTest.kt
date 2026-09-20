package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which frames a scrub preview keeps, in what order they are fetched, and which one to draw.
 *
 * Reported as a preview that did not work, twice, and correctly diagnosed as the architecture
 * rather than a bug: reading a frame takes tens of milliseconds and a drag wants one every
 * few, so decoding during the gesture can only chase it. Frames are pulled once into a cache
 * and the drag reads memory.
 */
class StoryboardTest {

    private val TEN_MIN = 10L * 60 * 1000

    // ── which frames ──

    @Test
    fun `a storyboard covers the whole video`() {
        val p = Storyboard.plan(TEN_MIN)
        assertEquals(0L, p.first())
        assertEquals(TEN_MIN, p.last())
    }

    @Test
    fun `frames are evenly spaced`() {
        val p = Storyboard.plan(TEN_MIN)
        val gaps = p.zipWithNext { a, b -> b - a }.distinct()
        // Integer truncation allows a millisecond of wobble, nothing more.
        assertTrue("gaps were $gaps", gaps.max() - gaps.min() <= 1L)
    }

    @Test
    fun `it holds the number of frames it planned for`() {
        assertEquals(Storyboard.framesFor(TEN_MIN), Storyboard.plan(TEN_MIN).size)
    }

    // ── how many frames ──

    @Test
    fun `spacing stays near the target rather than the count staying fixed`() {
        // Reported as "only 2 seconds each skip". The count used to be fixed at sixty, so a
        // two-minute video landed on exactly two-second steps. What should hold steady is the
        // spacing, not the number of pictures.
        // Up to where the memory ceiling binds, the target is met. Past it the spacing grows
        // rather than the app pretending - and spacingFor says which is which.
        for (d in listOf(30_000L, 60_000L, 2L * 60_000, 4L * 60_000)) {
            val plan = Storyboard.plan(d)
            val spacing = d / (plan.size - 1)
            assertEquals("$d ms", Storyboard.spacingFor(d), spacing)
            assertTrue("$d ms gave ${spacing}ms steps", spacing <= Storyboard.TARGET_SPACING_MS + 50)
        }
    }

    @Test
    fun `a two minute video no longer steps in two seconds`() {
        val plan = Storyboard.plan(2L * 60_000)
        val spacing = (2L * 60_000) / (plan.size - 1)
        assertTrue("still ${spacing}ms", spacing < 1_500L)
    }

    @Test
    fun `a short clip still gets enough frames to scrub`() {
        assertEquals(Storyboard.MIN_FRAMES, Storyboard.framesFor(5_000L))
        // And the floor is no lower than what every video used to get, so making long videos
        // finer cannot have made short ones coarser.
        assertTrue(Storyboard.MIN_FRAMES >= 60)
    }

    @Test
    fun `a long film is capped by memory, not by ambition`() {
        assertEquals(Storyboard.MAX_FRAMES, Storyboard.framesFor(3L * 60 * 60 * 1000))
    }

    @Test
    fun `past the ceiling the spacing grows instead of the memory`() {
        // The honest degradation. A two-hour film cannot be covered at a frame a second by
        // any amount of cleverness; what matters is that it says so rather than trying.
        val long = 2L * 60 * 60 * 1000
        assertTrue(Storyboard.spacingFor(long) > Storyboard.TARGET_SPACING_MS)
        assertEquals(Storyboard.MAX_FRAMES, Storyboard.plan(long).size)
    }

    @Test
    fun `no length is coarser than the old fixed sixty frames`() {
        // Sixty frames gave duration/59 at every length. The floor and the ceiling mean the
        // new spacing can equal that at one point - a one-minute video IS sixty frames at one
        // a second - but it must never be worse anywhere.
        for (d in listOf(10_000L, 30_000L, 60_000L, 2L * 60_000, 10L * 60_000, 2L * 60 * 60 * 1000)) {
            val was = d / 59
            val now = Storyboard.spacingFor(d)
            assertTrue("$d ms: was ${was}ms, now ${now}ms", now <= was)
        }
    }

    @Test
    fun `anything longer than a minute is strictly finer than before`() {
        // Where the complaint actually lives. Below a minute the old spacing was already
        // under a second, so there was nothing to win.
        for (d in listOf(90_000L, 2L * 60_000, 5L * 60_000, 30L * 60_000)) {
            val was = d / 59
            val now = Storyboard.spacingFor(d)
            assertTrue("$d ms: was ${was}ms, now ${now}ms", now < was)
        }
    }

    @Test
    fun `an unknown duration falls back to the floor`() {
        assertEquals(Storyboard.MIN_FRAMES, Storyboard.framesFor(0L))
    }

    @Test
    fun `a video shorter than its frame count does not repeat a timestamp`() {
        // A 10ms video cannot have sixty distinct frames, and asking for the same one sixty
        // times is sixty decodes for one picture.
        val p = Storyboard.plan(10L)
        assertEquals(p.size, p.distinct().size)
    }

    @Test
    fun `an unknown duration plans nothing`() {
        assertTrue(Storyboard.plan(0L).isEmpty())
        assertTrue(Storyboard.plan(-5L).isEmpty())
    }

    @Test
    fun `a single frame is the first frame`() {
        assertEquals(listOf(0L), Storyboard.plan(TEN_MIN, frames = 1))
    }

    // ── the order they are fetched in ──

    @Test
    fun `every frame is eventually fetched, exactly once`() {
        val count = Storyboard.framesFor(TEN_MIN)
        val order = Storyboard.fillOrder(count)
        assertEquals(count, order.size)
        assertEquals(count, order.distinct().size)
        assertEquals((0 until count).toSet(), order.toSet())
    }

    @Test
    fun `the ends come first`() {
        // The two positions a drag reaches most often.
        val order = Storyboard.fillOrder(20)
        assertEquals(0, order[0])
        assertEquals(19, order[1])
    }

    @Test
    fun `coverage is even while it is still filling`() {
        // The point of bisecting. Filling left to right leaves a scrub to the end of a long
        // video with nothing to show until the wait is nearly over.
        val count = 64
        val order = Storyboard.fillOrder(count)
        // After a quarter of the work, no part of the video should be far from a known frame.
        val early = order.take(count / 4).toSet()
        val worst = (0 until count).maxOf { i -> early.minOf { kotlin.math.abs(it - i) } }
        assertTrue("worst gap was $worst frames", worst <= count / 8)
    }

    @Test
    fun `filling left to right would fail that same check`() {
        // The negative control for the test above, so it is known to be able to fail.
        val count = 64
        val inOrder = (0 until count).take(count / 4).toSet()
        val worst = (0 until count).maxOf { i -> inOrder.minOf { kotlin.math.abs(it - i) } }
        assertTrue("left-to-right was somehow even: $worst", worst > count / 8)
    }

    @Test
    fun `an empty storyboard has no order`() {
        assertTrue(Storyboard.fillOrder(0).isEmpty())
        assertTrue(Storyboard.fillOrder(-3).isEmpty())
    }

    @Test
    fun `one frame is its own order`() {
        assertEquals(listOf(0), Storyboard.fillOrder(1))
    }

    // ── which one to draw ──

    @Test
    fun `the nearest extracted frame is drawn`() {
        val have = listOf(0L, 10_000L, 20_000L)
        assertEquals(10_000L, Storyboard.nearest(11_000L, have))
        assertEquals(20_000L, Storyboard.nearest(19_000L, have))
    }

    @Test
    fun `nearest works in both directions`() {
        // Insisting on the preceding frame leaves the start of the video blank while the
        // storyboard is still filling.
        assertEquals(10_000L, Storyboard.nearest(9_000L, listOf(10_000L, 30_000L)))
    }

    @Test
    fun `nothing extracted yet draws nothing`() {
        assertNull(Storyboard.nearest(5_000L, emptyList()))
    }

    @Test
    fun `a position beyond everything extracted takes the last one`() {
        assertEquals(20_000L, Storyboard.nearest(999_000L, listOf(0L, 10_000L, 20_000L)))
    }

    @Test
    fun `a half filled storyboard still answers everywhere`() {
        // The behaviour that makes filling in the background acceptable: rough immediately,
        // sharpening as it goes, never blank.
        val plan = Storyboard.plan(TEN_MIN)
        val order = Storyboard.fillOrder(plan.size)
        val half = order.take(plan.size / 2).map { plan[it] }
        for (at in 0..TEN_MIN step 5_000L) {
            assertTrue("nothing for $at", Storyboard.nearest(at, half) != null)
        }
    }
}

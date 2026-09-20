package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The thumbnail shown while dragging the progress bar.
 *
 * The decoding is the platform's job; what is tested here is the part that decides how often
 * to ask it, because that is where a preview goes wrong. Ask for every reported position and
 * the decodes queue up and arrive after the finger has gone, so the preview plays the drag
 * back rather than following it.
 */
class ScrubPreviewTest {

    private val DURATION = 300_000L

    // ── quantising ──

    @Test
    fun `nearby positions ask for the same frame`() {
        // A slow drag must sit on one decoded frame instead of asking for a hundred
        // neighbours nobody could tell apart.
        // Measured from a real step boundary: an arbitrary millisecond is part-way through a
        // step, so adding step-1 to it crosses into the next one.
        val step = ScrubPreview.stepFor(DURATION)
        val base = ScrubPreview.frameFor(10_000L, DURATION)
        assertEquals(base, ScrubPreview.frameFor(base, DURATION))
        assertEquals(base, ScrubPreview.frameFor(base + step - 1, DURATION))
    }

    @Test
    fun `moving a step along asks for a different frame`() {
        val step = ScrubPreview.stepFor(DURATION)
        val base = ScrubPreview.frameFor(10_000L, DURATION)
        assertTrue(ScrubPreview.frameFor(base + step, DURATION) > base)
    }

    @Test
    fun `frames land on step boundaries`() {
        for (p in listOf(0L, 1L, 1_999L, 2_000L, 55_555L, 299_999L)) {
            assertEquals(0L, ScrubPreview.frameFor(p, DURATION) % ScrubPreview.stepFor(DURATION))
        }
    }

    // ── edges ──

    @Test
    fun `the end of the video still previews`() {
        // A request past the end returns nothing from the platform, which reads as the
        // preview breaking at the right-hand edge of the bar.
        val f = ScrubPreview.frameFor(DURATION, DURATION)
        assertTrue(f in 0..DURATION)
    }

    @Test
    fun `dragging past either end is clamped`() {
        assertTrue(ScrubPreview.frameFor(-5_000L, DURATION) >= 0L)
        assertTrue(ScrubPreview.frameFor(DURATION * 4, DURATION) <= DURATION)
    }

    @Test
    fun `an unknown duration asks for nothing silly`() {
        assertEquals(0L, ScrubPreview.frameFor(10_000L, durationMs = 0L))
    }

    @Test
    fun `a video shorter than one step still previews its start`() {
        assertEquals(0L, ScrubPreview.frameFor(100L, durationMs = 900L))
    }

    // ── the step scales with the video ──

    @Test
    fun `a short clip gets a finer step than a film`() {
        // The fault reported as "the preview isn't smooth": a fixed two-second step is barely
        // a step on a film and an eternity on a thirty-second clip, so short videos moved in
        // visible jumps.
        val clip = ScrubPreview.stepFor(30_000L)
        val film = ScrubPreview.stepFor(2L * 60 * 60 * 1000)
        assertTrue("$clip vs $film", clip < film)
    }

    @Test
    fun `the step never goes below what the decoder can keep up with`() {
        assertEquals(ScrubPreview.MIN_STEP_MS, ScrubPreview.stepFor(1_000L))
        assertEquals(ScrubPreview.MIN_STEP_MS, ScrubPreview.stepFor(0L))
    }

    @Test
    fun `the step never grows past being useful`() {
        assertEquals(ScrubPreview.MAX_STEP_MS, ScrubPreview.stepFor(50L * 60 * 60 * 1000))
    }

    @Test
    fun `every length gets enough frames to feel continuous`() {
        // What the fixed step got wrong. Short videos are scaled to FRAMES_ACROSS; long ones
        // are held at MAX_STEP_MS and so get MORE frames than that, which is the right way to
        // be wrong - more frames is smoother, it is only more decoding.
        for (d in listOf(30_000L, 5L * 60_000, 45L * 60_000, 2L * 60 * 60 * 1000)) {
            val step = ScrubPreview.stepFor(d)
            val frames = d / step
            assertTrue("$d ms gave a $step ms step", step in ScrubPreview.MIN_STEP_MS..ScrubPreview.MAX_STEP_MS)
            assertTrue("$d ms gave only $frames frames", frames >= 20)
        }
    }

    // ── how often to decode ──

    @Test
    fun `the frame already on screen is not decoded again`() {
        assertFalse(ScrubPreview.shouldDecode(have = 4_000L, running = null, want = 4_000L))
    }

    @Test
    fun `a frame already being decoded is not started twice`() {
        assertFalse(ScrubPreview.shouldDecode(have = 2_000L, running = 4_000L, want = 4_000L))
    }

    @Test
    fun `a new frame is decoded`() {
        assertTrue(ScrubPreview.shouldDecode(have = 2_000L, running = null, want = 6_000L))
        assertTrue(ScrubPreview.shouldDecode(have = null, running = null, want = 0L))
    }

    @Test
    fun `the first frame of a drag is decoded`() {
        assertTrue(ScrubPreview.shouldDecode(have = null, running = null, want = 8_000L))
    }

    // ── the whole drag ──

    @Test
    fun `a slow drag within one step decodes once`() {
        // The point of quantising, stated as the behaviour rather than the arithmetic.
        var have: Long? = null
        var decodes = 0
        var p = ScrubPreview.frameFor(10_000L, DURATION)
        val until = p + ScrubPreview.stepFor(DURATION) - 1
        while (p < until) {
            val want = ScrubPreview.frameFor(p, DURATION)
            if (ScrubPreview.shouldDecode(have, null, want)) {
                decodes++
                have = want
            }
            p += 20L
        }
        assertEquals(1, decodes)
    }

    @Test
    fun `a fast drag across the whole video decodes once per step at most`() {
        var have: Long? = null
        var decodes = 0
        var p = 0L
        while (p <= DURATION) {
            val want = ScrubPreview.frameFor(p, DURATION)
            if (ScrubPreview.shouldDecode(have, null, want)) {
                decodes++
                have = want
            }
            p += 100L
        }
        assertTrue("$decodes decodes", decodes <= (DURATION / ScrubPreview.stepFor(DURATION)) + 1)
    }
}

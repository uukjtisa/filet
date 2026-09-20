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
        val a = ScrubPreview.frameFor(10_100L, DURATION)
        val b = ScrubPreview.frameFor(11_900L, DURATION)
        assertEquals(a, b)
    }

    @Test
    fun `moving a step along asks for a different frame`() {
        val a = ScrubPreview.frameFor(10_000L, DURATION)
        val b = ScrubPreview.frameFor(12_000L, DURATION)
        assertTrue(b > a)
    }

    @Test
    fun `frames land on step boundaries`() {
        for (p in listOf(0L, 1L, 1_999L, 2_000L, 55_555L, 299_999L)) {
            assertEquals(0L, ScrubPreview.frameFor(p, DURATION) % ScrubPreview.STEP_MS)
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
        assertEquals(0L, ScrubPreview.frameFor(400L, durationMs = 900L))
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
    fun `a slow drag across two seconds decodes once`() {
        // The point of quantising, stated as the behaviour rather than the arithmetic.
        var have: Long? = null
        var decodes = 0
        var p = 10_000L
        while (p < 11_900L) {
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
        assertTrue("$decodes decodes", decodes <= (DURATION / ScrubPreview.STEP_MS) + 1)
    }
}

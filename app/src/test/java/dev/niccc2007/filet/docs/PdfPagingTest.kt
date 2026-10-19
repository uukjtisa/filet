package dev.niccc2007.filet.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic of the PDF viewer: bitmap sizes, the page cache's budget, pinch zoom, and the
 * part of a page that is on screen.
 *
 * Each of these was either wrong before or absent, and each fails quietly on a device - a page
 * squashed, a cache that grows until the app dies, a pinch that drifts off the fingers, a
 * sharp layer drawn over the wrong part of the page.
 */
class PdfPagingTest {

    private val eps = 0.01f

    // ── fit ──

    @Test
    fun `an ordinary page keeps its width and aspect`() {
        val px = PdfPaging.fit(1080, 1.414f, 4000, 8000)
        assertEquals(1080, px.w)
        assertEquals(1527, px.h)
    }

    @Test
    fun `a very tall page shrinks in both directions, not just in height`() {
        // A receipt, ten times taller than wide. The caps used to be applied separately, which
        // gave 1080 x 8000 here - the page squashed to under three quarters of its height.
        val px = PdfPaging.fit(1080, 10f, 4000, 8000)
        assertEquals(8000, px.h)
        assertEquals(800, px.w)
        assertEquals(10f, px.h.toFloat() / px.w, eps)
    }

    @Test
    fun `a very wide request is capped at the width limit with its aspect intact`() {
        val px = PdfPaging.fit(6000, 0.5f, 4000, 8000)
        assertEquals(4000, px.w)
        assertEquals(2000, px.h)
    }

    // ── budget and plan ──

    @Test
    fun `the budget scales with the heap and stops at both ends`() {
        val mb = 1024L * 1024
        assertEquals(PdfPaging.MIN_BUDGET, PdfPaging.budget(64 * mb))
        assertEquals(32 * mb, PdfPaging.budget(256 * mb))
        assertEquals(PdfPaging.MAX_BUDGET, PdfPaging.budget(4096 * mb))
    }

    @Test
    fun `a three hundred page document holds a handful of pages, not three hundred`() {
        val perPage = PdfPaging.bytesFor(1080, 1.414f)
        val budget = PdfPaging.budget(256L * 1024 * 1024)
        // Read from the start to the end, a few pages at a time.
        for (first in 0 until 300 step 3) {
            val plan = PdfPaging.plan(first..(first + 1).coerceAtMost(299), 300, PdfDoc.KEEP, budget) { perPage }
            assertTrue("held ${plan.size} at page $first", plan.size <= 2 + 2 * PdfDoc.KEEP)
            assertTrue(plan.size * perPage <= budget || plan.size == 2)
            assertTrue(plan.all { it in 0 until 300 })
        }
    }

    @Test
    fun `visible pages come first and neighbours are added forward first`() {
        val plan = PdfPaging.plan(10..11, 100, 2, Long.MAX_VALUE) { 1L }
        assertEquals(listOf(10, 11, 12, 9, 13, 8), plan)
    }

    @Test
    fun `neighbours stop at the budget, visible pages never do`() {
        // Twelve landscape slides on screen at once - the case "two either side" never bounded.
        val plan = PdfPaging.plan(0..11, 40, 2, budget = 10L) { 1L }
        assertEquals((0..11).toList(), plan)
        val roomy = PdfPaging.plan(0..1, 40, 2, budget = 3L) { 1L }
        assertEquals(listOf(0, 1, 2), roomy)
    }

    @Test
    fun `a plan never names a page outside the document`() {
        assertEquals(listOf(0, 1), PdfPaging.plan(0..0, 2, 2, Long.MAX_VALUE) { 1L })
        assertTrue(PdfPaging.plan(IntRange.EMPTY, 10, 2, Long.MAX_VALUE) { 1L }.isEmpty())
        assertTrue(PdfPaging.plan(0..3, 0, 2, Long.MAX_VALUE) { 1L }.isEmpty())
    }

    // ── pinch ──

    /** Where content at list point (x, y) is drawn on screen under [v], scrolled by [scrolled]. */
    private fun screenOf(v: PdfPaging.View, w: Float, x: Float, y: Float, scrolled: Float): Pair<Float, Float> =
        Pair(w / 2f + (x - w / 2f) * v.scale + v.tx, (y - scrolled) * v.scale)

    @Test
    fun `the point under the fingers stays under them`() {
        val w = 1000f
        var v = PdfPaging.View()
        var scrolled = 0f
        val cx = 700f
        val cy = 400f
        // The content point currently under the centroid.
        val x = w / 2f + (cx - w / 2f - v.tx) / v.scale
        val y = cy / v.scale + scrolled
        repeat(10) {
            val step = PdfPaging.pinch(v, w, cx, cy, 1.1f, 0f, 0f)
            v = step.view
            scrolled += step.scroll
        }
        val (sx, sy) = screenOf(v, w, x, y, scrolled)
        assertEquals(cx, sx, 0.5f)
        assertEquals(cy, sy, 0.5f)
        assertTrue(v.scale > 2.5f)
    }

    @Test
    fun `zoom is clamped and the sideways offset never passes the content edge`() {
        val w = 1000f
        val big = PdfPaging.pinch(PdfPaging.View(), w, 0f, 0f, 100f, 0f, 0f).view
        assertEquals(PdfPaging.MAX_ZOOM, big.scale, eps)
        assertTrue(kotlin.math.abs(big.tx) <= (big.scale - 1f) * w / 2f + eps)
        val small = PdfPaging.pinch(PdfPaging.View(2f, 300f), w, 500f, 0f, 0.01f, 0f, 0f).view
        assertEquals(1f, small.scale, eps)
        assertEquals(0f, small.tx, eps)
    }

    @Test
    fun `dragging down scrolls the list back, dragging sideways pans`() {
        val step = PdfPaging.pinch(PdfPaging.View(2f, 0f), 1000f, 500f, 0f, 1f, 40f, 100f)
        assertEquals(-50f, step.scroll, eps)
        assertEquals(40f, step.view.tx, eps)
    }

    @Test
    fun `a sideways pan at one times does nothing`() {
        assertEquals(0f, PdfPaging.pan(PdfPaging.View(), 200f, 1000f).tx, eps)
        assertEquals(500f, PdfPaging.pan(PdfPaging.View(2f, 0f), 900f, 1000f).tx, eps)
    }

    @Test
    fun `a double tap goes in from one times and all the way out from anything else`() {
        val inStep = PdfPaging.doubleTap(PdfPaging.View(), 1000f, 500f, 0f)
        assertEquals(PdfPaging.TAP_ZOOM, inStep.view.scale, eps)
        val outStep = PdfPaging.doubleTap(PdfPaging.View(4f, 200f), 1000f, 500f, 0f)
        assertEquals(1f, outStep.view.scale, eps)
        assertEquals(0f, outStep.view.tx, eps)
    }

    @Test
    fun `the end of the list gets exactly the padding the zoom hides`() {
        assertEquals(0f, PdfPaging.bottomPad(1f, 2000f), eps)
        assertEquals(1000f, PdfPaging.bottomPad(2f, 2000f), eps)
        // At 2x the screen shows the top half of the list's viewport: the middle row is H/4.
        assertEquals(500f, PdfPaging.middleRow(PdfPaging.View(2f, 0f), 2000f), eps)
    }

    // ── slice ──

    @Test
    fun `no slice at one times, where the base is already sharp`() {
        assertNull(PdfPaging.slice(PdfPaging.View(), 1000f, 2000f, 0f, 0f, 1000f, 1414f))
    }

    @Test
    fun `at two times centred the slice is the middle half across and the top band down`() {
        // Page box fills the width and starts at the top of the list.
        val s = PdfPaging.slice(PdfPaging.View(2f, 0f), 1000f, 2000f, 0f, 0f, 1000f, 1414f)
        assertNotNull(s)
        s!!
        assertEquals(0.25f, s.u0, eps)
        assertEquals(0.75f, s.u1, eps)
        assertEquals(0f, s.v0, eps)
        // The screen shows list rows 0..1000; the page is 1414 tall, so 1000/1414 = 0.707,
        // snapped up to the next fortieth.
        assertEquals(0.725f, s.v1, eps)
    }

    @Test
    fun `panning right shows the left side of the page`() {
        val w = 1000f
        val v = PdfPaging.View(2f, PdfPaging.clampTx(10_000f, 2f, w))
        val s = PdfPaging.slice(v, w, 2000f, 0f, 0f, w, 1414f)!!
        assertEquals(0f, s.u0, eps)
        assertEquals(0.5f, s.u1, eps)
    }

    @Test
    fun `a page below the zoomed screen has no slice`() {
        // At 2x the screen covers list rows 0..1000; a page starting at 1200 is not on it.
        assertNull(PdfPaging.slice(PdfPaging.View(2f, 0f), 1000f, 2000f, 0f, 1200f, 1000f, 1414f))
    }

    @Test
    fun `a slice is never bigger than the screen plus its snapping margin`() {
        for (scale in listOf(1.5f, 2f, 3f, 5f)) {
            val v = PdfPaging.View(scale, 0f)
            val boxW = 1000f
            val boxH = 1414f
            val s = PdfPaging.slice(v, 1000f, 2000f, 0f, 0f, boxW, boxH)!!
            val pxW = (s.u1 - s.u0) * boxW * scale
            val pxH = (s.v1 - s.v0) * boxH * scale
            val margin = 2f * boxW * scale / PdfPaging.SNAP
            assertTrue("width $pxW at $scale", pxW <= 1000f + margin)
            assertTrue("height $pxH at $scale", pxH <= 2000f + 2f * boxH * scale / PdfPaging.SNAP)
        }
    }

    @Test
    fun `a typed page number is one-based and must name a page`() {
        assertEquals(0, PdfPaging.pageIndex("1", 300))
        assertEquals(299, PdfPaging.pageIndex(" 300 ", 300))
        assertNull(PdfPaging.pageIndex("0", 300))
        assertNull(PdfPaging.pageIndex("301", 300))
        assertNull(PdfPaging.pageIndex("", 300))
        assertNull(PdfPaging.pageIndex("abc", 300))
    }
}

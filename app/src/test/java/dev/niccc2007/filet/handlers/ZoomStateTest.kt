package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zoom that stopped zooming.
 *
 * Nic: *"the picture/image viewer it used to be zoomable now it's not.. I'm trying to pinch to
 * zoom but now it's not working.. double tap to zoom still works though"*.
 *
 * The first test here is the whole report. It fails against the old code, and it fails for the
 * real reason rather than a proxy for it: a pinch is delivered as a stream of small per-frame
 * ratios, and the old viewer multiplied every one of them by a `scale` captured at 1f. Any test
 * that applied a single large ratio would have passed against the broken code, which is how this
 * survived a release.
 */
class ZoomStateTest {

    private val W = 1000f
    private val H = 2000f

    // ── the regression ──

    @Test
    fun `a pinch delivered frame by frame accumulates instead of standing still`() {
        // What the detector actually reports: about 2% per frame, thirty-odd frames for a
        // comfortable spread of the fingers.
        var v = ZoomView.NONE
        repeat(35) { v = v.pinched(zoom = 1.02f, panX = 0f, panY = 0f, boxW = W, boxH = H) }

        // 1.02^35 is about 2.0. The old code returned 1.02 here, every time, forever.
        assertTrue("35 frames of 1.02 should roughly double the view, got ${v.scale}", v.scale > 1.9f)
        assertTrue(v.zoomed)
    }

    @Test
    fun `one frame alone barely moves it - the control that makes the test above mean something`() {
        // The positive control for the accumulation test. Without it, an implementation that
        // ignored the incoming ratio and jumped straight to some fixed scale would still pass
        // the test above. One frame must be worth exactly one frame.
        val v = ZoomView.NONE.pinched(1.02f, 0f, 0f, W, H)
        assertEquals(1.02f, v.scale, 0.0001f)
    }

    @Test
    fun `flat is one threshold, not two`() {
        // This test exists because the two halves disagreed. `ZoomView.FLAT` said 1.01 and
        // `clampPan` said 1.001, so a view at 1.005 held pan slack while reporting itself
        // unzoomed - and the double-tap toggle and the pan limit read the same view differently.
        val justOver = ZoomView(scale = ZoomView.FLAT + 0.0005f)
        assertTrue("anything the pan limit treats as zoomed must report itself zoomed", justOver.zoomed)
        assertTrue(
            "and it must actually have slack to pan into",
            clampPan(9999f, justOver.scale, W) > 0f,
        )

        val justUnder = ZoomView(scale = ZoomView.FLAT - 0.0005f)
        assertFalse(justUnder.zoomed)
        assertEquals("an unzoomed view has nowhere to pan", 0f, clampPan(9999f, justUnder.scale, W), 0f)
    }

    @Test
    fun `pinching back in returns to flat and does not go below it`() {
        var v = ZoomView.NONE
        repeat(35) { v = v.pinched(1.02f, 0f, 0f, W, H) }
        repeat(80) { v = v.pinched(0.98f, 0f, 0f, W, H) }
        assertEquals("an image never shrinks below the screen it is being viewed on", 1f, v.scale, 0.001f)
    }

    @Test
    fun `zoom stops at the ceiling rather than running to single pixels`() {
        var v = ZoomView.NONE
        repeat(500) { v = v.pinched(1.05f, 0f, 0f, W, H) }
        assertEquals(ZoomView.MAX, v.scale, 0.001f)
    }

    // ── the half of the bug that was not reported ──

    @Test
    fun `double tap on a zoomed view returns to flat`() {
        // He reported double tap as WORKING, because zooming in works. Toggling back out read
        // the same stale scale and zoomed in again. This is the assertion he could not see.
        val zoomed = ZoomView.NONE.doubleTapped(500f, 1000f, W, H)
        assertTrue(zoomed.zoomed)

        val back = zoomed.doubleTapped(500f, 1000f, W, H)
        assertEquals(1f, back.scale, 0.0001f)
        assertEquals(0f, back.offsetX, 0.0001f)
        assertEquals(0f, back.offsetY, 0.0001f)
    }

    @Test
    fun `double tap magnifies the point touched, not the middle of the picture`() {
        // Tapping the top-left quadrant must push the view down and right, so what was under
        // the finger is what fills the screen.
        val v = ZoomView.NONE.doubleTapped(atX = 100f, atY = 200f, boxW = W, boxH = H)
        assertTrue("tapping left of centre should shift the image right, got ${v.offsetX}", v.offsetX > 0f)
        assertTrue("tapping above centre should shift the image down, got ${v.offsetY}", v.offsetY > 0f)
    }

    @Test
    fun `double tap dead centre stays centred`() {
        val v = ZoomView.NONE.doubleTapped(W / 2f, H / 2f, W, H)
        assertEquals(0f, v.offsetX, 0.0001f)
        assertEquals(0f, v.offsetY, 0.0001f)
    }

    // ── panning ──

    @Test
    fun `an unzoomed view cannot be panned anywhere`() {
        // Otherwise a stray horizontal drag slides a fitted photo off its own screen and the
        // only way back is to reopen it.
        val v = ZoomView.NONE.panned(400f, 400f, W, H)
        assertEquals(0f, v.offsetX, 0.0001f)
        assertEquals(0f, v.offsetY, 0.0001f)
    }

    @Test
    fun `panning a zoomed view stops at the edge of the image`() {
        var v = ZoomView.NONE
        repeat(35) { v = v.pinched(1.02f, 0f, 0f, W, H) }   // about 2x
        val far = v.panned(99999f, 99999f, W, H)

        // At 2x exactly half the image is off screen, so the slack each way is a quarter of the
        // viewport. Past that the picture would be dragged clear of the window.
        val slackX = W * (far.scale - 1f) / 2f
        val slackY = H * (far.scale - 1f) / 2f
        assertEquals(slackX, far.offsetX, 0.5f)
        assertEquals(slackY, far.offsetY, 0.5f)
    }

    @Test
    fun `a pinch that ends flat drops the pan it had built up`() {
        var v = ZoomView.NONE
        repeat(35) { v = v.pinched(1.02f, 20f, 20f, W, H) }
        assertTrue(v.offsetX != 0f)

        repeat(80) { v = v.pinched(0.98f, 0f, 0f, W, H) }
        assertEquals("a fitted image with a leftover offset sits off-centre for no reason", 0f, v.offsetX, 0.001f)
        assertEquals(0f, v.offsetY, 0.001f)
    }
}

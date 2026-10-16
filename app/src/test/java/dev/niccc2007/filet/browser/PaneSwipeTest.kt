package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneSwipeTest {

    private val normal = PaneSwipe.Sensitivity.NORMAL

    @Test
    fun `a brisk sideways swipe changes tab`() {
        assertTrue(PaneSwipe.fires(dx = -90f, dy = 10f, speedDpPerSec = 800f, sinceDownMs = 120L, sensitivity = normal))
    }

    @Test
    fun `a swipe that began after the grace window is a drag, not a swipe`() {
        // This is the one that protects drag-and-drop: by 400ms the long press has the
        // finger, and nothing here may take it back.
        assertFalse(PaneSwipe.fires(-200f, 5f, 2000f, 400L, normal))
    }

    @Test
    fun `a scroll at a slight angle never changes tab`() {
        // Plenty of horizontal travel, but more vertical - that is a list being flicked.
        assertFalse(PaneSwipe.fires(-90f, 120f, 900f, 100L, normal))
    }

    @Test
    fun `a long push fires however slowly it got there`() {
        // The fault that made the gesture dead on arrival: this and the short flick below
        // are the two movements people actually make, and requiring distance AND speed
        // rejected both of them. Far enough is enough.
        assertTrue(PaneSwipe.fires(-120f, 4f, 100f, 100L, normal))
    }

    @Test
    fun `a short slow drift is not a swipe`() {
        assertFalse(PaneSwipe.fires(-45f, 4f, 90f, 100L, normal))
    }

    @Test
    fun `a short flick is not a swipe`() {
        assertFalse(PaneSwipe.fires(-20f, 2f, 3000f, 60L, normal))
    }

    @Test
    fun `light fires where firm does not`() {
        val gentle = PaneSwipe.fires(-50f, 6f, 300f, 110L, PaneSwipe.Sensitivity.LIGHT)
        val same = PaneSwipe.fires(-50f, 6f, 300f, 110L, PaneSwipe.Sensitivity.FIRM)
        assertTrue(gentle)
        assertFalse(same)
    }

    @Test
    fun `the label holds off until the swipe means something`() {
        assertEquals(0f, PaneSwipe.progress(10f, 2f, normal), 0.001f)
        assertTrue(PaneSwipe.progress(50f, 4f, normal) > 0f)
        assertEquals(1f, PaneSwipe.progress(400f, 4f, normal), 0.001f)
    }

    @Test
    fun `the label never shows on a vertical scroll`() {
        assertEquals(0f, PaneSwipe.progress(60f, 200f, normal), 0.001f)
    }

    @Test
    fun `an unknown sensitivity falls back to normal`() {
        assertEquals(PaneSwipe.Sensitivity.NORMAL, PaneSwipe.Sensitivity.of("nonsense"))
        assertEquals(PaneSwipe.Sensitivity.NORMAL, PaneSwipe.Sensitivity.of(null))
    }

    @Test
    fun `left goes on through the strip and right goes back`() {
        assertEquals(1, PaneSwipe.stepFor(-80f))
        assertEquals(-1, PaneSwipe.stepFor(80f))
    }

    @Test
    fun `the tab next door`() {
        assertEquals(2, PaneSwipe.nextTab(current = 1, step = 1, count = 4))
        assertEquals(0, PaneSwipe.nextTab(current = 1, step = -1, count = 4))
    }

    @Test
    fun `the ends of the strip do not wrap`() {
        // Running out is information, not a failure - the hint says so rather than throwing
        // the reader to the other end of the strip on a stray flick.
        assertNull(PaneSwipe.nextTab(current = 3, step = 1, count = 4))
        assertNull(PaneSwipe.nextTab(current = 0, step = -1, count = 4))
    }

    @Test
    fun `one tab has nowhere to go in either direction`() {
        assertNull(PaneSwipe.nextTab(0, 1, 1))
        assertNull(PaneSwipe.nextTab(0, -1, 1))
        assertNull(PaneSwipe.nextTab(0, 1, 0))
    }
}

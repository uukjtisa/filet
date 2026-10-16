package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneSwipeTest {

    private val normal = PaneSwipe.Sensitivity.NORMAL

    @Test
    fun `a brisk sideways swipe switches pane`() {
        assertTrue(PaneSwipe.fires(dx = -90f, dy = 10f, speedDpPerSec = 800f, sinceDownMs = 120L, sensitivity = normal))
    }

    @Test
    fun `a swipe that began after the grace window is a drag, not a swipe`() {
        // This is the one that protects drag-and-drop: by 400ms the long press has the
        // finger, and nothing here may take it back.
        assertFalse(PaneSwipe.fires(-200f, 5f, 2000f, 400L, normal))
    }

    @Test
    fun `a scroll at a slight angle never switches pane`() {
        // Plenty of horizontal travel, but more vertical - that is a list being flicked.
        assertFalse(PaneSwipe.fires(-90f, 120f, 900f, 100L, normal))
    }

    @Test
    fun `a slow push is not a swipe`() {
        assertFalse(PaneSwipe.fires(-120f, 4f, 100f, 100L, normal))
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
}

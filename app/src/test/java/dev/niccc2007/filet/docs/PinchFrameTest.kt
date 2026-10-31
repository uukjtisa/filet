package dev.niccc2007.filet.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinch gathered between frames. The freeze it fixes was one scroll per touch event, so what
 * is pinned here is that any number of events become ONE step, and that nothing is lost on the way.
 */
class PinchFrameTest {

    @Test
    fun `several events in one frame become one step with their ratios multiplied`() {
        val p = PinchFrame()
        p.begin()
        p.add(1.1f, 100f)
        p.add(1.1f, 120f)
        p.add(1.1f, 140f)
        val step = p.take(1f)!!
        assertEquals(1.331f, step.zoom, 0.001f)
        assertEquals(1.331f, step.ratio, 0.001f)
        // The latest focal point, which is where the fingers are now.
        assertEquals(140f, step.focalY, 0f)
        // And the next frame has nothing until another event arrives.
        assertNull(p.take(step.zoom))
    }

    @Test
    fun `the step is clamped and says how far the zoom really moved`() {
        val p = PinchFrame()
        p.add(2f, 0f)
        val step = p.take(2.5f)!!
        assertEquals(DocZoom.MAX, step.zoom, 0f)
        assertEquals(DocZoom.MAX / 2.5f, step.ratio, 0.0001f)
        // Pushing against the bound is not a step at all: no zoom to set, no scroll to make.
        p.add(1.5f, 0f)
        assertNull(p.take(DocZoom.MAX))
    }

    @Test
    fun `the size is remembered once, after the fingers lift and the last frame is in`() {
        val p = PinchFrame()
        p.begin()
        p.add(1.2f, 0f)
        assertFalse("not while pinching", p.settled())
        p.end()
        // Lifted with a frame still gathered: that frame lands first.
        assertFalse(p.settled())
        p.take(1f)
        assertTrue(p.settled())
        assertFalse("only once", p.settled())
    }

    @Test
    fun `a pinch that never moved the zoom saves nothing`() {
        val p = PinchFrame()
        p.begin()
        p.add(1.5f, 0f)
        p.end()
        assertNull(p.take(DocZoom.MAX))
        assertFalse(p.settled())
    }

    @Test
    fun `a double tap is a pinch of one step, landing on the step and saved once`() {
        val p = PinchFrame()
        val target = DocZoom.doubleTap(1f)
        p.begin()
        p.add(target / 1f, 200f)
        p.end()
        val step = p.take(1f)!!
        assertEquals(target, step.zoom, 0.0001f)
        // What is saved is the step itself, not a hair under it.
        assertEquals(Reading.sizeOf(target), Reading.sizeOf(step.zoom))
        assertTrue(p.settled())
    }

    @Test
    fun `a nonsense ratio is ignored rather than zeroing the zoom`() {
        val p = PinchFrame()
        p.add(Float.NaN, 0f)
        p.add(0f, 0f)
        p.add(-1f, 0f)
        assertNull(p.take(1f))
    }
}

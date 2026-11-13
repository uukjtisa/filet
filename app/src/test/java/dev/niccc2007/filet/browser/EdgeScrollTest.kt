package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A dragged item at a row's edge brings the rest of the row to it, and nowhere else does. */
class EdgeScrollTest {

    private fun step(at: Float) = EdgeScroll.step(at, viewport = 1000f, edge = 100f, fastest = 20f)

    @Test fun `the middle of the row does not scroll`() {
        assertEquals(0f, step(500f))
        assertEquals(0f, step(100f))
        assertEquals(0f, step(900f))
    }

    @Test fun `deeper into the band is faster, towards that edge`() {
        assertTrue(step(50f) < 0f)
        assertTrue(step(950f) > 0f)
        assertTrue(step(10f) < step(60f))
        assertTrue(step(990f) > step(940f))
    }

    @Test fun `at or past the edge it is the fastest, never more`() {
        assertEquals(-20f, step(0f))
        assertEquals(-20f, step(-300f))
        assertEquals(20f, step(1000f))
        assertEquals(20f, step(1600f))
    }

    @Test fun `a narrow row keeps a middle that does not scroll`() {
        // On a row narrower than three bands, the bands shrink so there is still a still middle.
        assertEquals(0f, EdgeScroll.step(60f, viewport = 120f, edge = 100f, fastest = 20f))
    }

    @Test fun `an unmeasured row never scrolls`() {
        assertEquals(0f, EdgeScroll.step(0f, viewport = 0f, edge = 100f, fastest = 20f))
    }
}

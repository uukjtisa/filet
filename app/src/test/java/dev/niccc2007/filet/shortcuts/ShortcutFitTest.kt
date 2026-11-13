package dev.niccc2007.filet.shortcuts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A file's picture on a launcher icon: a photo fills the shape, an icon sits whole in its middle. */
class ShortcutFitTest {

    private val side = 216f

    @Test fun `an opaque square picture fills the square exactly`() {
        assertArrayEquals(floatArrayOf(0f, 0f, side, side), ShortcutFit.rect(500, 500, seeThrough = false, side = side), 0.01f)
    }

    @Test fun `an opaque wide picture covers the square and is cut at the sides`() {
        val r = ShortcutFit.rect(1600, 900, seeThrough = false, side = side)
        assertEquals(0f, r[1], 0.01f)
        assertEquals(side, r[3], 0.01f)
        assertTrue("wider than the square", r[2] - r[0] > side)
        assertEquals("centred", side / 2, (r[0] + r[2]) / 2, 0.01f)
    }

    @Test fun `a see-through picture sits whole inside the safe middle`() {
        val r = ShortcutFit.rect(192, 192, seeThrough = true, side = side)
        val safe = side * ShortcutFit.SAFE
        assertEquals(safe, r[2] - r[0], 0.01f)
        assertEquals(safe, r[3] - r[1], 0.01f)
        assertEquals("centred", side / 2, (r[0] + r[2]) / 2, 0.01f)
        assertTrue("nothing outside the safe middle", r[0] >= (side - safe) / 2 - 0.01f)
    }

    @Test fun `a see-through tall picture keeps its shape inside the safe middle`() {
        val r = ShortcutFit.rect(100, 200, seeThrough = true, side = side)
        assertEquals(side * ShortcutFit.SAFE, r[3] - r[1], 0.01f)
        assertEquals((r[3] - r[1]) / 2, r[2] - r[0], 0.01f)
    }

    @Test fun `an empty picture falls back to the whole square`() {
        assertArrayEquals(floatArrayOf(0f, 0f, side, side), ShortcutFit.rect(0, 0, seeThrough = true, side = side), 0f)
    }
}

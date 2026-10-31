package dev.niccc2007.filet.docs

import org.junit.Assert.assertEquals
import org.junit.Test

/** A code block or a table dragged to a size by its corner. See [BlockResize]. */
class BlockResizeTest {

    @Test
    fun `the corner follows the finger in both directions at once`() {
        assertEquals(250f to 300f, BlockResize.next(300f, 200f, dx = -50f, dy = 100f, maxW = 360f, maxH = 900f))
    }

    @Test
    fun `it never grows wider than the page, which would take its own grip off the screen`() {
        assertEquals(360f, BlockResize.next(300f, 200f, dx = 500f, dy = 0f, maxW = 360f, maxH = 900f).first)
    }

    @Test
    fun `it never grows taller than its content, so a drag back up moves at once`() {
        assertEquals(640f, BlockResize.next(300f, 200f, dx = 0f, dy = 2000f, maxW = 360f, maxH = 640f).second)
    }

    @Test
    fun `it never shrinks past a usable size`() {
        val (w, h) = BlockResize.next(300f, 200f, dx = -1000f, dy = -1000f, maxW = 360f, maxH = 900f)
        assertEquals(BlockResize.MIN_W, w)
        assertEquals(BlockResize.MIN_H, h)
    }

    @Test
    fun `a room smaller than the minimum wins over the minimum`() {
        assertEquals(30f to 40f, BlockResize.next(30f, 40f, dx = -10f, dy = -10f, maxW = 30f, maxH = 40f))
    }
}

package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which covers make All Music's wall, and where they go. See [CoverMosaic]. */
class CoverMosaicTest {

    private val side = CoverMosaic.SIDE

    private fun flat(rgb: Int) = IntArray(side * side) { rgb }

    /** A cover drawn by a function of its position. */
    private fun cover(at: (x: Int, y: Int) -> Int) = IntArray(side * side) { at(it % side, it / side) }

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    @Test
    fun `one flat colour is a placeholder, and so is a flat colour with a title on it`() {
        assertTrue(CoverMosaic.isBland(flat(0x808080)))
        assertTrue(CoverMosaic.isBland(flat(0x1A2B3C)))
        // A black sleeve with a line of white lettering across it: the lettering pulls a MEAN
        // grey, which is the case the median is there for.
        val titled = cover { _, y -> if (y == 7) 0xFFFFFF else 0x000000 }
        assertTrue(CoverMosaic.isBland(titled))
    }

    @Test
    fun `a picture with something in it is kept`() {
        val halves = cover { x, _ -> if (x < side / 2) 0xD03020 else 0x2040C0 }
        assertFalse(CoverMosaic.isBland(halves))
        val gradient = cover { x, y -> rgb(x * 16, y * 16, 255 - x * 8) }
        assertFalse(CoverMosaic.isBland(gradient))
    }

    @Test
    fun `the same cover twice is one tile, and two different covers are two`() {
        val a = cover { x, y -> if ((x / 4 + y / 4) % 2 == 0) 0xF0F0F0 else 0x101010 }
        val b = cover { x, _ -> rgb(x * 16, 40, 200) }
        assertTrue(CoverMosaic.sameCover(CoverMosaic.fingerprint(a), CoverMosaic.fingerprint(a.copyOf())))
        assertFalse(CoverMosaic.sameCover(CoverMosaic.fingerprint(a), CoverMosaic.fingerprint(b)))
        // A re-encode nudges every pixel a little; it is still the same picture.
        val nudged = IntArray(a.size) { i -> val v = (a[i] and 0xFF) xor 3; rgb(v, v, v) }
        assertTrue(CoverMosaic.sameCover(CoverMosaic.fingerprint(a), CoverMosaic.fingerprint(nudged)))
    }

    @Test
    fun `the wall is the newest covers worth showing, in order, each once`() {
        val a = cover { x, _ -> if (x < side / 2) 0xD03020 else 0x2040C0 }
        val b = cover { _, y -> if (y < side / 2) 0x20C040 else 0x404040 }
        val samples = listOf(null, flat(0x777777), a, a.copyOf(), b, null)
        assertEquals(listOf(2, 4), CoverMosaic.choose(samples))
        assertEquals(listOf(2), CoverMosaic.choose(samples, max = 1))
        assertEquals(emptyList<Int>(), CoverMosaic.choose(listOf(null, flat(0))))
    }

    @Test
    fun `with fewer covers than cells, no cover sits directly over itself`() {
        val cols = 8
        val rows = 3
        val map = CoverMosaic.cells(tiles = 5, cols = cols, rows = rows)
        assertEquals(cols * rows, map.size)
        assertTrue(map.all { it in 0 until 5 })
        for (r in 1 until rows) for (c in 0 until cols) {
            assertNotEquals("row $r col $c", map[(r - 1) * cols + c], map[r * cols + c])
        }
        for (r in 0 until rows) for (c in 1 until cols) {
            assertNotEquals("row $r col $c", map[r * cols + c - 1], map[r * cols + c])
        }
        assertEquals(0, CoverMosaic.cells(0, cols, rows).size)
    }
}

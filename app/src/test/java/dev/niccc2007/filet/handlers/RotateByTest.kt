package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotateByTest {

    /** A 4x2 image whose pixels encode their own coordinates, so a mapping can be checked. */
    private fun grid(w: Int = 4, h: Int = 2) =
        Pixels.of(w, h) { x, y -> 0xFF000000.toInt() or (x shl 8) or y }

    @Test
    fun `no rotation returns the same image`() {
        val p = grid()
        assertEquals(p, p.rotatedBy(0f))
        assertEquals(p, p.rotatedBy(360f))
    }

    @Test
    fun `exact quarter turns go through the lossless path`() {
        // Resampling a 90-degree turn would soften every pixel for nothing, so these must agree
        // with the permutation exactly rather than approximately.
        val p = grid()
        for ((deg, turn) in listOf(90f to Turn.RIGHT, 270f to Turn.LEFT)) {
            val byAngle = p.rotatedBy(deg)
            val byTurn = p.rotated(turn)
            assertEquals(byTurn.width, byAngle.width)
            assertEquals(byTurn.height, byAngle.height)
            assertTrue("$deg is not the exact turn", byTurn.argb.contentEquals(byAngle.argb))
        }
    }

    @Test
    fun `a negative angle is the same as its positive partner`() {
        val p = grid()
        assertTrue(p.rotatedBy(-90f).argb.contentEquals(p.rotatedBy(270f).argb))
    }

    @Test
    fun `the canvas grows so nothing is cut off`() {
        // A rotation that keeps the old size quietly crops the corners, which is the kind of loss
        // somebody only notices after saving over the original.
        val p = grid(10, 10)
        val out = p.rotatedBy(45f)
        assertTrue("45 degrees needs more room, got ${out.width}x${out.height}", out.width > 10)
        assertTrue(out.height > 10)
        // sqrt(2) * 10 is about 14.1, so the box is 15 at most once rounded up.
        assertTrue(out.width <= 15)
    }

    @Test
    fun `the corners that were never in the picture are transparent`() {
        val p = Pixels.of(10, 10) { _, _ -> 0xFFFF0000.toInt() }
        val out = p.rotatedBy(45f)
        // The very corner of the bounding box cannot be covered by a rotated square.
        assertEquals(0, out[0, 0])
        assertEquals(0, out[out.width - 1, 0])
    }

    @Test
    fun `the middle of the picture survives the trip`() {
        val p = Pixels.of(20, 20) { _, _ -> 0xFF112233.toInt() }
        val out = p.rotatedBy(30f)
        assertEquals(0xFF112233.toInt(), out[out.width / 2, out.height / 2])
    }

    @Test
    fun `a small angle still changes something`() {
        // Guards the rounding: an angle that resolves to no change at all would make the slider
        // feel broken near zero.
        val p = grid(40, 40)
        assertNotEquals(p.width, p.rotatedBy(5f).width)
    }
}

package dev.niccc2007.filet.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The attempts made at a picture, and why there is more than one. See [QrLadder]. */
class QrLadderTest {

    private val text = "WIFI:T:WPA;S:Synthetic;P:not-a-real-one;;"

    /**
     * A code drawn with [module] pixels a module and a one-pixel checker of +/-[noise] over the
     * whole picture: the shape of a screen photographed up close, where the screen's pixel grid
     * beats against the camera's.
     */
    private fun noisy(module: Int = 24, noise: Int = 60, margin: Int = 100): Luma {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1)
        val side = m.width * module + 2 * margin
        val px = ByteArray(side * side)
        for (y in 0 until side) for (x in 0 until side) {
            val mx = (x - margin) / module
            val my = (y - margin) / module
            val dark = x >= margin && y >= margin && mx < m.width && my < m.height && m.get(mx, my)
            val v = (if (dark) 40 else 215) + if ((x + y) % 2 == 0) noise else -noise
            px[y * side + x] = v.coerceIn(0, 255).toByte()
        }
        return Luma(px, side, side)
    }

    // ── the bug ──

    @Test
    fun `a code under fine noise does not read at full size, and the ladder reads it`() {
        val luma = noisy()
        val asLoaded = listOf(QrPass(luma.longest, global = false), QrPass(luma.longest, global = true))
        assertNull("the old single attempt, and the other binarizer, both miss it", QrLadder.read(luma, asLoaded, thorough = true))
        assertEquals(text, QrLadder.read(luma, QrLadder.photo(luma.longest), thorough = true))
    }

    @Test
    fun `a camera frame of it reads within one turn of the frame attempts`() {
        val luma = noisy()
        val scratch = Luma.Scratch()
        val read = (0L until QrLadder.FRAME_TURNS.size).firstNotNullOfOrNull { n ->
            QrLadder.read(luma, QrLadder.frame(n, luma.longest), thorough = false, scratch = scratch)
        }
        assertEquals(text, read)
    }

    @Test
    fun `a clean code still reads on the first attempt`() {
        val luma = noisy(noise = 0)
        var asked = 0
        val read = QrLadder.read(luma, QrLadder.photo(luma.longest), thorough = true, keepGoing = { asked++; true })
        assertEquals(text, read)
        assertEquals(1, asked)
    }

    @Test
    fun `a search nobody is waiting for stops between attempts`() {
        var asked = 0
        assertNull(QrLadder.read(noisy(), QrLadder.photo(2000), thorough = true, keepGoing = { ++asked <= 2 }))
        assertEquals(3, asked)
    }

    // ── the order of attempts ──

    @Test
    fun `a photo is tried as loaded first, then smaller, through both binarizers at every size`() {
        val passes = QrLadder.photo(2048)
        assertEquals(QrPass(2048, global = false), passes.first())
        val sides = passes.map { it.side }.distinct()
        assertEquals(listOf(2048) + QrLadder.PHOTO_SIDES, sides)
        for (s in sides) assertEquals("side $s", setOf(false, true), passes.filter { it.side == s }.map { it.global }.toSet())
    }

    @Test
    fun `nothing is tried bigger than the picture, and a small one is tried as it is`() {
        val passes = QrLadder.photo(700)
        assertTrue(passes.all { it.side <= 700 })
        assertEquals(listOf(700, 640, 480, 360), passes.map { it.side }.distinct())
        assertEquals(2, QrLadder.photo(300).size)
    }

    @Test
    fun `every frame is tried as it is, plus one other, and four frames try them all`() {
        val all = (0L until 4).flatMap { n ->
            val p = QrLadder.frame(n, 1280)
            assertEquals(2, p.size)
            assertEquals(QrPass(1280, global = false), p.first())
            p.drop(1)
        }.toSet()
        assertEquals(setOf(QrPass(640, false), QrPass(1280, true), QrPass(640, true), QrPass(426, false)), all)
        assertEquals(QrLadder.frame(1, 1280), QrLadder.frame(5, 1280))
    }

    // ── shrinking ──

    @Test
    fun `a shrink averages what each new pixel covers`() {
        val luma = Luma(byteArrayOf(0, 100, 10, 30, 50, 50, 20, 40), 4, 2)
        val half = luma.shrunkTo(2)
        assertEquals(2, half.width)
        assertEquals(1, half.height)
        // (0 + 100 + 50 + 50) / 4 and (10 + 30 + 20 + 40) / 4.
        assertArrayEquals(byteArrayOf(50, 25), half.data.copyOf(2))
    }

    @Test
    fun `a camera row's padding is not part of the picture`() {
        // Two pixels a row, four bytes a row: the last two of each are padding that must be skipped.
        val padded = Luma(byteArrayOf(10, 30, 99, 99, 50, 70, 99, 99), width = 2, height = 2, stride = 4)
        val one = padded.shrunkTo(1)
        assertEquals(40, one.data[0].toInt() and 0xFF)
    }

    @Test
    fun `asking for the size it already is, or bigger, gives the plane back untouched`() {
        val luma = noisy(noise = 0)
        assertSame(luma, luma.shrunkTo(luma.longest))
        assertSame(luma, luma.shrunkTo(luma.longest * 2))
    }

    @Test
    fun `the shape is kept, and a scratch buffer is reused rather than replaced`() {
        val tall = Luma(ByteArray(400 * 900), 400, 900)
        val scratch = Luma.Scratch()
        val a = tall.shrunkTo(450, scratch)
        assertEquals(200, a.width)
        assertEquals(450, a.height)
        val b = tall.shrunkTo(300, scratch)
        assertSame("a smaller plane fits in the buffer already there", a.data, b.data)
    }

    @Test
    fun `brightness is weighted the way zxing weights it`() {
        assertEquals(255, Luma.of(0xFFFFFFFF.toInt()).toInt() and 0xFF)
        assertEquals(0, Luma.of(0xFF000000.toInt()).toInt() and 0xFF)
        // Pure green counts twice: 2 * 255 / 4.
        assertEquals(127, Luma.of(0xFF00FF00.toInt()).toInt() and 0xFF)
    }
}

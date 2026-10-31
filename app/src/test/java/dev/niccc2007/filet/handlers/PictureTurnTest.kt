package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.media.JpegOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Saving the viewer's turn, and fitting a turned picture to the screen. See [PictureTurn] and [turnFit]. */
class PictureTurnTest {

    /** A JPEG with no EXIF block and a stand-in for picture data. */
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2) +
        ByteArray(64) { it.toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("expected a refusal")
        } catch (e: IllegalStateException) {
            assertTrue("a sentence to show: ${e.message}", (e.message ?: "").endsWith("."))
        }
    }

    @Test
    fun `a JPEG is turned by its tag and nothing else`() {
        val out = PictureTurn.turn(jpeg, "JPG", 1, replacing = true)
        assertEquals("jpg", out.extension)
        assertEquals(6, out.orientation)
        assertEquals(6, JpegOrientation.read(out.bytes))
        // Turned again from where it now is, a further three quarters: back to upright.
        assertEquals(1, PictureTurn.turn(out.bytes, "jpg", 3, replacing = true).orientation)
    }

    @Test
    fun `nothing is put over an original that the format cannot write back as it was`() {
        refused { PictureTurn.turn(ByteArray(10), "webp", 1, replacing = true) }
        refused { PictureTurn.turn(ByteArray(10), "heic", 1, replacing = true) }
        // Named as a JPEG and not one: replacing would quietly re-encode it, so it refuses.
        refused { PictureTurn.turn("not a jpeg".toByteArray(), "jpg", 1, replacing = true) }
    }

    @Test
    fun `what each format offers, and what a new file is saved as`() {
        assertEquals(PictureTurn.Replace.TAG, PictureTurn.replaceFor("JPEG"))
        assertEquals(PictureTurn.Replace.REWRITE, PictureTurn.replaceFor("png"))
        assertEquals(PictureTurn.Replace.NONE, PictureTurn.replaceFor("webp"))
        assertEquals(PictureTurn.Replace.NONE, PictureTurn.replaceFor("gif"))
        assertEquals("jpg", PictureTurn.newFormatFor("jpg"))
        assertEquals("png", PictureTurn.newFormatFor("webp"))
        assertEquals("jpg", PictureTurn.newFormatFor("heic"))
    }

    @Test
    fun `a turned picture is scaled to fit the box again`() {
        // A landscape photo on a portrait phone: fitted to the width, turned it can grow until
        // its new width - the old height, 810 - meets the edges.
        assertEquals(1f, turnFit(1080f, 2400f, 4000f, 3000f, 0f), 1e-4f)
        assertEquals(1080f / 810f, turnFit(1080f, 2400f, 4000f, 3000f, 90f), 1e-3f)
        assertEquals(1f, turnFit(1080f, 2400f, 4000f, 3000f, 180f), 1e-4f)
        assertEquals(1080f / 810f, turnFit(1080f, 2400f, 4000f, 3000f, 450f), 1e-3f)
        // Half way through the turn it is smaller than either end, never larger.
        assertTrue(turnFit(1080f, 2400f, 4000f, 3000f, 45f) < 1f)
        // A portrait photo turned on a portrait phone shrinks to fit the width.
        assertEquals(1080f / 2400f, turnFit(1080f, 2400f, 1080f, 2400f, 90f), 1e-3f)
        assertEquals(1f, turnFit(0f, 0f, 10f, 10f, 90f), 0f)
    }
}

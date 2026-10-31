package dev.niccc2007.filet.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Reading and changing a JPEG's orientation without touching its pixels. See [JpegOrientation]. */
class JpegOrientationTest {

    // ── building test files ──

    private fun seg(kind: Int, body: ByteArray): ByteArray {
        val length = body.size + 2
        return byteArrayOf(0xFF.toByte(), kind.toByte(), (length shr 8).toByte(), length.toByte()) + body
    }

    private val exifId = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)

    /** Picture data that stands in for the scan: whatever follows must come out unchanged. */
    private val scan = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2) +
        ByteArray(300) { (it * 37).toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun jpeg(vararg segments: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segments.fold(ByteArray(0)) { a, b -> a + b } + scan

    private fun jfif() = seg(0xE0, "JFIF\u0000".toByteArray() + ByteArray(9))

    /** A TIFF directory of [tags] (tag to SHORT value), in the given byte order, plus a next-directory pointer. */
    private fun tiff(little: Boolean, tags: List<Pair<Int, Int>>, next: Long = 0, trailer: ByteArray = ByteArray(0)): ByteArray {
        fun s(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun l(v: Long) = if (little) {
            byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        } else {
            byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        }
        var out = (if (little) "II".toByteArray() else "MM".toByteArray()) + s(42) + l(8) + s(tags.size)
        for ((tag, value) in tags) out += s(tag) + s(3) + l(1) + s(value) + s(0)
        return out + l(next) + trailer
    }

    private fun exif(little: Boolean, tags: List<Pair<Int, Int>>, trailer: ByteArray = ByteArray(0)) =
        seg(0xE1, exifId + tiff(little, tags, trailer = trailer))

    private fun pictureData(bytes: ByteArray): ByteArray = bytes.copyOfRange(bytes.size - scan.size, bytes.size)

    // ── reading ──

    @Test
    fun `reads the tag in either byte order, and normal when there is none`() {
        assertEquals(6, JpegOrientation.read(jpeg(exif(true, listOf(0x010F to 1, 0x0112 to 6)))))
        assertEquals(8, JpegOrientation.read(jpeg(jfif(), exif(false, listOf(0x0112 to 8)))))
        assertEquals(JpegOrientation.NORMAL, JpegOrientation.read(jpeg(jfif())))
        assertEquals(JpegOrientation.NORMAL, JpegOrientation.read("not a jpeg".toByteArray()))
    }

    @Test
    fun `an out of range value reads as normal rather than as a turn nobody asked for`() {
        assertEquals(JpegOrientation.NORMAL, JpegOrientation.read(jpeg(exif(true, listOf(0x0112 to 9)))))
    }

    @Test
    fun `the head stops after the EXIF block and gives back exactly what it read`() {
        val file = jpeg(jfif(), exif(true, listOf(0x0112 to 3)), seg(0xE2, ByteArray(500)))
        val stream = ByteArrayInputStream(file)
        val head = JpegOrientation.head(stream)
        assertEquals(3, JpegOrientation.read(head))
        assertArrayEquals("the head is the start of the file", file.copyOfRange(0, head.size), head)
        assertTrue("it did not read the whole file", head.size < file.size - 500)
        assertArrayEquals("the rest is still in the stream", file.copyOfRange(head.size, file.size), stream.readBytes())
    }

    @Test
    fun `the head of something that is not a jpeg is two bytes`() {
        assertEquals(2, JpegOrientation.head(ByteArrayInputStream("PNG and more".toByteArray())).size)
    }

    // ── turning ──

    @Test
    fun `turning composes with the turn already there, mirrored ones included`() {
        assertEquals(6, JpegOrientation.turned(1, 1))
        assertEquals(3, JpegOrientation.turned(6, 1))
        assertEquals(1, JpegOrientation.turned(8, 1))
        assertEquals(8, JpegOrientation.turned(1, -1))
        assertEquals(1, JpegOrientation.turned(6, 4 + 3))
        // Mirrored: the mirror stays, the turn moves on.
        assertEquals(7, JpegOrientation.turned(2, 1))
        assertEquals(4, JpegOrientation.turned(7, 1))
        assertEquals(5, JpegOrientation.turned(4, 1))
        assertEquals(2, JpegOrientation.turned(5, 1))
    }

    @Test
    fun `every orientation has a transform, and the sideways ones swap the sides`() {
        for (o in 1..8) {
            val t = JpegOrientation.transformOf(o)
            assertEquals("round trip $o", o, JpegOrientation.turned(o, 0))
            assertEquals(o in 5..8, t.sideways)
        }
    }

    // ── writing ──

    @Test
    fun `a tag that is there is overwritten in place and nothing else changes`() {
        val file = jpeg(jfif(), exif(true, listOf(0x010F to 1, 0x0112 to 1, 0x8769 to 0)))
        val out = JpegOrientation.withOrientation(file, 6)!!
        assertEquals(file.size, out.size)
        assertEquals(6, JpegOrientation.read(out))
        val differing = file.indices.count { file[it] != out[it] }
        assertEquals("one byte of a little-endian SHORT", 1, differing)
    }

    @Test
    fun `a file with no EXIF gets a block, after the JFIF header, and its picture data intact`() {
        val file = jpeg(jfif())
        val out = JpegOrientation.withOrientation(file, 8)!!
        assertEquals(8, JpegOrientation.read(out))
        assertArrayEquals(pictureData(file), pictureData(out))
        assertEquals("JFIF is still first", 0xE0, out[3].toInt() and 0xFF)

        val bare = jpeg()
        assertEquals(3, JpegOrientation.read(JpegOrientation.withOrientation(bare, 3)!!))
    }

    @Test
    fun `an EXIF block without the tag gets a new directory and keeps every other entry`() {
        for (little in listOf(true, false)) {
            val tags = listOf(0x010F to 7, 0x0110 to 9, 0x8769 to 4)
            val file = jpeg(jfif(), exif(little, tags, trailer = ByteArray(10) { 0x55 }))
            val out = JpegOrientation.withOrientation(file, 6)!!
            assertEquals("byte order little=$little", 6, JpegOrientation.read(out))
            assertArrayEquals(pictureData(file), pictureData(out))
            // Turned again, the tag is now there, so this time it is a change in place.
            val again = JpegOrientation.withOrientation(out, 3)!!
            assertEquals(out.size, again.size)
            assertEquals(3, JpegOrientation.read(again))
        }
    }

    @Test
    fun `back to normal is written too, not skipped`() {
        val file = jpeg(exif(true, listOf(0x0112 to 6)))
        assertEquals(1, JpegOrientation.read(JpegOrientation.withOrientation(file, 1)!!))
    }

    @Test
    fun `an XMP copy of the orientation is kept in step`() {
        val xmp = seg(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x tiff:Orientation=\"1\"/>".toByteArray())
        val out = JpegOrientation.withOrientation(jpeg(exif(true, listOf(0x0112 to 1)), xmp), 6)!!
        assertTrue(String(out, Charsets.ISO_8859_1).contains("tiff:Orientation=\"6\""))
    }

    // ── carrying the details across an edit ──

    /** A frame header for [w] x [h], which is what [JpegOrientation.size] reads. */
    private fun sof(w: Int, h: Int) = seg(0xC0, byteArrayOf(8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 3))

    @Test
    fun `the size comes from the frame header`() {
        assertEquals(640 to 480, JpegOrientation.size(jpeg(jfif(), sof(640, 480))))
        assertNull(JpegOrientation.size(jpeg(jfif())))
    }

    @Test
    fun `an edit carries the original's details, upright and without the old thumbnail`() {
        // A photo whose IFD0 says Make and a turn, and links to a second directory (the thumbnail).
        val tiff = tiff(true, listOf(0x010F to 7, 0x0112 to 6), next = 40)
        val original = jpeg(jfif(), seg(0xE1, exifId + tiff + ByteArray(20)), sof(4000, 3000))
        val edited = jpeg(jfif(), sof(2400, 1800))

        val out = JpegOrientation.carried(JpegOrientation.head(ByteArrayInputStream(original)), edited)!!
        assertEquals("the turn is in the pixels now", 1, JpegOrientation.read(out))
        assertEquals(2400 to 1800, JpegOrientation.size(out))
        assertArrayEquals("the edit's own picture data is untouched", pictureData(edited), pictureData(out))
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue("the EXIF block is there", text.contains("Exif"))
        assertEquals("JFIF still first", 0xE0, out[3].toInt() and 0xFF)
        // The second directory is unlinked: IFD0's next pointer, after its two entries, is zero.
        val exifAt = text.indexOf("Exif")
        val tiffAt = exifAt + 6
        val next = tiffAt + 8 + 2 + 2 * 12
        assertEquals(0, out.copyOfRange(next, next + 4).sumOf { it.toInt() and 0xFF })
    }

    @Test
    fun `nothing to carry, or an edit that already has a block, gives null`() {
        assertNull(JpegOrientation.carried(jpeg(jfif()), jpeg(jfif())))
        val withExif = jpeg(exif(true, listOf(0x0112 to 1)))
        assertNull(JpegOrientation.carried(withExif, withExif))
        assertNull(JpegOrientation.carried(withExif, "not a jpeg".toByteArray()))
    }

    @Test
    fun `what it cannot change safely it refuses`() {
        assertNull(JpegOrientation.withOrientation("not a jpeg".toByteArray(), 6))
        // A broken TIFF header.
        assertNull(JpegOrientation.withOrientation(jpeg(seg(0xE1, exifId + "XX".toByteArray() + ByteArray(10))), 6))
        // A multi-picture index ahead of the EXIF block: growing EXIF would break its offsets.
        val mpf = seg(0xE2, byteArrayOf(0x4D, 0x50, 0x46, 0) + ByteArray(8))
        assertNull(JpegOrientation.withOrientation(jpeg(mpf, exif(true, listOf(0x010F to 1))), 6))
        assertFalse(JpegOrientation.isJpeg(ByteArray(2)))
    }
}

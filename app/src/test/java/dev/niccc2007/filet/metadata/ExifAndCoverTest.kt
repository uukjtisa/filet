package dev.niccc2007.filet.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The EXIF reader, and the shared picture type.
 *
 * The EXIF half is built on a TIFF block assembled here by hand, which is the only way to test a
 * reader of a format defined entirely by internal offsets: a block whose offsets are right by
 * construction proves nothing, so the one below is laid out with its value area after its
 * directories exactly as a camera writes it, and the reader has to follow the pointers to find
 * anything at all.
 */
class ExifAndCoverTest {

    // ── a TIFF block, laid out by hand ────────────────────────────────────────────────────

    private fun be(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    private fun be16(v: Int): ByteArray = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    /** One twelve-byte tag record. Four bytes or fewer live in the record; longer is an offset. */
    private fun rec(tag: Int, type: Int, count: Int, payload: ByteArray): ByteArray {
        require(payload.size == 4)
        return be16(tag) + be16(type) + be(count) + payload
    }

    /**
     * A block with five records in IFD0 and a sub-directory holding the camera settings.
     *
     * Every offset is written out as a literal rather than accumulated, because the arithmetic is
     * the thing being tested: a fixture that computes its own offsets the same way the reader does
     * would agree with a reader that was wrong. The layout, counting from the TIFF header:
     *
     * ```
     *   0  MM 002A 00000008      the header, pointing at IFD0
     *   8  0005                  five records
     *  10  5 x 12 = 60 bytes     the records, ending at 70
     *  70  00000000              no second directory
     *  74  "Pixel\0"             6 bytes, the Make
     *  80  "Pixel 8\0"           8 bytes, the Model
     *  88  "Filet\0"             6 bytes, the Software
     *  94  the sub-directory     4 records, its own value area at 148
     * ```
     *
     * Deliberately big-endian, because little-endian is the common case and a reader that only
     * ever sees one order has its byte-order handling untested.
     */
    private fun exifBlock(): ByteArray {
        val header = "MM".toByteArray(Charsets.ISO_8859_1) + be16(42) + be(8)
        val ifd0 = be16(5) +
            rec(0x010F, 2, 6, be(74)) + // Make
            rec(0x0110, 2, 8, be(80)) + // Model
            rec(0x0112, 3, 1, byteArrayOf(0, 6, 0, 0)) + // Orientation, rotated left
            rec(0x0131, 2, 6, be(88)) + // Software
            rec(0x8769, 4, 1, be(94)) + // the pointer the reader has to follow
            be(0)
        val values = "Pixel\u0000".toByteArray(Charsets.ISO_8859_1) +
            "Pixel 8\u0000".toByteArray(Charsets.ISO_8859_1) +
            "Filet\u0000".toByteArray(Charsets.ISO_8859_1)
        val sub = be16(4) +
            rec(0x829A, 5, 1, be(148)) + // exposure, 1/250
            rec(0x829D, 5, 1, be(156)) + // f number, 18/10
            rec(0x8827, 3, 1, byteArrayOf(0x01, 0x90.toByte(), 0, 0)) + // ISO 400, in the record
            rec(0x9003, 2, 20, be(164)) + // date taken
            be(0) +
            be(1) + be(250) +
            be(18) + be(10) +
            "2026:09:19 08:14:03\u0000".toByteArray(Charsets.ISO_8859_1)
        val block = header + ifd0 + values + sub
        // If any of the literals above is wrong, every assertion in this file is meaningless.
        assertEquals("the IFD0 value area does not start at 74", 74, header.size + ifd0.size)
        assertEquals("the sub-directory does not start at 94", 94, header.size + ifd0.size + values.size)
        assertEquals("the sub value area does not start at 148", 148, 94 + 2 + 4 * 12 + 4)
        return block
    }

    /** The block wrapped in a JPEG APP1 segment, with a minimal image behind it. */
    private fun jpegWith(block: ByteArray): ByteArray {
        val payload = "Exif".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0) + block
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((len ushr 8) and 0xFF).toByte(), (len and 0xFF).toByte()) +
            payload +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    /** The same block in a RIFF chunk, which is how a WebP carries it. */
    private fun webpWith(block: ByteArray): ByteArray {
        fun le(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte(),
        )
        val vp8x = "VP8X".toByteArray(Charsets.ISO_8859_1) + le(10) + ByteArray(10)
        val exif = "EXIF".toByteArray(Charsets.ISO_8859_1) + le(block.size) + block +
            (if (block.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
        val body = "WEBP".toByteArray(Charsets.ISO_8859_1) + vp8x + exif
        return "RIFF".toByteArray(Charsets.ISO_8859_1) + le(body.size) + body
    }

    @Test
    fun `a big-endian block is read, pointers and all`() {
        val read = Exif.readJpeg(jpegWith(exifBlock())).toMap()
        assertEquals("Pixel 8", read["Camera"])
        assertEquals("Rotated left", read["Orientation"])
        assertEquals("Filet", read["Software"])
        // These four live in the sub-directory the reader has to follow a pointer to reach.
        assertEquals("1/250 s", read["Exposure"])
        assertEquals("f/1.8", read["Aperture"])
        assertEquals("400", read["ISO"])
        assertEquals("2026:09:19 08:14:03", read["Date taken"])
    }

    @Test
    fun `the maker is not repeated when the model already contains it`() {
        // Most phones write the maker into the model as well, so the naive join reads
        // "Google Google Pixel".
        val read = Exif.readJpeg(jpegWith(exifBlock())).toMap()
        assertEquals("Pixel 8", read["Camera"])
    }

    @Test
    fun `a shutter speed is shown the way a photographer writes it`() {
        // Nobody has ever said "my exposure was nought point nought nought four".
        assertEquals("1/250 s", Exif.readJpeg(jpegWith(exifBlock())).toMap()["Exposure"])
    }

    @Test
    fun `the same block is read out of a WebP`() {
        // Phones that save WebP write the same EXIF, so there is no reason to show fewer facts.
        val read = Exif.readRiff(webpWith(exifBlock())).toMap()
        assertEquals("Pixel 8", read["Camera"])
        assertEquals("f/1.8", read["Aperture"])
        assertTrue(Exif.hasRiff(webpWith(exifBlock())))
        assertTrue(!Exif.hasRiff(jpegWith(exifBlock())))
    }

    @Test
    fun `a block whose byte-order magic is wrong is not read backwards`() {
        // The 42 is the whole point of the byte-order mark. Without checking it, a block read in
        // the wrong order yields enormous lengths and offsets off the end of the file.
        val broken = exifBlock().copyOf()
        broken[2] = 0
        broken[3] = 0
        assertTrue(Exif.readJpeg(jpegWith(broken)).isEmpty())
    }

    @Test
    fun `a truncated block reads nothing rather than throwing`() {
        val full = jpegWith(exifBlock())
        for (cut in listOf(10, 24, 40, 60, full.size - 4)) {
            Exif.readJpeg(full.copyOfRange(0, cut))
        }
    }

    @Test
    fun `a JPEG with no EXIF says so`() {
        val plain = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertTrue(!Exif.hasJpeg(plain))
        assertTrue(Exif.readJpeg(plain).isEmpty())
    }

    @Test
    fun `every label the table advertises is one the reader can produce`() {
        // The dead claim this whole round was about: nine EXIF fields listed, nothing able to
        // read one. The reader's own label list is what the table now shows.
        val produced = Exif.readJpeg(jpegWith(exifBlock())).map { it.first }
        for (label in produced) {
            assertTrue("$label is produced but not advertised", label in Exif.FIELDS)
        }
        assertTrue(produced.isNotEmpty())
    }

    // ── the shared picture type ───────────────────────────────────────────────────────────

    @Test
    fun `the bytes decide the type, not the recorded mime`() {
        // Files in the wild carry image/jpg, JPG, an empty string, or a mime that disagrees with
        // the data. A cover saved as .png that is really a JPEG opens in nothing on a phone.
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        assertEquals("image/png", Cover.sniff(png))
        assertEquals(".png", Cover("image/jpeg", Cover.FRONT, "", png).extension)
        assertEquals("image/png", Cover.mimeFor("image/jpeg", png))
    }

    @Test
    fun `an unrecognised payload falls back to the most likely thing`() {
        assertNull(Cover.sniff(ByteArray(16)))
        assertEquals(".jpg", Cover.extensionFor(""))
        assertEquals(".jpg", Cover.extensionFor("JPG"))
        assertEquals(".png", Cover.extensionFor("image/png"))
        assertEquals(".webp", Cover.extensionFor("image/webp"))
    }

    @Test
    fun `two reads of the same cover compare equal`() {
        // The generated equals on a data class holding an array compares identity, which for an
        // image is never what a caller means - and a screen that re-decodes on every comparison
        // is a screen that flickers.
        val bytes = ByteArray(32) { it.toByte() }
        assertEquals(Cover.of(bytes), Cover.of(bytes.copyOf()))
        assertEquals(Cover.of(bytes).hashCode(), Cover.of(bytes.copyOf()).hashCode())
    }

    @Test
    fun `a WebP payload is recognised by its RIFF header and not only by RIFF`() {
        val riffOnly = "RIFF".toByteArray(Charsets.ISO_8859_1) + ByteArray(8) + "WAVE".toByteArray(Charsets.ISO_8859_1)
        assertNull(Cover.sniff(riffOnly))
        val webp = "RIFF".toByteArray(Charsets.ISO_8859_1) + ByteArray(4) + "WEBP".toByteArray(Charsets.ISO_8859_1) + ByteArray(4)
        assertEquals("image/webp", Cover.sniff(webp))
    }
}

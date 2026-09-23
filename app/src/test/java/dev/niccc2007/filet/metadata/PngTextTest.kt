package dev.niccc2007.filet.metadata

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PNG writer, tested by writing a file and reading it back.
 *
 * Round-tripping rather than matching bytes: the property that matters is that a decoder can
 * still read the file, and a byte-level expectation passes happily on output that no decoder
 * accepts.
 */
class PngTextTest {

    // ── a minimal but structurally valid PNG ──────────────────────────────────────────

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val len = data.size
        out.write((len ushr 24) and 0xFF); out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF); out.write(len and 0xFF)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        val v = crc.value.toInt()
        out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
        return out.toByteArray()
    }

    private fun png(idats: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(chunk("IHDR", ByteArray(13)))
        repeat(idats) { out.write(chunk("IDAT", byteArrayOf(1, 2, 3))) }
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    /** Re-walk the output the way a decoder would, so a broken CRC or length is caught. */
    private fun chunkTypes(bytes: ByteArray): List<String> {
        val types = ArrayList<String>()
        var i = 8
        while (i + 8 <= bytes.size) {
            val len = ((bytes[i].toInt() and 0xFF) shl 24) or
                ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            val type = String(bytes, i + 4, 4, Charsets.US_ASCII)
            val data = bytes.copyOfRange(i + 8, i + 8 + len)
            val crc = CRC32(); crc.update(type.toByteArray(Charsets.US_ASCII)); crc.update(data)
            val stored = ((bytes[i + 8 + len].toInt() and 0xFF) shl 24) or
                ((bytes[i + 9 + len].toInt() and 0xFF) shl 16) or
                ((bytes[i + 10 + len].toInt() and 0xFF) shl 8) or
                (bytes[i + 11 + len].toInt() and 0xFF)
            assertEquals("CRC wrong on $type", crc.value.toInt(), stored)
            types.add(type)
            i += 12 + len
            if (type == "IEND") break
        }
        return types
    }

    // ── the basics ────────────────────────────────────────────────────────────────────

    @Test
    fun `a value written comes back`() {
        val out = PngText.put(png(), "Title", "Bench photo")!!
        assertEquals(listOf(PngText.Entry("Title", "Bench photo", true)), PngText.read(out))
    }

    @Test
    fun `the file is still a structurally valid PNG`() {
        val out = PngText.put(png(), "Title", "x")!!
        assertEquals(listOf("IHDR", "iTXt", "IDAT", "IEND"), chunkTypes(out))
    }

    @Test
    fun `text goes before the image data, never after IEND`() {
        // A chunk after IEND is ignored by every decoder, so the write appears to succeed and
        // the value does not exist.
        val types = chunkTypes(PngText.put(png(), "Title", "x")!!)
        assertTrue(types.indexOf("iTXt") < types.indexOf("IDAT"))
        assertTrue(types.indexOf("iTXt") > types.indexOf("IHDR"))
    }

    @Test
    fun `multiple IDAT chunks stay contiguous`() {
        // Splitting them is a decoder error, and a large PNG always has several.
        val types = chunkTypes(PngText.put(png(idats = 3), "Title", "x")!!)
        val first = types.indexOf("IDAT")
        assertEquals(listOf("IDAT", "IDAT", "IDAT"), types.subList(first, first + 3))
    }

    // ── replacing and removing ────────────────────────────────────────────────────────

    @Test
    fun `writing the same key twice replaces rather than duplicates`() {
        var out = PngText.put(png(), "Title", "first")!!
        out = PngText.put(out, "Title", "second")!!
        assertEquals(1, PngText.read(out).size)
        assertEquals("second", PngText.read(out).first().value)
    }

    @Test
    fun `other keys survive a write`() {
        var out = PngText.put(png(), "Title", "t")!!
        out = PngText.put(out, "Author", "a")!!
        assertEquals(setOf("Title", "Author"), PngText.read(out).map { it.key }.toSet())
    }

    @Test
    fun `a key can be removed`() {
        var out = PngText.put(png(), "Title", "t")!!
        out = PngText.put(out, "Author", "a")!!
        out = PngText.remove(out, "Title")!!
        assertEquals(listOf("Author"), PngText.read(out).map { it.key })
    }

    @Test
    fun `writing an empty set clears every text chunk`() {
        val withText = PngText.put(png(), "Title", "t")!!
        val cleared = PngText.write(withText, emptyList())!!
        assertEquals(emptyList<PngText.Entry>(), PngText.read(cleared))
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunkTypes(cleared))
    }

    // ── what he actually asked for: custom keys and an embedded image ─────────────────

    @Test
    fun `an arbitrary custom key works`() {
        val out = PngText.put(png(), "Secret note", "meet at six")!!
        assertEquals("meet at six", PngText.read(out).first { it.key == "Secret note" }.value)
    }

    @Test
    fun `a long binary payload survives as text`() {
        // An embedded image rides in as base64 in an iTXt chunk. 40 KB is bigger than the
        // carrier PNG, which is the case that catches a length written as a short.
        val payload = buildString { repeat(40_000) { append("ABCD"[it % 4]) } }
        val out = PngText.put(png(), "Thumb", payload)!!
        assertEquals(payload, PngText.read(out).first().value)
        chunkTypes(out)
    }

    @Test
    fun `unicode survives a round trip`() {
        val out = PngText.put(png(), "Title", "été — 日本語")!!
        assertEquals("été — 日本語", PngText.read(out).first().value)
    }

    @Test
    fun `latin-1 tEXt is readable too`() {
        val out = PngText.write(png(), listOf(PngText.Entry("Title", "plain", utf8 = false)))!!
        assertEquals(listOf("IHDR", "tEXt", "IDAT", "IEND"), chunkTypes(out))
        assertEquals("plain", PngText.read(out).first().value)
    }

    // ── refusing rather than corrupting ───────────────────────────────────────────────

    @Test
    fun `something that is not a PNG is refused, not mangled`() {
        assertNull(PngText.put("not a png at all".toByteArray(), "Title", "x"))
        assertNull(PngText.put(ByteArray(0), "Title", "x"))
        assertEquals(emptyList<PngText.Entry>(), PngText.read(ByteArray(3)))
    }

    @Test
    fun `a truncated PNG is refused`() {
        val cut = png().copyOfRange(0, 20)
        assertNull(PngText.put(cut, "Title", "x"))
    }

    @Test
    fun `an invalid keyword is refused rather than silently rewritten`() {
        // Sanitising the key means the value cannot be found again under the name it was given.
        assertNull(PngText.put(png(), "", "x"))
        assertNull(PngText.put(png(), " leading", "x"))
        assertNull(PngText.put(png(), "trailing ", "x"))
        assertNull(PngText.put(png(), "double  space", "x"))
        assertNull(PngText.put(png(), "a".repeat(80), "x"))
    }

    @Test
    fun `keyword rules match the specification`() {
        assertTrue(PngText.isValidKeyword("Title"))
        assertTrue(PngText.isValidKeyword("a".repeat(79)))
        assertTrue(PngText.isValidKeyword("Creation Time"))
        assertFalse(PngText.isValidKeyword("has\nnewline"))
        assertFalse(PngText.isValidKeyword("tab\there"))
    }

    @Test
    fun `an empty value is allowed`() {
        val out = PngText.put(png(), "Title", "")!!
        assertEquals("", PngText.read(out).first().value)
    }

    @Test
    fun `a PNG with no text chunks reads as empty rather than failing`() {
        assertEquals(emptyList<PngText.Entry>(), PngText.read(png()))
        assertNotNull(PngText.write(png(), emptyList()))
    }
}

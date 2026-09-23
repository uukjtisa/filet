package dev.niccc2007.filet.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The single-comment formats. Each one is checked by reading the result back. */
class ContainerCommentsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── JPEG ──────────────────────────────────────────────────────────────────────────

    /** SOI, one APP0, a scan, EOI - the minimum a real decoder would accept as structure. */
    private fun jpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        out.write(0xFF); out.write(0xE0)
        val app0 = "JFIF\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0)
        out.write(((app0.size + 2) ushr 8) and 0xFF); out.write((app0.size + 2) and 0xFF)
        out.write(app0)
        out.write(0xFF); out.write(0xDA)
        out.write(0x00); out.write(0x03); out.write(0x01)
        out.write(byteArrayOf(0x11, 0x22, 0x33, 0x44)) // "image data"
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    @Test
    fun `a jpeg comment round-trips`() {
        val out = ContainerComments.writeJpegComment(jpeg(), "taken at the bench")!!
        assertEquals("taken at the bench", ContainerComments.readJpegComment(out))
    }

    @Test
    fun `the comment goes before the scan, not after it`() {
        // After SOS everything is entropy-coded pixels. A "comment" there is not a comment.
        val out = ContainerComments.writeJpegComment(jpeg(), "x")!!
        val sos = indexOfMarker(out, 0xDA)
        val com = indexOfMarker(out, 0xFE)
        assertTrue(com in 0 until sos)
    }

    @Test
    fun `image data survives untouched`() {
        val out = ContainerComments.writeJpegComment(jpeg(), "hello")!!
        assertTrue(contains(out, byteArrayOf(0x11, 0x22, 0x33, 0x44)))
    }

    @Test
    fun `writing twice replaces rather than stacking comments`() {
        var out = ContainerComments.writeJpegComment(jpeg(), "first")!!
        out = ContainerComments.writeJpegComment(out, "second")!!
        assertEquals("second", ContainerComments.readJpegComment(out))
        assertEquals(1, countMarkers(out, 0xFE))
    }

    @Test
    fun `an empty comment removes it`() {
        var out = ContainerComments.writeJpegComment(jpeg(), "first")!!
        out = ContainerComments.writeJpegComment(out, "")!!
        assertNull(ContainerComments.readJpegComment(out))
        assertEquals(0, countMarkers(out, 0xFE))
    }

    @Test
    fun `unicode survives`() {
        val out = ContainerComments.writeJpegComment(jpeg(), "日本語")!!
        assertEquals("日本語", ContainerComments.readJpegComment(out))
    }

    @Test
    fun `a comment too long for the length field is refused, not truncated`() {
        assertNull(ContainerComments.writeJpegComment(jpeg(), "a".repeat(70_000)))
    }

    @Test
    fun `something that is not a jpeg is refused`() {
        assertNull(ContainerComments.writeJpegComment("nope".toByteArray(), "x"))
        assertNull(ContainerComments.readJpegComment("nope".toByteArray()))
    }

    // ── GIF ───────────────────────────────────────────────────────────────────────────

    private fun gif(withTable: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(1, 0, 1, 0)) // 1x1
        out.write(if (withTable) 0x80 else 0x00) // global colour table flag, size 2
        out.write(0); out.write(0)
        if (withTable) out.write(ByteArray(6)) // 2 entries x 3 bytes
        out.write(0x3B) // trailer
        return out.toByteArray()
    }

    @Test
    fun `a gif comment is written after the header`() {
        val out = ContainerComments.writeGifComment(gif(), "note")!!
        // 0x21 0xFE is the comment extension; it must come after the colour table.
        val at = indexOfPair(out, 0x21, 0xFE)
        assertTrue(at >= 19)
    }

    @Test
    fun `a long gif comment is split into sub-blocks of at most 255`() {
        // One block longer than 255 makes the file decode up to the comment and then stop.
        val out = ContainerComments.writeGifComment(gif(), "a".repeat(600))!!
        var i = indexOfPair(out, 0x21, 0xFE) + 2
        var blocks = 0
        while (i < out.size) {
            val n = out[i].toInt() and 0xFF
            assertTrue("a sub-block claims $n bytes", n <= 255)
            if (n == 0) break
            blocks++
            i += 1 + n
        }
        assertEquals(3, blocks)
    }

    @Test
    fun `a gif with no colour table still works`() {
        assertNotNull(ContainerComments.writeGifComment(gif(withTable = false), "x"))
    }

    @Test
    fun `something that is not a gif is refused`() {
        assertNull(ContainerComments.writeGifComment("nope".toByteArray(), "x"))
    }

    // ── ZIP ───────────────────────────────────────────────────────────────────────────

    private fun zip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("a.txt"))
            z.write("hello".toByteArray())
            z.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `a zip comment round-trips`() {
        val out = ContainerComments.writeZipComment(zip(), "built by Filet")!!
        assertEquals("built by Filet", ContainerComments.readZipComment(out))
    }

    @Test
    fun `the archive is still readable by a real zip reader afterwards`() {
        // The assertion that matters: the entries must still be reachable. Writing past the
        // central directory is exactly the mistake that leaves a file that looks fine and
        // opens in nothing.
        val out = ContainerComments.writeZipComment(zip(), "a comment")!!
        val f = File(tmp.root, "out.zip")
        f.writeBytes(out)
        ZipFile(f).use { z ->
            assertEquals(listOf("a.txt"), z.entries().toList().map { it.name })
            assertEquals("hello", z.getInputStream(z.getEntry("a.txt")).readBytes().decodeToString())
            assertEquals("a comment", z.comment)
        }
    }

    @Test
    fun `replacing a comment does not stack them`() {
        var out = ContainerComments.writeZipComment(zip(), "one")!!
        out = ContainerComments.writeZipComment(out, "two")!!
        assertEquals("two", ContainerComments.readZipComment(out))
    }

    @Test
    fun `an empty comment clears it`() {
        var out = ContainerComments.writeZipComment(zip(), "one")!!
        out = ContainerComments.writeZipComment(out, "")!!
        assertEquals("", ContainerComments.readZipComment(out))
    }

    @Test
    fun `a comment too long for the two-byte field is refused`() {
        assertNull(ContainerComments.writeZipComment(zip(), "a".repeat(70_000)))
    }

    @Test
    fun `something that is not a zip is refused`() {
        assertNull(ContainerComments.writeZipComment("nope".toByteArray(), "x"))
        assertNull(ContainerComments.readZipComment("nope".toByteArray()))
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────

    private fun indexOfMarker(b: ByteArray, marker: Int): Int {
        for (i in 0 until b.size - 1) {
            if ((b[i].toInt() and 0xFF) == 0xFF && (b[i + 1].toInt() and 0xFF) == marker) return i
        }
        return -1
    }

    private fun countMarkers(b: ByteArray, marker: Int): Int {
        var n = 0
        for (i in 0 until b.size - 1) {
            if ((b[i].toInt() and 0xFF) == 0xFF && (b[i + 1].toInt() and 0xFF) == marker) n++
        }
        return n
    }

    private fun indexOfPair(b: ByteArray, a: Int, c: Int): Int {
        for (i in 0 until b.size - 1) {
            if ((b[i].toInt() and 0xFF) == a && (b[i + 1].toInt() and 0xFF) == c) return i
        }
        return -1
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}

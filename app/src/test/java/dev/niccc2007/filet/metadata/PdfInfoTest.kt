package dev.niccc2007.filet.metadata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PDF writer, which does not write anything.
 *
 * It appends. An incremental update puts a new version of the Info object at the end of the file
 * with a small cross-reference section and a trailer chaining back to the previous one, and
 * readers take the newest version of each object. So the property every test here leans on is
 * that the original bytes come back byte for byte - which makes this the safest format Filet
 * touches rather than, as the support table used to have it, the most dangerous.
 */
class PdfInfoTest {

    /**
     * A minimal but genuinely valid PDF with a classic cross-reference table.
     *
     * Built with the real offsets rather than plausible ones, because `/Prev` has to point at
     * something and a reader that follows it to the wrong place finds nothing.
     */
    private fun pdf(
        info: String? = "<< /Title (First light) /Author (someone) >>",
        pageNote: String = "",
    ): ByteArray {
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = HashMap<Int, Int>()
        fun obj(n: Int, body: String) {
            offsets[n] = sb.length
            sb.append("$n 0 obj\n$body\nendobj\n")
        }
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842]$pageNote >>")
        if (info != null) obj(4, info)
        val size = if (info == null) 4 else 5
        val xrefAt = sb.length
        sb.append("xref\n0 $size\n")
        sb.append("0000000000 65535 f \n")
        for (n in 1 until size) sb.append(String.format("%010d 00000 n \n", offsets[n]))
        sb.append("trailer\n<< /Size $size /Root 1 0 R")
        if (info != null) sb.append(" /Info 4 0 R")
        sb.append(" >>\nstartxref\n$xrefAt\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun `the shape of a file is read off the end of it`() {
        val shape = PdfInfo.shape(pdf())!!
        assertEquals(4, shape.infoObject)
        assertEquals("1 0 R", shape.rootRef)
        assertEquals(5, shape.size)
        assertTrue(shape.classicXref)
        assertTrue(!shape.encrypted)
        assertNull(PdfInfo.refusal(pdf()))
    }

    @Test
    fun `the properties are read`() {
        val read = PdfInfo.read(pdf()).toMap()
        assertEquals("First light", read["Title"])
        assertEquals("someone", read["Author"])
    }

    @Test
    fun `not one original byte is touched`() {
        // The claim that makes this format safe, and the only one worth testing first. A signed
        // PDF keeps its signature for exactly this reason.
        val original = pdf()
        val out = PdfInfo.put(original, "Title", "Second light")!!
        assertArrayEquals(original, out.copyOfRange(0, original.size))
        assertTrue(out.size > original.size)
    }

    @Test
    fun `the appended version is the one that is read back`() {
        val out = PdfInfo.put(pdf(), "Title", "Second light")!!
        assertEquals("Second light", PdfInfo.read(out).toMap()["Title"])
        // And the field that was not touched survives into the new version of the object.
        assertEquals("someone", PdfInfo.read(out).toMap()["Author"])
    }

    @Test
    fun `an update chains to the previous cross-reference`() {
        val original = pdf()
        val before = PdfInfo.shape(original)!!.startxref
        val out = PdfInfo.put(original, "Title", "t")!!
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue("the trailer must point back at the old table", text.contains("/Prev $before"))
        // And the new startxref points at the new table rather than at the old one.
        val after = PdfInfo.shape(out)!!.startxref
        assertTrue(after > before)
        assertTrue(text.startsWith("xref", after))
    }

    @Test
    fun `every cross-reference entry is exactly twenty bytes`() {
        // A reader seeks into the table by multiplying, so one short entry shifts every entry
        // after it. The trailing space before the line ending is not decoration.
        val out = PdfInfo.put(pdf(), "Title", "t")!!
        val text = String(out, Charsets.ISO_8859_1)
        val at = text.lastIndexOf("xref\n")
        val body = text.substring(at + 5)
        for (line in body.lineSequence()) {
            if (line.startsWith("trailer")) break
            if (line.isBlank() || line.first().isDigit() && line.length < 18) continue
            if (line.endsWith(" n ") || line.endsWith(" f ")) {
                assertEquals("entry is the wrong width: '$line'", 19, line.length)
            }
        }
    }

    @Test
    fun `a file with no Info object gets one`() {
        val bare = pdf(info = null)
        assertTrue(PdfInfo.read(bare).isEmpty())
        val out = PdfInfo.put(bare, "Title", "a first title")!!
        assertEquals("a first title", PdfInfo.read(out).toMap()["Title"])
        // And the trailer's object count grows to cover the new object.
        assertTrue(String(out, Charsets.ISO_8859_1).contains("/Size 5"))
    }

    @Test
    fun `clearing a field leaves the others`() {
        val out = PdfInfo.put(pdf(), "Title", "")!!
        val read = PdfInfo.read(out).toMap()
        assertNull(read["Title"])
        assertEquals("someone", read["Author"])
    }

    @Test
    fun `a value a literal string cannot hold goes out as UTF-16`() {
        // PDFDocEncoding has no slot for most of what somebody might type, and a literal string
        // holding raw UTF-8 reads back as two characters per letter.
        val out = PdfInfo.put(pdf(), "Title", "Bukas   na 你好")!!
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue("the appended string should be hex with a byte-order mark", text.contains("<FEFF"))
        assertEquals("Bukas   na 你好", PdfInfo.read(out).toMap()["Title"])
    }

    @Test
    fun `a value containing parentheses is escaped`() {
        // An unescaped one closes the string early, and everything after it is read as PDF
        // syntax - which is a file that will not open rather than a wrong title.
        val out = PdfInfo.put(pdf(), "Title", "First light (take 2) \\ done")!!
        assertEquals("First light (take 2) \\ done", PdfInfo.read(out).toMap()["Title"])
    }

    @Test
    fun `a hex string is decoded`() {
        assertEquals("AB", PdfInfo.decodeString("<4142>"))
        // With a byte-order mark it is UTF-16, which is how most producers write a title.
        assertEquals("Hi", PdfInfo.decodeString("<FEFF00480069>"))
        // An odd number of digits means the last byte is half given and zero is assumed.
        assertEquals("A@", PdfInfo.decodeString("<41 4>"))
        assertNull(PdfInfo.decodeString("<zz>"))
    }

    @Test
    fun `an escape inside a literal string is honoured`() {
        assertEquals("a\nb", PdfInfo.decodeString("(a\\nb)"))
        assertEquals("a(b)c", PdfInfo.decodeString("(a\\(b\\)c)"))
        assertEquals("A", PdfInfo.decodeString("(\\101)"))
        // A nested pair of parentheses is legal unescaped, and counting is the only way to find
        // the real end of the string.
        assertEquals("a(b)c", PdfInfo.decodeString("(a(b)c)"))
    }

    @Test
    fun `a dictionary entry keeps the source text of anything not understood`() {
        // An entry written by some other tool is copied through rather than discarded, which is
        // the only way to edit one field without quietly dropping the rest.
        val info = "<< /Title (t) /Producer (something) /Custom [1 2 3] /Ref 9 0 R >>"
        val entries = PdfInfo.entries(info).toMap()
        assertEquals("(t)", entries["Title"])
        assertEquals("[1 2 3]", entries["Custom"])
        assertEquals("9 0 R", entries["Ref"])
        val out = PdfInfo.put(pdf(info), "Title", "changed")!!
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue(text.contains("/Custom [1 2 3]"))
        assertTrue(text.contains("/Ref 9 0 R"))
    }

    @Test
    fun `a cross-reference stream is read and refused`() {
        // Appending a plain table to one produces a file some readers accept and others do not,
        // and one that opens only on the machine that made it is the worst kind of bug to ship.
        val streamed = String(pdf(), Charsets.ISO_8859_1)
            .replace("xref\n0 5\n", "0 5\n")
            .toByteArray(Charsets.ISO_8859_1)
        assertTrue(!PdfInfo.shape(streamed)!!.classicXref)
        assertNotNull(PdfInfo.refusal(streamed))
        assertTrue(PdfInfo.refusal(streamed)!!.contains("stream"))
        assertNull(PdfInfo.put(streamed, "Title", "t"))
        // Reading still works, which is the half that is safe.
        assertEquals("First light", PdfInfo.read(streamed).toMap()["Title"])
    }

    @Test
    fun `an encrypted document is refused`() {
        val encrypted = String(pdf(), Charsets.ISO_8859_1)
            .replace("/Root 1 0 R", "/Root 1 0 R /Encrypt 9 0 R")
            .toByteArray(Charsets.ISO_8859_1)
        assertTrue(PdfInfo.shape(encrypted)!!.encrypted)
        assertTrue(PdfInfo.refusal(encrypted)!!.contains("encrypted"))
        assertNull(PdfInfo.put(encrypted, "Title", "t"))
    }

    @Test
    fun `the word Encrypt inside a page is not mistaken for the real thing`() {
        // Searching the whole file for it refuses a perfectly ordinary document because a page
        // happened to contain the word.
        val innocent = pdf(pageNote = " /Note (/Encrypt is discussed here)")
        assertTrue(!PdfInfo.shape(innocent)!!.encrypted)
        assertNull(PdfInfo.refusal(innocent))
        assertNotNull(PdfInfo.put(innocent, "Title", "t"))
    }

    @Test
    fun `an object number is not matched inside a longer one`() {
        // Object 4 must not be found inside `14 0 obj`, which would read somebody else's
        // dictionary as the document's properties.
        val text = "%PDF-1.4\n14 0 obj\n<< /Title (wrong) >>\nendobj\n4 0 obj\n<< /Title (right) >>\nendobj\n" +
            "xref\n0 1\n0000000000 65535 f \ntrailer\n<< /Size 15 /Root 1 0 R /Info 4 0 R >>\n" +
            "startxref\n9\n%%EOF\n"
        val read = PdfInfo.read(text.toByteArray(Charsets.ISO_8859_1)).toMap()
        assertEquals("right", read["Title"])
    }

    @Test
    fun `something that is not a PDF is refused quietly`() {
        assertNull(PdfInfo.shape(ByteArray(64)))
        assertNull(PdfInfo.put(ByteArray(64), "Title", "t"))
        assertTrue(PdfInfo.read(ByteArray(64)).isEmpty())
    }

    @Test
    fun `a field this format has no key for is refused`() {
        assertNull(PdfInfo.put(pdf(), "secret note", "x"))
    }
}

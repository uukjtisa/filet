package dev.niccc2007.filet.metadata

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zip rewriter and the Office document properties on top of it.
 *
 * The archives here are built with the standard library's own writer rather than by hand, so what
 * is being tested is a real zip produced by something else - which is the only version of this
 * test worth having.
 */
class ZipAndOoxmlTest {

    private val core = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>First light</dc:title><dc:creator>someone</dc:creator><cp:revision>3</cp:revision><dcterms:created xsi:type="dcterms:W3CDTF">2026-09-19T08:00:00Z</dcterms:created></cp:coreProperties>"""

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun docx(coreXml: String = core): ByteArray = zip(
        "[Content_Types].xml" to "<Types/>".toByteArray(Charsets.UTF_8),
        "_rels/.rels" to "<Relationships/>".toByteArray(Charsets.UTF_8),
        "docProps/core.xml" to coreXml.toByteArray(Charsets.UTF_8),
        "word/document.xml" to ("<w:document>" + "x".repeat(4000) + "</w:document>").toByteArray(Charsets.UTF_8),
    )

    // ── the zip layer ─────────────────────────────────────────────────────────────────────

    @Test
    fun `an entry reads back exactly`() {
        val payload = ByteArray(5_000) { (it % 251).toByte() }
        val archive = zip("a.bin" to payload, "b.txt" to "hello".toByteArray(Charsets.UTF_8))
        assertArrayEquals(payload, Zip.read(archive, "a.bin"))
        assertEquals("hello", Zip.read(archive, "b.txt")?.toString(Charsets.UTF_8))
        assertNull(Zip.read(archive, "nothing-here"))
    }

    @Test
    fun `replacing one entry leaves the others byte for byte`() {
        // The property the rewriter exists for: nothing else is re-compressed, re-ordered or
        // re-anything. A document under version control must not show a whole-file diff because
        // its title changed.
        val big = ByteArray(9_000) { (it % 97).toByte() }
        val archive = zip("keep.bin" to big, "edit.txt" to "before".toByteArray(Charsets.UTF_8))
        val out = Zip.replace(archive, "edit.txt", "after".toByteArray(Charsets.UTF_8))!!
        assertArrayEquals(big, Zip.read(out, "keep.bin"))
        assertEquals("after", Zip.read(out, "edit.txt")?.toString(Charsets.UTF_8))
    }

    @Test
    fun `the order of entries is preserved`() {
        // Some readers expect the first entry of an OOXML package to be the content types, and
        // a rewriter that sorts them is a rewriter that breaks those readers.
        val archive = docx()
        val names = Zip.entries(archive)!!.map { it.name }
        val out = Zip.replace(archive, "docProps/core.xml", "<x/>".toByteArray(Charsets.UTF_8))!!
        assertEquals(names, Zip.entries(out)!!.map { it.name })
    }

    @Test
    fun `the rebuilt central directory points at the rebuilt local headers`() {
        // An offset that is off by one byte gives an archive that lists correctly and fails to
        // extract, which is the failure that looks like a corrupt download.
        val out = Zip.replace(docx(), "docProps/core.xml", core.toByteArray(Charsets.UTF_8))!!
        for (e in Zip.entries(out)!!) {
            assertNotNull("${e.name} cannot be read back", Zip.read(out, e.name))
        }
    }

    @Test
    fun `the archive comment is carried across`() {
        val archive = docx()
        val withComment = ContainerComments.writeZipComment(archive, "a note")!!
        val out = Zip.replace(withComment, "docProps/core.xml", "<x/>".toByteArray(Charsets.UTF_8))!!
        assertEquals("a note", ContainerComments.readZipComment(out))
    }

    @Test
    fun `replacing an entry that is not there changes nothing`() {
        assertNull(Zip.replace(docx(), "docProps/absent.xml", ByteArray(4)))
    }

    @Test
    fun `something that is not a zip is refused`() {
        assertNull(Zip.entries(ByteArray(64)))
        assertTrue(!Zip.isZip(ByteArray(64)))
    }

    // ── the document properties ───────────────────────────────────────────────────────────

    @Test
    fun `the properties of a document are read`() {
        val read = Ooxml.read(docx()).toMap()
        assertEquals("First light", read["Title"])
        assertEquals("someone", read["Author"])
        assertEquals("3", read["Revision"])
        assertEquals("2026-09-19T08:00:00Z", read["Created"])
    }

    @Test
    fun `a title survives a round trip and the document body is untouched`() {
        val original = docx()
        val body = Zip.read(original, "word/document.xml")!!
        val out = Ooxml.put(original, "Title", "Second light")!!
        assertEquals("Second light", Ooxml.read(out).toMap()["Title"])
        assertArrayEquals(body, Zip.read(out, "word/document.xml"))
    }

    @Test
    fun `only the one element changes`() {
        // Checked on the XML text rather than on the parsed values, because the point is that
        // nothing else in the file was rewritten.
        val out = Ooxml.put(docx(), "Title", "Second light")!!
        val xml = Ooxml.core(out)!!
        assertTrue(xml.contains("<dc:creator>someone</dc:creator>"))
        assertTrue(xml.contains("<cp:revision>3</cp:revision>"))
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"))
    }

    @Test
    fun `a property the document has not got is added`() {
        val out = Ooxml.put(docx(), "Keywords", "light, bench")!!
        assertEquals("light, bench", Ooxml.read(out).toMap()["Keywords"])
        // And appended inside the root element rather than after it.
        assertTrue(Ooxml.core(out)!!.endsWith("</cp:coreProperties>"))
    }

    @Test
    fun `a new timestamp keeps the type attribute the format requires`() {
        val out = Ooxml.put(docx(), "Modified", "2026-10-04T09:00:00Z")!!
        val xml = Ooxml.core(out)!!
        assertTrue(xml.contains("<dcterms:modified xsi:type=\"dcterms:W3CDTF\">2026-10-04T09:00:00Z"))
    }

    @Test
    fun `an empty element is expanded rather than written past`() {
        // Office writes `<dc:subject/>` for a property that exists and is blank, and a writer
        // that only knows the paired form appends a second element with the same name.
        val blank = core.replace(
            "<cp:revision>3</cp:revision>",
            "<cp:revision>3</cp:revision><dc:subject/>",
        )
        val out = Ooxml.put(docx(blank), "Subject", "benches")!!
        val xml = Ooxml.core(out)!!
        assertEquals("benches", Ooxml.read(out).toMap()["Subject"])
        assertEquals(1, Regex("<dc:subject").findAll(xml).count())
    }

    @Test
    fun `an element with attributes keeps them when it is filled in`() {
        val blank = core.replace(
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">2026-09-19T08:00:00Z</dcterms:created>",
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\"/>",
        )
        val out = Ooxml.put(docx(blank), "Created", "2026-01-01T00:00:00Z")!!
        assertTrue(Ooxml.core(out)!!.contains("<dcterms:created xsi:type=\"dcterms:W3CDTF\">2026-01-01T00:00:00Z"))
    }

    @Test
    fun `the three characters that would break the XML are escaped`() {
        val out = Ooxml.put(docx(), "Title", "Fish & chips <or> not")!!
        val xml = Ooxml.core(out)!!
        assertTrue(xml.contains("Fish &amp; chips &lt;or&gt; not"))
        assertEquals("Fish & chips <or> not", Ooxml.read(out).toMap()["Title"])
    }

    @Test
    fun `an apostrophe is left as itself`() {
        // Escaping it inside element text is legal and produces a title that reads `someone&apos;s`
        // in every editor that shows the XML.
        val out = Ooxml.put(docx(), "Author", "someone's camera")!!
        assertTrue(Ooxml.core(out)!!.contains("someone's camera"))
        assertEquals("someone's camera", Ooxml.read(out).toMap()["Author"])
    }

    @Test
    fun `clearing a property leaves an element with nothing in it`() {
        val out = Ooxml.put(docx(), "Title", "")!!
        assertNull(Ooxml.read(out).toMap()["Title"])
    }

    @Test
    fun `an element whose name starts the same is not mistaken for it`() {
        // `<dc:title` must not match `<dc:titleAlternative`, which is the kind of near miss that
        // writes a value into the wrong property and leaves the right one alone.
        val xml = "<cp:coreProperties><dc:titleAlternative>other</dc:titleAlternative></cp:coreProperties>"
        assertNull(Ooxml.textOf(xml, "dc:title"))
        val out = Ooxml.withText(xml, "dc:title", "mine")!!
        assertEquals("mine", Ooxml.textOf(out, "dc:title"))
        assertEquals("other", Ooxml.textOf(out, "dc:titleAlternative"))
    }

    @Test
    fun `a package with no properties file is refused rather than given one`() {
        // Creating it means also declaring it in the content types and in the relationships, and
        // a document with two of those three is one Word offers to repair.
        val bare = zip("[Content_Types].xml" to "<Types/>".toByteArray(Charsets.UTF_8))
        assertTrue(Ooxml.isOoxml(bare))
        assertNull(Ooxml.put(bare, "Title", "t"))
        assertNotNull(OoxmlContainer.refusal(bare))
    }

    @Test
    fun `a plain zip is not mistaken for a document`() {
        val archive = zip("a.txt" to "x".toByteArray(Charsets.UTF_8))
        assertTrue(!Ooxml.isOoxml(archive))
        // And the zip container keeps it, so its archive comment is still editable.
        assertTrue(ZipContainer.matches("zip", archive))
        assertTrue(!ZipContainer.matches("docx", docx()))
    }

    @Test
    fun `every extension the format table lists is one the engine handles`() {
        for (e in MetadataSupport.FORMATS.first { it.engine == OoxmlContainer }.extensions) {
            assertTrue("$e is listed but not handled", Ooxml.handles(e))
        }
    }
}

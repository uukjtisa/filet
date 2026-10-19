package dev.niccc2007.filet.docs

import dev.niccc2007.filet.docs.OfficeFixtures.aPara
import dev.niccc2007.filet.docs.OfficeFixtures.docx
import dev.niccc2007.filet.docs.OfficeFixtures.pptx
import dev.niccc2007.filet.docs.OfficeFixtures.rels
import dev.niccc2007.filet.docs.OfficeFixtures.shape
import dev.niccc2007.filet.docs.OfficeFixtures.slide
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Editing an office file changes its words and nothing else, and a failed save costs nothing.
 *
 * Every archive here is real, and every assertion about "nothing else" is made on the bytes, not
 * on a re-parse - a re-parse would pass an editor that regenerated the file from its model.
 */
class OfficeEditTest {

    private val W = OfficeTextEdit.Flavor.WORD

    private fun body(inner: String) = "<w:document ${OfficeFixtures.W}><w:body>$inner</w:body></w:document>"

    private fun apply(xml: String, edits: Map<Int, String>, flavor: OfficeTextEdit.Flavor = W): String =
        when (val r = OfficeTextEdit.apply(xml, flavor, edits)) {
            is OfficeTextEdit.Result.Ok -> r.xml
            is OfficeTextEdit.Result.Refused -> throw AssertionError("refused: " + r.why)
        }

    // ── the splice ────────────────────────────────────────────────────────────────

    @Test
    fun `an edit inside one run changes only that run's characters`() {
        val xml = body(
            "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">Hello </w:t></w:r>" +
                "<w:r><w:t>world</w:t></w:r></w:p><w:sectPr><w:pgSz w:w=\"11906\"/></w:sectPr>",
        )
        val out = apply(xml, mapOf(0 to "Hello there"))
        // Byte-for-byte the input with one text node changed. A regenerated document cannot
        // pass this: it would have to reproduce the section properties it never modelled.
        assertEquals(xml.replace(">world<", ">there<"), out)
        val para = OfficeReader.wordBlocks(out).filterIsInstance<Block.Para>().single()
        assertEquals("Hello there", para.text)
        assertEquals(Span(0, 6, Mark.BOLD), para.spans.single())
    }

    @Test
    fun `an edit across runs keeps the formatting of the text it did not touch`() {
        val xml = body(
            "<w:p><w:r><w:t>one </w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t>two</w:t></w:r>" +
                "<w:r><w:t> three</w:t></w:r></w:p>",
        )
        val out = apply(xml, mapOf(0 to "one 2 three"))
        val para = OfficeReader.wordBlocks(out).filterIsInstance<Block.Para>().single()
        assertEquals("one 2 three", para.text)
        // The replacement went into the run it was typed into, which was italic.
        assertEquals(Span(4, 5, Mark.ITALIC), para.spans.single())
    }

    @Test
    fun `a new edge space is marked to be preserved, or Word would drop it`() {
        val xml = body("<w:p><w:r><w:t>a</w:t></w:r><w:r><w:t>b</w:t></w:r></w:p>")
        val out = apply(xml, mapOf(0 to "a b"))
        assertTrue(out, out.contains("<w:t xml:space=\"preserve\">a </w:t>"))
    }

    @Test
    fun `markup characters are escaped and entities round-trip`() {
        val xml = body("<w:p><w:r><w:t>A &amp; B</w:t></w:r></w:p>")
        assertEquals("A & B", OfficeTextEdit.scan(xml, W).single().text)
        val out = apply(xml, mapOf(0 to "A & <C>"))
        assertTrue(out, out.contains("<w:t>A &amp; &lt;C&gt;</w:t>"))
    }

    @Test
    fun `removing a line break or typing a new one is refused, not approximated`() {
        val xml = body("<w:p><w:r><w:t>top</w:t><w:br/><w:t>bottom</w:t></w:r></w:p>")
        assertEquals("top\nbottom", OfficeTextEdit.scan(xml, W).single().text)
        assertTrue(OfficeTextEdit.apply(xml, W, mapOf(0 to "topbottom")) is OfficeTextEdit.Result.Refused)
        assertTrue(OfficeTextEdit.apply(xml, W, mapOf(0 to "top\nbot\ntom")) is OfficeTextEdit.Result.Refused)
        // Either side of the break is still editable.
        assertEquals("TOP\nbottom", OfficeTextEdit.scan(apply(xml, mapOf(0 to "TOP\nbottom")), W).single().text)
    }

    @Test
    fun `the fallback copy of a text box is not offered`() {
        val xml = body(
            "<w:p><w:r><mc:AlternateContent><mc:Choice><w:drawing><w:txbxContent>" +
                "<w:p><w:r><w:t>box</w:t></w:r></w:p></w:txbxContent></w:drawing></mc:Choice>" +
                "<mc:Fallback><w:pict><w:txbxContent><w:p><w:r><w:t>box</w:t></w:r></w:p>" +
                "</w:txbxContent></w:pict></mc:Fallback></mc:AlternateContent></w:r></w:p>",
        )
        assertEquals(listOf("box"), OfficeTextEdit.scan(xml, W).filter { it.hasText }.map { it.text })
    }

    // ── the archive ───────────────────────────────────────────────────────────────

    private fun richDocx(): ByteArray = docx(
        "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Title</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>Body text</w:t></w:r></w:p>" +
            "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"10\" cy=\"10\"/><a:graphic><a:graphicData><pic:pic>" +
            "<pic:blipFill><a:blip r:embed=\"rId4\"/></pic:blipFill></pic:pic></a:graphicData></a:graphic>" +
            "</wp:inline></w:drawing></w:r></w:p>",
        styles = "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/></w:style>",
        rels = rels(Triple("rId4", "image", "media/image1.png")),
        media = mapOf("word/media/image1.png" to OfficeFixtures.PNG),
    )

    @Test
    fun `saving a docx rewrites the body and leaves every other entry byte-identical`() {
        val original = richDocx()
        val session = OfficeEditSession.load(original, presentation = false)
        val body = session.sections.single()
        val target = body.paragraphs.first { it.text == "Body text" }
        val (patched, changed) = session.save(mapOf(body.entry to mapOf(target.index to "Edited text")))
        assertEquals(setOf("word/document.xml"), changed.keys)

        // Raw compressed bytes of every other entry, compared directly.
        val a = ZipPatch.open(original)
        val b = ZipPatch.open(patched)
        assertEquals(a.entries.map { it.name }, b.entries.map { it.name })
        for ((ea, eb) in a.entries.zip(b.entries)) {
            if (ea.name == "word/document.xml") continue
            assertEquals(ea.crc, eb.crc)
            assertArrayEquals(ZipPatch.read(original, ea), ZipPatch.read(patched, eb))
        }
        // And it still reads as the document it was: heading, picture, new text.
        val doc = OfficeFixtures.readDocx(patched)
        assertEquals(1, doc.blocks.filterIsInstance<Block.Para>().first().level)
        assertEquals("Edited text", doc.blocks.filterIsInstance<Block.Para>()[1].text)
        assertArrayEquals(OfficeFixtures.PNG, doc.blocks.filterIsInstance<Block.Image>().single().data)
    }

    @Test
    fun `saving one slide rewrites only that slide`() {
        val original = pptx(
            slides = mapOf(
                "slide1.xml" to slide(shape(aPara("First"), ph = "title")),
                "slide2.xml" to slide(shape(aPara("Second"), ph = "title")),
            ),
        )
        val session = OfficeEditSession.load(original, presentation = true)
        assertEquals(listOf("Slide 1", "Slide 2"), session.sections.map { it.label })
        val two = session.sections[1]
        val (patched, changed) = session.save(mapOf(two.entry to mapOf(two.paragraphs.single().index to "Changed")))
        assertEquals(setOf("ppt/slides/slide2.xml"), changed.keys)
        val titles = OfficeFixtures.readPptx(patched).blocks.filterIsInstance<Block.Slide>().map { it.title }
        assertEquals(listOf("First", "Changed"), titles)
    }

    @Test
    fun `a stored entry and a deflated one are both rewritten correctly`() {
        val doc = body("<w:p><w:r><w:t>stored</w:t></w:r></w:p>").toByteArray()
        val other = "keep me".toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val e = ZipEntry("word/document.xml").apply {
                method = ZipEntry.STORED
                size = doc.size.toLong()
                compressedSize = doc.size.toLong()
                crc = CRC32().apply { update(doc) }.value
            }
            z.putNextEntry(e); z.write(doc); z.closeEntry()
            z.putNextEntry(ZipEntry("other.bin")); z.write(other); z.closeEntry()
        }
        val zip = out.toByteArray()
        val replacement = body("<w:p><w:r><w:t>changed and longer</w:t></w:r></w:p>").toByteArray()
        val patched = ZipPatch.replace(zip, mapOf("word/document.xml" to replacement))
        ZipPatch.check(zip, patched, mapOf("word/document.xml" to replacement))
        assertArrayEquals(replacement, ZipPatch.read(patched, "word/document.xml"))
        assertArrayEquals(other, ZipPatch.read(patched, "other.bin"))
    }

    @Test
    fun `check catches an archive that changed an entry it was not asked to`() {
        val original = richDocx()
        val forged = ZipPatch.replace(original, mapOf("word/styles.xml" to "<x/>".toByteArray()))
        try {
            ZipPatch.check(original, forged, emptyMap())
            fail("a changed styles part passed the check")
        } catch (expected: ZipPatch.Refused) {
            assertTrue(expected.message.orEmpty().contains("styles"))
        }
    }

    // ── the replace protocol ──────────────────────────────────────────────────────

    /** A folder in memory that fails on cue. */
    private class FakeStore(initial: Map<String, ByteArray>) : SiblingStore {
        val files = LinkedHashMap(initial)
        var failWriteAfter = -1
        var corruptOnWrite = false
        var failRenameTo: String? = null

        override suspend fun exists(name: String) = name in files
        override suspend fun write(name: String, bytes: ByteArray) {
            if (failWriteAfter >= 0) {
                files[name] = bytes.copyOf(failWriteAfter)
                error("disk full")
            }
            files[name] = if (corruptOnWrite) bytes.copyOf(bytes.size - 1) else bytes
        }
        override suspend fun read(name: String) = files[name] ?: error("missing $name")
        override suspend fun rename(from: String, to: String) {
            if (to == failRenameTo) error("refused")
            files[to] = files.remove(from) ?: error("missing $from")
        }
        override suspend fun delete(name: String) { files.remove(name) }
    }

    private val old = "original".toByteArray()
    private val new = "the new document".toByteArray()

    @Test
    fun `a successful replace leaves the new bytes and nothing beside them`() = runBlocking {
        val store = FakeStore(mapOf("a.docx" to old))
        SafeReplace.replace(store, "a.docx", new) {}
        assertArrayEquals(new, store.files["a.docx"])
        assertEquals(setOf("a.docx"), store.files.keys)
    }

    @Test
    fun `a write that dies halfway leaves the original untouched`() = runBlocking {
        val store = FakeStore(mapOf("a.docx" to old))
        store.failWriteAfter = 3
        assertFails { SafeReplace.replace(store, "a.docx", new) {} }
        assertArrayEquals(old, store.files["a.docx"])
        assertEquals(setOf("a.docx"), store.files.keys)
    }

    @Test
    fun `a backend that stores the wrong bytes is caught before the original is touched`() = runBlocking {
        val store = FakeStore(mapOf("a.docx" to old))
        store.corruptOnWrite = true
        assertFails { SafeReplace.replace(store, "a.docx", new) {} }
        assertArrayEquals(old, store.files["a.docx"])
        assertFalse(SafeReplace.tempName("a.docx") in store.files)
    }

    @Test
    fun `a failed verification leaves the original untouched`() = runBlocking {
        val store = FakeStore(mapOf("a.docx" to old))
        assertFails { SafeReplace.replace(store, "a.docx", new) { error("not a zip") } }
        assertArrayEquals(old, store.files["a.docx"])
    }

    @Test
    fun `a refused final rename puts the original back`() = runBlocking {
        val store = FakeStore(mapOf("a.docx" to old))
        store.failRenameTo = "a.docx"
        assertFails { SafeReplace.replace(store, "a.docx", new) {} }
        // The rename BACK also targets a.docx, so this store refuses that too - the worst case.
        // The original must still exist, under the name the error gives.
        assertArrayEquals(old, store.files[SafeReplace.asideName("a.docx")])
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        try {
            block()
        } catch (expected: Throwable) {
            return
        }
        fail("expected a failure")
    }
}

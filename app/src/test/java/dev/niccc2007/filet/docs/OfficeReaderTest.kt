package dev.niccc2007.filet.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OOXML readers, on XML rather than on files.
 *
 * These exist because a format reader is the thing most likely to be quietly wrong, and
 * quietly wrong here means a document that is missing a third of itself or has its emphasis in
 * the wrong places - neither of which throws, and both of which are found by whoever is trying
 * to read the document. SAX rather than `XmlPullParser` is what makes this testable at all;
 * the pull parser in the unit-test android.jar is a stub that throws.
 *
 * Every fixture below is the shape a real producer writes, prefixes and all.
 */
class OfficeReaderTest {

    private fun docx(body: String) =
        """<?xml version="1.0"?><w:document xmlns:w="x"><w:body>$body</w:body></w:document>"""

    @Test
    fun `a paragraph is its runs joined`() {
        val blocks = OfficeReader.wordBlocks(
            docx("<w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t>world</w:t></w:r></w:p>"),
        )
        assertEquals(1, blocks.size)
        assertEquals("Hello world", (blocks[0] as Block.Para).text)
    }

    @Test
    fun `whitespace between tags is not document text`() {
        // THE trap in reading this format with a parser that reports all character data. A
        // pretty-printed document has newlines and indentation between every tag, and treating
        // those as content turns a page of text into a page of blank lines with words in it.
        val blocks = OfficeReader.wordBlocks(
            docx(
                """
                <w:p>
                    <w:r>
                        <w:t>Only this</w:t>
                    </w:r>
                </w:p>
                """.trimIndent(),
            ),
        )
        assertEquals(1, blocks.size)
        assertEquals("Only this", (blocks[0] as Block.Para).text)
    }

    @Test
    fun `a style makes a heading, and the level comes from its name`() {
        val blocks = OfficeReader.wordBlocks(
            docx(
                "<w:p><w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr>" +
                    "<w:r><w:t>Chapter</w:t></w:r></w:p>",
            ),
        )
        assertEquals(2, (blocks[0] as Block.Para).level)
    }

    @Test
    fun `bold with no value attribute is still bold`() {
        // How the format actually works, and the case that loses every bold word in a document
        // Word wrote if an absent attribute is read as false.
        val blocks = OfficeReader.wordBlocks(
            docx("<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>strong</w:t></w:r></w:p>"),
        )
        val para = blocks[0] as Block.Para
        assertEquals(1, para.spans.size)
        assertEquals(Mark.BOLD, para.spans[0].style)
        assertEquals(0, para.spans[0].from)
        assertEquals("strong".length, para.spans[0].to)
    }

    @Test
    fun `bold explicitly switched off is not bold`() {
        val blocks = OfficeReader.wordBlocks(
            docx("<w:p><w:r><w:rPr><w:b w:val=\"0\"/></w:rPr><w:t>plain</w:t></w:r></w:p>"),
        )
        assertTrue((blocks[0] as Block.Para).spans.isEmpty())
    }

    @Test
    fun `emphasis applies to its own run and not the next one`() {
        // The off-by-one that matters: a mark's range runs from where its run opened to where
        // it closed, so formatting must not leak into the text that follows it.
        val blocks = OfficeReader.wordBlocks(
            docx(
                "<w:p><w:r><w:rPr><w:i/></w:rPr><w:t>one</w:t></w:r>" +
                    "<w:r><w:t> two</w:t></w:r></w:p>",
            ),
        )
        val para = blocks[0] as Block.Para
        assertEquals("one two", para.text)
        assertEquals(1, para.spans.size)
        assertEquals(0, para.spans[0].from)
        assertEquals(3, para.spans[0].to)
    }

    @Test
    fun `a numbered paragraph is a list item at its level`() {
        val blocks = OfficeReader.wordBlocks(
            docx(
                "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"1\"/></w:numPr></w:pPr>" +
                    "<w:r><w:t>nested</w:t></w:r></w:p>",
            ),
        )
        val item = blocks[0] as Block.Item
        assertEquals("nested", item.text)
        assertEquals(1, item.depth)
    }

    @Test
    fun `a table becomes rows of cells and not loose paragraphs`() {
        val blocks = OfficeReader.wordBlocks(
            docx(
                "<w:tbl>" +
                    "<w:tr><w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc>" +
                    "<w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc></w:tr>" +
                    "<w:tr><w:tc><w:p><w:r><w:t>1</w:t></w:r></w:p></w:tc>" +
                    "<w:tc><w:p><w:r><w:t>2</w:t></w:r></w:p></w:tc></w:tr>" +
                    "</w:tbl>",
            ),
        )
        val table = blocks.filterIsInstance<Block.Table>().single()
        assertEquals(listOf(listOf("A", "B"), listOf("1", "2")), table.rows)
        // And nothing leaked out as paragraphs of its own.
        assertTrue(blocks.none { it is Block.Para })
    }

    @Test
    fun `a picture is counted and said, not silently dropped`() {
        val blocks = OfficeReader.wordBlocks(
            docx("<w:p><w:r><w:drawing/></w:r></w:p><w:p><w:r><w:drawing/></w:r></w:p>"),
        )
        val note = blocks.filterIsInstance<Block.Note>().single()
        assertTrue("the note did not say how many: " + note.text, note.text.contains("2"))
    }

    @Test
    fun `heading levels are read from every spelling producers use`() {
        assertEquals(1, OfficeReader.headingLevel("Heading1"))
        assertEquals(3, OfficeReader.headingLevel("heading 3"))
        assertEquals(1, OfficeReader.headingLevel("Title"))
        assertEquals(2, OfficeReader.headingLevel("Subtitle"))
        assertEquals(0, OfficeReader.headingLevel("BodyText"))
        assertEquals(0, OfficeReader.headingLevel(""))
        // Clamped rather than trusted: there is no Heading 12 to draw.
        assertEquals(6, OfficeReader.headingLevel("Heading12"))
    }

    // ── slides ────────────────────────────────────────────────────────────────────

    private fun pptx(body: String) =
        """<?xml version="1.0"?><p:sld xmlns:p="x" xmlns:a="y"><p:cSld><p:spTree>$body</p:spTree></p:cSld></p:sld>"""

    @Test
    fun `a slide's marked title becomes its heading`() {
        val blocks = OfficeReader.slideBlocks(
            pptx(
                "<p:sp><p:nvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr>" +
                    "<p:txBody><a:p><a:r><a:t>The title</a:t></a:r></a:p></p:txBody></p:sp>" +
                    "<p:sp><p:txBody><a:p><a:r><a:t>Body line</a:t></a:r></a:p></p:txBody></p:sp>",
            ),
        )
        val first = blocks[0] as Block.Para
        assertEquals("The title", first.text)
        assertEquals(2, first.level)
        assertEquals("Body line", (blocks[1] as Block.Para).text)
    }

    @Test
    fun `a slide with no marked title promotes its first line`() {
        val blocks = OfficeReader.slideBlocks(
            pptx("<p:sp><p:txBody><a:p><a:r><a:t>First</a:t></a:r></a:p></p:txBody></p:sp>"),
        )
        assertEquals(2, (blocks[0] as Block.Para).level)
    }

    @Test
    fun `an indented slide paragraph is a bullet`() {
        val blocks = OfficeReader.slideBlocks(
            pptx(
                "<p:sp><p:txBody><a:p><a:r><a:t>Head</a:t></a:r></a:p>" +
                    "<a:p lvl=\"1\"><a:r><a:t>Point</a:t></a:r></a:p></p:txBody></p:sp>",
            ),
        )
        val item = blocks.filterIsInstance<Block.Item>().single()
        assertEquals("Point", item.text)
        assertEquals(0, item.depth)
    }

    @Test
    fun `slides are ordered by number and not by how the zip holds them`() {
        // A zip guarantees no ordering, and PowerPoint writes slide10 before slide2 often
        // enough that this is not theoretical. A deck reading 1, 10, 11, 2 looks like data loss.
        val names = listOf(
            "ppt/slides/slide10.xml",
            "ppt/slides/slide2.xml",
            "ppt/slides/slide1.xml",
            "ppt/slides/slide11.xml",
        )
        assertEquals(
            listOf(
                "ppt/slides/slide1.xml",
                "ppt/slides/slide2.xml",
                "ppt/slides/slide10.xml",
                "ppt/slides/slide11.xml",
            ),
            OfficeReader.slideOrder(names),
        )
    }

    @Test
    fun `a doctype is refused rather than resolved`() {
        // XXE. A document is untrusted input, and a parser that resolves an external entity
        // for one would let a .docx read a file off the phone, or make a network request, just
        // by being opened. The parse must fail rather than succeed with the entity expanded.
        val hostile =
            "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/hosts\">]>" +
                "<w:document xmlns:w=\"x\"><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p>" +
                "</w:body></w:document>"
        val blocks = runCatching { OfficeReader.wordBlocks(hostile) }.getOrDefault(emptyList())
        assertTrue(
            "an external entity was expanded",
            blocks.filterIsInstance<Block.Para>().none { it.text.contains("localhost") },
        )
    }
}

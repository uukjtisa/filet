package dev.niccc2007.filet.docs

import dev.niccc2007.filet.docs.OfficeFixtures.docx
import dev.niccc2007.filet.docs.OfficeFixtures.para
import dev.niccc2007.filet.docs.OfficeFixtures.readDocx
import dev.niccc2007.filet.docs.OfficeFixtures.rels
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A DOCX reads as the document it is, through the real package.
 *
 * Each test here is one way the first reader turned a formatted document into a wall of text:
 * a part it never opened, a property it never read, or a property it read from the wrong place.
 */
class WordFidelityTest {

    private fun paras(doc: Doc) = doc.blocks.filterIsInstance<Block.Para>()
    private fun items(doc: Doc) = doc.blocks.filterIsInstance<Block.Item>()

    // ── headings ──────────────────────────────────────────────────────────────────

    @Test
    fun `a heading is found by its style NAME, not by its id`() {
        // Word writes the id in the document's language and the name in English. A German Word
        // gives "Heading 1" the id `berschrift1`, and a reader that matched ids alone read every
        // heading in it as body text.
        val styles = "<w:style w:type=\"paragraph\" w:styleId=\"berschrift1\"><w:name w:val=\"heading 1\"/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"berschrift2\"><w:name w:val=\"heading 2\"/></w:style>"
        val doc = readDocx(
            docx(
                para("Kapitel", "<w:pStyle w:val=\"berschrift1\"/>") +
                    para("Abschnitt", "<w:pStyle w:val=\"berschrift2\"/>") +
                    para("Text"),
                styles = styles,
            ),
        )
        assertEquals(listOf(1, 2, 0), paras(doc).map { it.level })
    }

    @Test
    fun `a custom style based on a heading, or carrying an outline level, is a heading`() {
        val styles =
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/></w:style>" +
                "<w:style w:type=\"paragraph\" w:styleId=\"Chapter\"><w:name w:val=\"Chapter\"/>" +
                "<w:basedOn w:val=\"Heading1\"/></w:style>" +
                "<w:style w:type=\"paragraph\" w:styleId=\"Part\"><w:name w:val=\"Part\"/>" +
                "<w:pPr><w:outlineLvl w:val=\"2\"/></w:pPr></w:style>"
        val doc = readDocx(
            docx(
                para("One", "<w:pStyle w:val=\"Chapter\"/>") + para("Two", "<w:pStyle w:val=\"Part\"/>"),
                styles = styles,
            ),
        )
        assertEquals(listOf(1, 3), paras(doc).map { it.level })
    }

    @Test
    fun `a short bold line with no heading style is NOT a heading`() {
        // The forbidden shortcut, held in place: a heading is what the file says, never what a
        // line looks like.
        val doc = readDocx(docx(para("Results", rPr = "<w:b/><w:sz w:val=\"36\"/>")))
        assertEquals(0, paras(doc).single().level)
    }

    @Test
    fun `a numbered heading keeps its heading level and its number`() {
        val styles = "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/>" +
            "<w:pPr><w:numPr><w:numId w:val=\"5\"/></w:numPr></w:pPr></w:style>"
        val numbering = "<w:abstractNum w:abstractNumId=\"9\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>" +
            "<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/></w:lvl></w:abstractNum>" +
            "<w:num w:numId=\"5\"><w:abstractNumId w:val=\"9\"/></w:num>"
        val doc = readDocx(
            docx(
                para("Intro", "<w:pStyle w:val=\"Heading1\"/>") + para("Method", "<w:pStyle w:val=\"Heading1\"/>"),
                styles = styles,
                numbering = numbering,
            ),
        )
        assertTrue(items(doc).isEmpty())
        assertEquals(listOf("1. Intro", "2. Method"), paras(doc).map { it.text })
        assertTrue(paras(doc).all { it.level == 1 })
    }

    // ── run formatting ────────────────────────────────────────────────────────────

    @Test
    fun `underline, strike, colour and highlight survive into spans`() {
        val doc = readDocx(
            docx(
                para("u", rPr = "<w:u w:val=\"single\"/>") +
                    para("s", rPr = "<w:strike/>") +
                    para("c", rPr = "<w:color w:val=\"C00000\"/>") +
                    para("h", rPr = "<w:highlight w:val=\"yellow\"/>") +
                    para("none", rPr = "<w:u w:val=\"none\"/><w:color w:val=\"auto\"/>"),
            ),
        )
        val p = paras(doc)
        assertEquals(Mark.UNDERLINE, p[0].spans.single().style)
        assertEquals(Mark.STRIKE, p[1].spans.single().style)
        assertEquals(Mark.COLOR, p[2].spans.single().style)
        assertEquals(0xFFC00000L, p[2].spans.single().argb)
        assertEquals(Mark.HIGHLIGHT, p[3].spans.single().style)
        assertEquals(0xFFFFFF00L, p[3].spans.single().argb)
        // `u none` is an explicit NO, and `auto` is the theme's own colour, not a colour.
        assertTrue(p[4].spans.isEmpty())
    }

    @Test
    fun `a character style supplies the formatting it names`() {
        val styles = "<w:style w:type=\"character\" w:styleId=\"Strong\"><w:name w:val=\"Strong\"/>" +
            "<w:rPr><w:b/></w:rPr></w:style>"
        val doc = readDocx(docx(para("key", rPr = "<w:rStyle w:val=\"Strong\"/>"), styles = styles))
        assertEquals(Mark.BOLD, paras(doc).single().spans.single().style)
    }

    @Test
    fun `a paragraph mark's formatting does not leak into the first run`() {
        // Bug identified here: `w:pPr/w:rPr` formats the pilcrow, not the text. The run flags were
        // set from it and only cleared at the END of a run, so the first run of every paragraph
        // whose mark was bold came out bold.
        val doc = readDocx(
            docx("<w:p><w:pPr><w:rPr><w:b/></w:rPr></w:pPr><w:r><w:t>plain</w:t></w:r></w:p>"),
        )
        assertTrue(paras(doc).single().spans.isEmpty())
    }

    @Test
    fun `a tracked formatting change does not apply the formatting that was removed`() {
        val doc = readDocx(
            docx(
                "<w:p><w:r><w:rPr><w:rPrChange w:id=\"1\" w:author=\"a\"><w:rPr><w:b/></w:rPr>" +
                    "</w:rPrChange></w:rPr><w:t>was bold</w:t></w:r></w:p>",
            ),
        )
        assertTrue(paras(doc).single().spans.isEmpty())
    }

    @Test
    fun `tab stops in the paragraph properties are not text`() {
        // Bug identified here: `w:tab` is a character inside a run and a tab STOP inside
        // `w:pPr/w:tabs`. Both were read as a character, so every paragraph with tab stops opened
        // with stray whitespace.
        val doc = readDocx(
            docx(para("x", "<w:tabs><w:tab w:val=\"left\" w:pos=\"720\"/></w:tabs>")),
        )
        assertEquals("x", paras(doc).single().text)
    }

    @Test
    fun `alignment and indentation are read from the paragraph`() {
        val doc = readDocx(
            docx(para("mid", "<w:jc w:val=\"center\"/>") + para("in", "<w:ind w:left=\"720\"/>")),
        )
        assertEquals(Align.CENTER, paras(doc)[0].align)
        assertEquals(36, paras(doc)[1].indent)
    }

    @Test
    fun `a hyperlink carries where it goes`() {
        val doc = readDocx(
            docx(
                "<w:p><w:hyperlink r:id=\"rId7\"><w:r><w:t>site</w:t></w:r></w:hyperlink></w:p>",
                rels = rels(Triple("rId7", "hyperlink", "https://example.org/")),
            ),
        )
        val link = paras(doc).single().spans.single()
        assertEquals(Mark.LINK, link.style)
        assertEquals("https://example.org/", link.href)
    }

    // ── lists ─────────────────────────────────────────────────────────────────────

    private val twoLists =
        "<w:abstractNum w:abstractNumId=\"0\">" +
            "<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/></w:lvl>" +
            "<w:lvl w:ilvl=\"1\"><w:start w:val=\"1\"/><w:numFmt w:val=\"lowerLetter\"/><w:lvlText w:val=\"%2)\"/></w:lvl>" +
            "</w:abstractNum>" +
            "<w:abstractNum w:abstractNumId=\"1\">" +
            "<w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"\"/></w:lvl>" +
            "<w:lvl w:ilvl=\"1\"><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"o\"/></w:lvl>" +
            "</w:abstractNum>" +
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>" +
            "<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>"

    private fun li(text: String, numId: Int, ilvl: Int) =
        para(text, "<w:numPr><w:ilvl w:val=\"$ilvl\"/><w:numId w:val=\"$numId\"/></w:numPr>")

    @Test
    fun `an ordered list keeps its numbers, nested levels their own format`() {
        // Bug identified here: whether a list is numbered is in numbering.xml, and the reader
        // looked for it in the body, where it never occurs. Every numbered list was bullets.
        val doc = readDocx(
            docx(
                li("one", 1, 0) + li("two", 1, 0) + li("sub a", 1, 1) + li("sub b", 1, 1) + li("three", 1, 0),
                numbering = twoLists,
            ),
        )
        val list = items(doc)
        assertTrue(list.all { it.ordered })
        assertEquals(listOf("1.", "2.", "a)", "b)", "3."), list.map { it.marker })
        assertEquals(listOf(0, 0, 1, 1, 0), list.map { it.depth })
        assertEquals(listOf(1, 2, 1, 2, 3), list.map { it.number })
    }

    @Test
    fun `a bulleted list is unordered and its symbol font glyphs are mapped`() {
        val doc = readDocx(docx(li("dot", 2, 0) + li("ring", 2, 1), numbering = twoLists))
        val list = items(doc)
        assertTrue(list.none { it.ordered })
        assertEquals(listOf("•", "◦"), list.map { it.marker })
        assertEquals(listOf(0, 1), list.map { it.depth })
    }

    @Test
    fun `numId zero switches numbering off`() {
        val doc = readDocx(docx(li("not a list", 0, 0), numbering = twoLists))
        assertTrue(items(doc).isEmpty())
        assertEquals("not a list", paras(doc).single().text)
    }

    @Test
    fun `a start override restarts the count`() {
        val numbering = twoLists + "<w:num w:numId=\"3\"><w:abstractNumId w:val=\"0\"/>" +
            "<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>"
        val doc = readDocx(docx(li("a", 1, 0) + li("b", 1, 0) + li("again", 3, 0), numbering = numbering))
        assertEquals(listOf("1.", "2.", "1."), items(doc).map { it.marker })
    }

    // ── tables ────────────────────────────────────────────────────────────────────

    private fun tc(text: String, tcPr: String = "") =
        "<w:tc>" + (if (tcPr.isEmpty()) "" else "<w:tcPr>$tcPr</w:tcPr>") + para(text) + "</w:tc>"

    @Test
    fun `merged cells carry their span and a continued cell is empty`() {
        val doc = readDocx(
            docx(
                "<w:tbl><w:tblPr><w:tblLook w:firstRow=\"1\"/></w:tblPr>" +
                    "<w:tr>${tc("Wide", "<w:gridSpan w:val=\"2\"/>")}${tc("C")}</w:tr>" +
                    "<w:tr>${tc("tall", "<w:vMerge w:val=\"restart\"/>")}${tc("1")}${tc("2")}</w:tr>" +
                    "<w:tr>${tc("ignored", "<w:vMerge/>")}${tc("3")}${tc("4")}</w:tr>" +
                    "</w:tbl>",
            ),
        )
        val table = doc.blocks.filterIsInstance<Block.Table>().single()
        assertTrue(table.header)
        assertEquals(listOf(listOf("Wide", "C"), listOf("tall", "1", "2"), listOf("", "3", "4")), table.rows)
        assertEquals(listOf(listOf(2, 1), listOf(1, 1, 1), listOf(1, 1, 1)), table.colSpans)
    }

    @Test
    fun `a table nested in a cell does not wipe the outer row`() {
        // Bug identified here: the inner table's `w:tr` reset the OUTER row's cell list, so every
        // cell before a nested table disappeared from its row.
        val inner = "<w:tbl><w:tr>${tc("in1")}${tc("in2")}</w:tr></w:tbl>"
        val doc = readDocx(
            docx("<w:tbl><w:tr>${tc("left")}<w:tc>$inner</w:tc>${tc("right")}</w:tr></w:tbl>"),
        )
        val table = doc.blocks.filterIsInstance<Block.Table>().single()
        assertEquals(1, table.rows.size)
        assertEquals(3, table.rows[0].size)
        assertEquals("left", table.rows[0][0])
        assertTrue(table.rows[0][1].contains("in1") && table.rows[0][1].contains("in2"))
        assertEquals("right", table.rows[0][2])
    }

    // ── pictures ──────────────────────────────────────────────────────────────────

    private fun drawing(rId: String) =
        "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"952500\" cy=\"476250\"/>" +
            "<wp:docPr id=\"1\" name=\"Picture 1\" descr=\"A chart of sales\"/>" +
            "<a:graphic><a:graphicData><pic:pic><pic:blipFill><a:blip r:embed=\"$rId\"/></pic:blipFill>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"

    @Test
    fun `a picture is resolved through the relationships into word media`() {
        val doc = readDocx(
            docx(
                para("before") + drawing("rId4") + para("after"),
                rels = rels(Triple("rId4", "image", "media/image1.png")),
                media = mapOf("word/media/image1.png" to OfficeFixtures.PNG),
            ),
        )
        val image = doc.blocks.filterIsInstance<Block.Image>().single()
        assertArrayEquals(OfficeFixtures.PNG, image.data)
        assertEquals("png", image.format)
        assertEquals("A chart of sales", image.alt)
        assertEquals(2f, image.aspect, 0.001f)
        // In the order it appears, between the paragraphs around it.
        val order = doc.blocks.map { (it as? Block.Para)?.text ?: it::class.java.simpleName }
        assertEquals(listOf("before", "Image", "after"), order)
        assertTrue(doc.blocks.none { it is Block.Note })
    }

    @Test
    fun `a picture whose part is missing is counted, not silently dropped`() {
        val doc = readDocx(docx(drawing("rId4"), rels = rels(Triple("rId4", "image", "media/gone.png"))))
        assertTrue(doc.blocks.none { it is Block.Image })
        assertTrue(doc.blocks.filterIsInstance<Block.Note>().single().text.contains("One picture"))
    }

    @Test
    fun `the fallback half of a compatibility block is not read twice`() {
        val doc = readDocx(
            docx(
                "<w:p><w:r><mc:AlternateContent><mc:Choice Requires=\"wps\"><w:drawing><w:txbxContent>" +
                    para("boxed") + "</w:txbxContent></w:drawing></mc:Choice><mc:Fallback><w:pict><w:txbxContent>" +
                    para("boxed") + "</w:txbxContent></w:pict></mc:Fallback></mc:AlternateContent></w:r></w:p>",
            ),
        )
        assertEquals(listOf("boxed"), paras(doc).map { it.text })
        assertFalse(doc.blocks.any { it is Block.Note })
    }

    @Test
    fun `a page break is drawn as a rule`() {
        val doc = readDocx(docx("<w:p><w:r><w:t>end</w:t><w:br w:type=\"page\"/></w:r></w:p>" + para("next")))
        assertEquals(listOf("Para", "Rule", "Para"), doc.blocks.map { it::class.java.simpleName })
    }
}

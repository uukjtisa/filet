package dev.niccc2007.filet.docs

import dev.niccc2007.filet.docs.OfficeFixtures.aPara
import dev.niccc2007.filet.docs.OfficeFixtures.pptx
import dev.niccc2007.filet.docs.OfficeFixtures.readPptx
import dev.niccc2007.filet.docs.OfficeFixtures.rels
import dev.niccc2007.filet.docs.OfficeFixtures.shape
import dev.niccc2007.filet.docs.OfficeFixtures.slide
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A PPTX reads as a deck: slides in the deck's order, each with its title, body, pictures and notes. */
class SlideFidelityTest {

    private fun titled(title: String, body: String = "") =
        slide(shape(aPara(title), ph = "title") + (if (body.isEmpty()) "" else shape(aPara(body))))

    @Test
    fun `slides follow the deck's own order, not their file names`() {
        // Bug identified here: slides were sorted by file name, which is the order they were
        // CREATED in. A slide moved to the front in PowerPoint keeps its name, so every
        // reordered deck read out of order.
        val doc = readPptx(
            pptx(
                slides = mapOf("slide1.xml" to titled("Created first"), "slide2.xml" to titled("Shown first")),
                order = listOf("slide2.xml", "slide1.xml"),
            ),
        )
        val slides = doc.blocks.filterIsInstance<Block.Slide>()
        assertEquals(listOf("Shown first", "Created first"), slides.map { it.title })
        // Numbered by position, which is the number PowerPoint shows.
        assertEquals(listOf(1, 2), slides.map { it.number })
    }

    @Test
    fun `a slide id is its relationship id, not its own number`() {
        // `p:sldId` holds `id` and `r:id`, both `id` once the prefix goes. Reading the first
        // one returned the slide's number, which no relationship is keyed by.
        val xml = "<p:presentation ${OfficeFixtures.P}><p:sldIdLst>" +
            "<p:sldId id=\"256\" r:id=\"rId7\"/><p:sldId id=\"257\" r:id=\"rId3\"/></p:sldIdLst></p:presentation>"
        assertEquals(listOf("rId7", "rId3"), SlideReader.slideIds(xml))
    }

    @Test
    fun `a title is distinguished from the body`() {
        val doc = readPptx(pptx(slides = mapOf("slide1.xml" to titled("Plan", "Ship it"))))
        val paras = doc.blocks.filterIsInstance<Block.Para>()
        assertEquals("Plan", paras[0].text)
        assertEquals(2, paras[0].level)
        assertEquals(0, paras[1].level)
    }

    @Test
    fun `speaker notes are read from the notes slide and marked as notes`() {
        val notes = "<p:notes ${OfficeFixtures.P}><p:cSld><p:spTree>" +
            shape(aPara("Slide image"), ph = "sldImg") +
            shape(aPara("Say the number slowly."), ph = "body") +
            shape(aPara("7"), ph = "sldNum") +
            "</p:spTree></p:cSld></p:notes>"
        val doc = readPptx(
            pptx(
                slides = mapOf("slide1.xml" to titled("Revenue")),
                slideRels = mapOf("slide1.xml" to rels(Triple("rId2", "notesSlide", "../notesSlides/notesSlide1.xml"))),
                notes = mapOf("notesSlide1.xml" to notes),
            ),
        )
        val note = doc.blocks.filterIsInstance<Block.SlideNotes>().single()
        assertEquals("Say the number slowly.", note.text)
        // After its slide, and never mixed into the slide's own text.
        assertTrue(doc.blocks.indexOf(note) > doc.blocks.indexOfFirst { it is Block.Slide })
        assertTrue(doc.blocks.filterIsInstance<Block.Para>().none { it.text.contains("slowly") })
    }

    @Test
    fun `a picture is resolved through the slide's relationships`() {
        val pic = "<p:pic><p:nvPicPr><p:cNvPr id=\"4\" name=\"Picture 3\" descr=\"Logo\"/></p:nvPicPr>" +
            "<p:blipFill><a:blip r:embed=\"rId3\"/></p:blipFill>" +
            "<p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"200\" cy=\"100\"/></a:xfrm></p:spPr></p:pic>"
        val doc = readPptx(
            pptx(
                slides = mapOf("slide1.xml" to slide(pic)),
                slideRels = mapOf("slide1.xml" to rels(Triple("rId3", "image", "../media/image1.png"))),
                media = mapOf("ppt/media/image1.png" to OfficeFixtures.PNG),
            ),
        )
        val image = doc.blocks.filterIsInstance<Block.Image>().single()
        assertArrayEquals(OfficeFixtures.PNG, image.data)
        assertEquals("Logo", image.alt)
        assertEquals(2f, image.aspect, 0.001f)
    }

    @Test
    fun `shapes read top to bottom, not in the order they were stacked`() {
        // The shape tree is z-order. A caption added last sits last in the tree wherever it is
        // on the slide; its offset says where it actually is.
        val doc = readPptx(
            pptx(
                slides = mapOf(
                    "slide1.xml" to slide(
                        shape(aPara("Title"), ph = "title") +
                            shape(aPara("Lower"), y = 5_000_000) +
                            shape(aPara("Upper"), y = 1_000_000),
                    ),
                ),
            ),
        )
        assertEquals(listOf("Title", "Upper", "Lower"), doc.blocks.filterIsInstance<Block.Para>().map { it.text })
    }

    @Test
    fun `run formatting survives`() {
        val rPr = "<a:rPr lang=\"en-US\" b=\"1\" u=\"sng\" strike=\"sngStrike\">" +
            "<a:solidFill><a:srgbClr val=\"00B050\"/></a:solidFill></a:rPr>"
        val doc = readPptx(pptx(slides = mapOf("slide1.xml" to slide(shape(aPara("x", rPr = rPr))))))
        val styles = doc.blocks.filterIsInstance<Block.Para>().single().spans.map { it.style }.toSet()
        assertEquals(setOf(Mark.BOLD, Mark.UNDERLINE, Mark.STRIKE, Mark.COLOR), styles)
    }

    @Test
    fun `a body placeholder bullets, buNone stops it, buAutoNum numbers it`() {
        val body = aPara("point") +
            aPara("plain", pPr = "<a:pPr><a:buNone/></a:pPr>") +
            aPara("first", pPr = "<a:pPr><a:buAutoNum type=\"arabicPeriod\"/></a:pPr>") +
            aPara("second", pPr = "<a:pPr><a:buAutoNum type=\"arabicPeriod\"/></a:pPr>")
        val doc = readPptx(
            pptx(slides = mapOf("slide1.xml" to slide(shape(aPara("T"), ph = "title") + shape(body, ph = "body")))),
        )
        val items = doc.blocks.filterIsInstance<Block.Item>()
        assertEquals(listOf("point", "first", "second"), items.map { it.text })
        assertFalse(items[0].ordered)
        assertEquals(listOf("1.", "2."), items.drop(1).map { it.marker })
        assertTrue(doc.blocks.filterIsInstance<Block.Para>().any { it.text == "plain" })
    }

    @Test
    fun `a table in a graphic frame is a table with its merges`() {
        val table = "<p:graphicFrame><p:xfrm><a:off x=\"0\" y=\"0\"/></p:xfrm><a:graphic><a:graphicData>" +
            "<a:tbl><a:tblPr firstRow=\"1\"/>" +
            "<a:tr><a:tc gridSpan=\"2\"><a:txBody>${aPara("Both")}</a:txBody></a:tc>" +
            "<a:tc hMerge=\"1\"><a:txBody>${aPara("hidden")}</a:txBody></a:tc></a:tr>" +
            "<a:tr><a:tc><a:txBody>${aPara("1")}</a:txBody></a:tc><a:tc><a:txBody>${aPara("2")}</a:txBody></a:tc></a:tr>" +
            "</a:tbl></a:graphicData></a:graphic></p:graphicFrame>"
        val doc = readPptx(pptx(slides = mapOf("slide1.xml" to slide(table))))
        val t = doc.blocks.filterIsInstance<Block.Table>().single()
        assertTrue(t.header)
        assertEquals(listOf(listOf("Both", ""), listOf("1", "2")), t.rows)
        assertEquals(listOf(listOf(2, 1), listOf(1, 1)), t.colSpans)
        // The cells did not ALSO leak out as loose paragraphs.
        assertTrue(doc.blocks.none { it is Block.Para && (it.text == "1" || it.text == "Both") })
    }

    @Test
    fun `a hidden slide is shown and said to be hidden`() {
        val doc = readPptx(pptx(slides = mapOf("slide1.xml" to slide(shape(aPara("x"), ph = "title"), hidden = true))))
        assertTrue(doc.blocks.filterIsInstance<Block.Slide>().single().hidden)
    }

    @Test
    fun `a deck with no presentation part still reads every slide by number`() {
        val bytes = OfficeFixtures.zip(
            listOf(
                "ppt/slides/slide10.xml" to titled("Ten").toByteArray(),
                "ppt/slides/slide2.xml" to titled("Two").toByteArray(),
            ),
        )
        val doc = readPptx(bytes)
        assertEquals(listOf("Two", "Ten"), doc.blocks.filterIsInstance<Block.Slide>().map { it.title })
    }
}

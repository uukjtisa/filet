package dev.niccc2007.filet.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One README that uses every part of the Markdown the reader draws, read whole. The single-feature
 * tests are in [MarkdownReaderTest]; this one is for the features meeting each other in one file,
 * which is where a stack left open by one block breaks the next.
 */
class EverythingFixtureTest {

    private val doc = MarkdownReader.read(
        requireNotNull(javaClass.getResource("/docs/everything.md")) { "fixture missing" }.readText(),
    )

    private inline fun <reified T : Block> all(): List<T> = doc.blocks.filterIsInstance<T>()

    @Test
    fun `front matter is a card, and the title is the first heading's`() {
        val meta = all<Block.Meta>().single()
        assertEquals("Everything", meta.entries.toMap()["title"])
        assertEquals("Everything", doc.title)
        assertTrue(doc.blocks.first() is Block.Meta)
    }

    @Test
    fun `both alerts are read as alerts, and the plain quote after them is only a quote`() {
        assertEquals(listOf(Frame.NOTE, Frame.WARNING), all<Block.AlertHead>().map { it.frame })
        val plain = doc.blocks.indexOfFirst { it is Block.Para && it.text.startsWith("A plain quote") }
        assertEquals(listOf(Frame.QUOTE), doc.nestOf(plain).frames)
    }

    @Test
    fun `lists keep their depth, their numbers and their boxes`() {
        val items = all<Block.Item>()
        assertEquals(listOf(0, 1, 2, 0), items.filter { !it.ordered && it.task == null }.map { it.depth })
        // The last numbered item is the footnote, which the foot of the page lists as one.
        val numbered = items.filter { it.ordered }
        assertEquals(listOf(1, 2, 3, 8, 9, 10, 1), numbered.map { it.number })
        assertTrue(numbered.last().text.startsWith("The footnote"))
        assertEquals(listOf(true, false), items.mapNotNull { it.task })
    }

    @Test
    fun `the table, the code, the fold and the pictures all arrive`() {
        assertEquals(listOf(Align.START, Align.CENTER, Align.END), all<Block.Table>().single().aligns)
        assertEquals("kotlin", all<Block.Code>().single().language)
        assertEquals("Click to open", all<Block.Fold>().single().text)
        val sources = all<Block.Para>().flatMap { it.pictures }.map { it.src }
        assertTrue(sources.toString(), "docs/logo.png" in sources)
        assertTrue(sources.toString(), "docs/shot.png" in sources)
        assertTrue(sources.toString(), sources.any { it.startsWith("https://img.shields.io/") })
    }

    @Test
    fun `the links inside the page all lead somewhere in it`() {
        for (slug in listOf("tables", "code", "fn-1", "fnref-1")) {
            assertNotNull(slug, DocOutline.find(doc.anchors, slug))
        }
        assertEquals(listOf("Everything", "Lists", "Tables", "Code", "Pictures", "Folds", "Notes"), doc.outline.map { it.text })
        // The centred header block ends with its paragraph: the alerts below it are not centred.
        val note = doc.blocks.indexOfFirst { it is Block.Para && it.text.startsWith("Alerts are quotes") }
        assertEquals(Align.START, (doc.blocks[note] as Block.Para).align)
    }
}

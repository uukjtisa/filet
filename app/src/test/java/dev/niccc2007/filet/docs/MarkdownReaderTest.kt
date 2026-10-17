package dev.niccc2007.filet.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown into the document model.
 *
 * The parsing itself is commonmark-java's problem and is not re-tested here - it is the
 * reference implementation of the specification. What IS tested is the walk from its AST into
 * [Doc], which is this repository's code and where the mistakes would be: a span whose range is
 * off by one, a nested list that loses its depth, a table whose header is dropped, an image
 * that gets fetched.
 */
class MarkdownReaderTest {

    @Test
    fun `headings carry their level`() {
        val doc = MarkdownReader.read("# One\n\n## Two\n\n###### Six")
        val levels = doc.blocks.filterIsInstance<Block.Para>().map { it.level }
        assertEquals(listOf(1, 2, 6), levels)
    }

    @Test
    fun `the first heading becomes the title`() {
        assertEquals("Read me", MarkdownReader.read("# Read me\n\nbody").title)
        // And a document that opens on body text claims no title rather than inventing one.
        assertEquals("", MarkdownReader.read("just text").title)
    }

    @Test
    fun `emphasis marks exactly its own text`() {
        val doc = MarkdownReader.read("plain **bold** plain")
        val para = doc.blocks.filterIsInstance<Block.Para>().single()
        assertEquals("plain bold plain", para.text)
        val span = para.spans.single()
        assertEquals(Mark.BOLD, span.style)
        assertEquals(6, span.from)
        assertEquals(10, span.to)
    }

    @Test
    fun `overlapping emphasis both survive`() {
        // Legal markdown, and the reason spans are ranges rather than a tree: bold starting
        // inside an italic run cannot be nested without splitting one of them.
        val doc = MarkdownReader.read("*italic with **bold** inside*")
        val para = doc.blocks.filterIsInstance<Block.Para>().single()
        assertTrue(para.spans.any { it.style == Mark.ITALIC })
        assertTrue(para.spans.any { it.style == Mark.BOLD })
    }

    @Test
    fun `every span is inside its own text`() {
        // THE property. `AnnotatedString` throws on a range past the end rather than clamping,
        // so one arithmetic slip in this walk is a crash on opening a document - not a word in
        // the wrong weight. Asserted over everything awkward at once.
        val source = """
            # Title with `code`
            Some *emphasis*, some **strong**, a [link](https://example.com), ~~gone~~.
            - item with **bold**
              - nested with `code`
            > a quote with *emphasis*
            | a | b |
            |---|---|
            | 1 | **2** |
        """.trimIndent()
        val doc = MarkdownReader.read(source)
        for (block in doc.blocks) {
            val (text, spans) = when (block) {
                is Block.Para -> block.text to block.spans
                is Block.Item -> block.text to block.spans
                else -> continue
            }
            for (span in spans) {
                assertTrue(
                    "span ${span.from}..${span.to} outside \"$text\"",
                    span.from >= 0 && span.to <= text.length && span.to > span.from,
                )
            }
        }
    }

    @Test
    fun `a nested list keeps its depth`() {
        val doc = MarkdownReader.read("- one\n  - two\n    - three")
        val depths = doc.blocks.filterIsInstance<Block.Item>().map { it.depth }
        assertEquals(listOf(0, 1, 2), depths)
    }

    @Test
    fun `an ordered list numbers from where it starts`() {
        val doc = MarkdownReader.read("3. three\n4. four")
        val items = doc.blocks.filterIsInstance<Block.Item>()
        assertEquals(listOf(3, 4), items.map { it.number })
        assertTrue(items.all { it.ordered })
    }

    @Test
    fun `fenced code keeps its language and is not reflowed`() {
        val doc = MarkdownReader.read("```kotlin\nval x = 1\n```")
        val code = doc.blocks.filterIsInstance<Block.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.text)
    }

    @Test
    fun `a table keeps its header and its rows`() {
        val doc = MarkdownReader.read("| a | b |\n|---|---|\n| 1 | 2 |")
        val table = doc.blocks.filterIsInstance<Block.Table>().single()
        assertTrue(table.header)
        assertEquals(listOf("a", "b"), table.rows.first())
        assertEquals(listOf("1", "2"), table.rows.last())
    }

    @Test
    fun `a quote is marked as one rather than indented`() {
        val doc = MarkdownReader.read("> quoted")
        assertTrue(doc.blocks.filterIsInstance<Block.Para>().single().quote)
    }

    @Test
    fun `an image is named, never fetched`() {
        // A document can point at a remote URL. A reader that makes a network request because
        // a file asked it to is a worse surprise than a missing picture, so the alt text is
        // shown and the destination is not touched.
        val doc = MarkdownReader.read("![a cat](https://example.com/cat.png)")
        val para = doc.blocks.filterIsInstance<Block.Para>().single()
        assertEquals("[a cat]", para.text)
    }

    @Test
    fun `a link keeps its destination as data and not as an action`() {
        val doc = MarkdownReader.read("[text](https://example.com/x)")
        val span = doc.blocks.filterIsInstance<Block.Para>().single().spans.single()
        assertEquals(Mark.LINK, span.style)
        assertEquals("https://example.com/x", span.href)
    }

    @Test
    fun `an empty document is empty rather than a blank paragraph`() {
        assertTrue(MarkdownReader.read("").empty)
        assertTrue(MarkdownReader.read("\n\n   \n").empty)
    }

    @Test
    fun `raw html is shown as text rather than rendered`() {
        // Rendering it would mean an HTML engine, which is the thing walking the AST avoids.
        val doc = MarkdownReader.read("before <b>tag</b> after")
        val para = doc.blocks.filterIsInstance<Block.Para>().single()
        assertTrue(para.text.contains("<b>"))
    }
}

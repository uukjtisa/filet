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
    fun `an image is kept as its address at its place in the text`() {
        // Loading it is the screen's decision, under the reading settings; the reader stays a
        // pure function of the text and only says where the picture goes.
        val doc = MarkdownReader.read("![a cat](https://example.com/cat.png)")
        val para = doc.blocks.filterIsInstance<Block.Para>().single()
        assertEquals("￼", para.text)
        val pic = para.pictures.single()
        assertEquals("https://example.com/cat.png", pic.src)
        assertEquals("a cat", pic.alt)
        assertEquals(0, pic.at)
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
    fun `inline html in the safe list becomes marks, not tags`() {
        val para = MarkdownReader.read("before <b>tag</b> after").blocks.filterIsInstance<Block.Para>().single()
        assertEquals("before tag after", para.text)
        val bold = para.spans.single { it.style == Mark.BOLD }
        assertEquals(7, bold.from)
        assertEquals(10, bold.to)
    }

    // ── containers ──

    private fun indexOf(doc: Doc, text: String): Int =
        doc.blocks.indexOfFirst { (it as? Block.Para)?.text == text || (it as? Block.Item)?.text == text }

    @Test
    fun `an alert has its title line and colours everything inside it`() {
        val doc = MarkdownReader.read("> [!WARNING]\n> Careful here.\n>\n> Second line.")
        assertEquals(Block.AlertHead(Frame.WARNING), doc.blocks.first())
        for (i in doc.blocks.indices) assertEquals(listOf(Frame.WARNING), doc.nestOf(i).frames)
        assertEquals(3, doc.blocks.size)
    }

    @Test
    fun `an alert type GitHub does not draw stays a quote`() {
        val doc = MarkdownReader.read("> [!FANCY]\n> text")
        assertTrue(doc.blocks.none { it is Block.AlertHead })
        assertTrue(doc.nestOf(0).quoted)
    }

    @Test
    fun `a quote inside a quote is two rules`() {
        val doc = MarkdownReader.read("> outer\n>\n> > inner")
        assertEquals(listOf(Frame.QUOTE, Frame.QUOTE), doc.nestOf(indexOf(doc, "inner")).frames)
        assertEquals(listOf(Frame.QUOTE), doc.nestOf(indexOf(doc, "outer")).frames)
    }

    @Test
    fun `what follows an item inside it lines up with its text`() {
        val doc = MarkdownReader.read("- item\n\n  more of it\n\n  ```\n  code\n  ```\n\n  > quoted\n\nafter")
        assertEquals(listOf(Frame.INDENT), doc.nestOf(indexOf(doc, "more of it")).frames)
        assertEquals(listOf(Frame.INDENT), doc.nestOf(doc.blocks.indexOfFirst { it is Block.Code }).frames)
        // Indented and THEN ruled: the order of the frames is the order they are drawn in.
        assertEquals(listOf(Frame.INDENT, Frame.QUOTE), doc.nestOf(indexOf(doc, "quoted")).frames)
        assertEquals(Nest.NONE, doc.nestOf(indexOf(doc, "after")))
    }

    @Test
    fun `a nested list item is indented by its frames and keeps its depth`() {
        val doc = MarkdownReader.read("- one\n  - two")
        val i = indexOf(doc, "two")
        assertEquals(1, (doc.blocks[i] as Block.Item).depth)
        assertEquals(1, doc.nestOf(i).indents)
    }

    @Test
    fun `task items carry their box`() {
        val items = MarkdownReader.read("- [x] done\n- [ ] todo\n- plain").blocks.filterIsInstance<Block.Item>()
        assertEquals(listOf(true, false, null), items.map { it.task })
        assertEquals(listOf("done", "todo", "plain"), items.map { it.text })
    }

    @Test
    fun `a loose list says so and a tight one does not`() {
        val tight = MarkdownReader.read("- a\n- b").blocks.filterIsInstance<Block.Item>()
        val loose = MarkdownReader.read("- a\n\n- b").blocks.filterIsInstance<Block.Item>()
        assertTrue(tight.all { it.tight })
        assertTrue(loose.none { it.tight })
    }

    @Test
    fun `an ordered list knows how wide its widest number is`() {
        val items = MarkdownReader.read("9. nine\n10. ten").blocks.filterIsInstance<Block.Item>()
        // "10." - so "9." can be right-aligned under it.
        assertTrue(items.all { it.markerChars == 3 })
        val paren = MarkdownReader.read("1) one").blocks.filterIsInstance<Block.Item>().single()
        assertEquals("1)", paren.marker)
    }

    // ── tables ──

    @Test
    fun `a table keeps each column's alignment`() {
        val table = MarkdownReader.read("| a | b | c |\n|:--|:-:|--:|\n| 1 | 2 | 3 |").blocks.filterIsInstance<Block.Table>().single()
        assertEquals(listOf(Align.START, Align.CENTER, Align.END), table.aligns)
    }

    @Test
    fun `a table cell keeps its formatting and pictures`() {
        val table = MarkdownReader.read("| h | i |\n|---|---|\n| **x** | ![p](p.png) |").blocks.filterIsInstance<Block.Table>().single()
        assertEquals(Mark.BOLD, table.rich[1][0].spans.single().style)
        assertEquals("p.png", table.rich[1][1].pictures.single().src)
        assertEquals("x", table.rows[1][0])
    }

    // ── footnotes ──

    @Test
    fun `a footnote reference is a raised number that goes to its note and back`() {
        val doc = MarkdownReader.read("Text[^n].\n\n[^n]: The note.")
        val para = doc.blocks.first() as Block.Para
        assertEquals("Text1.", para.text)
        assertTrue(para.spans.any { it.style == Mark.SUPERSCRIPT && it.from == 4 && it.to == 5 })
        assertTrue(para.spans.any { it.style == Mark.LINK && it.href == "#fn-n" })
        val note = doc.blocks.last() as Block.Item
        assertTrue(note.text.startsWith("The note."))
        assertTrue(note.spans.any { it.style == Mark.LINK && it.href == "#fnref-n" })
        assertEquals(doc.blocks.lastIndex, doc.anchors["fn-n"])
        assertEquals(0, doc.anchors["fnref-n"])
        assertTrue(doc.blocks[doc.blocks.lastIndex - 1] is Block.Rule)
    }

    @Test
    fun `footnotes are numbered by first reference and an unused one is not drawn`() {
        val doc = MarkdownReader.read("[^b] then [^a]\n\n[^a]: A\n\n[^b]: B\n\n[^z]: never used")
        val notes = doc.blocks.filterIsInstance<Block.Item>()
        assertEquals(listOf(1, 2), notes.map { it.number })
        assertEquals(listOf("B", "A"), notes.map { it.text.removeSuffix(" ↩") })
        assertTrue(doc.blocks.none { (it as? Block.Item)?.text?.contains("never used") == true })
    }

    // ── front matter, autolinks ──

    @Test
    fun `front matter is a card at the top and names the page`() {
        val doc = MarkdownReader.read("---\ntitle: Hello\nauthor: Someone\n---\n# Body")
        val meta = doc.blocks.first() as Block.Meta
        assertEquals("title" to "Hello", meta.entries.first())
        assertEquals("Hello", doc.title)
        assertEquals(1, (doc.blocks[1] as Block.Para).level)
    }

    @Test
    fun `a bare address becomes a link`() {
        val para = MarkdownReader.read("see https://example.com/x now").blocks.single() as Block.Para
        val link = para.spans.single { it.style == Mark.LINK }
        assertEquals("https://example.com/x", link.href)
        assertEquals("https://example.com/x", para.text.substring(link.from, link.to))
    }

    // ── pictures ──

    @Test
    fun `a badge inside a link remembers where the link goes`() {
        val para = MarkdownReader.read("[![build](https://x.io/b.svg)](https://x.io) [![lic](l.svg)](LICENSE)")
            .blocks.single() as Block.Para
        assertEquals(listOf("https://x.io", "LICENSE"), para.pictures.map { it.href })
        for (p in para.pictures) assertEquals('￼', para.text[p.at])
    }

    @Test
    fun `an html picture keeps its size`() {
        val para = MarkdownReader.read("<p align=\"center\">\n  <img src=\"logo.png\" width=\"200\" alt=\"Logo\">\n</p>")
            .blocks.single() as Block.Para
        assertEquals(Align.CENTER, para.align)
        val pic = para.pictures.single()
        assertEquals(200, pic.width)
        assertEquals("Logo", pic.alt)
        assertEquals(50, (MarkdownReader.read("<img src=\"a.png\" width=\"50%\">").blocks.single() as Block.Para).pictures.single().widthPercent)
    }

    // ── the HTML subset ──

    @Test
    fun `a line break tag breaks the line`() {
        assertEquals("a\nb", (MarkdownReader.read("a<br>b").blocks.single() as Block.Para).text)
    }

    @Test
    fun `script and style are dropped with everything inside them`() {
        val doc = MarkdownReader.read("<script>alert(1)</script>\n\n<style>p{}</style>\n\ntext <script>x()</script>after")
        val all = doc.blocks.filterIsInstance<Block.Para>().joinToString("|") { it.text }
        assertEquals("text after", all)
    }

    @Test
    fun `a centred div centres the markdown inside it and nothing after it`() {
        val doc = MarkdownReader.read("<div align=\"center\">\n\n# Title\n\nbadges\n\n</div>\n\nafter")
        assertEquals(Align.CENTER, (doc.blocks[indexOf(doc, "Title")] as Block.Para).align)
        assertEquals(Align.CENTER, (doc.blocks[indexOf(doc, "badges")] as Block.Para).align)
        assertEquals(Align.START, (doc.blocks[indexOf(doc, "after")] as Block.Para).align)
    }

    @Test
    fun `an html heading is a heading, with its alignment and an anchor`() {
        val doc = MarkdownReader.read("<h1 align=\"center\">My App</h1>\n\nbody")
        val h = doc.blocks.first() as Block.Para
        assertEquals(1, h.level)
        assertEquals(Align.CENTER, h.align)
        assertEquals(0, doc.anchors["my-app"])
        assertEquals(Align.START, (doc.blocks[1] as Block.Para).align)
    }

    @Test
    fun `details is a fold that holds what follows until it closes`() {
        val doc = MarkdownReader.read("<details>\n<summary>More</summary>\n\nhidden text\n\n</details>\n\nvisible")
        val fold = doc.blocks.first() as Block.Fold
        assertEquals("More", fold.text)
        assertEquals(false, fold.open)
        assertEquals(listOf(fold.id), doc.nestOf(indexOf(doc, "hidden text")).folds)
        assertEquals(emptyList<Int>(), doc.nestOf(indexOf(doc, "visible")).folds)
        assertEquals(emptyList<Int>(), doc.nestOf(0).folds)
    }

    @Test
    fun `details open starts open and one without a summary is still a fold`() {
        val open = MarkdownReader.read("<details open>\n<summary>S</summary>\n\nx\n\n</details>").blocks.first() as Block.Fold
        assertTrue(open.open)
        val bare = MarkdownReader.read("<details>\n\ninside\n\n</details>")
        assertEquals("Details", (bare.blocks.first() as Block.Fold).text)
        assertEquals(1, bare.nestOf(1).folds.size)
    }

    @Test
    fun `folds nest`() {
        val doc = MarkdownReader.read(
            "<details><summary>A</summary>\n\n<details><summary>B</summary>\n\ndeep\n\n</details>\n\n</details>",
        )
        val folds = doc.blocks.filterIsInstance<Block.Fold>()
        assertEquals(listOf("A", "B"), folds.map { it.text })
        assertEquals(folds.map { it.id }, doc.nestOf(indexOf(doc, "deep")).folds)
    }

    @Test
    fun `a tag outside the list is dropped and its text kept`() {
        val para = MarkdownReader.read("<span class=\"x\">kept</span> and <font color=red>this</font>").blocks.single() as Block.Para
        assertEquals("kept and this", para.text)
    }

    @Test
    fun `html entities, rules and preformatted text`() {
        val doc = MarkdownReader.read("<p>a &amp; b &#169;</p>\n\n<hr>\n\n<pre>\n  two\n    spaces\n</pre>")
        assertEquals("a & b ©", (doc.blocks[0] as Block.Para).text)
        assertEquals(Block.Rule, doc.blocks[1])
        assertEquals("  two\n    spaces", (doc.blocks[2] as Block.Code).text)
    }

    @Test
    fun `an html anchor names the block that follows it`() {
        val doc = MarkdownReader.read("<a name=\"here\"></a>\n\n## Section\n\ntext")
        assertEquals(indexOf(doc, "Section"), doc.anchors["here"])
    }

    // ── anchors and the outline ──

    @Test
    fun `headings get GitHub's anchors and the outline lists them`() {
        val doc = MarkdownReader.read("# Hello World\n\ntext\n\n## Hello World\n\n### Setup & Use")
        assertEquals(0, doc.anchors["hello-world"])
        assertEquals(2, doc.anchors["hello-world-1"])
        assertEquals(3, doc.anchors["setup--use"])
        assertEquals(listOf(1, 2, 3), doc.outline.map { it.level })
        assertEquals(listOf(0, 2, 3), doc.outline.map { it.index })
    }

    @Test
    fun `every span and picture is inside its own text, over everything at once`() {
        // The property from above, over the new constructs: alerts, footnotes, HTML marks left
        // open, pictures in links, front matter and cells.
        val source = """
            ---
            title: T
            ---
            > [!NOTE]
            > A note with **bold** and a ref[^1].

            <p align="center"><b>open bold <i>and italic</p>

            - [x] task with [![b](b.svg)](https://x)
              continued `code`

            | **a** | <b>b</b> |
            |---|:-:|
            | ![p](p.png) | x[^1] |

            [^1]: Note with *emphasis*.
        """.trimIndent()
        val doc = MarkdownReader.read(source)
        assertEquals(doc.blocks.size, doc.nests.size)
        fun check(text: String, spans: List<Span>, pictures: List<Picture>) {
            for (s in spans) assertTrue("span ${s.from}..${s.to} outside \"$text\"", s.from >= 0 && s.to <= text.length && s.to > s.from)
            for (p in pictures) assertEquals('￼', text[p.at])
        }
        for (block in doc.blocks) {
            when (block) {
                is Block.Para -> check(block.text, block.spans, block.pictures)
                is Block.Item -> check(block.text, block.spans, block.pictures)
                is Block.Fold -> check(block.text, block.spans, emptyList())
                is Block.Table -> block.rows.forEachIndexed { r, row ->
                    row.forEachIndexed { c, t -> check(t, block.rich[r][c].spans, block.rich[r][c].pictures) }
                }
                else -> Unit
            }
        }
        for (i in doc.anchors.values) assertTrue(i in doc.blocks.indices)
    }
}

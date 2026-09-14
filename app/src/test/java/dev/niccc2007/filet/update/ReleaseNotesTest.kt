package dev.niccc2007.filet.update

// PORTED FROM TRAWL with the parser it tests. Same repo, same author, same licence.
//
// The bug being pinned here is not "the parser mis-handles an edge case". It is that the update
// dialog printed raw markup at the user: `<div align="center">`, three full <img> tags, `##` and
// `**`. So the load-bearing assertion in this file is a NEGATIVE one -- that no rendered run
// anywhere in a real release body still contains markup characters. Every structural test below
// exists to stop that negative test passing for the wrong reason (a parser that returns nothing
// also emits no markup).
//
// The input is the actual published body of Trawl v0.1.1, excerpted, not something written to
// suit the parser.

import dev.niccc2007.filet.update.NoteBlock
import dev.niccc2007.filet.update.ReleaseNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {

    /** Verbatim from the v0.1.1 release, trimmed to the constructs it uses. */
    private val realBody =
        """
        <div align="center">

        <img src="https://raw.githubusercontent.com/uukjtisa/trawl/main/docs/screenshots/home.jpg" width="30%" alt="Home">
        <img src="https://raw.githubusercontent.com/uukjtisa/trawl/main/docs/screenshots/float.jpg" width="30%" alt="Floating window">
        <img src="https://raw.githubusercontent.com/uukjtisa/trawl/main/docs/screenshots/history.jpg" width="30%" alt="Links history">

        </div>

        ## What this release is

        v0.1.0 was the first build that ran on a phone. This is the first one shaped by
        **using** it.

        ---

        ## New — a resolver for links that are just files

        Some links are a bare media file on a CDN. Some only pretend to be: a URL ending in
        `.mp4` that actually serves an HTML player page.

        - Follows **HTTP redirects, `<meta refresh>` and JavaScript `location=`** — capped at six hops.
        - **Verifies before saving.** `Content-Type` is evidence.

        > Installing the debug and release builds side by side breaks downloads for whichever
        > one did not create the folder.

        Still early development. [docs/STATUS.md](https://github.com/uukjtisa/trawl/blob/main/docs/STATUS.md) is the honest list.
        """
            .trimIndent()

    private fun NoteBlock.text(): String =
        when (this) {
            is NoteBlock.Heading -> spans.joinToString("") { it.text }
            is NoteBlock.Paragraph -> spans.joinToString("") { it.text }
            is NoteBlock.Bullet -> spans.joinToString("") { it.text }
            is NoteBlock.Quote -> spans.joinToString("") { it.text }
            is NoteBlock.CodeBlock -> text
            is NoteBlock.Table ->
                (header + rows.flatten()).joinToString(" ") { c -> c.joinToString("") { it.text } }
            is NoteBlock.ImageRow -> images.joinToString(" ") { it.alt }
            NoteBlock.Rule -> ""
        }

    // ------------------------------------------------------------------ the regression itself

    @Test
    fun `no rendered text still carries raw markup`() {
        val blocks = ReleaseNotes.parse(realBody)
        // The negative control: the body genuinely contains all of these, so a parser that did
        // nothing would fail here rather than pass.
        listOf("<div", "<img", "src=", "](http", "##").forEach { marker ->
            assertTrue("the source body must actually contain $marker", marker in realBody)
        }
        blocks.forEach { block ->
            val t = block.text()
            listOf("<div", "<img", "src=", "width=", "](http").forEach { marker ->
                assertTrue("block still renders raw markup ($marker): $t", marker !in t)
            }
        }
    }

    @Test
    fun `a parser that returns nothing would not satisfy the test above`() {
        // Guards the negative test: absence of markup must come from parsing, not from silence.
        val blocks = ReleaseNotes.parse(realBody)
        assertTrue("expected a real document, got ${blocks.size} blocks", blocks.size >= 8)
    }

    // ------------------------------------------------------------------------- the constructs

    @Test
    fun `the centred div becomes one image row of three, and the div itself vanishes`() {
        val rows = ReleaseNotes.parse(realBody).filterIsInstance<NoteBlock.ImageRow>()
        assertEquals("one strip, not three stacked images", 1, rows.size)
        assertEquals(3, rows.first().images.size)
        assertTrue(rows.first().images.all { it.url.startsWith("https://raw.githubusercontent.com/") })
        assertEquals("Floating window", rows.first().images[1].alt)
    }

    @Test
    fun `headings keep their level and lose their hashes`() {
        val headings = ReleaseNotes.parse(realBody).filterIsInstance<NoteBlock.Heading>()
        assertEquals(2, headings.size)
        assertTrue(headings.all { it.level == 2 })
        assertEquals("What this release is", headings[0].text())
    }

    @Test
    fun `soft-wrapped lines join into one paragraph`() {
        // The body wraps "shaped by\n**using** it" across two source lines; on a phone that break
        // would land mid-sentence at whatever width the author's editor happened to be.
        val p = ReleaseNotes.parse(realBody).filterIsInstance<NoteBlock.Paragraph>()
        assertTrue(p.any { "shaped by using it" in it.text() })
    }

    @Test
    fun `bold, code and links survive as styles rather than characters`() {
        val blocks = ReleaseNotes.parse(realBody)
        val runs = blocks.flatMap { b ->
            when (b) {
                is NoteBlock.Paragraph -> b.spans
                is NoteBlock.Bullet -> b.spans
                is NoteBlock.Heading -> b.spans
                is NoteBlock.Quote -> b.spans
                else -> emptyList()
            }
        }
        assertTrue("expected a bold run", runs.any { it.bold && it.text == "using" })
        assertTrue("expected a code run", runs.any { it.code && it.text == ".mp4" })
        val link = runs.firstOrNull { it.link != null }
        assertEquals("docs/STATUS.md", link?.text)
        assertEquals("https://github.com/uukjtisa/trawl/blob/main/docs/STATUS.md", link?.link)
    }

    @Test
    fun `an angle-bracketed word inside a code span is text, not a tag`() {
        // "`<meta refresh>`" is the trap: the tag-dropping rule must not reach inside a code span,
        // or the sentence loses the very thing it is describing.
        val runs =
            ReleaseNotes.parse(realBody).filterIsInstance<NoteBlock.Bullet>().flatMap { it.spans }
        assertTrue(
            "the code span lost its angle brackets: ${runs.map { it.text }}",
            runs.any { it.code && it.text == "<meta refresh>" },
        )
    }

    @Test
    fun `the rule and the block quote each become one block`() {
        val blocks = ReleaseNotes.parse(realBody)
        assertEquals(1, blocks.count { it is NoteBlock.Rule })
        val quotes = blocks.filterIsInstance<NoteBlock.Quote>()
        assertEquals(1, quotes.size)
        assertTrue("the quote's two lines should join", "side by side breaks downloads" in quotes[0].text())
        assertTrue("the quote must lose its '>'", !quotes[0].text().startsWith(">"))
    }

    @Test
    fun `bullets are bullets and keep their inline styling`() {
        val bullets = ReleaseNotes.parse(realBody).filterIsInstance<NoteBlock.Bullet>()
        assertEquals(2, bullets.size)
        assertTrue(bullets.all { it.marker == "-" })
        assertTrue(bullets.any { b -> b.spans.any { it.bold && "Verifies before saving" in it.text } })
    }

    // ------------------------------------------------------------------------------- tables

    @Test
    fun `a markdown table becomes a table rather than a row of pipes`() {
        val body =
            """
            | File | For |
            |---|---|
            | `Trawl-universal.apk` | Anything. Pick this if you are not sure. |
            | `Trawl-arm64-v8a.apk` | Almost every phone sold since about 2017. |
            """
                .trimIndent()
        val tables = ReleaseNotes.parse(body).filterIsInstance<NoteBlock.Table>()
        assertEquals(1, tables.size)
        assertEquals(2, tables[0].header.size)
        assertEquals("File", tables[0].header[0].joinToString("") { it.text })
        assertEquals(2, tables[0].rows.size)
        assertTrue(tables[0].rows[0][0].any { it.code })
        assertTrue(ReleaseNotes.parse(body).none { "|" in it.text() })
    }

    // ------------------------------------------------------------------------ malformed input

    @Test
    fun `unpaired delimiters are printed rather than swallowing the rest of the line`() {
        val blocks = ReleaseNotes.parse("A lone * asterisk and an unclosed **bold")
        val text = blocks.joinToString(" ") { it.text() }
        assertTrue("lost the text after a lone delimiter: $text", "asterisk" in text)
        assertTrue("lost the text after an unclosed delimiter: $text", "bold" in text)
    }

    @Test
    fun `an underscore inside a word is not emphasis`() {
        val runs = ReleaseNotes.parse("the some_field_name value").filterIsInstance<NoteBlock.Paragraph>()
            .flatMap { it.spans }
        assertTrue(runs.none { it.italic })
        assertTrue("some_field_name" in runs.joinToString("") { it.text })
    }

    @Test
    fun `an empty body parses to nothing rather than throwing`() {
        assertTrue(ReleaseNotes.parse("").isEmpty())
        assertTrue(ReleaseNotes.parse("   \n\n  \n").isEmpty())
    }

    @Test
    fun `html entities are decoded once`() {
        val text = ReleaseNotes.parse("Tom &amp; Jerry &lt;3 &quot;quoted&quot;")
            .joinToString("") { it.text() }
        assertEquals("Tom & Jerry <3 \"quoted\"", text)
    }
    // ── lazy continuation ──

    @Test
    fun a_bullet_that_soft_wraps_stays_one_bullet() {
        // Found on the phone, not in a test: Filet's own v0.1.0 body wraps its bullets at about
        // 100 columns, and the second line of the "Search." bullet broke out into its own
        // unindented paragraph with no dot. Markdown calls this lazy continuation.
        val blocks = ReleaseNotes.parse(
            """
            - **Search.** The whole device indexed, including inside APKs and
              archives. Searching during the first crawl bends the indexer toward
              your query.
            - **Archives.** Reads zip and tar.
            """.trimIndent()
        )
        val bullets = blocks.filterIsInstance<NoteBlock.Bullet>()
        assertEquals(2, bullets.size)
        assertTrue(
            "the wrapped half must not become a paragraph",
            blocks.none { it is NoteBlock.Paragraph },
        )
        val first = bullets[0].spans.joinToString("") { it.text }
        assertTrue(first.contains("bends the indexer toward your query"))
        // Joined with a space, not a newline: the dialog wraps to its own width.
        assertTrue(first.contains("inside APKs and archives."))
    }

    @Test
    fun a_blank_line_ends_the_bullet_rather_than_swallowing_the_next_paragraph() {
        val blocks = ReleaseNotes.parse(
            """
            - One item.

            A separate paragraph.
            """.trimIndent()
        )
        assertEquals(1, blocks.filterIsInstance<NoteBlock.Bullet>().size)
        assertEquals(1, blocks.filterIsInstance<NoteBlock.Paragraph>().size)
    }

    @Test
    fun a_heading_right_after_a_bullet_ends_it() {
        val blocks = ReleaseNotes.parse("- One item.\n## Next section\nBody.")
        assertEquals(1, blocks.filterIsInstance<NoteBlock.Bullet>().size)
        assertEquals(1, blocks.filterIsInstance<NoteBlock.Heading>().size)
        val bullet = blocks.filterIsInstance<NoteBlock.Bullet>().single()
        assertTrue(
            "the heading must not be folded into the bullet",
            !bullet.spans.joinToString("") { it.text }.contains("Next section"),
        )
    }

    @Test
    fun an_ordered_item_wraps_the_same_way_and_keeps_its_number() {
        val blocks = ReleaseNotes.parse(
            """
            1. First thing, which runs on
               to a second line.
            2. Second thing.
            """.trimIndent()
        )
        val bullets = blocks.filterIsInstance<NoteBlock.Bullet>()
        assertEquals(2, bullets.size)
        assertEquals("1.", bullets[0].marker)
        assertEquals("2.", bullets[1].marker)
        assertTrue(bullets[0].spans.joinToString("") { it.text }.contains("runs on to a second line"))
    }

}

package dev.niccc2007.filet.docs

/**
 * A document, as something that can be drawn.
 *
 * ## Why one model for four formats
 *
 * Markdown, DOCX, PPTX and the text side of HTML are four very different files that are read
 * for the same reason: somebody wants to know what the document SAYS. Rendering each one with
 * its own screen would mean four implementations of the same scrolling, the same typography,
 * the same selection and the same empty state, drifting apart one fix at a time.
 *
 * So each format has a reader that produces this, and there is one screen. A reader is then a
 * pure function from bytes to blocks, which is the part worth testing and the part that is
 * wrong when a document looks odd - and it can be tested without a phone.
 *
 * ## What this is deliberately NOT
 *
 * Not a layout. No page geometry, no columns, no absolute positions, no fonts from the file.
 * Reflowing a document to the width of a phone is the only way it is readable on one, and a
 * model that carried the original geometry would invite trying to reproduce it - which is how
 * a reader turns into a word processor that renders everything slightly wrong.
 *
 * Not editable either. These formats are read here and edited as their source where that
 * means anything: Markdown and HTML open in the code editor, and a DOCX has no source to
 * open. See `DocHandlers`.
 */
data class Doc(
    val blocks: List<Block>,
    /** What to call it in the chrome, when the format carries a title of its own. */
    val title: String = "",
    /** Said out loud at the top when a reader could only get part of the way. See [Note]. */
    val note: String? = null,
) {
    val empty: Boolean get() = blocks.isEmpty()
}

/** One thing in a document, in the order it appears. */
sealed interface Block {

    /**
     * A run of text at one level.
     *
     * @param level 0 for body text, 1 to 6 for a heading.
     * @param spans the inline formatting, as offsets into [text] - see [Span].
     */
    data class Para(
        val text: String,
        val level: Int = 0,
        val spans: List<Span> = emptyList(),
        val quote: Boolean = false,
    ) : Block

    /** A list item, flattened: the depth is carried rather than nested. */
    data class Item(
        val text: String,
        val depth: Int = 0,
        val ordered: Boolean = false,
        val number: Int = 0,
        val spans: List<Span> = emptyList(),
    ) : Block

    /** Preformatted text, drawn monospaced and never reflowed. */
    data class Code(val text: String, val language: String = "") : Block

    /**
     * A table, as rows of cells.
     *
     * Rows rather than a grid with a column count: a malformed table with a short row is
     * common and must draw rather than throw, and a ragged list of rows is the shape that
     * cannot be inconsistent with itself.
     */
    data class Table(val rows: List<List<String>>, val header: Boolean = true) : Block

    /** A horizontal rule. */
    data object Rule : Block

    /**
     * A slide boundary, for a presentation.
     *
     * A presentation is a document with hard breaks in it, which is all a phone can usefully
     * do with one - see `PptxReader`. The number is the slide's own, so a reader can say
     * "slide 7" and mean the same thing the file does.
     */
    data class Slide(val number: Int, val title: String = "") : Block

    /** Something the reader found and cannot draw, named rather than silently dropped. */
    data class Note(val text: String) : Block
}

/**
 * Inline formatting over a range of a paragraph's text.
 *
 * Offsets rather than nested spans, because that is what Compose's `AnnotatedString` wants and
 * because overlapping emphasis - bold starting inside an italic run - is legal in every one of
 * these formats and cannot be represented by a tree without splitting it anyway.
 */
data class Span(
    val from: Int,
    val to: Int,
    val style: Mark,
    /** Where a [Mark.LINK] goes. Empty for every other mark. */
    val href: String = "",
) {
    val valid: Boolean get() = to > from && from >= 0
}

enum class Mark { BOLD, ITALIC, CODE, STRIKE, LINK }

/**
 * Build a paragraph's spans while appending its text, which is what every reader needs.
 *
 * The readers all walk a tree and emit text as they go, so a span's end offset is not known
 * when its start is. A builder keeps that bookkeeping in one tested place rather than in three
 * readers, each with its own off-by-one.
 */
class SpanBuilder {
    private val sb = StringBuilder()
    private val out = ArrayList<Span>()

    val length: Int get() = sb.length

    fun append(text: String): SpanBuilder {
        sb.append(text)
        return this
    }

    /** Mark everything appended between [start] and now. */
    fun mark(start: Int, style: Mark, href: String = "") {
        val span = Span(start, sb.length, style, href)
        // Dropped rather than stored: an empty span is a mark over nothing, which happens
        // with `**` next to `**` in real markdown, and a zero-width annotation makes
        // AnnotatedString throw on some versions rather than draw nothing.
        if (span.valid) out.add(span)
    }

    fun text(): String = sb.toString()

    fun spans(): List<Span> = out.toList()

    fun isBlank(): Boolean = sb.isBlank()
}

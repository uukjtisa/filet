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
 * Not editable either. The model is lossy by design, so writing a file back FROM it would
 * destroy whatever it does not carry. Markdown and HTML are edited as their source in the code
 * editor; DOCX and PPTX text is edited in place inside the original archive - see
 * `OfficeTextEdit` - and never regenerated from here.
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
        /** How the paragraph sits across the width. Read from the file, never guessed. */
        val align: Align = Align.START,
        /** Indent from the start edge, in points, as the file declares it. 0 for none. */
        val indent: Int = 0,
    ) : Block

    /** A list item, flattened: the depth is carried rather than nested. */
    data class Item(
        val text: String,
        val depth: Int = 0,
        val ordered: Boolean = false,
        val number: Int = 0,
        val spans: List<Span> = emptyList(),
        /**
         * The marker exactly as the file defines it - `1.`, `a)`, `iv.`, `2.3.` or a bullet
         * glyph. Empty when the reader had no definition to work from, in which case the screen
         * falls back to [number] or a plain bullet.
         */
        val marker: String = "",
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
    data class Table(
        val rows: List<List<String>>,
        val header: Boolean = true,
        /**
         * How many grid columns each cell covers, parallel to [rows]. Empty means every cell is
         * one column wide, which is what every reader produced before merges were read, and a
         * row shorter than its cells means the same for the cells it does not cover.
         */
        val colSpans: List<List<Int>> = emptyList(),
    ) : Block

    /** A horizontal rule. */
    data object Rule : Block

    /**
     * A slide boundary, for a presentation.
     *
     * A presentation is a document with hard breaks in it, which is all a phone can usefully
     * do with one - see `PptxReader`. The number is the slide's own, so a reader can say
     * "slide 7" and mean the same thing the file does.
     */
    data class Slide(
        val number: Int,
        val title: String = "",
        /** Skipped when the deck is presented. Still shown here, and said. */
        val hidden: Boolean = false,
    ) : Block

    /**
     * A picture the document carries, as its encoded bytes.
     *
     * Bytes rather than a bitmap so the reader stays a pure function that runs off the device;
     * decoding is the screen's job and happens off the main thread at the width it is drawn at.
     *
     * @param format the file extension of the part it came from, so a picture in a format the
     *   platform cannot decode (EMF, WMF) can be named rather than shown as an empty box.
     * @param emuWidth the size the document places it at, in EMU (914400 to the inch). 0 when
     *   the file does not say.
     */
    class Image(
        val data: ByteArray,
        val alt: String = "",
        val format: String = "",
        val emuWidth: Long = 0,
        val emuHeight: Long = 0,
    ) : Block {
        /** Width over height as placed, or 0 when unknown. */
        val aspect: Float
            get() = if (emuWidth > 0 && emuHeight > 0) emuWidth.toFloat() / emuHeight else 0f
    }

    /** A slide's speaker notes. Drawn apart from the slide, so they never read as its body. */
    data class SlideNotes(val text: String) : Block

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
    /** The colour of a [Mark.COLOR] or [Mark.HIGHLIGHT], as 0xAARRGGBB. 0 for every other mark. */
    val argb: Long = 0L,
    /** For [Mark.SIZE]: the run's size over the body size. 0 for every other mark. */
    val scale: Float = 0f,
) {
    val valid: Boolean get() = to > from && from >= 0
}

enum class Mark {
    BOLD, ITALIC, CODE, STRIKE, LINK,

    UNDERLINE,

    /** Text colour, in [Span.argb]. The screen drops it where it would not read on its ground. */
    COLOR,

    /** A highlighter behind the text, in [Span.argb]. */
    HIGHLIGHT,

    /** A size relative to the document's body text, in [Span.scale]. */
    SIZE,

    SUPERSCRIPT,
    SUBSCRIPT,
}

/** Paragraph alignment, as declared by the file. */
enum class Align { START, CENTER, END, JUSTIFY }

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
    fun mark(start: Int, style: Mark, href: String = "", argb: Long = 0L, scale: Float = 0f) {
        val span = Span(start, sb.length, style, href, argb, scale)
        // Dropped rather than stored: an empty span is a mark over nothing, which happens
        // with `**` next to `**` in real markdown, and a zero-width annotation makes
        // AnnotatedString throw on some versions rather than draw nothing.
        if (span.valid) out.add(span)
    }

    fun text(): String = sb.toString()

    fun spans(): List<Span> = out.toList()

    fun isBlank(): Boolean = sb.isBlank()
}

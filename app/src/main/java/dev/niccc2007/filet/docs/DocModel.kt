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
    /**
     * What each block sits inside, parallel to [blocks]. Empty for a reader with no nesting,
     * which is every reader but Markdown's - see [nestOf].
     */
    val nests: List<Nest> = emptyList(),
    /** Where each `#name` in a link lands, as an index into [blocks]. See `DocOutline`. */
    val anchors: Map<String, Int> = emptyMap(),
    /** The headings in order, for the Contents sheet. */
    val outline: List<Heading> = emptyList(),
) {
    val empty: Boolean get() = blocks.isEmpty()

    /** The nesting of block [i], and none for a reader that never set any. */
    fun nestOf(i: Int): Nest = nests.getOrNull(i) ?: Nest.NONE
}

/** One heading, as the Contents sheet lists it. [index] is its block, [slug] its anchor. */
data class Heading(val level: Int, val text: String, val index: Int, val slug: String)

/**
 * What a block sits inside, outermost first.
 *
 * ## Why a block carries this rather than the model being a tree
 *
 * The screen is a flat lazy list, because a long document has to compose only what is on screen,
 * and a tree would be flattened again to draw it. So each block says what it is inside, and the
 * screen draws those containers a block-high slice at a time: a quote's rule down the edge, an
 * alert's coloured one, a list item's indent. Slices with the same frames meet edge to edge and
 * read as one container, which is the same trick a slide card already plays.
 *
 * Kept in order because the order is what is drawn: a quote inside a list item is indented and
 * THEN ruled, and a list inside a quote is the other way round.
 */
data class Nest(
    val frames: List<Frame> = emptyList(),
    /** The folds this block is inside, by [Block.Fold.id]. Hidden while any of them is closed. */
    val folds: List<Int> = emptyList(),
) {
    /** How many list levels deep this is - the indent its own frames already draw. */
    val indents: Int get() = frames.count { it == Frame.INDENT }

    /** The alert this block is part of, if any: the innermost one. */
    val alert: Frame? get() = frames.lastOrNull { it.alert }

    val quoted: Boolean get() = frames.any { it == Frame.QUOTE }

    companion object {
        val NONE = Nest()
    }
}

/** One container a block can sit inside. See [Nest]. */
enum class Frame {
    /** The space a list item's own text starts at, for what follows it inside the item. */
    INDENT,
    QUOTE,
    NOTE, TIP, IMPORTANT, WARNING, CAUTION,
    ;

    /** One of GitHub's five alerts, as opposed to an indent or a plain quote. */
    val alert: Boolean get() = this != INDENT && this != QUOTE

    companion object {
        /** The alert a `> [!TYPE]` names, or null for a type GitHub does not draw. */
        fun alertOf(type: String): Frame? = when (type.uppercase()) {
            "NOTE" -> NOTE
            "TIP" -> TIP
            "IMPORTANT" -> IMPORTANT
            "WARNING" -> WARNING
            "CAUTION" -> CAUTION
            else -> null
        }
    }
}

/**
 * A picture placed in a run of text.
 *
 * The text holds U+FFFC at [at], which is where the screen puts the picture once it has one -
 * so a row of badges flows and wraps like the words around it, and a picture that has not
 * loaded yet is a small gap rather than a block-sized hole.
 *
 * @param src as the file wrote it; resolving it is `DocRefs`'s job, at the screen.
 * @param width the size an HTML `<img>` asked for, in CSS pixels. 0 when it did not say.
 * @param widthPercent a `width="50%"`, as a percentage of the column. 0 when it did not say.
 * @param href where tapping it goes, when it sits inside a link.
 */
data class Picture(
    val at: Int,
    val src: String,
    val alt: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val widthPercent: Int = 0,
    val href: String = "",
)

/** A table cell's formatting and pictures; its text is in [Block.Table.rows]. */
data class Rich(val spans: List<Span> = emptyList(), val pictures: List<Picture> = emptyList())

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
        /** Pictures in the run, each at a U+FFFC in [text]. */
        val pictures: List<Picture> = emptyList(),
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
        /** A task list's box: checked, unchecked, or null for an ordinary item. Display only. */
        val task: Boolean? = null,
        /** False for a loose list, whose items are spaced like paragraphs. */
        val tight: Boolean = true,
        /**
         * How many characters the widest marker in this item's list has, so an ordered list's
         * numbers can be right-aligned in one column. 0 when the reader did not work it out.
         */
        val markerChars: Int = 0,
        val pictures: List<Picture> = emptyList(),
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
        /** Each cell's formatting, parallel to [rows]. Empty for a reader with plain cells. */
        val rich: List<List<Rich>> = emptyList(),
        /** How each column's text sits, by column. Empty, or short, means [Align.START]. */
        val aligns: List<Align> = emptyList(),
    ) : Block

    /** A horizontal rule. */
    data object Rule : Block

    /** The title line of a GitHub alert - its icon and name - for [frame], which is an alert. */
    data class AlertHead(val frame: Frame) : Block

    /**
     * The summary line of a fold (`<details>`), which opens and closes the blocks inside it.
     *
     * @param id what the blocks inside name in [Nest.folds].
     * @param open whether it starts open, as `<details open>` asks.
     */
    data class Fold(
        val id: Int,
        val text: String,
        val spans: List<Span> = emptyList(),
        val open: Boolean = false,
    ) : Block

    /**
     * A document's front matter - the YAML block at the top of a page - as its keys and values.
     *
     * Drawn as a card rather than as the text it is, because it is data ABOUT the page and
     * reading it as the page's first paragraph is how it looks in an editor, not on GitHub.
     */
    data class Meta(val entries: List<Pair<String, String>>) : Block

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

    /** The last character appended, or null when nothing has been. */
    val last: Char? get() = if (sb.isEmpty()) null else sb[sb.length - 1]

    /**
     * Drop trailing whitespace, and pull in every mark that ran into it.
     *
     * For text that arrives with its whitespace collapsed but not trimmed - HTML - where trimming
     * the finished string instead would leave a mark ending past the text, and `annotate` drops
     * such a mark whole rather than clamping it.
     */
    fun trimEnd() {
        var end = sb.length
        while (end > 0 && sb[end - 1].isWhitespace() && sb[end - 1] != ' ') end--
        if (end == sb.length) return
        sb.setLength(end)
        val kept = out.map { if (it.to > end) it.copy(to = end) else it }.filter { it.valid }
        out.clear()
        out.addAll(kept)
    }

    fun text(): String = sb.toString()

    fun spans(): List<Span> = out.toList()

    fun isBlank(): Boolean = sb.isBlank()
}

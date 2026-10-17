package dev.niccc2007.filet.docs

import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * DOCX and PPTX, read without a dependency.
 *
 * ## Why this is not Apache POI
 *
 * POI is the obvious answer and it is the wrong one on Android. It is around twelve megabytes
 * of jars, it reaches for `javax.xml` classes Android does not ship, it needs desugaring to get
 * past Java 8 APIs, and the community forks that paper over all of that are years stale. For an
 * app whose entire release is twenty-eight megabytes, adding half again to read two formats is
 * not a trade worth making.
 *
 * And it is not necessary, because of what these files are. A DOCX is a ZIP holding
 * `word/document.xml`; a PPTX is a ZIP holding `ppt/slides/slide1.xml` and friends. Filet reads
 * ZIPs already - it is a file manager - and both Android and the JVM ship a SAX parser. So the
 * whole reader is a stream walk and a tag match: a few hundred lines, no dependency, no NDK and
 * no size cost.
 *
 * ## Why SAX and not Android's pull parser
 *
 * `XmlPullParser` is the idiomatic Android choice and it cannot be unit-tested: the class in the
 * unit-test android.jar is a stub that throws. A format reader is the single thing here most
 * likely to be subtly wrong - a tag nested one level deeper than expected, a producer that
 * writes `w:b` with no value - and a reader that cannot be tested without a phone is a reader
 * whose bugs are found by the person reading the document. `javax.xml.parsers.SAXParserFactory`
 * is real on both, so every one of these cases is a test.
 *
 * ## What it reads, and what it does not pretend to
 *
 * Paragraphs, headings, bold, italic, strikethrough, lists, tables, and slide text in slide
 * order. **Not** page layout, columns, floating shapes, charts, embedded images, footnotes,
 * revision marks or content controls. That is a line rather than a to-do: reflowing to a
 * phone's width is the only way a document is readable on one, so the original geometry is not
 * wanted even where it is present - and a reader that half-reproduces a layout reads as broken,
 * where one that plainly reflows reads as a reader.
 *
 * Anything found and not drawn is counted and said at the top, because a document that is
 * quietly missing a third of itself is the one failure mode that must not be silent.
 */
object OfficeReader {

    /** What a DOCX keeps its text in. */
    private const val WORD_BODY = "word/document.xml"

    /** And where a PPTX keeps its slides. Numbered, and NOT in zip order - see [slides]. */
    private const val SLIDE_PREFIX = "ppt/slides/slide"

    /**
     * Read whichever of the two this is.
     *
     * @param open a fresh stream over the file. A function rather than a stream, because a zip
     *   walk is one pass and a retry needs a second one - handing this an already-open stream
     *   makes the caller responsible for a detail it cannot know about.
     */
    fun read(open: () -> InputStream, presentation: Boolean): Doc = runCatching {
        if (presentation) slides(open()) else document(open())
    }.getOrElse { e ->
        Doc(
            emptyList(),
            note = "This file could not be read: " + (e.message ?: e::class.java.simpleName),
        )
    }

    // ── Word ──────────────────────────────────────────────────────────────────────

    private fun document(stream: InputStream): Doc {
        val xml = entries(stream) { it == WORD_BODY }[WORD_BODY]
            ?: return Doc(emptyList(), note = "No document text was found inside this file.")
        return Doc(wordBlocks(xml))
    }

    /**
     * `word/document.xml` into blocks.
     *
     * The shape that matters: `w:p` is a paragraph, `w:r` a run of identically formatted text
     * inside it, `w:t` the text of a run, `w:pPr/w:pStyle` the paragraph's style, and
     * `w:rPr/w:b` and `w:i` a run's bold and italic flags. Everything else in there is layout.
     */
    fun wordBlocks(xml: String): List<Block> {
        val handler = WordHandler()
        parse(xml, handler)
        return handler.finish()
    }

    private class WordHandler : DefaultHandler() {
        private val out = ArrayList<Block>()
        private var b = SpanBuilder()
        private var style = ""
        private var listLevel = -1
        private var numbered = false
        private var bold = false
        private var italic = false
        private var strike = false
        private var runStart = 0
        private var inText = false
        private var skipped = 0

        // A table is rows of cells of paragraphs. Cell text is all a phone can use, so the
        // paragraphs inside a cell collect into it instead of becoming blocks of their own.
        private var inTable = 0
        private var rows = ArrayList<List<String>>()
        private var cells = ArrayList<String>()
        private var cell = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
            when (local(qName, localName)) {
                "p" -> if (inTable == 0) {
                    b = SpanBuilder()
                    style = ""
                    listLevel = -1
                    numbered = false
                }
                "pStyle" -> style = attr(a, "val") ?: ""
                // A numbered or bulleted paragraph carries numPr with an ilvl. WHICH symbol is
                // defined in numbering.xml and is deliberately not chased: a bullet is a bullet
                // at this size, and following the definition would mean a second zip entry and
                // an id map, for a glyph.
                "numPr" -> if (listLevel < 0) listLevel = 0
                "ilvl" -> listLevel = (attr(a, "val")?.toIntOrNull() ?: 0).coerceIn(0, 8)
                "numFmt" -> numbered = attr(a, "val")?.startsWith("decimal") == true
                // `w:val` absent means ON - that is how the format works, and reading an
                // absent attribute as false loses the bold in every document Word writes.
                "b" -> bold = attr(a, "val").let { it == null || it == "1" || it == "true" }
                "i" -> italic = attr(a, "val").let { it == null || it == "1" || it == "true" }
                "strike" -> strike = attr(a, "val").let { it == null || it == "1" || it == "true" }
                "r" -> runStart = b.length
                "t" -> inText = true
                "tbl" -> {
                    flush()
                    inTable++
                    if (inTable == 1) rows = ArrayList()
                }
                "tr" -> if (inTable > 0) cells = ArrayList()
                "tc" -> if (inTable > 0) cell = StringBuilder()
                "br", "cr" -> b.append("\n")
                "tab" -> b.append("\t")
                // Named rather than drawn. A count at the top is honest; silence is not.
                "drawing", "pict", "object", "chart" -> skipped++
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            // ONLY inside w:t. Every other element's character data is the whitespace a
            // pretty-printed document has between its tags, and appending it turns a document
            // into a wall of blank lines.
            if (!inText || ch == null) return
            b.append(String(ch, start, length))
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (local(qName, localName)) {
                "t" -> inText = false
                "r" -> {
                    if (bold) b.mark(runStart, Mark.BOLD)
                    if (italic) b.mark(runStart, Mark.ITALIC)
                    if (strike) b.mark(runStart, Mark.STRIKE)
                    bold = false
                    italic = false
                    strike = false
                }
                "p" -> flush()
                "tc" -> if (inTable > 0) cells.add(cell.toString().trim())
                "tr" -> if (inTable > 0 && cells.isNotEmpty()) rows.add(cells.toList())
                "tbl" -> {
                    inTable--
                    if (inTable == 0 && rows.isNotEmpty()) out.add(Block.Table(rows.toList()))
                }
            }
        }

        private fun flush() {
            if (inTable > 0) {
                if (!b.isBlank()) {
                    if (cell.isNotEmpty()) cell.append(' ')
                    cell.append(b.text())
                }
            } else if (!b.isBlank()) {
                val heading = headingLevel(style)
                when {
                    listLevel >= 0 -> out.add(
                        Block.Item(b.text(), depth = listLevel, ordered = numbered, spans = b.spans()),
                    )
                    heading > 0 -> out.add(Block.Para(b.text(), level = heading, spans = b.spans()))
                    style.replace(" ", "").equals("quote", true) ||
                        style.replace(" ", "").equals("intensequote", true) ->
                        out.add(Block.Para(b.text(), spans = b.spans(), quote = true))
                    else -> out.add(Block.Para(b.text(), spans = b.spans()))
                }
            }
            b = SpanBuilder()
            style = ""
            listLevel = -1
            numbered = false
        }

        fun finish(): List<Block> {
            flush()
            if (skipped > 0) {
                out.add(
                    Block.Note(
                        if (skipped == 1) {
                            "One picture or chart in this document is not shown."
                        } else {
                            "$skipped pictures or charts in this document are not shown."
                        },
                    ),
                )
            }
            return out
        }
    }

    /** `Heading3`, `heading 3` and `Title` all mean something. Anything else is body text. */
    fun headingLevel(style: String): Int {
        val lower = style.lowercase().replace(" ", "")
        if (!lower.startsWith("heading")) {
            // Title and Subtitle are headings in every way that matters to a reader.
            if (lower == "title") return 1
            if (lower == "subtitle") return 2
            return 0
        }
        return lower.removePrefix("heading").toIntOrNull()?.coerceIn(1, 6) ?: 0
    }

    // ── PowerPoint ────────────────────────────────────────────────────────────────

    private fun slides(stream: InputStream): Doc {
        val found = entries(stream) { it.startsWith(SLIDE_PREFIX) && it.endsWith(".xml") }
        if (found.isEmpty()) {
            return Doc(emptyList(), note = "No slides were found inside this file.")
        }
        val out = ArrayList<Block>()
        for (name in slideOrder(found.keys)) {
            val number = slideNumber(name) ?: (out.count { it is Block.Slide } + 1)
            val blocks = slideBlocks(found.getValue(name))
            out.add(Block.Slide(number, (blocks.firstOrNull() as? Block.Para)?.text.orEmpty()))
            out.addAll(blocks)
        }
        return Doc(out)
    }

    /**
     * Slide entries in the order a human would read them.
     *
     * BY NUMBER, not by zip order. A zip makes no ordering guarantee at all, and PowerPoint
     * writes `slide10.xml` before `slide2.xml` often enough that this is not theoretical - a
     * deck that reads 1, 10, 11, 2 is the kind of wrong that looks like data loss.
     */
    fun slideOrder(names: Collection<String>): List<String> =
        names.sortedBy { slideNumber(it) ?: Int.MAX_VALUE }

    private fun slideNumber(name: String): Int? =
        name.removePrefix(SLIDE_PREFIX).removeSuffix(".xml").toIntOrNull()

    /**
     * One slide's XML into blocks.
     *
     * `a:p` is a paragraph inside a shape's text body and `a:t` is its text, which is the whole
     * of what a phone can use. PowerPoint records which placeholder is the title in
     * `p:ph type="title"`, and that is read where it is present; where it is not, a slide's
     * first line is its heading, which is true of nearly every deck.
     */
    fun slideBlocks(xml: String): List<Block> {
        val handler = SlideHandler()
        parse(xml, handler)
        return handler.finish()
    }

    private class SlideHandler : DefaultHandler() {
        private val out = ArrayList<Block>()
        private var b = SpanBuilder()
        private var runStart = 0
        private var bold = false
        private var italic = false
        private var isTitle = false
        private var sawTitle = false
        private var level = 0
        private var inText = false

        override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
            when (local(qName, localName)) {
                "ph" -> {
                    val type = attr(a, "type") ?: ""
                    if (type == "title" || type == "ctrTitle") isTitle = true
                }
                "p" -> {
                    b = SpanBuilder()
                    level = attr(a, "lvl")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                }
                "pPr" -> level = attr(a, "lvl")?.toIntOrNull()?.coerceIn(0, 8) ?: level
                "r" -> runStart = b.length
                "rPr" -> {
                    bold = attr(a, "b") == "1"
                    italic = attr(a, "i") == "1"
                }
                "br" -> b.append("\n")
                "t" -> inText = true
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (!inText || ch == null) return
            b.append(String(ch, start, length))
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (local(qName, localName)) {
                "t" -> inText = false
                "r" -> {
                    if (bold) b.mark(runStart, Mark.BOLD)
                    if (italic) b.mark(runStart, Mark.ITALIC)
                    bold = false
                    italic = false
                }
                "p" -> {
                    if (!b.isBlank()) {
                        if (isTitle && !sawTitle) {
                            sawTitle = true
                            out.add(0, Block.Para(b.text(), level = 2, spans = b.spans()))
                        } else if (level > 0) {
                            out.add(Block.Item(b.text(), depth = level - 1, spans = b.spans()))
                        } else {
                            out.add(Block.Para(b.text(), spans = b.spans()))
                        }
                    }
                    b = SpanBuilder()
                    level = 0
                }
                "sp" -> isTitle = false
            }
        }

        fun finish(): List<Block> {
            if (!sawTitle) {
                val first = out.indexOfFirst { it is Block.Para }
                if (first >= 0) out[first] = (out[first] as Block.Para).copy(level = 2)
            }
            return out
        }
    }

    // ── the zip, and the XML ──────────────────────────────────────────────────────

    /**
     * Read the entries whose names match, as strings.
     *
     * One pass over a `ZipInputStream` rather than a random-access read of the central
     * directory: the entries wanted are a handful of named paths, they are near the front, and
     * a stream means a 200 MB presentation does not have to fit anywhere.
     */
    private fun entries(stream: InputStream, want: (String) -> Boolean): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val next = zip.nextEntry ?: break
                if (next.isDirectory) continue
                if (!want(next.name)) continue
                // Capped here rather than in the caller, where it would be a comment: a
                // malformed or hostile file can declare an entry far larger than anything a
                // reader should hold, and this is read into a String.
                out[next.name] = zip.boundedText(MAX_ENTRY_BYTES)
            }
        }
        return out
    }

    /** Sixteen megabytes of XML, which is a document of several hundred pages. */
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024

    private fun InputStream.boundedText(limit: Int): String {
        val buffer = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < limit) {
            val read = read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, minOf(read, limit - out.size()))
        }
        return out.toString("UTF-8")
    }

    private fun parse(xml: String, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        // Namespaces OFF on purpose. These files come from Word, LibreOffice and Google Docs
        // and their prefixes are not stable between them; a namespace-aware parse then fails
        // on a document a person can plainly read. Matching the local name is what every
        // lightweight reader of these formats does.
        factory.isNamespaceAware = false
        // External entities are refused. A document is untrusted input, and an XML parser that
        // resolves entities for one is the XXE hole - it would let a .docx read a file off the
        // phone or make a network request by being opened.
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        factory.newSAXParser().parse(xml.byteInputStream(Charsets.UTF_8), handler)
    }

    /**
     * `w:pStyle` to `pStyle`.
     *
     * With namespaces off SAX reports the whole `w:pStyle` as the qualified name and leaves the
     * local name empty, so both are consulted and the prefix is stripped.
     */
    private fun local(qName: String?, localName: String?): String {
        val n = qName?.takeIf { it.isNotEmpty() } ?: localName ?: return ""
        val colon = n.indexOf(':')
        return if (colon < 0) n else n.substring(colon + 1)
    }

    /** An attribute by local name, whatever prefix it happens to carry. */
    private fun attr(a: Attributes?, name: String): String? {
        val attrs = a ?: return null
        for (i in 0 until attrs.length) {
            if (local(attrs.getQName(i), attrs.getLocalName(i)) == name) return attrs.getValue(i)
        }
        return null
    }
}

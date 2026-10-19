package dev.niccc2007.filet.docs

import dev.niccc2007.filet.docs.Ooxml.attr
import dev.niccc2007.filet.docs.Ooxml.local
import dev.niccc2007.filet.docs.Ooxml.onOff
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * The DOCX half of [OfficeReader]: `word/document.xml` plus the parts that give it meaning.
 *
 * ## Why the other parts are read at all
 *
 * The first version of this read `word/document.xml` alone, and that is where its fidelity was
 * lost. The body says *which* style a paragraph has and *which* list it belongs to; what those
 * mean lives elsewhere:
 *
 * - `word/styles.xml` - that `Heading1` is "heading 1", that a style called `Überschrift1`
 *   in a German Word is too (by its outline level), and that "Strong" is bold.
 * - `word/numbering.xml` - whether a list is bulleted or numbered, and in what format. The
 *   body never says; a `numFmt` read from the body is a tag that does not occur there, so
 *   every numbered list came out as bullets.
 * - `word/_rels/document.xml.rels` - which picture an `r:embed` is, and where a link goes.
 *
 * Every one of these is optional. A body with nothing beside it still reads, the way it did.
 */
internal class WordParts(
    val styles: Map<String, WordStyle> = emptyMap(),
    val numbering: WordNumbering = WordNumbering(),
    val rels: Map<String, Ooxml.Rel> = emptyMap(),
    val media: Map<String, ByteArray> = emptyMap(),
    /** The body text size in half-points, which every [Mark.SIZE] is relative to. */
    val baseHalfPoints: Int = 22,
) {
    /** Walk a style's `basedOn` chain until [pick] answers. Bounded, because a loop is legal XML. */
    fun <T : Any> fromStyle(id: String?, pick: (WordStyle) -> T?): T? {
        var current = id
        var guard = 0
        while (current != null && guard++ < 24) {
            val style = styles[current] ?: return null
            pick(style)?.let { return it }
            current = style.basedOn
        }
        return null
    }

    companion object {
        val EMPTY = WordParts()
    }
}

/** Run formatting, where null means "not said here, ask the next layer". */
internal data class RunProps(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val color: Long = 0L,
    val halfPoints: Int = 0,
)

internal data class WordStyle(
    val id: String,
    val name: String = "",
    val basedOn: String? = null,
    val outline: Int? = null,
    val numId: String? = null,
    val ilvl: Int? = null,
    val run: RunProps = RunProps(),
    val isDefaultParagraph: Boolean = false,
)

/** One level of a list definition. */
internal data class ListLevel(val format: String = "decimal", val text: String = "", val start: Int = 1)

/**
 * `word/numbering.xml`: which abstract definition a `numId` uses, and what each level of that
 * definition looks like.
 */
internal class WordNumbering(
    val abstractOf: Map<String, String> = emptyMap(),
    val levels: Map<String, Map<Int, ListLevel>> = emptyMap(),
    val startOverrides: Map<String, Map<Int, Int>> = emptyMap(),
) {
    fun level(numId: String, ilvl: Int): ListLevel? = abstractOf[numId]?.let { levels[it]?.get(ilvl) }
}

internal object WordReader {

    const val BODY = "word/document.xml"
    const val STYLES = "word/styles.xml"
    const val NUMBERING = "word/numbering.xml"
    val BODY_RELS = Ooxml.relsPathOf(BODY)

    // ── the side parts ───────────────────────────────────────────────────────────

    fun styles(xml: String?): Pair<Map<String, WordStyle>, Int> {
        if (xml.isNullOrEmpty()) return emptyMap<String, WordStyle>() to 0
        val out = HashMap<String, WordStyle>()
        var docDefaultSize = 0
        Ooxml.parse(xml, object : DefaultHandler() {
            private val stack = ArrayList<String>()
            private var cur: WordStyle? = null
            private var run = RunProps()

            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
                val name = local(qName, localName)
                val parent = stack.lastOrNull()
                stack.add(name)
                if ("rPrChange" in stack || "pPrChange" in stack) return
                when (name) {
                    "style" -> {
                        cur = WordStyle(
                            id = attr(a, "styleId").orEmpty(),
                            isDefaultParagraph = attr(a, "type") == "paragraph" && onOffAttr(attr(a, "default")),
                        )
                        run = RunProps()
                    }
                    "name" -> if (parent == "style") cur = cur?.copy(name = attr(a, "val").orEmpty())
                    "basedOn" -> if (parent == "style") cur = cur?.copy(basedOn = attr(a, "val"))
                    "outlineLvl" -> cur = cur?.copy(outline = attr(a, "val")?.toIntOrNull())
                    "numId" -> cur = cur?.copy(numId = attr(a, "val"))
                    "ilvl" -> cur = cur?.copy(ilvl = attr(a, "val")?.toIntOrNull())
                    "b" -> if (parent == "rPr") run = run.copy(bold = onOff(a))
                    "i" -> if (parent == "rPr") run = run.copy(italic = onOff(a))
                    "u" -> if (parent == "rPr") run = run.copy(underline = underlineOn(a))
                    "strike", "dstrike" -> if (parent == "rPr") run = run.copy(strike = onOff(a))
                    "color" -> if (parent == "rPr") run = run.copy(color = Ooxml.hexColor(attr(a, "val")))
                    "sz" -> if (parent == "rPr") {
                        val v = attr(a, "val")?.toIntOrNull() ?: 0
                        if ("rPrDefault" in stack) docDefaultSize = v else run = run.copy(halfPoints = v)
                    }
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val name = local(qName, localName)
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                if (name == "style") {
                    cur?.let { if (it.id.isNotEmpty()) out[it.id] = it.copy(run = run) }
                    cur = null
                }
            }
        })
        // The body size is the default paragraph style's when it sets one, the document
        // default's otherwise, and Word's own 11 pt when neither does.
        val normal = out.values.firstOrNull { it.isDefaultParagraph }?.run?.halfPoints ?: 0
        return out to (normal.takeIf { it > 0 } ?: docDefaultSize)
    }

    fun numbering(xml: String?): WordNumbering {
        if (xml.isNullOrEmpty()) return WordNumbering()
        val abstractOf = HashMap<String, String>()
        val levels = HashMap<String, HashMap<Int, ListLevel>>()
        val overrides = HashMap<String, HashMap<Int, Int>>()
        Ooxml.parse(xml, object : DefaultHandler() {
            private var abstractId: String? = null
            private var numId: String? = null
            private var lvl = -1
            private var overrideLvl = -1
            private var level = ListLevel()

            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
                when (local(qName, localName)) {
                    "abstractNum" -> abstractId = attr(a, "abstractNumId")
                    "num" -> numId = attr(a, "numId")
                    "abstractNumId" -> numId?.let { n -> attr(a, "val")?.let { abstractOf[n] = it } }
                    "lvlOverride" -> overrideLvl = attr(a, "ilvl")?.toIntOrNull() ?: -1
                    "startOverride" -> {
                        val n = numId
                        val start = attr(a, "val")?.toIntOrNull()
                        if (n != null && overrideLvl >= 0 && start != null) {
                            overrides.getOrPut(n) { HashMap() }[overrideLvl] = start
                        }
                    }
                    "lvl" -> if (abstractId != null) {
                        lvl = attr(a, "ilvl")?.toIntOrNull() ?: -1
                        level = ListLevel()
                    }
                    "start" -> if (lvl >= 0) level = level.copy(start = attr(a, "val")?.toIntOrNull() ?: 1)
                    "numFmt" -> if (lvl >= 0) level = level.copy(format = attr(a, "val") ?: "decimal")
                    "lvlText" -> if (lvl >= 0) level = level.copy(text = attr(a, "val").orEmpty())
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (local(qName, localName)) {
                    "lvl" -> {
                        val id = abstractId
                        if (id != null && lvl >= 0) levels.getOrPut(id) { HashMap() }[lvl] = level
                        lvl = -1
                    }
                    "abstractNum" -> abstractId = null
                    "num" -> numId = null
                    "lvlOverride" -> overrideLvl = -1
                }
            }
        })
        return WordNumbering(abstractOf, levels, overrides)
    }

    // ── the body ─────────────────────────────────────────────────────────────────

    fun blocks(xml: String, parts: WordParts = WordParts.EMPTY): List<Block> {
        val handler = BodyHandler(parts)
        Ooxml.parse(xml, handler)
        return handler.finish()
    }

    private fun onOffAttr(v: String?) = v == "1" || v == "true" || v == "on"

    /** `w:u` is a kind of underline, and `none` is one of the kinds. */
    private fun underlineOn(a: Attributes?): Boolean = attr(a, "val").let { it == null || it != "none" }

    /** A Word highlight is a named colour, not a hex value. */
    fun highlightColor(name: String?): Long = when (name) {
        "yellow" -> 0xFFFFFF00
        "green" -> 0xFF00FF00
        "cyan" -> 0xFF00FFFF
        "magenta" -> 0xFFFF00FF
        "blue" -> 0xFF0000FF
        "red" -> 0xFFFF0000
        "darkBlue" -> 0xFF000080
        "darkCyan" -> 0xFF008080
        "darkGreen" -> 0xFF008000
        "darkMagenta" -> 0xFF800080
        "darkRed" -> 0xFF800000
        "darkYellow" -> 0xFF808000
        "darkGray" -> 0xFF808080
        "lightGray" -> 0xFFC0C0C0
        "black" -> 0xFF000000
        "white" -> 0xFFFFFFFF
        else -> 0L
    }

    /**
     * A list marker as Word would draw it: `lvlText` with each `%n` replaced by level n's
     * counter in level n's own format, so a `%1.%2.` level under a decimal-then-letter list
     * reads `3.b.` and not `3.2.`.
     */
    fun marker(levels: Map<Int, ListLevel>, ilvl: Int, counts: IntArray): String {
        val def = levels[ilvl] ?: return ""
        if (def.format == "bullet") return bulletGlyph(def.text)
        if (def.format == "none") return ""
        val template = def.text.ifEmpty { "%${ilvl + 1}." }
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '%' && i + 1 < template.length && template[i + 1].isDigit()) {
                val at = template[i + 1] - '1'
                val levelDef = levels[at] ?: ListLevel()
                out.append(formatNumber(counts.getOrElse(at) { 1 }.coerceAtLeast(1), levelDef.format))
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /**
     * Word draws its default bullets from the Symbol and Wingdings fonts, at code points in the
     * private-use area that mean nothing in any other font. Mapped to what they look like.
     */
    fun bulletGlyph(text: String): String {
        val c = text.firstOrNull() ?: return "•"
        return when {
            c == 'o' -> "◦"
            c == '' || c == '' -> "▪"
            c == '' || c == '' -> "›"
            c in ''..'' -> "•"
            c == '-' || c == '–' || c == '*' || c == '•' || c == '◦' || c == '▪' || c == '>' -> c.toString()
            else -> "•"
        }
    }

    fun formatNumber(n: Int, format: String): String = when (format) {
        "lowerLetter" -> letters(n).lowercase()
        "upperLetter" -> letters(n)
        "lowerRoman" -> roman(n).lowercase()
        "upperRoman" -> roman(n)
        "decimalZero" -> if (n < 10) "0$n" else "$n"
        else -> n.toString()
    }

    /** 1 = A, 26 = Z, 27 = AA - Word repeats the letter rather than counting in base 26. */
    private fun letters(n: Int): String {
        val letter = 'A' + (n - 1) % 26
        return letter.toString().repeat((n - 1) / 26 + 1)
    }

    private fun roman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var rest = n
        val out = StringBuilder()
        for (i in values.indices) while (rest >= values[i]) { out.append(symbols[i]); rest -= values[i] }
        return out.toString()
    }

    /** The paragraph being read. A stack of these, because a text box puts paragraphs inside one. */
    private class ParaState {
        var b = SpanBuilder()
        var styleId = ""
        var numId: String? = null
        var ilvl: Int? = null
        var hasNumPr = false
        var align = Align.START
        var indentTwips = 0
        var outline: Int? = null
        var linkStart = -1
        var linkHref = ""
        var pageBreak = false
        val images = ArrayList<Block.Image>()

        // The run being read. Held per paragraph, so the runs of a text box inside this
        // paragraph cannot overwrite the state of the run that contains the box, and reset on
        // every `w:r`, so a paragraph mark's formatting - the `w:rPr` inside `w:pPr` - can never
        // leak into the first run after it.
        var runStart = 0
        var run = RunProps()
        var runStyle: String? = null
        var highlight = 0L
        var vertAlign = ""
    }

    /** One picture being read out of a `w:drawing` or `w:pict`. */
    private class Picture {
        var embed: String? = null
        var alt = ""
        var cx = 0L
        var cy = 0L
        var chart = false
        var textBox = false
    }

    private class BodyHandler(private val parts: WordParts) : DefaultHandler() {
        private val out = ArrayList<Block>()
        private val stack = ArrayList<String>()
        private val paras = ArrayList<ParaState>()
        private var skipped = 0
        private var picture: Picture? = null

        private var inText = false

        // Tables. Only the outermost one becomes a block; a table nested in a cell is read into
        // that cell as text, because a grid inside a grid is not something 400 dp can draw.
        private var rows = ArrayList<List<String>>()
        private var rowSpans = ArrayList<List<Int>>()
        private var cells = ArrayList<String>()
        private var cellSpans = ArrayList<Int>()
        private var cell = StringBuilder()
        private var cellSpan = 1
        private var cellContinues = false
        private var tableHeader = false

        // Numbering counters, by abstract definition: two `numId`s over one definition are one
        // list in Word and keep counting across each other.
        private val counts = HashMap<String, IntArray>()
        private val numSeen = HashSet<String>()

        private fun depthOf(name: String) = stack.count { it == name }
        private val tableDepth get() = depthOf("tbl")
        private val para get() = paras.lastOrNull()

        /** Inside the run of the innermost paragraph, not merely somewhere under a run. */
        private fun inRun() = stack.lastIndexOf("r") > stack.lastIndexOf("p")

        override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
            val name = local(qName, localName)
            val parent = stack.lastOrNull()
            stack.add(name)
            // The fallback half of a compatibility block repeats what the choice half said, in
            // an older vocabulary. Reading both prints every text box twice.
            if ("Fallback" in stack) return
            // A tracked formatting change carries the OLD properties. Reading them applies the
            // formatting the author removed.
            if ("rPrChange" in stack || "pPrChange" in stack) return

            when (name) {
                "p" -> paras.add(ParaState())
                "pStyle" -> if (parent == "pPr") para?.styleId = attr(a, "val").orEmpty()
                "numPr" -> if (parent == "pPr") para?.hasNumPr = true
                "numId" -> if ("numPr" == parent) para?.numId = attr(a, "val")
                "ilvl" -> if ("numPr" == parent) para?.ilvl = attr(a, "val")?.toIntOrNull()?.coerceIn(0, 8)
                "outlineLvl" -> if (parent == "pPr") para?.outline = attr(a, "val")?.toIntOrNull()
                "jc" -> if (parent == "pPr") para?.align = alignOf(attr(a, "val"))
                "ind" -> if (parent == "pPr") {
                    para?.indentTwips = (attr(a, "start") ?: attr(a, "left"))?.toIntOrNull() ?: 0
                }

                "r" -> if (inRun()) para?.let { p ->
                    p.runStart = p.b.length
                    p.run = RunProps()
                    p.runStyle = null
                    p.highlight = 0L
                    p.vertAlign = ""
                }
                "rStyle", "b", "i", "u", "strike", "dstrike", "color", "sz", "highlight", "vertAlign" ->
                    if (parent == "rPr" && inRun()) para?.let { runProperty(it, name, a) }

                "t" -> if (parent == "r") inText = true
                // ONLY inside a run. `w:tab` is also how a paragraph declares its tab STOPS,
                // inside `w:pPr/w:tabs`, and treating those as characters put a stray tab at
                // the start of every paragraph that had any.
                "tab" -> if (parent == "r") para?.b?.append("\t")
                "br" -> if (parent == "r") {
                    if (attr(a, "type") == "page") para?.pageBreak = true else para?.b?.append("\n")
                }
                "cr" -> if (parent == "r") para?.b?.append("\n")
                "noBreakHyphen" -> if (parent == "r") para?.b?.append("-")

                "hyperlink" -> para?.let { p ->
                    val rel = Ooxml.relId(a)?.let { parts.rels[it] }
                    p.linkStart = p.b.length
                    p.linkHref = when {
                        rel != null -> rel.target
                        attr(a, "anchor") != null -> "#" + attr(a, "anchor")
                        else -> ""
                    }
                }

                // Pictures. `w:drawing` is the modern one; `w:pict` and `w:object` carry a VML
                // `v:imagedata`, which is how older files and embedded objects show a preview.
                "drawing", "pict", "object" -> if (picture == null) picture = Picture()
                "extent" -> picture?.let {
                    if (parent == "inline" || parent == "anchor") {
                        it.cx = attr(a, "cx")?.toLongOrNull() ?: 0
                        it.cy = attr(a, "cy")?.toLongOrNull() ?: 0
                    }
                }
                "docPr" -> picture?.alt = (attr(a, "descr") ?: attr(a, "title")).orEmpty()
                "blip" -> picture?.let { it.embed = it.embed ?: attr(a, "embed") }
                "imagedata" -> picture?.let { it.embed = it.embed ?: Ooxml.relId(a) }
                "chart", "relIds" -> picture?.chart = true
                "txbxContent" -> picture?.textBox = true

                "tbl" -> if (tableDepth == 1) {
                    rows = ArrayList()
                    rowSpans = ArrayList()
                    tableHeader = false
                }
                "tblLook" -> if (tableDepth == 1) {
                    // Either the explicit attribute or the bit in the legacy hex mask.
                    val first = attr(a, "firstRow") == "1" ||
                        ((attr(a, "val")?.toIntOrNull(16) ?: 0) and 0x20) != 0
                    if (first) tableHeader = true
                }
                "tblHeader" -> if (tableDepth == 1 && rows.isEmpty()) tableHeader = onOff(a)
                "tr" -> if (tableDepth == 1) {
                    cells = ArrayList()
                    cellSpans = ArrayList()
                }
                "tc" -> if (tableDepth == 1) {
                    cell = StringBuilder()
                    cellSpan = 1
                    cellContinues = false
                }
                "gridSpan" -> if (tableDepth == 1 && parent == "tcPr") {
                    cellSpan = (attr(a, "val")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                }
                // A vertical merge with no value, or `continue`, is the lower part of a cell
                // that started above. Its text is the cell above's; drawn empty, as Word does.
                "vMerge" -> if (tableDepth == 1 && parent == "tcPr") {
                    cellContinues = attr(a, "val").let { it == null || it == "continue" }
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            // ONLY inside w:t. Every other element's character data is the whitespace a
            // pretty-printed document has between its tags, and appending it turns a document
            // into a wall of blank lines.
            if (!inText || ch == null || "Fallback" in stack) return
            para?.b?.append(String(ch, start, length))
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = local(qName, localName)
            val skipping = "Fallback" in stack
            if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            if (skipping) return

            when (name) {
                "t" -> inText = false
                "r" -> if (stack.lastIndexOf("p") > stack.lastIndexOf("r")) para?.let { endRun(it) }
                "hyperlink" -> para?.let { p ->
                    if (p.linkStart >= 0 && p.linkHref.isNotEmpty()) p.b.mark(p.linkStart, Mark.LINK, p.linkHref)
                    p.linkStart = -1
                }
                "drawing", "pict", "object" -> if (!stack.any { it == "drawing" || it == "pict" || it == "object" }) {
                    picture?.let { endPicture(it) }
                    picture = null
                }
                "p" -> {
                    val p = paras.removeLastOrNull() ?: return
                    flush(p)
                }
                "tc" -> if (tableDepth == 1) {
                    cells.add(if (cellContinues) "" else cell.toString().trim())
                    cellSpans.add(cellSpan)
                }
                "tr" -> if (tableDepth == 1 && cells.isNotEmpty()) {
                    rows.add(cells.toList())
                    rowSpans.add(cellSpans.toList())
                }
                "tbl" -> if (tableDepth == 0 && rows.isNotEmpty()) {
                    val merged = rowSpans.any { r -> r.any { it > 1 } }
                    out.add(
                        Block.Table(
                            rows.toList(),
                            header = tableHeader,
                            colSpans = if (merged) rowSpans.toList() else emptyList(),
                        ),
                    )
                }
            }
        }

        private fun runProperty(p: ParaState, name: String, a: Attributes?) {
            when (name) {
                "rStyle" -> p.runStyle = attr(a, "val")
                "b" -> p.run = p.run.copy(bold = onOff(a))
                "i" -> p.run = p.run.copy(italic = onOff(a))
                "u" -> p.run = p.run.copy(underline = underlineOn(a))
                "strike", "dstrike" -> p.run = p.run.copy(strike = onOff(a))
                "color" -> p.run = p.run.copy(color = Ooxml.hexColor(attr(a, "val")))
                "sz" -> p.run = p.run.copy(halfPoints = attr(a, "val")?.toIntOrNull() ?: 0)
                "highlight" -> p.highlight = highlightColor(attr(a, "val"))
                "vertAlign" -> p.vertAlign = attr(a, "val").orEmpty()
            }
        }

        private fun endRun(p: ParaState) {
            val b = p.b
            val run = p.run
            val runStart = p.runStart
            val highlight = p.highlight
            val vertAlign = p.vertAlign
            val style = p.runStyle
            val bold = run.bold ?: parts.fromStyle(style) { it.run.bold } ?: false
            val italic = run.italic ?: parts.fromStyle(style) { it.run.italic } ?: false
            val underline = run.underline ?: parts.fromStyle(style) { it.run.underline } ?: false
            val strike = run.strike ?: parts.fromStyle(style) { it.run.strike } ?: false
            val color = run.color.takeIf { it != 0L } ?: parts.fromStyle(style) { s -> s.run.color.takeIf { it != 0L } } ?: 0L
            val half = run.halfPoints.takeIf { it > 0 } ?: parts.fromStyle(style) { s -> s.run.halfPoints.takeIf { it > 0 } } ?: 0

            if (bold) b.mark(runStart, Mark.BOLD)
            if (italic) b.mark(runStart, Mark.ITALIC)
            if (underline) b.mark(runStart, Mark.UNDERLINE)
            if (strike) b.mark(runStart, Mark.STRIKE)
            if (color != 0L) b.mark(runStart, Mark.COLOR, argb = color)
            if (highlight != 0L) b.mark(runStart, Mark.HIGHLIGHT, argb = highlight)
            if (vertAlign == "superscript") b.mark(runStart, Mark.SUPERSCRIPT)
            if (vertAlign == "subscript") b.mark(runStart, Mark.SUBSCRIPT)
            if (half > 0) {
                val scale = half.toFloat() / parts.baseHalfPoints.coerceAtLeast(1)
                // Small differences are noise from a template, not emphasis. Only a size somebody
                // chose to make different is carried.
                if (scale < 0.9f || scale > 1.1f) b.mark(runStart, Mark.SIZE, scale = scale.coerceIn(0.6f, 2.2f))
            }
            p.run = RunProps()
            p.runStyle = null
            p.highlight = 0L
            p.vertAlign = ""
        }

        private fun endPicture(pic: Picture) {
            val rel = pic.embed?.let { parts.rels[it] }
            val bytes = rel?.takeIf { !it.external }?.let { parts.media[it.target] }
            val holder = para
            when {
                bytes != null && tableDepth == 0 -> {
                    val image = Block.Image(bytes, pic.alt, Ooxml.extensionOf(rel.target), pic.cx, pic.cy)
                    if (holder != null) holder.images.add(image) else out.add(image)
                }
                // A text box is a shape whose content is paragraphs, and those were read. It is
                // not a picture that went missing.
                pic.textBox && pic.embed == null && !pic.chart -> Unit
                else -> skipped++
            }
        }

        private fun flush(p: ParaState) {
            val b = p.b
            if (tableDepth > 0) {
                if (!b.isBlank()) {
                    if (cell.isNotEmpty()) cell.append('\n')
                    if (p.hasNumPr && p.numId != "0") cell.append("• ")
                    cell.append(b.text().trim())
                }
                skipped += p.images.size
                return
            }
            if (!b.isBlank()) out.add(blockFor(p))
            out.addAll(p.images)
            if (p.pageBreak) out.add(Block.Rule)
        }

        private fun blockFor(p: ParaState): Block {
            val b = p.b
            val style = p.styleId
            val heading = headingOf(p)
            val numId = p.numId ?: parts.fromStyle(style) { it.numId }
            val isList = (p.hasNumPr || numId != null) && numId != "0"
            if (isList) {
                val ilvl = p.ilvl ?: parts.fromStyle(style) { it.ilvl } ?: 0
                val item = listItem(b, numId, ilvl.coerceIn(0, 8))
                // A numbered HEADING - Heading 1 tied to a multilevel list, "2.3 Results" - is
                // still a heading. Read as a list item it lost its size and became a bullet in
                // the body; it keeps its level, with the number in front as Word draws it.
                if (item is Block.Item && heading > 0) {
                    val prefix = if (item.marker.isEmpty()) "" else item.marker + " "
                    return Block.Para(
                        prefix + b.text(),
                        level = heading,
                        spans = b.spans()
                            .filter { it.style != Mark.SIZE }
                            .map { it.copy(from = it.from + prefix.length, to = it.to + prefix.length) },
                        align = p.align,
                    )
                }
                if (item != null) return item
            }
            val quote = parts.styles[style]?.name.orEmpty().ifEmpty { style }
                .replace(" ", "").lowercase().let { it == "quote" || it == "intensequote" }
            return Block.Para(
                b.text(),
                level = heading,
                spans = if (heading > 0) b.spans().filter { it.style != Mark.SIZE } else b.spans(),
                quote = quote,
                align = p.align,
                indent = (p.indentTwips / 20).coerceIn(0, 144),
            )
        }

        /**
         * The heading level, from the style and never from how the text looks.
         *
         * In order: the style's NAME through its `basedOn` chain (`heading 2`, `Title`), then an
         * outline level the paragraph or its style declares, then the style id itself for a body
         * with no styles part. The outline level is what makes a German `Überschrift1` or a
         * custom "Chapter" style a heading - it is the property Word's own navigation pane reads.
         */
        private fun headingOf(p: ParaState): Int {
            parts.fromStyle(p.styleId) { s -> headingLevel(s.name).takeIf { it > 0 } }?.let { return it }
            val outline = p.outline ?: parts.fromStyle(p.styleId) { it.outline }
            if (outline != null && outline in 0..5) return outline + 1
            return headingLevel(p.styleId)
        }

        private fun listItem(b: SpanBuilder, numId: String?, ilvl: Int): Block? {
            val abstract = numId?.let { parts.numbering.abstractOf[it] }
            val levels = abstract?.let { parts.numbering.levels[it] }
            val def = levels?.get(ilvl)
            // `none` is a list level that draws no marker: an indented paragraph, not a bullet.
            if (def?.format == "none") return null
            if (levels == null || def == null || numId == null || abstract == null) {
                // No definition to read: a bullet, which is what an unresolvable list looks like
                // in every reader and the one guess that is never wrong in a harmful way.
                return Block.Item(b.text(), depth = ilvl, ordered = false, spans = b.spans())
            }
            val c = counts.getOrPut(abstract) { IntArray(9) }
            val overrides = parts.numbering.startOverrides[numId]
            if (numSeen.add(numId) && overrides != null) {
                // A `num` with a start override is a list that restarts here.
                for ((lvl, _) in overrides) if (lvl in 0..8) c[lvl] = 0
            }
            c[ilvl]++
            for (k in ilvl + 1 until 9) c[k] = 0
            // What each level is actually showing: its start, plus how far it has counted.
            val shown = IntArray(9) { k ->
                val start = overrides?.get(k) ?: levels[k]?.start ?: 1
                if (c[k] == 0) start else start + c[k] - 1
            }
            val ordered = def.format != "bullet"
            return Block.Item(
                b.text(),
                depth = ilvl,
                ordered = ordered,
                number = if (ordered) shown[ilvl] else 0,
                spans = b.spans(),
                marker = marker(levels, ilvl, shown),
            )
        }

        fun finish(): List<Block> {
            // A body cut short still has its last paragraph in hand.
            while (paras.isNotEmpty()) flush(paras.removeAt(paras.size - 1))
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

        private fun alignOf(v: String?): Align = when (v) {
            "center" -> Align.CENTER
            "right", "end" -> Align.END
            "both", "distribute", "justify" -> Align.JUSTIFY
            else -> Align.START
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
}

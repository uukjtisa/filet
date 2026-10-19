package dev.niccc2007.filet.docs

import dev.niccc2007.filet.docs.Ooxml.attr
import dev.niccc2007.filet.docs.Ooxml.local
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * The PPTX half of [OfficeReader].
 *
 * ## The order of the slides is in `presentation.xml`, not in the file names
 *
 * `ppt/presentation.xml` lists the slides in `p:sldIdLst`, each by relationship id, and that
 * list IS the deck's order. The file names are only the order the slides were created in: move
 * slide 7 to the front in PowerPoint and `slide7.xml` keeps its name. Sorting by name is kept
 * as the fallback for a package with no readable presentation part.
 *
 * ## The order of the shapes is where they sit
 *
 * A slide's shape tree is in z-order - the order they were stacked - which is not the order
 * anybody reads them in. A picture added last is last in the tree whatever its position. Each
 * shape carries its own offset in `a:xfrm/a:off`, so the reading order is the title first, then
 * top to bottom and left to right by those offsets. Placeholders that inherit their position
 * from the layout carry no offset, and where any body shape has none the tree order is kept
 * rather than guessed - the layout would have to be read to do better.
 */
internal class SlideParts(
    val rels: Map<String, Ooxml.Rel> = emptyMap(),
    val media: Map<String, ByteArray> = emptyMap(),
) {
    companion object {
        val EMPTY = SlideParts()
    }
}

internal object SlideReader {

    const val PRESENTATION = "ppt/presentation.xml"
    val PRESENTATION_RELS = Ooxml.relsPathOf(PRESENTATION)
    const val SLIDE_PREFIX = "ppt/slides/slide"

    /** The body size PowerPoint's default templates use, which [Mark.SIZE] is relative to. */
    private const val BODY_HUNDREDTHS = 1800

    /** `p:sldIdLst` in order, as relationship ids. */
    fun slideIds(xml: String?): List<String> {
        if (xml.isNullOrEmpty()) return emptyList()
        val out = ArrayList<String>()
        Ooxml.parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
                if (local(qName, localName) == "sldId") Ooxml.relId(a)?.let(out::add)
            }
        })
        return out
    }

    /**
     * Slide parts in the order the deck presents them.
     *
     * [presentationOrder] wins where it names slides that exist; any slide it does not name is
     * appended by number, so a deck with a damaged list still shows every slide it has.
     */
    fun order(names: Collection<String>, presentationOrder: List<String> = emptyList()): List<String> {
        val present = names.toSet()
        val listed = presentationOrder.filter { it in present }.distinct()
        val rest = names.filter { it !in listed }.sortedBy { slideNumber(it) ?: Int.MAX_VALUE }
        return listed + rest
    }

    fun slideNumber(name: String): Int? = name.removePrefix(SLIDE_PREFIX).removeSuffix(".xml").toIntOrNull()

    /** Whether the slide root says `show="0"`: in the file, skipped in the presentation. */
    fun isHidden(xml: String): Boolean {
        var hidden = false
        runCatching {
            Ooxml.parse(xml, object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
                    if (local(qName, localName) == "sld") hidden = attr(a, "show") == "0"
                    // The root is the first element; nothing after it can change the answer.
                    throw Done()
                }
            })
        }
        return hidden
    }

    /** Thrown to stop a parse once the answer is known. Never escapes [isHidden]. */
    private class Done : RuntimeException()

    /** A slide's XML into blocks, title first. */
    fun blocks(xml: String, parts: SlideParts = SlideParts.EMPTY): List<Block> {
        val handler = ShapeHandler(parts, notes = false)
        Ooxml.parse(xml, handler)
        return handler.finish()
    }

    /**
     * The text of a notes slide.
     *
     * Only the body placeholder. A notes page also holds a picture of the slide, its number and
     * sometimes a header and footer, and none of those are the notes.
     */
    fun notes(xml: String): String {
        val handler = ShapeHandler(SlideParts.EMPTY, notes = true)
        Ooxml.parse(xml, handler)
        return handler.finish().joinToString("\n") { b ->
            when (b) {
                is Block.Para -> b.text
                is Block.Item -> "• " + b.text
                else -> ""
            }
        }.trim()
    }

    /** One top-level shape and what it produced. */
    private class ShapeUnit(val order: Int) {
        val blocks = ArrayList<Block>()
        var x: Long? = null
        var y: Long? = null
        var title = false
        var placeholder: String? = null
    }

    private class ShapeHandler(private val parts: SlideParts, private val notes: Boolean) : DefaultHandler() {
        private val stack = ArrayList<String>()
        private val units = ArrayList<ShapeUnit>()
        private var unit: ShapeUnit? = null
        private var skipped = 0

        // The paragraph and run being read.
        private var b = SpanBuilder()
        private var level = 0
        private var bullet: String? = null
        private var autoNum: String? = null
        private var autoStart = 1
        private var align = Align.START
        private var runStart = 0
        private var bold = false
        private var italic = false
        private var underline = false
        private var strike = false
        private var size = 0
        private var color = 0L
        private var link = ""
        private var inText = false
        private val counters = IntArray(9)

        // A table in a graphic frame.
        private var rows = ArrayList<List<String>>()
        private var rowSpans = ArrayList<List<Int>>()
        private var cells = ArrayList<String>()
        private var spans = ArrayList<Int>()
        private var cell = StringBuilder()
        private var cellSpan = 1
        private var cellHidden = false
        private var tableFirstRow = false

        // A picture.
        private var embed: String? = null
        private var alt = ""
        private var cx = 0L
        private var cy = 0L

        private val shapeKinds = setOf("sp", "pic", "graphicFrame", "grpSp", "cxnSp")

        /** Depth of the shape tree we are in, so only TOP-level shapes become units. */
        private fun shapeDepth() = stack.count { it in shapeKinds }

        override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
            val name = local(qName, localName)
            val parent = stack.lastOrNull()
            stack.add(name)
            if ("Fallback" in stack) return

            if (name in shapeKinds && shapeDepth() == 1) {
                unit = ShapeUnit(units.size).also { units.add(it) }
                counters.fill(0)
            }
            val u = unit ?: return

            when (name) {
                "ph" -> {
                    val type = attr(a, "type") ?: "obj"
                    if (shapeDepth() == 1) u.placeholder = type
                    if (type == "title" || type == "ctrTitle") u.title = true
                }
                // The first offset inside a top-level shape is its own: its `spPr`, `grpSpPr` or
                // frame `xfrm` comes before anything nested in it.
                "off" -> if (u.y == null && parent == "xfrm") {
                    u.x = attr(a, "x")?.toLongOrNull()
                    u.y = attr(a, "y")?.toLongOrNull()
                }
                "ext" -> if (parent == "xfrm" && embed != null && cx == 0L) {
                    cx = attr(a, "cx")?.toLongOrNull() ?: 0
                    cy = attr(a, "cy")?.toLongOrNull() ?: 0
                }
                "cNvPr" -> if (stack.contains("pic")) alt = (attr(a, "descr") ?: attr(a, "title")).orEmpty()
                "blip" -> embed = attr(a, "embed")
                "chart", "relIds" -> skipped++

                "p" -> {
                    b = SpanBuilder()
                    level = attr(a, "lvl")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                    bullet = null
                    autoNum = null
                    align = Align.START
                }
                "pPr" -> {
                    level = attr(a, "lvl")?.toIntOrNull()?.coerceIn(0, 8) ?: level
                    align = when (attr(a, "algn")) {
                        "ctr" -> Align.CENTER
                        "r" -> Align.END
                        "just", "dist" -> Align.JUSTIFY
                        else -> Align.START
                    }
                }
                "buNone" -> if (parent == "pPr") bullet = ""
                "buChar" -> if (parent == "pPr") bullet = attr(a, "char")?.let { WordReader.bulletGlyph(it) } ?: "•"
                "buAutoNum" -> if (parent == "pPr") {
                    autoNum = attr(a, "type") ?: "arabicPeriod"
                    autoStart = attr(a, "startAt")?.toIntOrNull() ?: 1
                }
                "r" -> {
                    runStart = b.length
                    bold = false
                    italic = false
                    underline = false
                    strike = false
                    size = 0
                    color = 0L
                    link = ""
                }
                "rPr" -> if (parent == "r") {
                    bold = attr(a, "b") == "1"
                    italic = attr(a, "i") == "1"
                    underline = attr(a, "u").let { it != null && it != "none" }
                    strike = attr(a, "strike").let { it != null && it != "noStrike" }
                    size = attr(a, "sz")?.toIntOrNull() ?: 0
                }
                // A run's colour is `rPr/solidFill/srgbClr`. A scheme colour is a reference into
                // the theme, which is not read, so it is left as the screen's own text colour.
                "srgbClr" -> if (parent == "solidFill" && stack.getOrNull(stack.size - 3) == "rPr") {
                    color = Ooxml.hexColor(attr(a, "val"))
                }
                "hlinkClick" -> if (parent == "rPr") {
                    link = Ooxml.relId(a)?.let { parts.rels[it] }?.target.orEmpty()
                }
                "br" -> b.append("\n")
                "t" -> inText = true

                "tbl" -> {
                    rows = ArrayList()
                    rowSpans = ArrayList()
                    tableFirstRow = false
                }
                "tblPr" -> tableFirstRow = attr(a, "firstRow") == "1"
                "tr" -> {
                    cells = ArrayList()
                    spans = ArrayList()
                }
                "tc" -> {
                    cell = StringBuilder()
                    cellSpan = (attr(a, "gridSpan")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                    // The cells a merge covers are still in the file, flagged. Their text is the
                    // merged cell's, so they are drawn empty.
                    cellHidden = attr(a, "hMerge") == "1" || attr(a, "vMerge") == "1"
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (!inText || ch == null || "Fallback" in stack) return
            b.append(String(ch, start, length))
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = local(qName, localName)
            val skipping = "Fallback" in stack
            val wasDepth = shapeDepth()
            if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            if (skipping) return
            val u = unit ?: return

            when (name) {
                "t" -> inText = false
                "r" -> {
                    if (bold) b.mark(runStart, Mark.BOLD)
                    if (italic) b.mark(runStart, Mark.ITALIC)
                    if (underline) b.mark(runStart, Mark.UNDERLINE)
                    if (strike) b.mark(runStart, Mark.STRIKE)
                    if (color != 0L) b.mark(runStart, Mark.COLOR, argb = color)
                    if (link.isNotEmpty()) b.mark(runStart, Mark.LINK, link)
                    if (size > 0) {
                        val scale = size.toFloat() / BODY_HUNDREDTHS
                        if (scale < 0.85f || scale > 1.15f) b.mark(runStart, Mark.SIZE, scale = scale.coerceIn(0.6f, 2.0f))
                    }
                }
                "p" -> endParagraph(u)
                "tc" -> if ("tbl" in stack) {
                    cells.add(if (cellHidden) "" else cell.toString().trim())
                    spans.add(cellSpan)
                }
                "tr" -> if ("tbl" in stack && cells.isNotEmpty()) {
                    rows.add(cells.toList())
                    rowSpans.add(spans.toList())
                }
                "tbl" -> if (rows.isNotEmpty()) {
                    val merged = rowSpans.any { r -> r.any { it > 1 } }
                    u.blocks.add(Block.Table(rows.toList(), header = tableFirstRow, colSpans = if (merged) rowSpans.toList() else emptyList()))
                }
                "pic" -> {
                    val rel = embed?.let { parts.rels[it] }
                    val bytes = rel?.takeIf { !it.external }?.let { parts.media[it.target] }
                    if (bytes != null) {
                        u.blocks.add(Block.Image(bytes, alt, Ooxml.extensionOf(rel.target), cx, cy))
                    } else if (!notes) {
                        skipped++
                    }
                    embed = null
                    alt = ""
                    cx = 0
                    cy = 0
                }
            }
            if (name in shapeKinds && wasDepth == 1) unit = null
        }

        private fun endParagraph(u: ShapeUnit) {
            if (b.isBlank()) return
            if ("tbl" in stack) {
                if (cell.isNotEmpty()) cell.append('\n')
                cell.append(b.text().trim())
                return
            }
            // Boilerplate a layout stamps on every slide: not the slide's content.
            if (u.placeholder == "sldNum" || u.placeholder == "dt" || u.placeholder == "sldImg") return
            if (notes && u.placeholder != "body") return

            val spans = if (u.title) b.spans().filter { it.style != Mark.SIZE } else b.spans()
            if (u.title) {
                u.blocks.add(Block.Para(b.text(), level = 2, spans = spans, align = align))
                return
            }
            // A body placeholder bullets every level by default - that is what the master says
            // in every built-in template - and an explicit `buNone` or `buChar` overrides it. A
            // free text box bullets nothing at the top level unless the paragraph asks.
            val body = u.placeholder == "body" || u.placeholder == "obj"
            val auto = autoNum
            val bulleted = when {
                auto != null -> true
                bullet == "" -> false
                bullet != null -> true
                body -> !notes
                else -> level > 0
            }
            if (!bulleted) {
                u.blocks.add(Block.Para(b.text(), spans = spans, align = align))
                return
            }
            val depth = if (body || bullet != null || auto != null) level else (level - 1).coerceAtLeast(0)
            if (auto != null) {
                counters[level]++
                for (k in level + 1 until 9) counters[k] = 0
                val n = autoStart + counters[level] - 1
                u.blocks.add(
                    Block.Item(b.text(), depth = depth, ordered = true, number = n, spans = spans, marker = autoMarker(auto, n)),
                )
            } else {
                u.blocks.add(Block.Item(b.text(), depth = depth, spans = spans, marker = bullet.orEmpty()))
            }
        }

        fun finish(): List<Block> {
            val titles = units.filter { it.title }
            val rest = units.filterNot { it.title }
            // Positions are only trusted when every body shape has one. See the type's KDoc.
            val sorted = if (rest.all { it.y != null }) {
                rest.sortedWith(compareBy<ShapeUnit>({ it.y }, { it.x ?: 0L }, { it.order }))
            } else {
                rest
            }
            val out = ArrayList<Block>()
            titles.forEach { out.addAll(it.blocks) }
            sorted.forEach { out.addAll(it.blocks) }
            if (!notes && titles.none { t -> t.blocks.any { it is Block.Para } }) {
                // No marked title: the first line reads as one, which is true of nearly every
                // deck and is the slide's own first line, not a guess about its content.
                val first = out.indexOfFirst { it is Block.Para }
                if (first >= 0) out[first] = (out[first] as Block.Para).copy(level = 2)
            }
            if (skipped > 0 && !notes) {
                out.add(
                    Block.Note(if (skipped == 1) "One chart or picture on this slide is not shown." else "$skipped charts or pictures on this slide are not shown."),
                )
            }
            return out
        }
    }

    /** PowerPoint's auto-number schemes: `arabicPeriod`, `alphaLcParenR`, `romanUcPeriod`... */
    fun autoMarker(scheme: String, n: Int): String {
        val format = when {
            scheme.startsWith("alphaLc") -> "lowerLetter"
            scheme.startsWith("alphaUc") -> "upperLetter"
            scheme.startsWith("romanLc") -> "lowerRoman"
            scheme.startsWith("romanUc") -> "upperRoman"
            else -> "decimal"
        }
        val num = WordReader.formatNumber(n, format)
        return when {
            scheme.endsWith("ParenBoth") -> "($num)"
            scheme.endsWith("ParenR") -> "$num)"
            scheme.endsWith("Plain") -> num
            else -> "$num."
        }
    }
}

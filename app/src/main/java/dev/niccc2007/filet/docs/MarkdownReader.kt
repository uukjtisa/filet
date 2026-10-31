package dev.niccc2007.filet.docs

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.front.matter.YamlFrontMatterBlock
import org.commonmark.ext.front.matter.YamlFrontMatterExtension
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor
import org.commonmark.ext.gfm.alerts.Alert
import org.commonmark.ext.gfm.alerts.AlertsExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading as MdHeading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Markdown into [Doc], drawn the way GitHub draws it.
 *
 * ## Why a real parser, and why not through HTML
 *
 * CommonMark is a sixty-page specification. A hand-rolled subset gets lists inside lists,
 * fenced code inside a list item, reference links and the three different emphasis rules wrong
 * in ways that are found one complaint at a time, years apart. commonmark-java is the
 * reference implementation and is BSD-2-Clause, which is permissive and GPL-3 compatible, and
 * its own extensions cover what GitHub adds: tables, strikethrough, alerts, task lists,
 * footnotes, bare-URL links and front matter.
 *
 * The usual Android route is markdown to HTML to a `WebView`, and it is the wrong one here.
 * It costs a whole browser engine on screen, it cannot be selected or searched with the rest
 * of the app's text, its theming is a second stylesheet that has to be kept in step with the
 * real one, and it means a local document gets to run script. Walking the AST into Compose
 * text costs a tree walk and renders with the app's own type.
 *
 * ## The HTML inside it
 *
 * READMEs lean on a little HTML - a centred logo, a `<details>` fold, a `<br>` - and showing
 * those as raw tags made every one of them look broken. So a short, safe list is drawn:
 * `br`, `b`/`strong`, `i`/`em`, `code`/`kbd`, `s`/`del`, `u`, `sub`/`sup`, `a`, `img`,
 * `h1`-`h6`, `p`/`div`/`center` with their `align`, `hr`, `pre`, and `details`/`summary` as a
 * fold. Every other tag is dropped and its text kept, except `script` and `style`, whose
 * content is dropped with them. Nothing here can run anything - a tag is a mark or nothing.
 *
 * ## Pictures
 *
 * Kept as their address, at a place in the text (see [Picture]); the screen resolves a local
 * one against the file's own folder and loads a web one if the reading settings allow it.
 * Deciding that is the screen's, because this stays a pure function of the text.
 */
object MarkdownReader {

    private val parser: Parser by lazy {
        Parser.builder()
            .extensions(
                listOf(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    AlertsExtension.create(),
                    FootnotesExtension.create(),
                    TaskListItemsExtension.create(),
                    AutolinkExtension.create(),
                    YamlFrontMatterExtension.create(),
                ),
            )
            .build()
    }

    fun read(source: String): Doc {
        val root = runCatching { parser.parse(source) }.getOrNull()
            ?: return Doc(
                listOf(Block.Code(source)),
                note = "This could not be parsed as Markdown, so it is shown as text.",
            )
        return Walk().run(root)
    }
}

/** A `<details>` seen whose summary line has not been written yet. */
private class PendingFold(val id: Int, val open: Boolean)

/** Text being gathered for one block, with what is open inside it. */
private class Inline {
    val b = SpanBuilder()
    val pictures = ArrayList<Picture>()
    /** Names that should land on the block this text becomes: footnote references, `<a name>`. */
    val anchors = ArrayList<String>()
    var task: Boolean? = null
    /** The links open around the text being added, innermost last, for a picture inside one. */
    val links = ArrayList<String>()
    /** Inline HTML tags still open: name, where it opened, and an `<a>`'s address. */
    val tags = ArrayList<Triple<String, Int, String>>()
    /** Inside `<script>` or `<style>`: the tag whose close ends the skip. */
    var skip: String? = null

    /** Close whatever HTML was left open, at the end of the text, as a browser would. */
    fun done(): Inline {
        for ((name, start, href) in tags.asReversed()) markOf(name)?.let { b.mark(start, it, href) }
        tags.clear()
        return this
    }

    val blank: Boolean get() = b.isBlank()
}

private fun markOf(tag: String): Mark? = when (tag) {
    "b", "strong" -> Mark.BOLD
    "i", "em", "cite", "var" -> Mark.ITALIC
    "code", "kbd", "tt", "samp" -> Mark.CODE
    "s", "del", "strike" -> Mark.STRIKE
    "u", "ins" -> Mark.UNDERLINE
    "sub" -> Mark.SUBSCRIPT
    "sup" -> Mark.SUPERSCRIPT
    "a" -> Mark.LINK
    else -> null
}

/** Tags that start and end a block of their own. Their text is kept; the tag is not drawn. */
private val BREAKS = setOf(
    "li", "ul", "ol", "tr", "td", "th", "table", "thead", "tbody", "tfoot", "blockquote", "dl", "dt", "dd",
    "section", "article", "header", "footer", "nav", "aside", "main", "figure", "figcaption", "address",
)

private const val PICTURE_MARK = "￼"

private class Walk {
    val out = ArrayList<Block>()
    val nests = ArrayList<Nest>()
    val anchors = LinkedHashMap<String, Int>()
    val outline = ArrayList<Heading>()
    val slugger = DocOutline.Slugger()

    // HTML containers outlive the block they open in: `<div align="center">`, a blank line,
    // Markdown, a blank line, `</div>` is the usual way a README centres its heading.
    val folds = ArrayList<Int>()
    var pendingFold: PendingFold? = null
    var summary: Inline? = null
    var nextFold = 1
    val aligns = ArrayList<Pair<String, Align>>()
    /** Anchors from a block that drew nothing, for whichever block comes next. */
    val pendingAnchors = ArrayList<String>()

    val definitions = LinkedHashMap<String, FootnoteDefinition>()
    /** Footnote numbers, by label, in the order first referenced - which is GitHub's numbering. */
    val numbers = LinkedHashMap<String, Int>()

    val align: Align get() = aligns.lastOrNull()?.second ?: Align.START

    fun run(root: Node): Doc {
        collectDefinitions(root)
        val meta = runCatching { YamlFrontMatterVisitor().also { root.accept(it) }.data }.getOrNull().orEmpty()
        if (meta.isNotEmpty()) {
            emit(Block.Meta(meta.map { (k, v) -> k to v.joinToString(", ") }), emptyList())
        }
        children(root, emptyList(), 0)
        closeContainers()
        footnotes()
        // A first heading is the document's name far more often than not, and the chrome has
        // nowhere else to get one from - the filename is already in the path bar. Front matter
        // that names the page says so outright, so it wins.
        val first = out.firstOrNull { it !is Block.Meta } as? Block.Para
        val title = meta["title"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: first?.takeIf { it.level in 1..2 }?.text?.replace(PICTURE_MARK, "")?.trim()
            ?: ""
        return Doc(out, title = title, nests = nests, anchors = anchors, outline = outline)
    }

    /** Add a block, inside [frames] and whatever folds are open. Returns its index. */
    fun emit(block: Block, frames: List<Frame>, names: List<String> = emptyList()): Int {
        // Content arriving under a `<details>` that never had a `<summary>` - or inside one
        // still open - means the fold's line is due now, before the content it holds.
        if (pendingFold != null) openFold(frames)
        val i = out.size
        for (a in pendingAnchors) anchors.putIfAbsent(a, i)
        pendingAnchors.clear()
        for (a in names) anchors.putIfAbsent(a, i)
        out.add(block)
        nests.add(Nest(frames, folds.toList()))
        return i
    }

    fun openFold(frames: List<Frame>) {
        val p = pendingFold ?: return
        pendingFold = null
        val title = summary?.done()
        summary = null
        title?.b?.trimEnd()
        val text = title?.b?.text()?.takeIf { it.isNotBlank() }
        val head = Block.Fold(p.id, text ?: "Details", if (text != null) title.b.spans() else emptyList(), p.open)
        emit(head, frames, title?.anchors.orEmpty())
        folds.add(p.id)
    }

    fun closeContainers() {
        if (pendingFold != null) openFold(emptyList())
        folds.clear()
        aligns.clear()
    }

    fun collectDefinitions(node: Node) {
        var c = node.firstChild
        while (c != null) {
            if (c is FootnoteDefinition) definitions.putIfAbsent(c.label, c) else collectDefinitions(c)
            c = c.next
        }
    }

    fun children(node: Node, frames: List<Frame>, depth: Int) {
        var c = node.firstChild
        while (c != null) {
            // Held before recursing: a reader that advances after the recursion walks the
            // tree twice when a handler replaces nodes, and commonmark's own visitors have
            // this exact note in them.
            val next = c.next
            block(c, frames, depth)
            c = next
        }
    }

    fun block(node: Node, frames: List<Frame>, depth: Int) {
        when (node) {
            is MdHeading -> heading(node, frames)
            is Paragraph -> {
                val inl = inline(node)
                para(inl, frames, level = 0)
            }
            is FencedCodeBlock -> emit(Block.Code(node.literal.trimEnd(), node.info.orEmpty().trim()), frames)
            is IndentedCodeBlock -> emit(Block.Code(node.literal.trimEnd()), frames)
            is ThematicBreak -> emit(Block.Rule, frames)
            is Alert -> {
                val frame = Frame.alertOf(node.type) ?: Frame.QUOTE
                if (frame.alert) emit(Block.AlertHead(frame), frames + frame)
                children(node, frames + frame, depth)
            }
            is BlockQuote -> children(node, frames + Frame.QUOTE, depth)
            is ListBlock -> list(node, frames, depth)
            is TableBlock -> table(node, frames)
            is HtmlBlock -> html(node.literal.orEmpty(), frames)
            // Drawn at the end, in the order they are first referenced. See [footnotes].
            is FootnoteDefinition -> Unit
            // Read once, up front, into a card. See [run].
            is YamlFrontMatterBlock -> Unit
            else -> children(node, frames, depth)
        }
    }

    fun heading(node: MdHeading, frames: List<Frame>) {
        val inl = inline(node)
        para(inl, frames, node.level)
    }

    fun para(inl: Inline, frames: List<Frame>, level: Int) {
        if (inl.blank) {
            pendingAnchors += inl.anchors
            return
        }
        val text = inl.b.text()
        val i = emit(
            Block.Para(
                text,
                level = level,
                spans = inl.b.spans(),
                quote = frames.contains(Frame.QUOTE),
                align = align,
                pictures = inl.pictures.toList(),
            ),
            frames,
            inl.anchors,
        )
        if (level > 0) {
            val plain = text.replace(PICTURE_MARK, "").trim()
            val slug = slugger.next(plain)
            anchors.putIfAbsent(slug, i)
            outline.add(Heading(level, plain, i, slug))
        }
    }

    /**
     * A list, flattened.
     *
     * Each item is a block carrying its depth; what else is inside the item - a second
     * paragraph, a code fence, a nested list - follows it one [Frame.INDENT] in, so it lines up
     * with the item's text rather than its marker. That is what nesting looks like on paper.
     */
    fun list(list: ListBlock, frames: List<Frame>, depth: Int) {
        val ordered = list is OrderedList
        val start = (list as? OrderedList)?.markerStartNumber ?: 1
        val delimiter = (list as? OrderedList)?.markerDelimiter ?: "."
        var count = 0
        var c = list.firstChild
        while (c != null) { if (c is ListItem) count++; c = c.next }
        val chars = if (ordered) "${start + count - 1}".length + delimiter.length else 0
        var number = start
        var item = list.firstChild
        while (item != null) {
            val nextItem = item.next
            if (item !is ListItem) { item = nextItem; continue }
            var inner = item.firstChild
            var task: Boolean? = null
            if (inner is TaskListItemMarker) {
                task = inner.isChecked
                inner = inner.next
            }
            val inl = if (inner is Paragraph) inline(inner).also { inner = inner?.next } else Inline()
            emit(
                Block.Item(
                    text = inl.b.text(),
                    depth = depth,
                    ordered = ordered,
                    number = if (ordered) number else 0,
                    spans = inl.b.spans(),
                    marker = if (ordered && delimiter != ".") "$number$delimiter" else "",
                    task = task ?: inl.task,
                    tight = list.isTight,
                    markerChars = chars,
                    pictures = inl.pictures.toList(),
                ),
                frames,
                inl.anchors,
            )
            while (inner != null) {
                val next = inner!!.next
                block(inner!!, frames + Frame.INDENT, depth + 1)
                inner = next
            }
            if (ordered) number++
            item = nextItem
        }
    }

    fun table(block: TableBlock, frames: List<Frame>) {
        val rows = ArrayList<List<String>>()
        val rich = ArrayList<List<Rich>>()
        val aligns = ArrayList<Align>()
        val names = ArrayList<String>()
        var hasHead = false
        var section = block.firstChild
        while (section != null) {
            if (section is TableHead) hasHead = true
            if (section is TableHead || section is TableBody) {
                var row = section.firstChild
                while (row != null) {
                    if (row is TableRow) {
                        val cells = ArrayList<String>()
                        val richRow = ArrayList<Rich>()
                        var cell = row.firstChild
                        while (cell != null) {
                            if (cell is TableCell) {
                                val inl = inline(cell)
                                cells.add(inl.b.text())
                                richRow.add(Rich(inl.b.spans(), inl.pictures.toList()))
                                names += inl.anchors
                                if (rows.isEmpty()) aligns.add(alignOf(cell.alignment))
                            }
                            cell = cell.next
                        }
                        rows.add(cells)
                        rich.add(richRow)
                    }
                    row = row.next
                }
            }
            section = section.next
        }
        emit(Block.Table(rows, header = hasHead, rich = rich, aligns = aligns), frames, names)
    }

    private fun alignOf(a: TableCell.Alignment?): Align = when (a) {
        TableCell.Alignment.CENTER -> Align.CENTER
        TableCell.Alignment.RIGHT -> Align.END
        else -> Align.START
    }

    /**
     * GitHub's footnotes: numbered by first reference, gathered at the foot after a rule, each
     * with a way back to where it was referenced. A definition nothing refers to is not drawn,
     * which is GitHub's rule too.
     */
    fun footnotes() {
        if (numbers.isEmpty()) return
        emit(Block.Rule, emptyList())
        for ((label, n) in numbers) {
            val def = definitions[label] ?: continue
            var inner = def.firstChild
            val inl = if (inner is Paragraph) inline(inner).also { inner = inner?.next } else Inline()
            val at = inl.b.length
            inl.b.append(" ↩")
            inl.b.mark(at + 1, Mark.LINK, "#fnref-$label")
            emit(
                Block.Item(
                    text = inl.b.text(),
                    ordered = true,
                    number = n,
                    spans = inl.b.spans(),
                    markerChars = "${numbers.size}.".length,
                    pictures = inl.pictures.toList(),
                ),
                emptyList(),
                listOf("fn-$label") + inl.anchors,
            )
            while (inner != null) {
                val next = inner!!.next
                block(inner!!, listOf(Frame.INDENT), 1)
                inner = next
            }
        }
    }

    /** One block's inline content into text, marks and pictures. */
    fun inline(node: Node): Inline = Inline().also { inlineInto(node, it) }.done().also { it.b.trimEnd() }

    fun inlineInto(node: Node, ctx: Inline) {
        var child = node.firstChild
        while (child != null) {
            val next = child.next
            if (ctx.skip != null) {
                if (child is HtmlInline) htmlInline(child.literal.orEmpty(), ctx)
                child = next
                continue
            }
            val at = ctx.b.length
            when (child) {
                is Text -> ctx.b.append(child.literal)
                is Code -> {
                    ctx.b.append(child.literal)
                    ctx.b.mark(at, Mark.CODE)
                }
                is StrongEmphasis -> {
                    inlineInto(child, ctx)
                    ctx.b.mark(at, Mark.BOLD)
                }
                is Emphasis -> {
                    inlineInto(child, ctx)
                    ctx.b.mark(at, Mark.ITALIC)
                }
                is Strikethrough -> {
                    inlineInto(child, ctx)
                    ctx.b.mark(at, Mark.STRIKE)
                }
                is Link -> {
                    val href = child.destination.orEmpty()
                    ctx.links.add(href)
                    inlineInto(child, ctx)
                    ctx.links.removeAt(ctx.links.lastIndex)
                    ctx.b.mark(at, Mark.LINK, href)
                }
                is SoftLineBreak -> ctx.b.append(" ")
                is HardLineBreak -> ctx.b.append("\n")
                is org.commonmark.node.Image -> {
                    val alt = Inline().also { inlineInto(child, it) }.b.text().replace(PICTURE_MARK, "")
                    picture(ctx, child.destination.orEmpty(), alt)
                }
                is HtmlInline -> htmlInline(child.literal.orEmpty(), ctx)
                is FootnoteReference -> footnoteRef(child.label, ctx)
                is TaskListItemMarker -> ctx.task = child.isChecked
                else -> inlineInto(child, ctx)
            }
            child = next
        }
    }

    fun picture(ctx: Inline, src: String, alt: String, width: Int = 0, height: Int = 0, percent: Int = 0) {
        if (src.isBlank()) return
        val at = ctx.b.length
        ctx.b.append(PICTURE_MARK)
        ctx.pictures.add(Picture(at, src.trim(), alt, width, height, percent, ctx.links.lastOrNull().orEmpty()))
    }

    fun footnoteRef(label: String, ctx: Inline) {
        val at = ctx.b.length
        if (label !in definitions) {
            // A reference to nothing is the text it was typed as, which is what GitHub shows.
            ctx.b.append("[^$label]")
            return
        }
        val first = label !in numbers
        val n = numbers.getOrPut(label) { numbers.size + 1 }
        ctx.b.append("$n")
        ctx.b.mark(at, Mark.SUPERSCRIPT)
        ctx.b.mark(at, Mark.LINK, "#fn-$label")
        if (first) ctx.anchors.add("fnref-$label")
    }

    /** One inline HTML tag, as commonmark hands them over: a tag at a time, text in between. */
    fun htmlInline(literal: String, ctx: Inline) {
        for (tok in HtmlBits.tokens(literal)) inlineTok(tok, ctx, collapse = false)
    }

    /**
     * A tag or text that can sit inside a line, into [ctx]. Returns false for a tag that is
     * not one of those, so a block-level caller can deal with it.
     */
    fun inlineTok(tok: HtmlBits.Tok, ctx: Inline, collapse: Boolean): Boolean {
        val skipping = ctx.skip
        if (skipping != null) {
            if (tok is HtmlBits.Close && tok.name == skipping) ctx.skip = null
            return true
        }
        when (tok) {
            is HtmlBits.Text -> if (collapse) appendCollapsed(ctx, tok.text) else ctx.b.append(tok.text)
            is HtmlBits.Open -> when (tok.name) {
                "script", "style", "template", "noscript", "title" -> if (!tok.closed) ctx.skip = tok.name
                "br" -> ctx.b.append("\n")
                "img" -> {
                    val (w, pct) = size(tok.attrs["width"])
                    val (h, _) = size(tok.attrs["height"])
                    picture(ctx, tok.attrs["src"].orEmpty(), tok.attrs["alt"].orEmpty(), w, h, pct)
                }
                "a" -> {
                    (tok.attrs["name"] ?: tok.attrs["id"])?.takeIf { it.isNotBlank() }?.let { ctx.anchors.add(it) }
                    val href = tok.attrs["href"].orEmpty()
                    if (href.isNotEmpty() && !tok.closed) {
                        ctx.links.add(href)
                        ctx.tags.add(Triple("a", ctx.b.length, href))
                    }
                }
                else -> {
                    if (markOf(tok.name) == null) return false
                    if (!tok.closed) ctx.tags.add(Triple(tok.name, ctx.b.length, ""))
                }
            }
            is HtmlBits.Close -> {
                if (tok.name != "a" && markOf(tok.name) == null) return false
                val i = ctx.tags.indexOfLast { it.first == tok.name }
                if (i >= 0) {
                    val (name, start, href) = ctx.tags.removeAt(i)
                    markOf(name)?.let { ctx.b.mark(start, it, href) }
                    if (name == "a") ctx.links.lastIndexOf(href).takeIf { it >= 0 }?.let { ctx.links.removeAt(it) }
                }
            }
        }
        return true
    }

    /** HTML's whitespace rule: any run is one space, and none at the start of a line. */
    private fun appendCollapsed(ctx: Inline, s: String) {
        val sb = StringBuilder(s.length)
        var last = ctx.b.last
        for (ch in s) {
            if (ch.isWhitespace() && ch != ' ') {
                if (last == null || last == ' ' || last == '\n') continue
                sb.append(' ')
                last = ' '
            } else {
                sb.append(ch)
                last = ch
            }
        }
        if (sb.isNotEmpty()) ctx.b.append(sb.toString())
    }

    /** `200`, `200px` or `50%`, as pixels and a percentage. */
    private fun size(raw: String?): Pair<Int, Int> {
        val s = raw?.trim().orEmpty()
        if (s.endsWith("%")) return 0 to (s.dropLast(1).trim().toFloatOrNull()?.toInt()?.coerceIn(1, 100) ?: 0)
        return (s.removeSuffix("px").trim().toFloatOrNull()?.toInt()?.coerceIn(0, 4000) ?: 0) to 0
    }

    /** A block of raw HTML: the subset in the class note, everything else as its text. */
    fun html(literal: String, frames: List<Frame>) {
        var cur = Inline()
        var level = 0
        var pre: StringBuilder? = null
        var headingAlign = false
        fun target(): Inline = summary ?: cur
        fun flush() {
            cur.done()
            cur.b.trimEnd()
            if (cur.blank) {
                pendingAnchors += cur.anchors
            } else {
                para(cur, frames, level)
            }
            cur = Inline()
        }
        for (tok in HtmlBits.tokens(literal)) {
            pre?.let { code ->
                when {
                    tok is HtmlBits.Close && tok.name == "pre" -> {
                        emit(Block.Code(code.toString().trim('\n').trimEnd()), frames)
                        pre = null
                    }
                    tok is HtmlBits.Text -> code.append(tok.text)
                    tok is HtmlBits.Open && tok.name == "br" -> code.append('\n')
                }
                continue
            }
            if (target().skip != null) {
                inlineTok(tok, target(), collapse = true)
                continue
            }
            when {
                tok is HtmlBits.Open && tok.name in setOf("p", "div", "center") -> {
                    flush()
                    val a = if (tok.name == "center") Align.CENTER else alignAttr(tok.attrs["align"]) ?: align
                    if (!tok.closed) aligns.add(tok.name to a)
                }
                tok is HtmlBits.Close && tok.name in setOf("p", "div", "center") -> {
                    flush()
                    val i = aligns.indexOfLast { it.first == tok.name }
                    if (i >= 0) aligns.removeAt(i)
                }
                tok is HtmlBits.Open && tok.name.length == 2 && tok.name[0] == 'h' && tok.name[1] in '1'..'6' -> {
                    flush()
                    level = tok.name[1] - '0'
                    headingAlign = alignAttr(tok.attrs["align"])?.let { aligns.add(tok.name to it) } != null
                }
                tok is HtmlBits.Close && tok.name.length == 2 && tok.name[0] == 'h' && tok.name[1] in '1'..'6' -> {
                    flush()
                    level = 0
                    if (headingAlign) {
                        val i = aligns.indexOfLast { it.first == tok.name }
                        if (i >= 0) aligns.removeAt(i)
                        headingAlign = false
                    }
                }
                tok is HtmlBits.Open && tok.name == "details" -> {
                    flush()
                    if (pendingFold != null) openFold(frames)
                    pendingFold = PendingFold(nextFold++, open = "open" in tok.attrs)
                }
                tok is HtmlBits.Open && tok.name == "summary" -> {
                    flush()
                    if (pendingFold != null) summary = Inline()
                }
                tok is HtmlBits.Close && tok.name == "summary" -> {
                    if (summary != null) openFold(frames)
                }
                tok is HtmlBits.Close && tok.name == "details" -> {
                    flush()
                    if (pendingFold != null) openFold(frames)
                    if (folds.isNotEmpty()) folds.removeAt(folds.lastIndex)
                }
                tok is HtmlBits.Open && tok.name == "hr" -> {
                    flush()
                    emit(Block.Rule, frames)
                }
                tok is HtmlBits.Open && tok.name == "pre" -> {
                    flush()
                    pre = StringBuilder()
                }
                (tok is HtmlBits.Open && tok.name in BREAKS) || (tok is HtmlBits.Close && tok.name in BREAKS) -> flush()
                // Anything else that is not a line-level tag is dropped and its text kept.
                else -> inlineTok(tok, target(), collapse = true)
            }
        }
        pre?.let { emit(Block.Code(it.toString().trim('\n').trimEnd()), frames) }
        flush()
        // A `<p>` left open ends with its block, as the browser's parser would end it at the
        // next block it met. A `<div>` does not: it is how a README wraps Markdown in a centre.
        aligns.removeAll { it.first == "p" || it.first.startsWith("h") }
    }

    private fun alignAttr(raw: String?): Align? = when (raw?.trim()?.lowercase()) {
        "center", "middle" -> Align.CENTER
        "right" -> Align.END
        "left" -> Align.START
        "justify" -> Align.JUSTIFY
        else -> null
    }
}

package dev.niccc2007.filet.docs

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Markdown into [Doc].
 *
 * ## Why a real parser, and why not through HTML
 *
 * CommonMark is a sixty-page specification. A hand-rolled subset gets lists inside lists,
 * fenced code inside a list item, reference links and the three different emphasis rules wrong
 * in ways that are found one complaint at a time, years apart. commonmark-java is the
 * reference implementation and is BSD-2-Clause, which is permissive and GPL-3 compatible.
 *
 * The usual Android route is markdown to HTML to a `WebView`, and it is the wrong one here.
 * It costs a whole browser engine on screen, it cannot be selected or searched with the rest
 * of the app's text, its theming is a second stylesheet that has to be kept in step with the
 * real one, and it means a local document gets to run script. Walking the AST into Compose
 * text costs a tree walk and renders with the app's own type.
 *
 * ## What is dropped, and why that is said out loud
 *
 * Images are named rather than fetched: a document can reference a remote URL, and a reader
 * that quietly reaches out to the network because a file asked it to is a worse surprise than
 * a missing picture. HTML blocks inside markdown are shown as code for the same reason -
 * rendering them would mean an HTML engine, which is the thing this avoids.
 */
object MarkdownReader {

    private val parser: Parser by lazy {
        Parser.builder()
            .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
            .build()
    }

    fun read(source: String): Doc {
        val root = runCatching { parser.parse(source) }.getOrNull()
            ?: return Doc(
                listOf(Block.Code(source)),
                note = "This could not be parsed as Markdown, so it is shown as text.",
            )
        val out = ArrayList<Block>()
        walk(root, out, depth = 0)
        // A first heading is the document's name far more often than not, and the chrome has
        // nowhere else to get one from - the filename is already in the path bar.
        val title = (out.firstOrNull() as? Block.Para)?.takeIf { it.level in 1..2 }?.text ?: ""
        return Doc(out, title = title)
    }

    private fun walk(node: Node, out: ArrayList<Block>, depth: Int, quote: Boolean = false) {
        var child = node.firstChild
        while (child != null) {
            // Held before recursing: a reader that advances after the recursion walks the
            // tree twice when a handler replaces nodes, and commonmark's own visitors have
            // this exact note in them.
            val next = child.next
            when (child) {
                is Heading -> {
                    val b = SpanBuilder()
                    inline(child, b, 0)
                    out.add(Block.Para(b.text(), level = child.level, spans = b.spans()))
                }

                is Paragraph -> {
                    val b = SpanBuilder()
                    inline(child, b, 0)
                    if (!b.isBlank()) {
                        out.add(Block.Para(b.text(), spans = b.spans(), quote = quote))
                    }
                }

                is FencedCodeBlock -> out.add(Block.Code(child.literal.trimEnd(), child.info ?: ""))
                is IndentedCodeBlock -> out.add(Block.Code(child.literal.trimEnd()))
                is ThematicBreak -> out.add(Block.Rule)
                is BlockQuote -> walk(child, out, depth, quote = true)
                is ListBlock -> listItems(child, out, depth)
                is TableBlock -> out.add(table(child))
                else -> walk(child, out, depth, quote)
            }
            child = next
        }
    }

    /**
     * A list, flattened.
     *
     * The depth is carried on each item rather than the structure being kept, because the
     * screen draws a flat lazy list - see `DocScreen`. Nesting is therefore an indent, which
     * is what it looks like on paper anyway.
     */
    private fun listItems(list: ListBlock, out: ArrayList<Block>, depth: Int) {
        val ordered = list is OrderedList
        var number = if (list is OrderedList) list.markerStartNumber ?: 1 else 0
        var item = list.firstChild
        while (item != null) {
            val next = item.next
            // An item's own text is its first paragraph; anything else inside it - a nested
            // list, a code fence - is walked after, one level deeper.
            val b = SpanBuilder()
            var inner = item.firstChild
            val tail = ArrayList<Block>()
            while (inner != null) {
                val innerNext = inner.next
                when (inner) {
                    is Paragraph -> if (b.isBlank()) inline(inner, b, 0) else {
                        val extra = SpanBuilder()
                        inline(inner, extra, 0)
                        tail.add(Block.Para(extra.text(), spans = extra.spans()))
                    }
                    is ListBlock -> listItems(inner, tail, depth + 1)
                    is FencedCodeBlock -> tail.add(Block.Code(inner.literal.trimEnd(), inner.info ?: ""))
                    is IndentedCodeBlock -> tail.add(Block.Code(inner.literal.trimEnd()))
                    else -> walk(inner, tail, depth + 1)
                }
                inner = innerNext
            }
            out.add(
                Block.Item(
                    text = b.text(),
                    depth = depth,
                    ordered = ordered,
                    number = number,
                    spans = b.spans(),
                ),
            )
            out.addAll(tail)
            if (ordered) number++
            item = next
        }
    }

    private fun table(block: TableBlock): Block.Table {
        val rows = ArrayList<List<String>>()
        var hasHead = false
        var section = block.firstChild
        while (section != null) {
            if (section is TableHead) hasHead = true
            if (section is TableHead || section is TableBody) {
                var row = section.firstChild
                while (row != null) {
                    if (row is TableRow) {
                        val cells = ArrayList<String>()
                        var cell = row.firstChild
                        while (cell != null) {
                            if (cell is TableCell) {
                                val b = SpanBuilder()
                                inline(cell, b, 0)
                                cells.add(b.text())
                            }
                            cell = cell.next
                        }
                        rows.add(cells)
                    }
                    row = row.next
                }
            }
            section = section.next
        }
        return Block.Table(rows, header = hasHead)
    }

    /**
     * Inline content into text plus marks.
     *
     * @param start where this node's text begins, for the recursion - a mark's range is from
     *   wherever the builder was when the node opened to wherever it is when it closes.
     */
    private fun inline(node: Node, b: SpanBuilder, start: Int) {
        var child = node.firstChild
        while (child != null) {
            val next = child.next
            val at = b.length
            when (child) {
                is Text -> b.append(child.literal)
                is Code -> {
                    b.append(child.literal)
                    b.mark(at, Mark.CODE)
                }
                is StrongEmphasis -> {
                    inline(child, b, at)
                    b.mark(at, Mark.BOLD)
                }
                is Emphasis -> {
                    inline(child, b, at)
                    b.mark(at, Mark.ITALIC)
                }
                is Strikethrough -> {
                    inline(child, b, at)
                    b.mark(at, Mark.STRIKE)
                }
                is Link -> {
                    inline(child, b, at)
                    b.mark(at, Mark.LINK, child.destination ?: "")
                }
                is SoftLineBreak -> b.append(" ")
                is HardLineBreak -> b.append("\n")
                // An image is NAMED, never fetched. A document can point at a remote URL, and
                // a reader that makes a network request because a file asked it to is a worse
                // surprise than a missing picture.
                is org.commonmark.node.Image -> {
                    val alt = SpanBuilder()
                    inline(child, alt, 0)
                    val label = alt.text().ifBlank { child.destination ?: "image" }
                    b.append("[$label]")
                    b.mark(at, Mark.CODE)
                }
                // Raw HTML stays raw, for the same reason: drawing it would mean an HTML
                // engine, which is precisely what walking the AST avoids.
                is org.commonmark.node.HtmlInline -> {
                    b.append(child.literal)
                    b.mark(at, Mark.CODE)
                }
                else -> inline(child, b, at)
            }
            child = next
        }
    }
}

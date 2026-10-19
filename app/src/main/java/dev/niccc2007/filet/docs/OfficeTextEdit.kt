package dev.niccc2007.filet.docs

import org.xml.sax.helpers.DefaultHandler

/**
 * Text-level editing of a DOCX body or a PPTX slide, by splicing the original XML.
 *
 * ## Why splicing and not regenerating
 *
 * The reader's [Doc] is lossy by design. Writing a document back from it would compile, pass a
 * one-paragraph test, and silently delete every style, picture, table property, header and
 * relationship the model does not carry. So nothing here builds XML: [scan] finds where each
 * paragraph's text lives in the original string, and [apply] replaces the characters inside
 * those `t` elements and nothing else. Every byte outside a changed text node is the input's.
 *
 * ## Where new text goes
 *
 * A paragraph's text is split across runs, one per change of formatting. An edit is reduced to
 * the one changed region (common prefix and suffix removed), so text outside the region keeps
 * its run and therefore its formatting. Inserted text joins the run it was typed into - the one
 * ending where the insertion starts - which is what Word does with a caret.
 *
 * ## What it refuses, rather than gets wrong
 *
 * Line breaks and tabs are elements, not characters; an edit that would remove one or type a new
 * one is refused for that paragraph. A text node holding CDATA, a comment or an entity outside
 * the five XML defines is left read-only. A DOCTYPE refuses the whole part, as the reader does.
 * The fallback half of a compatibility block is not offered: Word renders the choice half.
 */
internal object OfficeTextEdit {

    enum class Flavor { WORD, SLIDES }

    /** One `t` element's content: [start, end) in the XML, already decoded to [text]. */
    class TextNode(val start: Int, val end: Int, val text: String, val tagEnd: Int, val preserve: Boolean)

    sealed interface Piece {
        val text: String
        class Text(val node: TextNode) : Piece { override val text get() = node.text }

        /** A break, tab or field that reads as text but is not editable text. */
        class Fixed(override val text: String) : Piece
    }

    class Paragraph(val index: Int, val pieces: List<Piece>, val editable: Boolean) {
        val text: String = pieces.joinToString("") { it.text }
        val hasText: Boolean get() = pieces.any { it is Piece.Text }
    }

    sealed interface Result {
        class Ok(val xml: String) : Result
        class Refused(val paragraph: Int, val why: String) : Result
    }

    private class Builder(val index: Int) {
        val pieces = ArrayList<Piece>()
        var editable = true
    }

    /** Every paragraph in document order, including empty ones, so indexes are stable. */
    fun scan(xml: String, flavor: Flavor): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        val open = ArrayList<Builder>()
        val stack = ArrayList<String>()
        var count = 0
        var pendingStart = -1
        var pendingTagEnd = -1
        var pendingPreserve = false
        var i = 0
        fun current() = open.lastOrNull()
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            when {
                xml.startsWith("<!--", lt) -> {
                    if (pendingStart >= 0) current()?.editable = false
                    i = xml.indexOf("-->", lt).let { if (it < 0) xml.length else it + 3 }
                    continue
                }
                xml.startsWith("<![CDATA[", lt) -> {
                    if (pendingStart >= 0) current()?.editable = false
                    i = xml.indexOf("]]>", lt).let { if (it < 0) xml.length else it + 3 }
                    continue
                }
                xml.startsWith("<?", lt) -> {
                    i = xml.indexOf("?>", lt).let { if (it < 0) xml.length else it + 2 }
                    continue
                }
                xml.startsWith("<!", lt) -> throw IllegalArgumentException("A DOCTYPE in a document part is refused.")
            }
            val gt = tagEnd(xml, lt)
            val closing = xml[lt + 1] == '/'
            val selfClosing = xml[gt - 2] == '/'
            val nameStart = if (closing) lt + 2 else lt + 1
            var nameEnd = nameStart
            while (nameEnd < gt && !xml[nameEnd].isWhitespace() && xml[nameEnd] != '/' && xml[nameEnd] != '>') nameEnd++
            val qname = xml.substring(nameStart, nameEnd)
            val local = qname.substringAfter(':')
            val skipping = "Fallback" in stack

            if (closing) {
                if (pendingStart >= 0) {
                    if (local == "t") {
                        val raw = xml.substring(pendingStart, lt)
                        val decoded = decode(raw)
                        if (decoded == null) {
                            current()?.editable = false
                            current()?.pieces?.add(Piece.Fixed(raw))
                        } else {
                            current()?.pieces?.add(Piece.Text(TextNode(pendingStart, lt, decoded, pendingTagEnd, pendingPreserve)))
                        }
                    } else {
                        current()?.editable = false
                    }
                    pendingStart = -1
                }
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                if (local == "p" && !skipping) {
                    val b = open.removeLastOrNull()
                    if (b != null) out.add(Paragraph(b.index, b.pieces, b.editable))
                }
            } else {
                if (pendingStart >= 0) {
                    // An element inside a text node. Not something these formats write.
                    current()?.editable = false
                    pendingStart = -1
                }
                val parent = stack.lastOrNull()
                if (!skipping) {
                    val b = current()
                    when (local) {
                        "p" -> if (!selfClosing) open.add(Builder(count++)) else out.add(Paragraph(count++, emptyList(), false))
                        "t" -> when {
                            parent == "r" && !selfClosing && b != null -> {
                                pendingStart = gt
                                pendingTagEnd = gt
                                pendingPreserve = xml.substring(lt, gt).contains("xml:space=\"preserve\"")
                            }
                            parent == "fld" && !selfClosing && b != null -> {
                                // Slide number, date: drawn as text, recomputed by PowerPoint.
                                val close = xml.indexOf('<', gt)
                                if (close > gt) b.pieces.add(Piece.Fixed(decode(xml.substring(gt, close)) ?: ""))
                            }
                        }
                        "tab" -> if (parent == "r" && flavor == Flavor.WORD) b?.pieces?.add(Piece.Fixed("\t"))
                        "br" -> when {
                            flavor == Flavor.WORD && parent == "r" -> b?.pieces?.add(Piece.Fixed("\n"))
                            flavor == Flavor.SLIDES && parent == "p" -> b?.pieces?.add(Piece.Fixed("\n"))
                        }
                        "cr" -> if (parent == "r" && flavor == Flavor.WORD) b?.pieces?.add(Piece.Fixed("\n"))
                        "noBreakHyphen" -> if (parent == "r" && flavor == Flavor.WORD) b?.pieces?.add(Piece.Fixed("-"))
                    }
                }
                if (!selfClosing) stack.add(local)
            }
            i = gt
        }
        // Paragraphs left open mean a truncated part. None of them is offered for editing.
        for (b in open) out.add(Paragraph(b.index, b.pieces, false))
        return out.sortedBy { it.index }
    }

    /**
     * Rewrite the text of the paragraphs in [edits] (paragraph index to new text).
     *
     * The result is checked before it is returned: it must parse, and re-scanning it must give
     * exactly the intended text for every paragraph, edited or not. A splice that went wrong is a
     * [Result.Refused], never a document.
     */
    fun apply(xml: String, flavor: Flavor, edits: Map<Int, String>): Result {
        val paragraphs = runCatching { scan(xml, flavor) }.getOrElse { return Result.Refused(-1, it.message ?: "Unreadable part.") }
        val byIndex = paragraphs.associateBy { it.index }
        val splices = ArrayList<Triple<Int, Int, String>>()
        for ((index, wanted) in edits) {
            val p = byIndex[index] ?: return Result.Refused(index, "That paragraph is no longer in the file.")
            if (wanted == p.text) continue
            if (!p.editable) return Result.Refused(index, "This paragraph holds content that can't be edited here.")
            splices += plan(p, wanted, flavor) ?: return Result.Refused(index, refusalFor(p, wanted))
        }
        if (splices.isEmpty()) return Result.Ok(xml)
        val out = StringBuilder(xml)
        for ((start, end, text) in splices.sortedByDescending { it.first }) out.replace(start, end, text)
        val written = out.toString()

        // The check. Well-formed, and every paragraph says what it should.
        val parsed = runCatching { Ooxml.parse(written, DefaultHandler()) }
        if (parsed.isFailure) return Result.Refused(-1, "The edited text did not produce a valid document.")
        val after = runCatching { scan(written, flavor) }.getOrNull()
            ?: return Result.Refused(-1, "The edited text did not produce a valid document.")
        if (after.size != paragraphs.size) return Result.Refused(-1, "The edit changed the document's structure.")
        for (p in after) {
            val expected = edits[p.index] ?: byIndex.getValue(p.index).text
            if (p.text != expected) return Result.Refused(p.index, "The edit could not be placed exactly.")
        }
        return Result.Ok(written)
    }

    private fun refusalFor(p: Paragraph, wanted: String): String = when {
        wanted.contains('\n') || wanted.contains('\t') ->
            "New line breaks and tabs inside a paragraph can't be added here."
        !p.hasText -> "This paragraph has no text to change."
        else -> "Line breaks, tabs and fields inside a paragraph can't be removed here."
    }

    /** The splices that turn [p]'s text into [wanted], or null when the edit is refused. */
    fun plan(p: Paragraph, wanted: String, flavor: Flavor): List<Triple<Int, Int, String>>? {
        val old = p.text
        var pre = 0
        while (pre < old.length && pre < wanted.length && old[pre] == wanted[pre]) pre++
        var suf = 0
        while (suf < old.length - pre && suf < wanted.length - pre &&
            old[old.length - 1 - suf] == wanted[wanted.length - 1 - suf]
        ) suf++
        val from = pre
        val to = old.length - suf
        val inserted = wanted.substring(pre, wanted.length - suf)
        if (inserted.contains('\n') || inserted.contains('\t')) return null

        // Where each piece sits in the paragraph's text.
        val starts = IntArray(p.pieces.size)
        var at = 0
        for ((k, piece) in p.pieces.withIndex()) { starts[k] = at; at += piece.text.length }

        // A fixed piece inside the changed region would have to be deleted: refused.
        for ((k, piece) in p.pieces.withIndex()) {
            val s = starts[k]
            val e = s + piece.text.length
            if (piece is Piece.Fixed && s < to && e > from) return null
        }

        // The run that receives the inserted text: the one the region starts in, else the one
        // ending at the insertion point, else the one starting there.
        val texts = p.pieces.indices.filter { p.pieces[it] is Piece.Text }
        val anchor = texts.firstOrNull { starts[it] <= from && from < starts[it] + p.pieces[it].text.length && from < to }
            ?: texts.firstOrNull { from > starts[it] && from == starts[it] + p.pieces[it].text.length }
            ?: texts.firstOrNull { starts[it] == from }
            ?: return null

        val out = ArrayList<Triple<Int, Int, String>>()
        for (k in texts) {
            val node = (p.pieces[k] as Piece.Text).node
            val s = starts[k]
            val e = s + node.text.length
            val overlaps = s < to && e > from
            if (!overlaps && k != anchor) continue
            val cutFrom = (from - s).coerceIn(0, node.text.length)
            val cutTo = (to - s).coerceIn(0, node.text.length)
            val next = node.text.substring(0, cutFrom) + (if (k == anchor) inserted else "") + node.text.substring(cutTo)
            if (next == node.text) continue
            out += Triple(node.start, node.end, encode(next))
            // Word drops leading and trailing spaces from a `w:t` unless told to keep them.
            val edgeSpace = next.isNotEmpty() && (next.first() == ' ' || next.last() == ' ')
            if (flavor == Flavor.WORD && edgeSpace && !node.preserve) {
                out += Triple(node.tagEnd - 1, node.tagEnd - 1, " xml:space=\"preserve\"")
            }
        }
        return out
    }

    /** The index of the `>` that ends the tag opening at [lt], plus one. Quotes may hold `>`. */
    private fun tagEnd(xml: String, lt: Int): Int {
        var quote = NO_QUOTE
        var k = lt + 1
        while (k < xml.length) {
            val c = xml[k]
            if (quote != NO_QUOTE) {
                if (c == quote) quote = NO_QUOTE
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return k + 1
            }
            k++
        }
        throw IllegalArgumentException("An unterminated tag.")
    }

    private const val NO_QUOTE = ' '

    /** XML character data to text, or null for an entity this cannot round-trip. */
    fun decode(raw: String): String? {
        if (raw.indexOf('&') < 0) return raw
        val out = StringBuilder()
        var k = 0
        while (k < raw.length) {
            val c = raw[k]
            if (c != '&') { out.append(c); k++; continue }
            val semi = raw.indexOf(';', k)
            if (semi < 0) return null
            val name = raw.substring(k + 1, semi)
            when {
                name == "amp" -> out.append('&')
                name == "lt" -> out.append('<')
                name == "gt" -> out.append('>')
                name == "quot" -> out.append('"')
                name == "apos" -> out.append('\'')
                name.startsWith("#x") -> out.appendCodePoint(name.substring(2).toIntOrNull(16) ?: return null)
                name.startsWith("#") -> out.appendCodePoint(name.substring(1).toIntOrNull() ?: return null)
                else -> return null
            }
            k = semi + 1
        }
        return out.toString()
    }

    /** Text to XML character data. Control characters XML 1.0 forbids are dropped. */
    fun encode(text: String): String {
        val out = StringBuilder(text.length + 8)
        for (c in text) {
            when {
                c == '&' -> out.append("&amp;")
                c == '<' -> out.append("&lt;")
                c == '>' -> out.append("&gt;")
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> Unit
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}

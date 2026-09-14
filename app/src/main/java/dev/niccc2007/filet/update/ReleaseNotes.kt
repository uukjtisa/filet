package dev.niccc2007.filet.update

// A parser for GitHub release bodies.
//
// PORTED FROM TRAWL (github.com/uukjtisa/trawl, app/src/main/java/com/junkfood/seal/util/
// ReleaseNotes.kt), which is mine and is GPL-3.0 like this repo. Copied rather than rewritten
// because it is the same problem with the same answer, and a second implementation would just
// be a second set of bugs. The tests came with it.
//
// The update sheet used to hand `release.body` straight to a Text(), so the reader got the raw
// source: `**First release.**`, `### What is in it` and `- **Tabs and panes.**` printed as
// literal characters, with the author's hard line breaks left ragged on a phone screen. The
// release notes are the one piece of writing in this app that somebody reads before deciding to
// trust an APK, and they were the worst-looking thing in it.
//
// A release body is Markdown with HTML mixed in -- GitHub renders both, so bodies use both. The
// subset that actually appears is small and known, because we write these bodies ourselves:
// headings, paragraphs, bullet lists, block quotes, fenced code, horizontal rules, two-column
// tables, centred image rows, and inline bold / italic / code / links.
//
// WHY NOT A MARKDOWN LIBRARY. The usual answer is Markwon, which is a View-based renderer bridged
// into Compose through AndroidView. It would drag in an HTML plugin, an image plugin and a
// theming layer that fights this app's palette, to render a document shape we control completely.
// This is ~250 lines with no dependency and it draws in the app's own type and colour.
//
// This file is deliberately free of Compose so it can be tested on the JVM. See ReleaseNotesTest.

/** One stretch of text carrying a single set of styles. */
data class NoteRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

data class NoteImage(val url: String, val alt: String = "")

sealed interface NoteBlock {
    data class Heading(val level: Int, val spans: List<NoteRun>) : NoteBlock

    data class Paragraph(val spans: List<NoteRun>) : NoteBlock

    /** [marker] is "-" for an unordered item, or the literal "1." / "2." of an ordered one. */
    data class Bullet(val marker: String, val spans: List<NoteRun>) : NoteBlock

    data class Quote(val spans: List<NoteRun>) : NoteBlock

    data class CodeBlock(val text: String) : NoteBlock

    /** Consecutive images, drawn side by side. Release bodies use these as a screenshot strip. */
    data class ImageRow(val images: List<NoteImage>) : NoteBlock

    data class Table(
        val header: List<List<NoteRun>>,
        val rows: List<List<List<NoteRun>>>,
    ) : NoteBlock

    data object Rule : NoteBlock
}

private data class Style(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

object ReleaseNotes {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val RULE = Regex("^\\s*([-*_])\\1{2,}\\s*$")
    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val IMG = Regex("<img\\s[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([a-zA-Z-]+)\\s*=\\s*\"([^\"]*)\"")

    /** A table's separator line: `|---|---|`, optionally with alignment colons. */
    private val TABLE_SEP = Regex("^\\s*\\|?[\\s:|-]*-[\\s:|-]*\\|?\\s*$")

    fun parse(body: String): List<NoteBlock> {
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val out = ArrayList<NoteBlock>()
        val para = ArrayList<String>()

        // The list item still open, if any: its marker and the text collected so far.
        //
        // Held rather than emitted immediately because of markdown's lazy continuation - a
        // bullet that soft-wraps onto the next source line is still ONE bullet. Emitting on
        // sight meant the wrapped half broke out as its own paragraph, unindented and without
        // the dot, which is exactly what a release body written to 100 columns looks like.
        var bulletMarker: String? = null
        val bulletText = ArrayList<String>()

        fun flushBullet() {
            val marker = bulletMarker ?: return
            bulletMarker = null
            val text = bulletText.joinToString(" ").trim()
            bulletText.clear()
            if (text.isNotEmpty()) out += NoteBlock.Bullet(marker, inline(text))
        }

        fun flushParagraph() {
            flushBullet()
            if (para.isEmpty()) return
            // Markdown joins soft-wrapped lines into one paragraph. Doing that here rather than
            // in the renderer means the text wraps to the dialog's width instead of keeping the
            // author's line breaks, which on a phone would be ragged.
            val text = para.joinToString(" ").trim()
            para.clear()
            if (text.isNotEmpty()) out += NoteBlock.Paragraph(inline(text))
        }

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()

            // ---- fenced code -------------------------------------------------------------
            if (line.startsWith("```") || line.startsWith("~~~")) {
                flushParagraph()
                val fence = line.take(3)
                val buf = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                    buf += lines[i]
                    i++
                }
                i++ // the closing fence
                out += NoteBlock.CodeBlock(buf.joinToString("\n").trimEnd())
                continue
            }

            // ---- images ------------------------------------------------------------------
            // Both the HTML form GitHub bodies use inside a centred div, and Markdown's own.
            // Consecutive image lines become ONE row: that is what a screenshot strip is, and
            // stacking three phone screenshots vertically would push the notes off the screen.
            if (IMG.containsMatchIn(line) || line.startsWith("![")) {
                flushParagraph()
                val images = ArrayList<NoteImage>()
                while (i < lines.size) {
                    val l = lines[i].trim()
                    val found = imagesIn(l)
                    if (found.isEmpty()) break
                    images += found
                    i++
                }
                if (images.isNotEmpty()) out += NoteBlock.ImageRow(images)
                continue
            }

            // ---- table -------------------------------------------------------------------
            if (
                line.startsWith("|") &&
                    i + 1 < lines.size &&
                    TABLE_SEP.matches(lines[i + 1].trim()) &&
                    lines[i + 1].contains('-')
            ) {
                flushParagraph()
                val header = cells(line)
                i += 2
                val rows = ArrayList<List<List<NoteRun>>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows += cells(lines[i].trim())
                    i++
                }
                out += NoteBlock.Table(header, rows)
                continue
            }

            // ---- blank -------------------------------------------------------------------
            if (line.isEmpty()) {
                flushParagraph()
                i++
                continue
            }

            // ---- a bare block-level HTML tag ---------------------------------------------
            // `<div align="center">` and its closing tag carry layout that this renderer applies
            // by other means. Dropping them is the whole point: printing them was the bug.
            if (isBareTag(line)) {
                flushParagraph()
                i++
                continue
            }

            val heading = HEADING.matchEntire(line)
            val ordered = ORDERED.matchEntire(line)
            val bullet = BULLET.matchEntire(line)

            when {
                heading != null -> {
                    flushParagraph()
                    out +=
                        NoteBlock.Heading(
                            level = heading.groupValues[1].length,
                            spans = inline(heading.groupValues[2].trim()),
                        )
                    i++
                }

                RULE.matches(line) -> {
                    flushParagraph()
                    out += NoteBlock.Rule
                    i++
                }

                QUOTE.matches(line) -> {
                    flushParagraph()
                    val buf = ArrayList<String>()
                    while (i < lines.size) {
                        val m = QUOTE.matchEntire(lines[i].trim()) ?: break
                        buf += m.groupValues[1]
                        i++
                    }
                    out += NoteBlock.Quote(inline(buf.joinToString(" ").trim()))
                }

                ordered != null -> {
                    flushParagraph()
                    bulletMarker = ordered.groupValues[1] + "."
                    bulletText += ordered.groupValues[2].trim()
                    i++
                }

                bullet != null -> {
                    flushParagraph()
                    bulletMarker = "-"
                    bulletText += bullet.groupValues[1].trim()
                    i++
                }

                // A plain line directly under a list item continues it. Everything that could
                // END the item - a blank line, a heading, a rule, a quote, a fence, a table, a
                // new bullet - was matched above and has already flushed it.
                bulletMarker != null -> {
                    bulletText += line
                    i++
                }

                else -> {
                    para += line
                    i++
                }
            }
        }
        flushParagraph()
        return out
    }

    // ------------------------------------------------------------------------------ helpers

    private fun imagesIn(line: String): List<NoteImage> {
        val out = ArrayList<NoteImage>()
        IMG.findAll(line).forEach { m ->
            val attrs = ATTR.findAll(m.value).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val src = attrs["src"]?.trim()
            if (!src.isNullOrEmpty()) out += NoteImage(src, unescape(attrs["alt"].orEmpty()))
        }
        // Markdown's own `![alt](url)`.
        Regex("!\\[([^\\]]*)]\\(([^)\\s]+)[^)]*\\)").findAll(line).forEach { m ->
            out += NoteImage(m.groupValues[2].trim(), unescape(m.groupValues[1]))
        }
        return out
    }

    /** True when the line is nothing but one HTML tag, e.g. `<div align="center">` or `</div>`. */
    private fun isBareTag(line: String): Boolean =
        line.startsWith("<") && line.endsWith(">") && Regex("^</?[a-zA-Z][^<>]*>$").matches(line)

    private fun cells(line: String): List<List<NoteRun>> =
        line.trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split("|")
            .map { inline(it.trim()) }

    // ------------------------------------------------------------------------------- inline

    /**
     * Turn one line of Markdown-with-HTML into styled runs.
     *
     * Nesting is handled by recursion rather than a style stack: on an opening delimiter the inner
     * text is parsed again with the extra style applied. That is what makes `**[JunkFood02](url)**`
     * come out bold AND linked, which a flat scan gets wrong.
     *
     * Every delimiter is matched before it is honoured. An unpaired `*` is just an asterisk, and
     * printing it is better than swallowing the rest of the line into an italic that never closes.
     */
    private fun inline(src: String, style: Style = Style()): List<NoteRun> {
        val out = ArrayList<NoteRun>()
        val buf = StringBuilder()

        fun flush() {
            if (buf.isNotEmpty()) {
                out += NoteRun(unescape(buf.toString()), style.bold, style.italic, style.code, style.link)
                buf.clear()
            }
        }

        var i = 0
        while (i < src.length) {
            val c = src[i]

            // `code` -- wins over everything, because its content is literal by definition.
            if (c == '`') {
                val end = src.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    out +=
                        NoteRun(
                            unescape(src.substring(i + 1, end)),
                            style.bold,
                            style.italic,
                            code = true,
                            link = style.link,
                        )
                    i = end + 1
                    continue
                }
            }

            // **bold** / __bold__
            if ((src.startsWith("**", i) || src.startsWith("__", i))) {
                val token = src.substring(i, i + 2)
                val end = src.indexOf(token, i + 2)
                if (end > i + 2) {
                    flush()
                    out += inline(src.substring(i + 2, end), style.copy(bold = true))
                    i = end + 2
                    continue
                }
            }

            // *italic* / _italic_
            if (c == '*' || c == '_') {
                val end = src.indexOf(c, i + 1)
                // An underscore inside a word (some_field_name) is not emphasis, and a delimiter
                // followed by a space is an unpaired one that happens to have a partner later.
                val insideWord =
                    c == '_' &&
                        ((i > 0 && src[i - 1].isLetterOrDigit()) ||
                            (end in 0 until src.length - 1 && src[end + 1].isLetterOrDigit()))
                if (end > i + 1 && src[i + 1] != ' ' && src[end - 1] != ' ' && !insideWord) {
                    flush()
                    out += inline(src.substring(i + 1, end), style.copy(italic = true))
                    i = end + 1
                    continue
                }
            }

            // [text](url)
            if (c == '[') {
                val close = matching(src, i, '[', ']')
                if (close > 0 && close + 1 < src.length && src[close + 1] == '(') {
                    val paren = matching(src, close + 1, '(', ')')
                    if (paren > 0) {
                        flush()
                        val url = src.substring(close + 2, paren).trim().substringBefore(' ')
                        out += inline(src.substring(i + 1, close), style.copy(link = url))
                        i = paren + 1
                        continue
                    }
                }
            }

            // inline HTML
            if (c == '<') {
                val gt = src.indexOf('>', i)
                if (gt > i) {
                    val tag = src.substring(i + 1, gt).trim()
                    val name = tag.substringBefore(' ').removeSuffix("/").lowercase()
                    val inner: Style? =
                        when (name) {
                            "b", "strong" -> style.copy(bold = true)
                            "i", "em" -> style.copy(italic = true)
                            "code" -> style.copy(code = true)
                            "a" -> {
                                val href =
                                    ATTR.findAll(tag)
                                        .firstOrNull { it.groupValues[1].equals("href", true) }
                                        ?.groupValues
                                        ?.get(2)
                                style.copy(link = href ?: style.link)
                            }
                            else -> null
                        }
                    if (name == "br") {
                        flush()
                        out += NoteRun("\n", style.bold, style.italic, style.code, style.link)
                        i = gt + 1
                        continue
                    }
                    if (inner != null && !tag.startsWith("/")) {
                        val closeTag = "</$name>"
                        val end = src.indexOf(closeTag, gt + 1, ignoreCase = true)
                        if (end > gt) {
                            flush()
                            out += inline(src.substring(gt + 1, end), inner)
                            i = end + closeTag.length
                            continue
                        }
                    }
                    // Anything else -- an unclosed tag, a closing tag, a tag we do not draw --
                    // is dropped rather than printed. Printing it is the bug this file fixes.
                    // The test is on the tag BODY, which no longer carries its angle brackets:
                    // a letter or a slash after the '<' is enough to call it a tag.
                    if (tag.isNotEmpty() && (tag[0].isLetter() || tag[0] == '/')) {
                        flush()
                        i = gt + 1
                        continue
                    }
                }
            }

            buf.append(c)
            i++
        }
        flush()
        return out.filter { it.text.isNotEmpty() }
    }

    /** Index of the [close] that matches the [open] at [from], honouring nesting. -1 if none. */
    private fun matching(src: String, from: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = from
        while (i < src.length) {
            when (src[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun unescape(s: String): String =
        if ('&' !in s) s
        else
            s.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&mdash;", "\u2014")
                .replace("&ndash;", "\u2013")
                // Ampersand last: doing it first would turn `&amp;lt;` into a `<`.
                .replace("&amp;", "&")
}

package dev.niccc2007.filet.docs

/**
 * The HTML that turns up inside Markdown, as tags and text - nothing more.
 *
 * ## Why not an HTML parser
 *
 * What a README carries is a few tags on their own lines: a centred `<p>` around a logo, a
 * `<details>` fold, a `<br>`, a badge `<img>`. The Markdown reader draws a short list of those
 * (see `MarkdownReader`) and drops the tag on everything else, keeping its text. That needs to
 * know where each tag starts and ends and what its attributes say, which a tokenizer does in a
 * page; a parser that builds a DOM would be built only to be walked back into this list.
 *
 * Tolerant by construction, because the input is whatever somebody typed: a `<` that does not
 * start a tag is text, an unterminated tag is text, attributes may be quoted either way or not
 * at all, and names are case-insensitive.
 */
internal object HtmlBits {

    sealed interface Tok

    /** An opening tag. [name] is lower-case; so are the attribute names. */
    data class Open(val name: String, val attrs: Map<String, String> = emptyMap(), val closed: Boolean = false) : Tok

    data class Close(val name: String) : Tok

    /** Text between tags, with its entities decoded and its whitespace as written. */
    data class Text(val text: String) : Tok

    fun tokens(html: String): List<Tok> {
        val out = ArrayList<Tok>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) out.add(Text(decode(text.toString())))
            text.setLength(0)
        }
        var i = 0
        val n = html.length
        while (i < n) {
            val c = html[i]
            if (c != '<') {
                text.append(c)
                i++
                continue
            }
            when {
                html.startsWith("<!--", i) -> {
                    flush()
                    val end = html.indexOf("-->", i + 4)
                    i = if (end < 0) n else end + 3
                }
                // A doctype, a CDATA section, a processing instruction: nothing to draw.
                html.startsWith("<!", i) || html.startsWith("<?", i) -> {
                    flush()
                    val end = html.indexOf('>', i)
                    i = if (end < 0) n else end + 1
                }
                else -> {
                    val tag = tag(html, i)
                    if (tag == null) {
                        text.append(c)
                        i++
                    } else {
                        flush()
                        out.add(tag.first)
                        i = tag.second
                    }
                }
            }
        }
        flush()
        return out
    }

    /** One tag starting at [at], and where it ends; null when what is there is not a tag. */
    private fun tag(s: String, at: Int): Pair<Tok, Int>? {
        var i = at + 1
        val closing = i < s.length && s[i] == '/'
        if (closing) i++
        val nameStart = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-')) i++
        if (i == nameStart || !s[nameStart].isLetter()) return null
        val name = s.substring(nameStart, i).lowercase()
        val attrs = LinkedHashMap<String, String>()
        var selfClosed = false
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '>' -> {
                    val tok: Tok = if (closing) Close(name) else Open(name, attrs, selfClosed)
                    return tok to i + 1
                }
                ch == '/' -> { selfClosed = true; i++ }
                ch.isWhitespace() -> i++
                ch == '<' -> return null
                else -> {
                    selfClosed = false
                    val keyStart = i
                    while (i < s.length && !s[i].isWhitespace() && s[i] != '=' && s[i] != '>' && s[i] != '/') i++
                    val key = s.substring(keyStart, i).lowercase()
                    while (i < s.length && s[i].isWhitespace()) i++
                    var value = ""
                    if (i < s.length && s[i] == '=') {
                        i++
                        while (i < s.length && s[i].isWhitespace()) i++
                        if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                            val q = s[i]
                            val end = s.indexOf(q, i + 1)
                            if (end < 0) return null
                            value = s.substring(i + 1, end)
                            i = end + 1
                        } else {
                            val vStart = i
                            while (i < s.length && !s[i].isWhitespace() && s[i] != '>') i++
                            value = s.substring(vStart, i)
                        }
                    }
                    if (key.isNotEmpty()) attrs[key] = decode(value)
                }
            }
        }
        return null
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "laquo" to "«", "raquo" to "»", "middot" to "·", "bull" to "•", "times" to "×", "deg" to "°",
        "rarr" to "→", "larr" to "←", "uarr" to "↑", "darr" to "↓", "check" to "✓",
        "ensp" to " ", "emsp" to " ", "thinsp" to " ", "zwj" to "‍", "zwnj" to "‌",
    )

    /** `&amp;`, `&#38;` and `&#x26;` into `&`. An entity it does not know is left as written. */
    fun decode(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val semi = if (c == '&') s.indexOf(';', i + 1).takeIf { it in (i + 2)..(i + 32) } ?: -1 else -1
            if (semi < 0) {
                out.append(c)
                i++
                continue
            }
            val body = s.substring(i + 1, semi)
            val decoded = when {
                body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)?.let(::codePoint)
                body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePoint)
                else -> NAMED[body]
            }
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = semi + 1
            }
        }
        return out.toString()
    }

    private fun codePoint(cp: Int): String? =
        if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null
}

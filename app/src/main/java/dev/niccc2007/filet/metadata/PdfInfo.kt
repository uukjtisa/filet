package dev.niccc2007.filet.metadata

/**
 * The document information dictionary of a PDF: Title, Author, Subject and the rest.
 *
 * ## This is the safest format here, not the worst
 *
 * The support table used to call PDF read-only because the Info dictionary is reachable only
 * through the cross-reference table, which records a byte offset for every object - so rewriting
 * it means rebuilding the xref, and a wrong offset gives a file that opens in one reader and not
 * another. All true, and all beside the point, because a PDF is not supposed to be rewritten.
 *
 * It is supposed to be **appended to**. An incremental update writes a new version of an object at
 * the end of the file, followed by a small xref section listing only that object and a trailer
 * whose `/Prev` points back at the previous one. Readers follow the chain backwards and take the
 * newest version of each object. The original bytes are not touched at all - not one of them - so
 * the failure mode that worries everybody about a metadata writer does not exist here. A signed
 * PDF even keeps its signature, because the signed byte range is still byte for byte what it was.
 *
 * ## What is refused, and why
 *
 * **A cross-reference stream.** PDF 1.5 allowed the xref to be a compressed object instead of a
 * text table. Appending a classic table whose `/Prev` points at a stream is the "hybrid" shape:
 * Acrobat reads it, and a strict reader is entitled not to. Writing one would mean producing a
 * file that opens on the machine it was made on and not on somebody else's, which is the worst
 * kind of bug to ship. Those files are read and refused with the reason.
 *
 * **An encrypted document.** Strings in the Info dictionary are encrypted with the rest, so a
 * plaintext title appended to one comes back as mojibake.
 */
object PdfInfo {

    /** The keys worth showing, in the order a properties panel shows them. */
    val FIELDS: List<Pair<String, String>> = listOf(
        "Title" to "Title",
        "Author" to "Author",
        "Subject" to "Subject",
        "Keywords" to "Keywords",
        "Creator" to "Creator",
        "Producer" to "Producer",
        "Created" to "CreationDate",
        "Modified" to "ModDate",
    )

    private val BY_LABEL = FIELDS.associate { (label, key) -> label.lowercase() to key }
    private val BY_KEY = FIELDS.associate { (label, key) -> key to label }

    fun keyFor(label: String): String? = BY_LABEL[label.trim().lowercase()]

    fun labelFor(key: String): String? = BY_KEY[key]

    fun isPdf(b: ByteArray): Boolean =
        b.size > 8 && String(b, 0, 5, Charsets.ISO_8859_1) == "%PDF-"

    // ── the lexer, which is as much of one as this needs ──────────────────────────────────

    private fun isWhite(c: Char): Boolean = c == ' ' || c == '\n' || c == '\r' || c == '\t' ||
        c == '\u000C' || c == '\u0000'

    private fun isDelimiter(c: Char): Boolean =
        c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' ||
            c == '{' || c == '}' || c == '/' || c == '%'

    private fun skipWhite(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            if (isWhite(s[i])) {
                i++
            } else if (s[i] == '%') {
                // A comment runs to the end of the line, and one inside a dictionary is legal.
                while (i < s.length && s[i] != '\n' && s[i] != '\r') i++
            } else {
                break
            }
        }
        return i
    }

    /**
     * Read one object, returning its source text exactly as written.
     *
     * The text rather than a parsed value, because most of what is in an Info dictionary is not
     * something this needs to understand - a `/Producer` entry or a private key written by some
     * other tool is copied through unchanged, which is the only way to edit one field without
     * quietly discarding the rest.
     */
    fun readValue(s: String, from: Int): Pair<String, Int>? {
        val start = skipWhite(s, from)
        if (start >= s.length) return null
        return when (s[start]) {
            '(' -> literalEnd(s, start)?.let { s.substring(start, it) to it }
            '[' -> balanced(s, start, '[', ']')?.let { s.substring(start, it) to it }
            '<' ->
                if (start + 1 < s.length && s[start + 1] == '<') {
                    balanced(s, start, '<', '>', doubled = true)?.let { s.substring(start, it) to it }
                } else {
                    val close = s.indexOf('>', start)
                    if (close < 0) null else s.substring(start, close + 1) to (close + 1)
                }
            '/' -> {
                var i = start + 1
                while (i < s.length && !isWhite(s[i]) && !isDelimiter(s[i])) i++
                s.substring(start, i) to i
            }
            else -> {
                var i = start
                while (i < s.length && !isWhite(s[i]) && !isDelimiter(s[i])) i++
                val first = s.substring(start, i)
                // An indirect reference is three tokens, and treating `3 0 R` as the number 3
                // loses the pointer to the object that actually holds the value.
                val afterNum = skipWhite(s, i)
                var j = afterNum
                while (j < s.length && !isWhite(s[j]) && !isDelimiter(s[j])) j++
                val second = s.substring(afterNum, j)
                val afterSecond = skipWhite(s, j)
                var k = afterSecond
                while (k < s.length && !isWhite(s[k]) && !isDelimiter(s[k])) k++
                val third = s.substring(afterSecond, k)
                if (first.toIntOrNull() != null && second.toIntOrNull() != null && third == "R") {
                    "$first $second R" to k
                } else {
                    first to i
                }
            }
        }
    }

    /** Past the closing paren of a literal string, counting nesting and honouring escapes. */
    private fun literalEnd(s: String, at: Int): Int? {
        var depth = 0
        var i = at
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++ // whatever follows is data, including a paren
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return null
    }

    private fun balanced(s: String, at: Int, open: Char, close: Char, doubled: Boolean = false): Int? {
        val step = if (doubled) 2 else 1
        var depth = 0
        var i = at
        while (i < s.length) {
            when {
                s[i] == '(' -> {
                    i = literalEnd(s, i) ?: return null
                    continue
                }
                doubled && s.startsWith("$open$open", i) -> {
                    depth++
                    i += step
                    continue
                }
                doubled && s.startsWith("$close$close", i) -> {
                    depth--
                    i += step
                    if (depth == 0) return i
                    continue
                }
                !doubled && s[i] == open -> depth++
                !doubled && s[i] == close -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return null
    }

    /** Every `/Key value` pair in a dictionary, in source order, values as written. */
    fun entries(dict: String): List<Pair<String, String>> {
        val inner = dict.removePrefix("<<").removeSuffix(">>")
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (true) {
            i = skipWhite(inner, i)
            if (i >= inner.length || inner[i] != '/') break
            var j = i + 1
            while (j < inner.length && !isWhite(inner[j]) && !isDelimiter(inner[j])) j++
            val key = inner.substring(i + 1, j)
            val (value, next) = readValue(inner, j) ?: break
            out.add(key to value)
            i = next
        }
        return out
    }

    // ── strings ───────────────────────────────────────────────────────────────────────────

    /** Decode a PDF string as written: literal with escapes, or hex, either possibly UTF-16. */
    fun decodeString(raw: String): String? {
        if (raw.startsWith("(") && raw.endsWith(")")) {
            val body = raw.substring(1, raw.length - 1)
            val out = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c != '\\') {
                    out.append(c)
                    i++
                    continue
                }
                i++
                if (i >= body.length) break
                when (val e = body[i]) {
                    'n' -> { out.append('\n'); i++ }
                    'r' -> { out.append('\r'); i++ }
                    't' -> { out.append('\t'); i++ }
                    'b' -> { out.append('\b'); i++ }
                    'f' -> { out.append('\u000C'); i++ }
                    '\n' -> i++ // a backslash before a newline is a line continuation
                    '\r' -> { i++; if (i < body.length && body[i] == '\n') i++ }
                    in '0'..'7' -> {
                        var v = 0
                        var n = 0
                        while (i < body.length && n < 3 && body[i] in '0'..'7') {
                            v = v * 8 + (body[i] - '0')
                            i++
                            n++
                        }
                        out.append(v.toChar())
                    }
                    else -> { out.append(e); i++ }
                }
            }
            return utf16OrLatin(out.toString())
        }
        if (raw.startsWith("<") && raw.endsWith(">") && !raw.startsWith("<<")) {
            val hex = raw.substring(1, raw.length - 1).filter { !it.isWhitespace() }
            if (hex.any { Character.digit(it, 16) < 0 }) return null
            // An odd number of digits means the last byte is half given, and zero is assumed -
            // which the specification states outright rather than leaving to a reader.
            val even = if (hex.length % 2 == 0) hex else hex + "0"
            val bytes = ByteArray(even.length / 2) {
                ((Character.digit(even[it * 2], 16) shl 4) or Character.digit(even[it * 2 + 1], 16)).toByte()
            }
            return bytesToText(bytes)
        }
        return null
    }

    private fun utf16OrLatin(s: String): String {
        val bytes = ByteArray(s.length) { (s[it].code and 0xFF).toByte() }
        return bytesToText(bytes)
    }

    /**
     * Text from bytes, honouring the byte-order mark.
     *
     * A PDF string is PDFDocEncoding unless it starts with the UTF-16 mark, and a reader that
     * assumes one encoding shows every accented author name as two characters.
     */
    private fun bytesToText(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        else -> String(bytes, Charsets.ISO_8859_1)
    }

    /**
     * Encode a value as a PDF string.
     *
     * Anything outside printable ASCII goes out as UTF-16BE hex with a byte-order mark, which is
     * the one encoding every reader since Acrobat 4 agrees on. A literal string would need
     * PDFDocEncoding, which has no slot for most of what somebody might type.
     */
    fun encodeString(value: String): String {
        val plain = value.all { it.code in 0x20..0x7E }
        if (plain) {
            val escaped = value
                .replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)")
            return "($escaped)"
        }
        val bytes = value.toByteArray(Charsets.UTF_16BE)
        val sb = StringBuilder("<FEFF")
        for (byte in bytes) sb.append(String.format("%02X", byte.toInt() and 0xFF))
        sb.append('>')
        return sb.toString()
    }

    // ── the file ──────────────────────────────────────────────────────────────────────────

    /** The whole file as text, in a one-byte-per-character encoding so positions are byte positions. */
    private fun text(b: ByteArray): String = String(b, Charsets.ISO_8859_1)

    data class Shape(
        val infoObject: Int?,
        val infoDict: String?,
        val rootRef: String?,
        val size: Int,
        val startxref: Int,
        val classicXref: Boolean,
        val encrypted: Boolean,
    )

    /**
     * Everything an update needs to know, read from the end of the file backwards.
     *
     * `/Info` is searched for over the whole buffer rather than only in the last trailer, because
     * in a file whose xref is a stream the key lives in that stream's dictionary instead - and the
     * dictionary is plain text in both cases. The last occurrence wins: that is the newest one.
     */
    fun shape(b: ByteArray): Shape? {
        if (!isPdf(b)) return null
        val s = text(b)
        val sx = s.lastIndexOf("startxref")
        if (sx < 0) return null
        val at = skipWhite(s, sx + 9)
        var end = at
        while (end < s.length && s[end].isDigit()) end++
        val startxref = s.substring(at, end).toIntOrNull() ?: return null
        val classic = startxref in 0 until b.size &&
            s.startsWith("xref", skipWhite(s, startxref))

        val infoAt = s.lastIndexOf("/Info")
        var infoObject: Int? = null
        var infoDict: String? = null
        if (infoAt >= 0) {
            val value = readValue(s, infoAt + 5)?.first
            val num = value?.substringBefore(' ')?.toIntOrNull()
            if (value != null && value.endsWith(" R") && num != null) {
                infoObject = num
                infoDict = objectDict(s, num)
            }
        }
        val rootAt = s.lastIndexOf("/Root")
        val rootRef = if (rootAt < 0) null else readValue(s, rootAt + 5)?.first
        val sizeAt = s.lastIndexOf("/Size")
        val size = if (sizeAt < 0) 0 else readValue(s, sizeAt + 5)?.first?.toIntOrNull() ?: 0
        return Shape(
            infoObject = infoObject,
            infoDict = infoDict,
            rootRef = rootRef,
            size = size,
            startxref = startxref,
            classicXref = classic,
            encrypted = encryptedBy(s, startxref),
        )
    }

    /**
     * Is the document encrypted?
     *
     * Looked for in the trailer dictionary, not in the whole file. The same text can appear
     * inside a page's content, and refusing a perfectly ordinary document because one of its
     * pages discusses encryption is its own bug - which is what the test for this is called.
     *
     * The trailer keyword is preferred over the `startxref` offset because the offset can be
     * stale, and a stale one lands the search in the middle of an object where anything might be.
     * A file whose cross-reference is a stream has no trailer keyword, and there the offset is
     * the only handle there is.
     */
    private fun encryptedBy(s: String, startxref: Int): Boolean {
        val trailer = s.lastIndexOf("trailer")
        val from = if (trailer >= 0) trailer else startxref.coerceIn(0, s.length)
        return s.indexOf("/Encrypt", from) >= 0
    }

    /** The dictionary of object [num], found by its own `N G obj` header. */
    private fun objectDict(s: String, num: Int): String? {
        var from = 0
        var found: String? = null
        while (true) {
            val at = s.indexOf("$num 0 obj", from)
            if (at < 0) break
            from = at + 1
            // The digit before must not be one, or object 1 would match inside object 21.
            if (at > 0 && s[at - 1].isDigit()) continue
            val open = s.indexOf("<<", at)
            val endObj = s.indexOf("endobj", at)
            if (open < 0 || (endObj in 0 until open)) continue
            val close = balanced(s, open, '<', '>', doubled = true) ?: continue
            // The last definition of an object number is the live one: an incrementally updated
            // file has every earlier version still in it.
            found = s.substring(open, close)
        }
        return found
    }

    fun read(b: ByteArray): List<Pair<String, String>> {
        val dict = shape(b)?.infoDict ?: return emptyList()
        return entries(dict).mapNotNull { (key, raw) ->
            val label = labelFor(key) ?: return@mapNotNull null
            val value = decodeString(raw) ?: return@mapNotNull null
            if (value.isBlank()) null else label to value
        }
    }

    /** Why a write is not possible on this particular file, or null if it is. */
    fun refusal(b: ByteArray): String? {
        val shape = shape(b) ?: return "This does not start with a PDF header."
        if (shape.encrypted) {
            return "This PDF is encrypted. Its text is scrambled with a key Filet does not " +
                "have, so a title appended in plain form would come back as nonsense."
        }
        if (!shape.classicXref) {
            return "This PDF stores its cross-reference table as a compressed stream, which " +
                "Filet can read but not extend. Appending a plain table to it makes a file some " +
                "readers accept and others do not, and one that opens only on the machine that " +
                "made it is worse than one Filet declines to change."
        }
        if (shape.rootRef == null) return "This PDF has no document catalogue, so it is already damaged."
        return null
    }

    /**
     * Append an incremental update setting one field.
     *
     * Nothing before [original]`.size` is read back out or altered. The appended section is a new
     * version of the Info object, an xref listing exactly that object, and a trailer chaining to
     * the previous one.
     */
    fun put(original: ByteArray, label: String, value: String): ByteArray? {
        val key = keyFor(label) ?: return null
        val shape = shape(original) ?: return null
        if (refusal(original) != null) return null
        val root = shape.rootRef ?: return null

        val existing = shape.infoDict?.let { entries(it) } ?: emptyList()
        val kept = existing.filterNot { it.first == key }
        val merged = if (value.isBlank()) kept else kept + (key to encodeString(value))
        val objectNumber = shape.infoObject ?: maxOf(shape.size, 1)
        val size = maxOf(shape.size, objectNumber + 1)

        val sb = StringBuilder()
        // A newline first, because the original may end without one and `%%EOF3 0 obj` is not a
        // thing any reader recovers from.
        sb.append('\n')
        val objectAt = original.size + sb.length
        sb.append("$objectNumber 0 obj\n<<")
        for ((k, v) in merged) sb.append(" /").append(k).append(' ').append(v)
        sb.append(" >>\nendobj\n")
        val xrefAt = original.size + sb.length
        sb.append("xref\n")
        sb.append("0 1\n")
        sb.append("0000000000 65535 f \n")
        sb.append("$objectNumber 1\n")
        // Each entry is exactly twenty bytes - ten digits, a space, five digits, a space, the
        // type letter and a two-byte ending. A reader seeks by multiplying, so a short entry
        // shifts every one after it.
        sb.append(String.format("%010d 00000 n \n", objectAt))
        sb.append("trailer\n<< /Size $size /Root $root /Info $objectNumber 0 R /Prev ${shape.startxref} >>\n")
        sb.append("startxref\n$xrefAt\n%%EOF\n")
        return original + sb.toString().toByteArray(Charsets.ISO_8859_1)
    }
}

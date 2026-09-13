package dev.niccc2007.filet.index

import java.util.Locale

/**
 * The query language from SEARCH.md §6.1.
 *
 * ```
 * type:apk size:>50mb modified:<7d -in:Android
 * from:youtube modified:today
 * (pkg:com.google OR pkg:com.android) perm:RECORD_AUDIO
 * ```
 *
 * Parsed to an AST, which is then split: the parts expressible as SQL become a predicate
 * *before* retrieval so they cost nothing, and the rest becomes a post-filter over the ~500
 * survivors. This file owns the AST and the post-filter; the SQL half lives with the index.
 *
 * **Invalid syntax never errors.** An unparsable fragment degrades to a literal substring
 * term, because a search box that refuses to search is worse than one that guesses.
 */
sealed interface Node {
    data class And(val parts: List<Node>) : Node
    data class Or(val parts: List<Node>) : Node
    data class Not(val part: Node) : Node
    /** A bare word or quoted phrase: matched fuzzily against the name. */
    data class Text(val value: String) : Node
    data class Field(val field: QField, val op: Cmp, val value: String) : Node
    data object Everything : Node
}

enum class Cmp { EQ, LT, LTE, GT, GTE, RANGE }

enum class QField(val token: String, val sqlPushable: Boolean) {
    NAME("name", true),
    EXT("ext", true),
    TYPE("type", true),
    SIZE("size", true),
    MODIFIED("modified", true),
    CREATED("created", true),
    IN("in", false),
    FROM("from", false),
    PKG("pkg", false),
    CLASS("class", false),
    PERM("perm", false),
    LABEL("label", false),
    INZIP("inzip", false),
    MEMBER("member", false),
    DUP("dup", false),
    TAG("tag", false);

    companion object {
        private val byToken = entries.associateBy { it.token }
        fun of(s: String): QField? = byToken[s.lowercase(Locale.US)]
    }
}

/** The parse result. [free] is every bare word, joined - what the fuzzy scorer ranks on. */
data class ParsedQuery(
    val root: Node,
    val free: String,
    /** Fragments that did not parse, surfaced so the UI can mark them rather than fail silently. */
    val unparsed: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = root == Node.Everything && free.isEmpty()
}

object QueryParser {

    fun parse(raw: String): ParsedQuery {
        val tokens = tokenize(raw)
        if (tokens.isEmpty()) return ParsedQuery(Node.Everything, "")
        val p = Cursor(tokens)
        val node = p.orExpr()
        val free = tokens.filter { it.kind == T.WORD && !it.consumedAsField }
            .joinToString(" ") { it.text }
        return ParsedQuery(node, free.trim(), p.unparsed)
    }

    // ── lexer ──
    private enum class T { WORD, QUOTED, LPAREN, RPAREN, OR, AND, MINUS }

    private class Tok(val kind: T, val text: String) {
        var consumedAsField = false
    }

    private fun tokenize(raw: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> { out += Tok(T.LPAREN, "("); i++ }
                c == ')' -> { out += Tok(T.RPAREN, ")"); i++ }
                c == '-' && (out.isEmpty() || out.last().kind !in setOf(T.WORD, T.QUOTED, T.RPAREN)) -> {
                    out += Tok(T.MINUS, "-"); i++
                }
                c == '"' -> {
                    val end = raw.indexOf('"', i + 1)
                    if (end < 0) { out += Tok(T.QUOTED, raw.substring(i + 1)); i = raw.length }
                    else { out += Tok(T.QUOTED, raw.substring(i + 1, end)); i = end + 1 }
                }
                else -> {
                    var j = i
                    var inQuote = false
                    while (j < raw.length && (inQuote || !raw[j].isWhitespace()) && raw[j] != '(' && raw[j] != ')') {
                        if (raw[j] == '"') inQuote = !inQuote
                        j++
                    }
                    val word = raw.substring(i, j)
                    val kind = when (word.uppercase(Locale.US)) {
                        "OR" -> T.OR
                        "AND" -> T.AND
                        else -> T.WORD
                    }
                    out += Tok(kind, word)
                    i = j
                }
            }
        }
        return out
    }

    // ── recursive descent ──
    private class Cursor(val toks: List<Tok>) {
        var i = 0
        val unparsed = ArrayList<String>()

        fun peek(): Tok? = toks.getOrNull(i)

        fun orExpr(): Node {
            val parts = ArrayList<Node>()
            parts += andExpr()
            while (peek()?.kind == T.OR) { i++; parts += andExpr() }
            return if (parts.size == 1) parts[0] else Node.Or(parts)
        }

        fun andExpr(): Node {
            val parts = ArrayList<Node>()
            while (true) {
                val t = peek() ?: break
                if (t.kind == T.OR || t.kind == T.RPAREN) break
                if (t.kind == T.AND) { i++; continue }
                parts += unary()
            }
            return when {
                parts.isEmpty() -> Node.Everything
                parts.size == 1 -> parts[0]
                else -> Node.And(parts)
            }
        }

        fun unary(): Node {
            val t = peek() ?: return Node.Everything
            if (t.kind == T.MINUS) { i++; return Node.Not(unary()) }
            if (t.kind == T.LPAREN) {
                i++
                val inner = orExpr()
                if (peek()?.kind == T.RPAREN) i++ else unparsed += "("
                return inner
            }
            i++
            if (t.kind == T.QUOTED) return Node.Text(t.text)
            return term(t)
        }

        private fun term(t: Tok): Node {
            val colon = t.text.indexOf(':')
            if (colon <= 0) return Node.Text(t.text)
            val field = QField.of(t.text.substring(0, colon))
            if (field == null) {
                // Not a known field. `10:30` in a filename is not a syntax error.
                return Node.Text(t.text)
            }
            t.consumedAsField = true
            var v = t.text.substring(colon + 1).trim('"')
            if (v.isEmpty()) { unparsed += t.text; return Node.Everything }
            val op = when {
                v.startsWith(">=") -> { v = v.drop(2); Cmp.GTE }
                v.startsWith("<=") -> { v = v.drop(2); Cmp.LTE }
                v.startsWith(">") -> { v = v.drop(1); Cmp.GT }
                v.startsWith("<") -> { v = v.drop(1); Cmp.LT }
                v.contains("..") -> Cmp.RANGE
                else -> Cmp.EQ
            }
            return Node.Field(field, op, v)
        }
    }
}

/** What a query is evaluated against. Kept structural so both a live walk and an index row fit. */
interface Candidate {
    val name: String
    val pathText: String
    val isDir: Boolean
    val size: Long
    val mtime: Long
    val extension: String
    /** Provenance origin (PLAN.md §5.1), or null when nothing recorded it. */
    val origin: String? get() = null
    /** Container facts: package name, class names, permissions, inner entry names. */
    fun inner(kind: String): List<String> = emptyList()
}

/** Evaluates the non-SQL half of a query. The SQL half has already narrowed the set. */
object QueryEval {

    fun matches(node: Node, c: Candidate, now: Long = System.currentTimeMillis()): Boolean = when (node) {
        is Node.Everything -> true
        is Node.And -> node.parts.all { matches(it, c, now) }
        is Node.Or -> node.parts.any { matches(it, c, now) }
        is Node.Not -> !matches(node.part, c, now)
        // A bare word is a *ranking* signal, not a filter: the fuzzy scorer decides how well
        // it matched. Filtering here too would drop the abbreviation matches that are the
        // whole point of the scorer.
        is Node.Text -> true
        is Node.Field -> field(node, c, now)
    }

    private fun field(n: Node.Field, c: Candidate, now: Long): Boolean {
        val v = n.value
        return when (n.field) {
            QField.NAME -> c.name.contains(v, ignoreCase = true)
            QField.EXT -> c.extension.equals(v.removePrefix("."), ignoreCase = true)
            QField.TYPE -> typeMatches(v, c)
            QField.SIZE -> compareLong(c.size, n.op, v) { parseSize(it) }
            QField.MODIFIED -> compareTime(c.mtime, n.op, v, now)
            QField.CREATED -> compareTime(c.mtime, n.op, v, now)
            QField.IN -> c.pathText.contains(v, ignoreCase = true)
            QField.FROM -> c.origin?.contains(v, ignoreCase = true) == true
            QField.PKG -> c.inner("pkg").any { it.contains(v, true) }
            QField.CLASS -> c.inner("class").any { it.contains(v, true) }
            QField.PERM -> c.inner("perm").any { it.contains(v, true) }
            QField.LABEL -> c.inner("label").any { it.contains(v, true) }
            QField.INZIP -> c.inner("entry").any { it.contains(v, true) }
            QField.MEMBER -> c.inner("member").any { it.contains(v, true) }
            QField.TAG -> c.inner("tag").any { it.equals(v, true) }
            QField.DUP -> true    // resolved by the duplicate pass, not per-candidate
        }
    }

    private fun typeMatches(v: String, c: Candidate): Boolean {
        val t = v.lowercase(Locale.US)
        if (t == "dir" || t == "folder") return c.isDir
        if (t == "file") return !c.isDir
        val groups = TYPE_GROUPS[t]
        return if (groups != null) c.extension in groups else c.extension.equals(t, true)
    }

    private val TYPE_GROUPS: Map<String, Set<String>> = mapOf(
        "image" to setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "svg"),
        "video" to setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "ts", "m4v"),
        "audio" to setOf("mp3", "opus", "ogg", "m4a", "aac", "flac", "wav"),
        "archive" to setOf("zip", "jar", "tar", "gz", "7z", "rar", "xz", "bz2"),
        "code" to setOf("kt", "java", "smali", "xml", "json", "py", "lua", "js", "c", "cpp", "go", "rs", "sh"),
        "doc" to setOf("txt", "md", "pdf", "doc", "docx", "csv", "log"),
    )

    private inline fun compareLong(actual: Long, op: Cmp, raw: String, parse: (String) -> Long?): Boolean {
        if (op == Cmp.RANGE) {
            val (a, b) = raw.split("..").let { it.getOrNull(0) to it.getOrNull(1) }
            val lo = a?.let(parse) ?: return true
            val hi = b?.let(parse) ?: return true
            return actual in lo..hi
        }
        val target = parse(raw) ?: return true
        return when (op) {
            Cmp.EQ -> actual == target
            Cmp.LT -> actual < target
            Cmp.LTE -> actual <= target
            Cmp.GT -> actual > target
            Cmp.GTE -> actual >= target
            Cmp.RANGE -> true
        }
    }

    /**
     * `modified:<7d` reads as "changed within the last 7 days", which is the opposite of a
     * literal `mtime < 7d`. Users mean recency, so `<` maps to "newer than".
     */
    private fun compareTime(mtime: Long, op: Cmp, raw: String, now: Long): Boolean {
        val cutoff = parseTime(raw, now) ?: return true
        return when (op) {
            Cmp.LT, Cmp.LTE, Cmp.EQ -> mtime >= cutoff
            Cmp.GT, Cmp.GTE -> mtime < cutoff
            Cmp.RANGE -> true
        }
    }

    fun parseSize(raw: String): Long? {
        val s = raw.trim().lowercase(Locale.US)
        val m = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(b|kb|k|mb|m|gb|g|tb|t)?$").find(s) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2]) {
            "kb", "k" -> 1024.0
            "mb", "m" -> 1024.0 * 1024
            "gb", "g" -> 1024.0 * 1024 * 1024
            "tb", "t" -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (n * mult).toLong()
    }

    /** `today`, `yesterday`, `7d`, `2w`, `3mo`, `1y` -> an epoch-millis cutoff. */
    fun parseTime(raw: String, now: Long): Long? {
        val s = raw.trim().lowercase(Locale.US)
        val day = 86_400_000L
        return when (s) {
            "today" -> now - (now % day)
            "yesterday" -> now - (now % day) - day
            "week" -> now - 7 * day
            "month" -> now - 30 * day
            "year" -> now - 365 * day
            else -> {
                val m = Regex("^([0-9]+)\\s*(h|d|w|mo|m|y)$").find(s) ?: return null
                val n = m.groupValues[1].toLongOrNull() ?: return null
                when (m.groupValues[2]) {
                    "h" -> now - n * 3_600_000L
                    "d" -> now - n * day
                    "w" -> now - n * 7 * day
                    "mo", "m" -> now - n * 30 * day
                    "y" -> now - n * 365 * day
                    else -> null
                }
            }
        }
    }
}

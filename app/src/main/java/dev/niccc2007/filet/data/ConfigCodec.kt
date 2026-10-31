package dev.niccc2007.filet.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * `filet.json`: the settings as a file somebody can read and edit. Pure; [ConfigSync] does the I/O.
 *
 * ## The shape
 *
 * One flat object, one dotted key per setting, in [ConfigKeys.FILE] order with a blank line
 * between sections, and every value in its natural JSON type - `true`, `13`, `0.35`, `"DARK"`,
 * `["Download"]`, an object for a setting that is itself a document. Not the export's
 * `"b:true"` strings: those exist so a value survives a trip without a type table, and this file
 * has one ([ConfigKeys]).
 *
 * Every registered key is listed, a setting never changed as `null`. A file that listed only what
 * had been changed would be a file somebody has to know the key names to use, and `null` is also
 * the way back: setting a key to `null` puts it back to Filet's default.
 *
 * ## Reading is forgiving per value and strict per file
 *
 * A file that is not JSON is refused whole, with the line the parser stopped at: applying the
 * half of a file before the syntax error would be guessing what the rest said. A value of the
 * wrong type is skipped alone and named with its line, and everything else in the file still
 * applies. A key this build does not know is KEPT - a file edited for a newer Filet, or a typo
 * somebody will want to find again - and written back out at the end of the file.
 */
object ConfigCodec {

    /** Bumped only when the SHAPE changes. */
    const val FORMAT = 1
    const val FORMAT_KEY = "filet.config"

    /** JSON has no comments; this is the conventional stand-in, and it is never kept or applied. */
    const val NOTE_KEY = "//"
    const val NOTE = "Filet's settings. Edit and save: Filet applies a change while it is open, or " +
        "the next time it opens. null means Filet's default. A value Filet cannot use is skipped " +
        "and named; a key it does not know is kept."

    /** Something in a file that was not applied, and where. */
    data class Problem(val line: Int?, val key: String?, val message: String) {
        fun describe(file: String): String = buildString {
            append(file)
            if (line != null) append(" line ").append(line)
            append(": ")
            if (key != null) append(key).append(" - ")
            append(message)
        }
    }

    sealed interface Parsed {
        data class Refused(val problem: Problem) : Parsed

        /**
         * @param values what to store, in the store's own types.
         * @param reset keys set to `null`: back to the default, which is removing them.
         * @param unknown keys this build does not know, raw, to be written back unchanged.
         */
        data class Ok(
            val values: Map<String, Any>,
            val reset: Set<String>,
            val unknown: Map<String, Any?>,
            val problems: List<Problem>,
        ) : Parsed
    }

    // ── writing ──────────────────────────────────────────────────────────────────────────

    /**
     * The file for [stored] - what the store holds, by key, in the store's types.
     *
     * [unknown] comes from the file being replaced, so a key this build does not know survives
     * Filet writing the file again.
     */
    fun render(stored: Map<String, Any?>, keys: List<ConfigKey>, unknown: Map<String, Any?> = emptyMap()): String {
        val sb = StringBuilder("{\n")
        sb.append("  ").append(quote(NOTE_KEY)).append(": ").append(quote(NOTE)).append(",\n")
        sb.append("  ").append(quote(FORMAT_KEY)).append(": ").append(FORMAT)
        var section: SettingsTransfer.Section? = null
        for (k in keys) {
            sb.append(",\n")
            if (k.section != section) {
                sb.append("\n")
                section = k.section
            }
            sb.append("  ").append(quote(k.key)).append(": ")
            writeStored(sb, k, stored[k.key], 1)
        }
        if (unknown.isNotEmpty()) {
            sb.append(",\n")
            var first = true
            for ((key, raw) in unknown) {
                if (!first) sb.append(",\n")
                if (first) sb.append("\n")
                first = false
                sb.append("  ").append(quote(key)).append(": ")
                writeJson(sb, raw, 1)
            }
        }
        sb.append("\n}\n")
        return sb.toString()
    }

    private fun writeStored(sb: StringBuilder, k: ConfigKey, v: Any?, depth: Int) {
        when {
            v == null -> sb.append("null")
            k.type == ConfigType.JSON && v is String -> {
                val doc = runCatching { parseDocument(v) }.getOrNull()
                if (doc != null) writeJson(sb, doc, depth) else sb.append(quote(v))
            }
            v is Set<*> -> writeJson(sb, JSONArray(v.map { it.toString() }.sorted()), depth)
            v is Float -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else v.toString())
            v is Boolean || v is Int || v is Long -> sb.append(v.toString())
            else -> sb.append(quote(v.toString()))
        }
    }

    private fun parseDocument(text: String): Any? {
        val t = text.trim()
        return when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            else -> null
        }
    }

    /** Indented JSON with object keys sorted, so the same settings always give the same bytes. */
    private fun writeJson(sb: StringBuilder, v: Any?, depth: Int) {
        val pad = "  ".repeat(depth)
        when (v) {
            null, JSONObject.NULL -> sb.append("null")
            is JSONObject -> {
                val keys = v.keys().asSequence().toList().sorted()
                if (keys.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                keys.forEachIndexed { i, key ->
                    sb.append(pad).append("  ").append(quote(key)).append(": ")
                    writeJson(sb, v.opt(key), depth + 1)
                    sb.append(if (i < keys.size - 1) ",\n" else "\n")
                }
                sb.append(pad).append("}")
            }
            is JSONArray -> {
                if (v.length() == 0) { sb.append("[]"); return }
                // A list of short scalars stays on one line; anything nested gets a line each.
                val flat = (0 until v.length()).all { v.opt(it) !is JSONObject && v.opt(it) !is JSONArray }
                if (flat) {
                    sb.append("[")
                    for (i in 0 until v.length()) {
                        if (i > 0) sb.append(", ")
                        writeJson(sb, v.opt(i), depth + 1)
                    }
                    sb.append("]")
                } else {
                    sb.append("[\n")
                    for (i in 0 until v.length()) {
                        sb.append(pad).append("  ")
                        writeJson(sb, v.opt(i), depth + 1)
                        sb.append(if (i < v.length() - 1) ",\n" else "\n")
                    }
                    sb.append(pad).append("]")
                }
            }
            is String -> sb.append(quote(v))
            is Boolean -> sb.append(v.toString())
            is Double -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else v.toString())
            is Float -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else v.toString())
            is Number -> sb.append(v.toString())
            else -> sb.append(quote(v.toString()))
        }
    }

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────

    /**
     * Read a file against [keys]. Nothing is applied here.
     *
     * A registered key that is not one of [keys] - a share list, which carries access codes -
     * and a key that is deliberately never a setting are reported and dropped, not kept: keeping
     * them would write them back into a file anything can read.
     */
    fun parse(text: String, keys: List<ConfigKey>): Parsed {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return Parsed.Refused(Problem(syntaxLine(e.message, text), null, "not valid JSON (" + syntaxReason(e.message) + "); nothing was applied"))
        }
        val version = root.optInt(FORMAT_KEY, FORMAT)
        if (version > FORMAT) {
            return Parsed.Refused(
                Problem(lineOf(text, FORMAT_KEY), FORMAT_KEY, "written by a newer Filet (format $version); nothing was applied"),
            )
        }
        val byKey = keys.associateBy { it.key }
        val values = LinkedHashMap<String, Any>()
        val reset = LinkedHashSet<String>()
        val unknown = LinkedHashMap<String, Any?>()
        val problems = ArrayList<Problem>()
        for (key in root.keys()) {
            if (key == NOTE_KEY || key == FORMAT_KEY) continue
            val raw = root.opt(key)
            val k = byKey[key]
            if (k == null) {
                when {
                    ConfigKeys.of(key) != null || key in ConfigKeys.EXCLUDED ->
                        problems += Problem(lineOf(text, key), key, "not read from this file")
                    else -> unknown[key] = raw
                }
                continue
            }
            if (raw == null || raw == JSONObject.NULL) {
                reset += key
                continue
            }
            when (val c = coerce(k, raw)) {
                is Coerced.Value -> values[key] = c.value
                is Coerced.Wrong -> problems += Problem(lineOf(text, key), key, c.why)
            }
        }
        return Parsed.Ok(values, reset, unknown, problems)
    }

    sealed interface Coerced {
        data class Value(val value: Any) : Coerced
        data class Wrong(val why: String) : Coerced
    }

    /** One value from a file, in the type the store keeps it in, or why it cannot be. */
    fun coerce(k: ConfigKey, raw: Any): Coerced = when (k.type) {
        ConfigType.BOOL -> when {
            raw is Boolean -> Coerced.Value(raw)
            raw is String && raw.lowercase() in setOf("true", "false") -> Coerced.Value(raw.lowercase() == "true")
            else -> Coerced.Wrong("expects true or false")
        }
        ConfigType.INT -> whole(raw)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
            ?.let { Coerced.Value(it.toInt()) } ?: Coerced.Wrong("expects a whole number")
        ConfigType.LONG -> whole(raw)?.let { Coerced.Value(it) } ?: Coerced.Wrong("expects a whole number")
        ConfigType.FLOAT -> (raw as? Number)?.toDouble()?.takeIf { !it.isNaN() && !it.isInfinite() }
            ?.let { Coerced.Value(it.toFloat()) } ?: Coerced.Wrong("expects a number")
        ConfigType.STRING -> when {
            raw !is String -> Coerced.Wrong("expects text in quotes")
            k.values != null && raw !in k.values -> Coerced.Wrong("expects one of " + k.values.joinToString(", "))
            else -> Coerced.Value(raw)
        }
        ConfigType.STRING_SET -> {
            val arr = raw as? JSONArray
            if (arr == null || (0 until arr.length()).any { arr.opt(it) !is String }) {
                Coerced.Wrong("expects a list of text, like [\"a\", \"b\"]")
            } else {
                Coerced.Value((0 until arr.length()).map { arr.getString(it) }.toSet())
            }
        }
        ConfigType.JSON -> when (raw) {
            is JSONObject, is JSONArray -> Coerced.Value(raw.toString())
            is String -> if (runCatching { parseDocument(raw) }.getOrNull() != null) Coerced.Value(raw)
            else Coerced.Wrong("expects an object or a list")
            else -> Coerced.Wrong("expects an object or a list")
        }
    }

    private fun whole(raw: Any): Long? = when (raw) {
        is Int -> raw.toLong()
        is Long -> raw
        is Number -> raw.toDouble().takeIf { it == Math.floor(it) && !it.isInfinite() && kotlin.math.abs(it) < 9.0e15 }?.toLong()
        else -> null
    }

    /** The line a key is written on, for naming it in a problem. */
    fun lineOf(text: String, key: String): Int? {
        val needle = quote(key)
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return null
            var i = at + needle.length
            while (i < text.length && text[i].isWhitespace()) i++
            if (i < text.length && text[i] == ':') return lineAt(text, at)
            from = at + 1
        }
    }

    private fun lineAt(text: String, index: Int): Int =
        1 + (0 until index.coerceAtMost(text.length)).count { text[it] == '\n' }

    /**
     * Where the parser stopped, from its message.
     *
     * Android's org.json says "at character N of ..."; the reference implementation the unit tests
     * run against says "at N [character C line L]". Both are read.
     */
    fun syntaxLine(message: String?, text: String): Int? {
        val m = message ?: return null
        Regex("""line (\d+)""").find(m)?.let { return it.groupValues[1].toInt() }
        Regex("""at character (\d+)""").find(m)?.let { return lineAt(text, it.groupValues[1].toInt()) }
        Regex("""at (\d+)""").find(m)?.let { return lineAt(text, it.groupValues[1].toInt()) }
        return null
    }

    /** The parser's reason without the echo of the whole file some versions append. */
    private fun syntaxReason(message: String?): String {
        val m = message ?: return "unreadable"
        val cut = listOf(" at character ", " of ", " at ").map { m.indexOf(it) }.filter { it > 0 }.minOrNull()
        return (if (cut != null) m.substring(0, cut) else m).trim().lowercase().ifEmpty { "unreadable" }
    }

    // ── the list files ───────────────────────────────────────────────────────────────────

    /**
     * `bookmarks.json` and `playlists.json`: the stored list, indented. Their stores already keep
     * a JSON array, so the file is that array made readable and nothing is translated.
     */
    fun renderList(stored: String?): String {
        val arr = runCatching { JSONArray(stored ?: "[]") }.getOrElse { JSONArray() }
        val sb = StringBuilder()
        writeJson(sb, arr, 0)
        return sb.append("\n").toString()
    }

    /** The array to store, or why the file cannot be used. Entries must be objects. */
    fun parseList(text: String): Pair<String?, Problem?> {
        val arr = try {
            JSONArray(text)
        } catch (e: JSONException) {
            return null to Problem(syntaxLine(e.message, text), null, "not a valid list (" + syntaxReason(e.message) + "); nothing was applied")
        }
        for (i in 0 until arr.length()) {
            if (arr.opt(i) !is JSONObject) {
                return null to Problem(null, null, "entry ${i + 1} is not an object; nothing was applied")
            }
        }
        return arr.toString() to null
    }
}

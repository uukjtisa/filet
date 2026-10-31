package dev.niccc2007.filet.docs

/**
 * Headings into anchors, the way GitHub names them, so a `[see below](#install)` written for
 * GitHub lands on the same heading here.
 *
 * ## The slug rule
 *
 * GitHub's is `github-slugger`: lower-case the heading's text, drop every character that is not a
 * letter, a digit, a combining mark, `-` or `_`, and turn each space into `-`. A heading that
 * slugs the same as an earlier one gets `-1`, then `-2`. Nothing is collapsed or trimmed, which
 * is why `## A  B` is `a--b` - and copying that oddity is the point, because the links in a
 * README were written against it.
 */
internal object DocOutline {

    fun slug(text: String): String = buildString {
        for (ch in text.lowercase()) {
            when {
                ch == ' ' -> append('-')
                ch == '-' || ch == '_' -> append(ch)
                ch.isLetterOrDigit() -> append(ch)
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() ||
                    Character.getType(ch) == Character.COMBINING_SPACING_MARK.toInt() -> append(ch)
            }
        }
    }

    /** Hands out slugs for one document, numbering the repeats as GitHub does. */
    class Slugger {
        private val seen = HashMap<String, Int>()

        fun next(text: String): String {
            val base = slug(text)
            var out = base
            while (out in seen) {
                val n = (seen[base] ?: 0) + 1
                seen[base] = n
                out = "$base-$n"
            }
            seen[out] = 0
            return out
        }
    }

    /**
     * Where a link's `#fragment` goes, or null when nothing in the document has that name.
     *
     * Tried as written, then lower-cased, then without GitHub's `user-content-` prefix, then
     * percent-decoded: all four spellings are in real READMEs, because people copy the anchor
     * from the address bar, from the rendered HTML, or type it.
     */
    fun find(anchors: Map<String, Int>, fragment: String): Int? {
        val raw = fragment.removePrefix("#")
        if (raw.isEmpty()) return null
        val decoded = runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
        for (c in listOf(raw, raw.lowercase(), decoded, decoded.lowercase())) {
            anchors[c]?.let { return it }
            anchors[c.removePrefix("user-content-")]?.let { return it }
        }
        return null
    }
}

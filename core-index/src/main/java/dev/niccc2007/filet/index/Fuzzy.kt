package dev.niccc2007.filet.index

import java.text.Normalizer

/**
 * The reranker from SEARCH.md §5.2 — subsequence matching with positional bonuses.
 *
 * This is the half of search that is allowed to be clever, because it never sees more than
 * the ~500 candidates SQL handed it. The retrieval half is fast and dumb on purpose.
 *
 * No regex and no per-candidate allocation in the hot path: [score] walks two `CharArray`s.
 * 500 candidates x ~30 chars is a few microseconds of work; the budget is under 2 ms.
 */
object Fuzzy {

    const val NO_MATCH = Int.MIN_VALUE

    // Weights, straight from the SEARCH.md table. Named so a tuning session is a diff
    // of numbers rather than an archaeology expedition through the loop.
    private const val W_EXACT = 1000
    private const val W_AT_START = 200
    private const val W_BOUNDARY = 120
    private const val W_RUN = 40
    private const val W_IN_NAME = 150
    private const val W_EXT_EXACT = 80
    private const val P_GAP = -8
    private const val W_PREFIX = 90

    /**
     * Case-fold, NFC-normalise, and strip diacritics.
     *
     * Done once per stored name (it is the `name_fold` column) and once per query, never
     * per comparison. Without it `Résumé` is unfindable by `resume`, and a filename that
     * arrived NFD from a Mac fails to match the same name typed NFC.
     */
    fun fold(s: String): String {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        for (c in nfd) {
            // Mn = non-spacing mark: the combining accents NFD just split off.
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            sb.append(Character.toLowerCase(c))
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFC)
    }

    /** True at a position that starts a new "word": after a separator, or at a camelCase hump. */
    private fun isBoundary(hay: CharArray, i: Int, raw: CharArray?): Boolean {
        if (i == 0) return true
        val prev = hay[i - 1]
        if (prev == '-' || prev == '_' || prev == '.' || prev == ' ' || prev == '/' || prev == '(') return true
        // The camel hump has to be read off the ORIGINAL spelling: name_fold is lowercase,
        // so by the time we are matching, `AndroidManifest` has no humps left to find.
        if (raw != null && i < raw.size && Character.isUpperCase(raw[i]) &&
            i > 0 && Character.isLowerCase(raw[i - 1])
        ) return true
        return false
    }

    /**
     * Score [needle] (already folded) against [hayFold].
     *
     * @param hayRaw the unfolded spelling, used only for camelCase boundary detection.
     * @param inName true when [hayFold] is the file's own name rather than an ancestor's.
     * @param extFold the file's folded extension, for the exact-extension bonus.
     * @return a score, or [NO_MATCH] when the needle is not a subsequence of the haystack.
     */
    fun score(
        needle: CharArray,
        hayFold: CharArray,
        hayRaw: CharArray? = null,
        inName: Boolean = true,
        extFold: String? = null,
    ): Int {
        if (needle.isEmpty()) return 0
        if (needle.size > hayFold.size) return NO_MATCH

        // Greedy-forward pass to prove a match exists and find the earliest start.
        var n = 0
        var firstAt = -1
        var score = 0
        var run = 0
        var lastMatch = -1

        for (i in hayFold.indices) {
            if (n >= needle.size) break
            if (hayFold[i] != needle[n]) {
                run = 0
                continue
            }
            if (firstAt < 0) firstAt = i
            if (lastMatch >= 0) {
                val gap = i - lastMatch - 1
                if (gap > 0) score += gap * P_GAP
            }
            if (lastMatch == i - 1) {
                run++
                // Superlinear: a run of k consecutive characters is worth far more than
                // k scattered hits, which is what separates "dwnld -> Downloads" from noise.
                score += W_RUN * run
            } else {
                run = 1
                score += W_RUN
            }
            if (isBoundary(hayFold, i, hayRaw)) score += W_BOUNDARY
            lastMatch = i
            n++
        }
        if (n < needle.size) return NO_MATCH

        if (firstAt == 0) score += W_AT_START
        if (needle.size <= hayFold.size && startsWith(hayFold, needle)) score += W_PREFIX
        if (hayFold.size == needle.size && startsWith(hayFold, needle)) score += W_EXACT
        if (inName) score += W_IN_NAME
        if (extFold != null && extFold.isNotEmpty() && matches(needle, extFold)) score += W_EXT_EXACT

        // Small penalty for trailing slack: between two files that both contain the query,
        // the shorter name is nearly always the one meant.
        score -= (hayFold.size - lastMatch - 1).coerceAtMost(40)
        return score
    }

    private fun startsWith(hay: CharArray, needle: CharArray): Boolean {
        if (needle.size > hay.size) return false
        for (i in needle.indices) if (hay[i] != needle[i]) return false
        return true
    }

    private fun matches(needle: CharArray, s: String): Boolean {
        if (needle.size != s.length) return false
        for (i in needle.indices) if (needle[i] != s[i]) return false
        return true
    }

    /**
     * Damerau-Levenshtein distance, capped at [max].
     *
     * Applied only to candidates the subsequence pass rejected, and only within the 500-row
     * set. It is a repair for typos (`screnshot` -> `screenshot`), never a retrieval
     * strategy — running it over a whole index would be quadratic misery.
     *
     * @return the distance, or [max] + 1 when it exceeds the cap.
     */
    fun editDistance(a: CharArray, b: CharArray, max: Int): Int {
        if (kotlin.math.abs(a.size - b.size) > max) return max + 1
        val prev2 = IntArray(b.size + 1)
        val prev = IntArray(b.size + 1)
        val cur = IntArray(b.size + 1)
        for (j in 0..b.size) prev[j] = j
        for (i in 1..a.size) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.size) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, prev2[j - 2] + 1)   // transposition
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            System.arraycopy(prev, 0, prev2, 0, prev.size)
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return if (prev[b.size] > max) max + 1 else prev[b.size]
    }

    /** The tolerance SEARCH.md §5.2 specifies: tighter for short queries, where a typo is rarer. */
    fun typoBudget(queryLength: Int): Int = if (queryLength <= 6) 1 else 2
}

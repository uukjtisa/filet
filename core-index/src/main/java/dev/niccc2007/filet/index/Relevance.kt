package dev.niccc2007.filet.index

/**
 * Whether a name answers a query at all, and how well. SEARCH.md §14.4.
 *
 * ## The defect this is the fix for
 *
 * Searching "minecraft" put `7_6M_views_51K_reactions_EPIC_spinning_backfire.jpg` first. The walk
 * decided membership with the subsequence scorer, which admits any name containing the query's
 * letters in order - m…i…n…e…c…r…a…f…t, scattered across nine words - and a long filename
 * almost always contains most queries that way. The index never did this, because its SQL
 * retrieves only names holding the word as one contiguous piece before anything is scored. So
 * the same query answered differently depending on which source happened to reach a file first,
 * and the loose one was the walk.
 *
 * ## The rule
 *
 * Membership is decided here; ranking is still the scorer's. Each word of the query must be, of
 * this name, one of:
 *
 *  - a **contiguous piece** - and better when it is the whole name, its start, or a word's start;
 *  - a **word-start acronym** - `mc` against `MineCraft`, `mine craft`, `mine_craft`;
 *  - a **typo of one word** in it - within the typo budget against that word or its leading
 *    part, never against the whole name, where a long name is close to almost anything.
 *
 * Scattered letters alone never admit. One function, called by the walk and by the index's
 * rerank alike, so the answer to a query cannot depend on which source answered it.
 */
object Relevance {

    /** How a query word matched, best first. The weight is what ranking starts from. */
    enum class Kind(val weight: Int) {
        EXACT(1000),
        PREFIX(800),
        WORD_START(600),
        CONTAINS(400),
        ACRONYM(300),
        TYPO(150),
    }

    /**
     * One query word's match.
     *
     * @param spans the characters of the NAME that matched, for highlighting. Empty when the
     *   name changes length under case folding - a rare accented case where a span on the folded
     *   string would point at the wrong letters, and no highlight beats a wrong one.
     */
    data class Hit(val kind: Kind, val spans: List<IntRange>)

    /** Every query word's match against one name. Present only when every word matched. */
    data class Verdict(val hits: List<Hit>) {
        val weight: Int get() = hits.sumOf { it.kind.weight }
        val spans: List<IntRange> get() = hits.flatMap { it.spans }
    }

    /**
     * @param words the query, already split and folded - see [wordsOf].
     * @return null when any word fails to match, which is the whole point.
     */
    fun judge(words: List<String>, name: String): Verdict? {
        if (words.isEmpty()) return Verdict(emptyList())
        val fold = Fuzzy.fold(name)
        val aligned = fold.length == name.length
        val parts = partsOf(name)
        val out = ArrayList<Hit>(words.size)
        for (w in words) out += judgeWord(w, name, fold, aligned, parts) ?: return null
        return Verdict(out)
    }

    /** The query as the gate reads it: folded, split on whitespace, empties dropped. */
    fun wordsOf(query: String): List<String> =
        Fuzzy.fold(query).split(' ', '\t').filter { it.isNotEmpty() }

    private fun judgeWord(q: String, name: String, fold: String, aligned: Boolean, parts: List<IntRange>): Hit? {
        // The extension is not part of what a file is called: "notes" is an exact answer for
        // notes.txt, and ranking it as a mere prefix would put notes-old.txt level with it.
        val stem = fold.substringBeforeLast('.', fold).ifEmpty { fold }
        if (q == fold || q == stem) {
            return Hit(Kind.EXACT, if (aligned) listOf(0 until stem.length) else emptyList())
        }

        val at = fold.indexOf(q)
        if (at >= 0) {
            val kind = when {
                at == 0 -> Kind.PREFIX
                aligned && parts.any { it.first == at } -> Kind.WORD_START
                else -> Kind.CONTAINS
            }
            return Hit(kind, if (aligned) listOf(at until at + q.length) else emptyList())
        }

        acronym(q, name, parts, aligned)?.let { return it }
        typo(q, name, parts, aligned)?.let { return it }
        return null
    }

    /**
     * The query's letters as the first letters of consecutive words of the name.
     *
     * Consecutive, not merely in order. Allowing gaps - `mc` against "My Cat … Club" - is the
     * scattered-letters defect again at the level of words.
     */
    private fun acronym(q: String, name: String, parts: List<IntRange>, aligned: Boolean): Hit? {
        if (q.length < 2 || parts.size < q.length) return null
        val initials = parts.map { Fuzzy.fold(name.substring(it.first, it.first + 1)).firstOrNull() ?: ' ' }
        for (start in 0..parts.size - q.length) {
            var ok = true
            for (k in q.indices) if (initials[start + k] != q[k]) { ok = false; break }
            if (ok) {
                val spans = if (aligned) (start until start + q.length).map { parts[it].first..parts[it].first } else emptyList()
                return Hit(Kind.ACRONYM, spans)
            }
        }
        return null
    }

    /**
     * Within the typo budget of one word of the name, or of that word's leading part.
     *
     * The leading part matters for a word typed with a slip before it was finished: "minecarft"
     * should still find "minecraftworld" when the name never separated the two. Words under four
     * letters get no budget at all - one edit from a three-letter word is a third of it, and
     * "cat" admitting "car", "bat" and "cut" is noise, not tolerance.
     */
    private fun typo(q: String, name: String, parts: List<IntRange>, aligned: Boolean): Hit? {
        if (q.length < MIN_TYPO_LENGTH) return null
        val budget = Fuzzy.typoBudget(q.length)
        val needle = q.toCharArray()
        for (p in parts) {
            val word = Fuzzy.fold(name.substring(p.first, p.last + 1))
            if (word.length < MIN_TYPO_LENGTH) continue
            for (len in (q.length - budget)..(q.length + budget)) {
                if (len < MIN_TYPO_LENGTH || len > word.length) continue
                val piece = if (len == word.length) word else word.substring(0, len)
                if (Fuzzy.editDistance(needle, piece.toCharArray(), budget) <= budget) {
                    val span = if (aligned) listOf(p.first until p.first + len) else emptyList()
                    return Hit(Kind.TYPO, span)
                }
            }
        }
        return null
    }

    /**
     * The words of a name, as ranges over it.
     *
     * A boundary is any character that is not a letter or digit, a lower-to-upper step
     * (`mineCraft`), the end of a capital run before a capitalised word (`XMLFile` is XML and
     * File), and a letter-digit step (`IMG2026`). Those are the places a person reading the name
     * would say a new word starts, which is what an acronym or a word-start has to mean.
     */
    fun partsOf(name: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        for (i in name.indices) {
            val c = name[i]
            if (!c.isLetterOrDigit()) {
                if (start >= 0) out += start until i
                start = -1
                continue
            }
            if (start < 0) { start = i; continue }
            val prev = name[i - 1]
            val next = name.getOrNull(i + 1)
            val boundary =
                (prev.isLowerCase() && c.isUpperCase()) ||
                    (prev.isUpperCase() && c.isUpperCase() && next != null && next.isLowerCase()) ||
                    (prev.isLetter() && c.isDigit()) ||
                    (prev.isDigit() && c.isLetter())
            if (boundary) {
                out += start until i
                start = i
            }
        }
        if (start >= 0) out += start until name.length
        return out
    }

    private const val MIN_TYPO_LENGTH = 4
}

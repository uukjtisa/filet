package dev.niccc2007.filet.index

/**
 * Getting a mistyped word out of SQL, so the relevance gate can judge it. SEARCH.md §14.4.
 *
 * ## Why this has to exist now
 *
 * The gate admits a word within its typo budget, but the gate only ever sees what retrieval hands
 * it, and retrieval is strict: an FTS phrase or `LIKE %word%` wants the word as typed, so
 * "minecarft" retrieves nothing and the gate never meets `Minecraft_world.zip`. The live walk used
 * to hide that by reading every name on the phone. Once the walk stood down for a drive the index
 * covers (decision 15), a typo on that drive had no source left that could find it.
 *
 * ## How
 *
 * With the trigram table: every trigram of the word, ORed, ranked by FTS5's own bm25, so the
 * names sharing the most - and the rarest - of the word's trigrams come back first. A one-letter
 * slip leaves most of a word's trigrams intact, which is exactly the property that makes this
 * rank the intended file near the top. Without the table: pieces of the word, any one of which
 * survives the budget's edits, as `LIKE` alternatives. Either way SQL only narrows; the gate
 * decides membership, so nothing retrieved here can admit a row the gate would refuse.
 */
object TypoRetrieval {

    /** Below this many rows from the strict query, look for typos too. */
    const val FEW = 8

    /** How many trigram-ranked rows to hand the gate. */
    const val POOL = 400

    /** The shortest word given a typo budget - the same floor the gate uses. */
    const val MIN_LENGTH = 4

    /** Whether the strict answer is thin enough, and the query typo-able enough, to look again. */
    fun wanted(words: List<String>, found: Int): Boolean =
        found < FEW && words.any { it.length >= MIN_LENGTH }

    /** Every distinct trigram of [word], in order. */
    fun trigrams(word: String): List<String> =
        if (word.length < 3) emptyList() else (0..word.length - 3).map { word.substring(it, it + 3) }.distinct()

    /** The FTS5 expression for [words]: their trigrams, ORed, each one quoted. */
    fun ftsExpression(words: List<String>): String? {
        val grams = words.filter { it.length >= MIN_LENGTH }.flatMap(::trigrams).distinct()
        if (grams.isEmpty()) return null
        return grams.joinToString(" OR ") { "\"" + it.replace("\"", "\"\"") + "\"" }
    }

    /**
     * Pieces of [word] at least one of which is intact in any name within the budget.
     *
     * Pigeonhole: k edits can break at most k of k+1 pieces - except a swap of two letters
     * straddling a boundary, which costs one edit and breaks two. So the word is split twice, the
     * second split shifted by one letter, and a swap on a boundary of one is inside a piece of the
     * other.
     */
    fun pieces(word: String): List<String> {
        if (word.length < MIN_LENGTH) return emptyList()
        val k = Fuzzy.typoBudget(word.length)
        return (split(word, k + 1, 0) + split(word, k + 1, 1)).filter { it.length >= 2 }.distinct()
    }

    private fun split(word: String, parts: Int, shift: Int): List<String> {
        val cuts = (1 until parts).map { (it * word.length / parts + shift).coerceIn(1, word.length - 1) }.distinct()
        val bounds = listOf(0) + cuts + listOf(word.length)
        return bounds.zipWithNext { a, b -> word.substring(a, b) }.filter { it.isNotEmpty() }
    }
}

package dev.niccc2007.filet.vfs

/**
 * How a large listing is broken up so the first rows can be drawn before the last are known.
 *
 * ## Why a plan rather than a fixed chunk size
 *
 * The first chunk and the rest are answering different questions. The first one only has to fill
 * a screen - about fifteen rows in the densest list view, a hundred-odd in the smallest grid -
 * and every entry past that is delay the person can see. The ones after it are no longer racing
 * anything, and small chunks there just pay the re-sort and re-compose cost more often for no
 * visible gain. So the first is deliberately small and the rest are wide.
 *
 * ## Why most folders are not chunked at all
 *
 * Streaming has a cost of its own: the list settles while it fills, because a row that arrives
 * later can sort above one already on screen. That is worth paying when the alternative is
 * seconds of nothing, and not worth paying to save forty milliseconds - so below [WHOLE] the
 * listing is handed over in one piece and behaves exactly as it always has. The threshold is
 * where the wait becomes something a person notices rather than something a profiler does.
 */
object ListChunks {

    /** At or below this many entries, one piece. */
    const val WHOLE = 400

    /** Enough to fill any view step with room to scroll into. */
    const val FIRST = 120

    /** Everything after the first. */
    const val REST = 400

    /**
     * Chunk sizes for [total] entries, in order, summing to exactly [total].
     *
     * A single-element list means no streaming.
     */
    fun plan(total: Int): List<Int> {
        if (total <= 0) return emptyList()
        if (total <= WHOLE) return listOf(total)
        val out = ArrayList<Int>()
        out += FIRST
        var left = total - FIRST
        while (left > 0) {
            out += minOf(REST, left)
            left -= minOf(REST, left)
        }
        return out
    }

    /** Whether [total] entries are worth streaming. */
    fun streams(total: Int): Boolean = plan(total).size > 1
}

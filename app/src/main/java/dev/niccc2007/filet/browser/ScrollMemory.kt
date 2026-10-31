package dev.niccc2007.filet.browser

/**
 * Where one pane was scrolled to, in each folder it has been in.
 *
 * Bug identified here: Back put the folder you came from back at its first row. The position
 * lived in the view, one per pane, and the view starts every folder it is shown at the top - so
 * the folder being returned to had already been forgotten by the time it was shown. It lives
 * with the pane now, one entry per folder, and survives the app closing through [TabRecord].
 *
 * ## A row, not an index
 *
 * A mark names the first row on screen by its list KEY as well as its index. An index alone is
 * wrong on the way back in: a folder arrives in chunks and a tree's branches arrive after the
 * folder, so row 40 of the half-read list is a different file from row 40 of the finished one.
 * [resolve] scrolls to the named row wherever it ends up, and falls back to the index only once
 * everything has arrived and the row is gone.
 */
class ScrollMemory(private val cap: Int = CAP) {

    /** The first row on screen and how far it was scrolled past the top. */
    data class Mark(val index: Int, val offset: Int, val key: String? = null)

    /** A list view and a tile view of the same folder scroll separately. */
    data class Marks(val list: Mark? = null, val grid: Mark? = null)

    // Access order, so the folders visited longest ago are the ones that fall off.
    private val byPlace = object : LinkedHashMap<String, Marks>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Marks>?) = size > cap
    }

    fun park(place: String, marks: Marks) {
        byPlace[place] = marks
    }

    fun at(place: String): Marks? = byPlace[place]

    /**
     * Every place remembered, the one used longest ago first - the order [restore] takes them
     * back in, so the folders that fall off after a restart are the same ones that would have
     * fallen off without it.
     */
    fun all(): List<Pair<String, Marks>> = byPlace.entries.map { it.key to it.value }

    /** Put back what [all] gave, on a pane that has remembered nothing yet. */
    fun restore(places: List<Pair<String, Marks>>) {
        for ((place, marks) in places) byPlace[place] = marks
    }

    /** What the outcome of putting a mark back is, given the rows there are so far. */
    sealed interface Outcome {
        /** The rows that would place it have not all arrived. */
        data object Wait : Outcome

        data class Scroll(val index: Int, val offset: Int) : Outcome
    }

    companion object {
        /** Folders remembered per pane. A long session's worth of returns, and a few KB. */
        const val CAP = 64

        /**
         * Where [mark] lands among [keys], the rows in their list order.
         *
         * @param settled every row has arrived: the listing is complete and every open branch
         *   has been read. Before that, a missing row may simply not be here yet.
         */
        fun resolve(mark: Mark, keys: List<Any>, settled: Boolean): Outcome {
            val at = mark.key?.let { k -> keys.indexOf(k) } ?: -1
            if (at >= 0) return Outcome.Scroll(at, mark.offset)
            if (!settled) return Outcome.Wait
            // Gone, or never named: the index is the best there is. Clamped, and without the
            // offset, which belonged to a row that is not the one now at that index.
            if (keys.isEmpty()) return Outcome.Scroll(0, 0)
            val index = mark.index.coerceIn(0, keys.lastIndex)
            return Outcome.Scroll(index, if (mark.key == null) mark.offset else 0)
        }
    }
}

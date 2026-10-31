package dev.niccc2007.filet.data

/**
 * Which cards a surface shows, and in what order: Home's cards, the side bar's folds.
 *
 * [order] holds every card, hidden ones included, so a card brought back has somewhere to go.
 * [hidden] is the set taken off. Pure, so every rearrangement is a test rather than a drag on a
 * phone.
 *
 * Built with [of], never from stored values directly: what was stored was written by some
 * build, and the cards it knew are not necessarily this build's. A card it never heard of - a
 * new one - goes to its default place rather than to the end or nowhere, and an id this build
 * does not draw is dropped, so neither a future card nor a removed one can wedge the list.
 */
data class SectionLayout(val order: List<String>, val hidden: Set<String>) {

    /** The cards drawn, in order. */
    val visible: List<String> get() = order.filter { it !in hidden }

    /** The cards taken off, in the order they would come back. */
    val hiddenOnes: List<String> get() = order.filter { it in hidden }

    /**
     * The visible card at [from] dropped at [to], both indices into [visible]. Hidden cards
     * keep their places among each other, after the visible ones.
     */
    fun move(from: Int, to: Int): SectionLayout {
        val shown = visible.toMutableList()
        if (from !in shown.indices) return this
        val card = shown.removeAt(from)
        shown.add(to.coerceIn(0, shown.size), card)
        return copy(order = shown + hiddenOnes)
    }

    /** [id] taken off, unless it is one of [locked], which are never hidden. */
    fun hide(id: String, locked: Set<String>): SectionLayout =
        if (id in locked || id !in order) this else copy(hidden = hidden + id)

    /** [id] put back, at the end of the cards that are showing. */
    fun show(id: String): SectionLayout {
        if (id !in hidden) return this
        val shown = visible + id
        return SectionLayout(order = shown + hiddenOnes.filter { it != id }, hidden = hidden - id)
    }

    companion object {

        /**
         * The layout for [defaults] - this build's cards in their default order - from what was
         * stored. See the class note for unknown and new ids. [locked] cards are never hidden,
         * whatever was stored.
         */
        fun of(
            defaults: List<String>,
            storedOrder: List<String>,
            storedHidden: Set<String>,
            locked: Set<String> = emptySet(),
        ): SectionLayout {
            val known = defaults.toSet()
            val order = storedOrder.filter { it in known }.distinct().toMutableList()
            defaults.forEachIndexed { k, id ->
                if (id in order) return@forEachIndexed
                // After the nearest card that comes before it by default, or first.
                val before = defaults.subList(0, k).lastOrNull { it in order }
                order.add(if (before == null) 0 else order.indexOf(before) + 1, id)
            }
            return SectionLayout(order, storedHidden.filter { it in known && it !in locked }.toSet())
        }

        /** The stored form of an order: ids joined with commas. Ids never contain one. */
        fun encode(order: List<String>): String = order.joinToString(",")

        fun decode(raw: String?): List<String> =
            raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

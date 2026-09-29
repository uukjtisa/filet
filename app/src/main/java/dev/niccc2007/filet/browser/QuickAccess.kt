package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath

/**
 * What goes in the navigation pane's Quick access, and in what order.
 *
 * ## The shape this copies, and why
 *
 * Windows Explorer's left pane leads with Quick access: the folders you pinned, then the ones
 * you keep going back to. It works because a file manager's navigation is overwhelmingly
 * repetitive - the same four or five folders, over and over - and a tree that makes you walk
 * down to them every time is making you re-derive something it already knows.
 *
 * ## Ranking is a decision, so it is a function with a test
 *
 * Three rules, and each one has a failure it exists to prevent:
 *
 * 1. **Pinned first, always, in the order they were pinned.** A pin is an explicit statement
 *    and a frequency count is an inference; letting the inference reorder the statement means
 *    somebody's pinned folder moves because they used a different one this morning.
 * 2. **A pinned folder never appears twice.** It is in Quick access because it was pinned, and
 *    it will also be near the top of the frequent list by definition.
 * 3. **Folders only.** A file in a navigation pane is a destination you cannot navigate INTO,
 *    and the pane's whole job is going somewhere.
 *
 * Ties break on recency, because two folders with three visits each are not equally interesting
 * if one of them was last week.
 */
object QuickAccess {

    /** How many frequent entries the pane shows under the pinned ones. */
    const val FREQUENT = 6

    /** How many recent folders the Recent group shows. */
    const val RECENT = 8

    /**
     * A folder that earned its place, with the numbers it earned it by.
     *
     * [pinned] is carried rather than inferred at the call site so the pane can draw the pin
     * without asking a second source and risking a different answer.
     */
    data class Entry(
        val path: VPath,
        val label: String,
        val pinned: Boolean,
        val visits: Int,
        val lastAt: Long,
    )

    /**
     * @param pinned bookmarked folders, in the order they were pinned.
     * @param visited every folder seen, with how many times it was opened and when it was last
     *   opened. Files are filtered out here rather than by the caller, because "only folders" is
     *   part of the rule and a caller that forgets it produces a pane with dead rows in it.
     */
    fun quick(
        pinned: List<Entry>,
        visited: List<Entry>,
        limit: Int = FREQUENT,
    ): List<Entry> {
        val pinnedPaths = pinned.map { it.path }.toSet()
        val frequent = visited
            .filterNot { it.path in pinnedPaths }
            .sortedWith(compareByDescending<Entry> { it.visits }.thenByDescending { it.lastAt })
            .take(limit)
        return pinned.map { it.copy(pinned = true) } + frequent
    }

    /**
     * The Recent group: most recent first, folders only, and never repeating Quick access.
     *
     * The overlap matters. Without it the pane shows the same folder twice, a few rows apart,
     * under two headings that both claim to be about it - which reads as a bug rather than as
     * two views of one fact.
     */
    fun recent(
        visited: List<Entry>,
        alreadyShown: List<Entry>,
        limit: Int = RECENT,
    ): List<Entry> {
        val shown = alreadyShown.map { it.path }.toSet()
        return visited
            .filterNot { it.path in shown }
            .sortedByDescending { it.lastAt }
            .take(limit)
    }
}

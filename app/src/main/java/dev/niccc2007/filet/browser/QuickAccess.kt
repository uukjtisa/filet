package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath

/**
 * What the side bar's Quick access lists under Home, and in what order.
 *
 * Bookmarked folders only. A bookmarked file is not a place to go, and a folder that was never
 * bookmarked is not something anybody asked to keep - the earlier version ranked every visited
 * folder, which filled the fold with whatever had been walked through last.
 *
 * Two kinds of entry, in this order:
 *
 * 1. **Added by hand**, from the Bookmarks list, in the order they were added. Never cut: a
 *    folder somebody put here on purpose does not drop out because the list is long.
 * 2. **The most-used bookmarked folders**, filling whatever is left of [SLOTS]. Recency breaks
 *    a tie, and an unused bookmark still fills an empty slot in bookmark order, so a phone with
 *    three bookmarks shows all three without anything being added by hand.
 *
 * Use is counted by [usedBy] and [workedIn], and stored in `FolderVisits`.
 */
object QuickAccess {

    /** Folders under Home, before anything added by hand pushes past it. */
    const val SLOTS = 6

    /**
     * A bookmarked folder and what it is ranked by.
     *
     * @param addedAt when it was added to Quick access by hand, or 0 for never.
     */
    data class Entry(
        val path: VPath,
        val label: String,
        val addedAt: Long,
        val visits: Int,
        val lastAt: Long,
    )

    fun quick(bookmarked: List<Entry>, limit: Int = SLOTS): List<Entry> {
        val byHand = bookmarked.filter { it.addedAt > 0 }.sortedBy { it.addedAt }
        val rest = bookmarked.filter { it.addedAt <= 0 }
            .sortedWith(compareByDescending<Entry> { it.visits }.thenByDescending { it.lastAt })
        return byHand + rest.take((limit - byHand.size).coerceAtLeast(0))
    }

    /**
     * The folder an open counts as use of: the folder a file was opened from. Opening or walking
     * into a folder counts for nothing - every trip to `Android/data` passes through `Android`,
     * which would otherwise climb the list for being in the way.
     */
    fun usedBy(opened: VPath, isDir: Boolean): VPath? =
        if (isDir) null else opened.parent?.let(::workedIn)

    /**
     * A folder that work was done in - a paste, a new folder, a rename, a delete, an extract -
     * or null where it cannot be kept: inside an archive or an APK, which are mounts that last
     * the session, so a Quick access row into one would open onto nothing next time.
     */
    fun workedIn(folder: VPath): VPath? = folder.takeUnless { it.scheme in TRANSIENT }

    private val TRANSIENT = setOf("zip", "apk")
}

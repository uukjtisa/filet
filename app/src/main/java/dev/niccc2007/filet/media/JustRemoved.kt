package dev.niccc2007.filet.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Files Filet has just removed or moved away, which the media index may still list.
 *
 * Bug identified here: a file deleted from a category or from New files stayed on screen. Both
 * lists read MediaStore, and MediaStore only drops a row once the scanner has been handed the
 * path. [MediaAnnounce] batches those, up to 8 s late, and then New files waited another 2 s
 * to re-read, while a category re-read only when its refresh button was pressed. So the delete
 * succeeded and the row stayed, which reads exactly like the delete having failed. Tapping the
 * row opened nothing.
 *
 * The lists now leave out every row at or under a path in here, from the moment the delete
 * returns.
 *
 * ## When a path leaves
 *
 * Decision: a path leaves when it EXISTS again (something was written back to the same place)
 * or after [KEEP_MS]. It does not leave when its own scanner batch finishes. The lists re-read
 * some time after that batch, and letting go first would draw the deleted row again until the
 * re-read landed. Holding a path that is still absent costs nothing: once it is scanned,
 * MediaStore has no row for it either, so there is nothing left for the entry to hide.
 *
 * Fed from [MediaAnnounce.announce], which every write and removal in the app already goes
 * through: deletes, moves, renames, extraction, received files. That is one place rather than
 * one per operation, so an operation added later is covered without anyone remembering to do
 * it. Each announce carries only the top-level paths of an operation, so the check is a handful
 * of `exists` calls, run on the announcer's own thread.
 */
class JustRemoved(
    private val exists: (String) -> Boolean = { java.io.File(it).exists() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Path to when it was found missing. Insertion order, so the oldest go first at [MAX]. */
    private val held = LinkedHashMap<String, Long>()
    private val _paths = MutableStateFlow<Set<String>>(emptySet())

    /** What the lists leave out right now. Empty almost always, so filtering is usually free. */
    val paths: StateFlow<Set<String>> = _paths.asStateFlow()

    /** [paths] were just written or removed: hold the missing ones, release the ones that are back. */
    fun noted(paths: Collection<String>) {
        if (paths.isEmpty()) return
        synchronized(held) {
            val now = clock()
            var changed = false
            for (p in paths) {
                if (exists(p)) {
                    if (held.remove(p) != null) changed = true
                } else {
                    held.remove(p)
                    held[p] = now
                    changed = true
                }
            }
            if (trim(now) || changed) publish()
        }
    }

    /**
     * A scanner batch has finished: release anything back on disk and anything old. Something
     * another app wrote to the same place is only noticed here.
     */
    fun recheck() {
        synchronized(held) {
            if (held.isEmpty()) return
            val back = held.keys.filter(exists)
            back.forEach { held.remove(it) }
            if (trim(clock()) || back.isNotEmpty()) publish()
        }
    }

    /** Must hold [held]. True when anything was dropped. */
    private fun trim(now: Long): Boolean {
        val before = held.size
        held.entries.removeAll { now - it.value > KEEP_MS }
        while (held.size > MAX) held.remove(held.keys.first())
        return held.size != before
    }

    private fun publish() {
        _paths.value = held.keys.toSet()
    }

    companion object {
        /** Ten minutes: the scanner has had this path for nearly all of it. */
        const val KEEP_MS = 10 * 60_000L

        /** A bound, not an expectation: a whole folder deleted is ONE path here. */
        const val MAX = 5_000

        /**
         * Whether [path] is, or is inside, something in [gone].
         *
         * Walks up the path rather than across the set. Deleting a folder announces the folder,
         * and the rows to hide are the files inside it. A list is thousands of rows, so each
         * row costs its depth rather than the size of the set.
         */
        fun covers(gone: Set<String>, path: String): Boolean {
            if (gone.isEmpty()) return false
            var p = path.trimEnd('/')
            while (p.isNotEmpty()) {
                if (p in gone) return true
                val cut = p.lastIndexOf('/')
                if (cut <= 0) return false
                p = p.substring(0, cut)
            }
            return false
        }

        /**
         * A row's path as this set holds it. A category keeps the whole `local:///storage/...`
         * form and the set holds the plain filesystem path, so comparing the two directly never
         * matched anything. Any other scheme is left whole, and so is never covered.
         */
        fun plain(pathOrUri: String): String = pathOrUri.removePrefix("local://")

        /** [rows] without the ones [gone] covers; the same list, untouched, when nothing is gone. */
        fun <T> without(rows: List<T>, gone: Set<String>, path: (T) -> String): List<T> =
            if (gone.isEmpty()) rows else rows.filterNot { covers(gone, path(it)) }
    }
}

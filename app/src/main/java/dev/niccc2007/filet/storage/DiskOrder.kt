package dev.niccc2007.filet.storage

import dev.niccc2007.filet.browser.naturalCompare
import dev.niccc2007.filet.vfs.VNode

/**
 * The order of a folder in Disk map, and the share each row is of it. Pure.
 *
 * Largest first, whatever the list's own sort says: the question Disk map answers is "what is
 * taking the space", and every other order hides the answer below the fold. Folders and files are
 * mixed, because a 6 GB file beside a 2 GB folder is the thing to see, and grouping folders first
 * would put it after every small folder.
 */
object DiskOrder {

    /**
     * Largest first by [sizeOf]. A row the walk has not reached yet (null) goes after every row it
     * has, so the top of the list never jumps as unread folders arrive below it; ties and unread
     * rows are in name order, so two redraws never order the same rows two ways.
     */
    fun sorted(rows: List<VNode>, sizeOf: (VNode) -> Long?): List<VNode> {
        val sizes = rows.associateWith(sizeOf)
        return rows.sortedWith(
            compareBy<VNode> { if (sizes[it] == null) 1 else 0 }
                .thenByDescending { sizes[it] ?: 0L }
                .then(Comparator { a, b -> naturalCompare(a.name, b.name) }),
        )
    }

    /** [bytes] as a share of [total], from 0 to 1. Zero when either is missing. */
    fun share(bytes: Long?, total: Long?): Float {
        if (bytes == null || total == null || total <= 0) return 0f
        return (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    /**
     * The share in words: whole percents, and `<1%` for something that is there but rounds to
     * nothing - a 40 MB folder on a 128 GB drive printed as `0%` reads as empty.
     */
    fun percent(bytes: Long?, total: Long?): String {
        if (bytes == null || total == null || total <= 0) return ""
        if (bytes <= 0) return "0%"
        val p = bytes * 100.0 / total
        return if (p < 1) "<1%" else "${Math.round(p).coerceAtMost(100)}%"
    }
}

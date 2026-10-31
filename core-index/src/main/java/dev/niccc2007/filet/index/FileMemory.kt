package dev.niccc2007.filet.index

/**
 * What Filet remembers about a file, as opposed to what it worked out.
 *
 * The index is derived: every row in it can be had again by walking the filesystem. This is
 * not. Nothing else on the phone records when a file first appeared HERE - the media index's
 * `DATE_ADDED` resets when an editor saves by delete-and-recreate, mtime travels with a copy,
 * and EXIF says when the content was made, somewhere. So it lives in its own file
 * ([MemoryDb]), under rules the index does not follow: never moved by the cache setting, never
 * offered by a Clear button, and set aside rather than wiped if it will not open.
 *
 * ## Identity is a lineage, not a path
 *
 * A file deleted and another later created under the same name are two files with two
 * histories. Each [ItemMemory] is a lineage id; a path points at at most one live lineage,
 * and a lineage whose file has gone keeps its history with the path it last had.
 *
 * ## What may move a lineage
 *
 * Only a CERTAIN move: Filet's own rename or move, or a crawl relink matched on inode. A crawl
 * relink matched only on name, size and time is a good enough guess for the index - it keeps a
 * shortcut alive - but not for history: a wrong guess would transplant one file's past onto
 * another, which a re-crawl cannot detect or undo. An uncertain match retires the old lineage
 * and the new place starts its own. A gap is honest; a wrong lineage is not.
 */
interface FileMemory {
    /** The crawler, or Filet itself, saw [path]. A no-op if a live lineage already holds it. */
    fun seen(path: String, isDir: Boolean, at: Long, found: Boolean)

    /** A file's size changed between two looks at it. */
    fun resized(path: String, oldSize: Long, newSize: Long, at: Long)

    /** [from] is now [to]. See the class notes for what [certain] decides. */
    fun moved(from: String, to: String, isDir: Boolean, at: Long, certain: Boolean)

    /** [path] is no longer there. Its lineage keeps its history and frees the path. */
    fun gone(path: String, at: Long)

    /** Whether a complete crawl has been through [root] since the memory began. */
    fun baselined(root: String): Boolean

    fun markBaselined(root: String, at: Long)

    /** Whether the one-time import of everything the index already knew has happened. */
    fun seeded(): Boolean

    /** Record what the index already held, all marked [ItemMemory.found], in one transaction. */
    fun seed(paths: Sequence<Pair<String, Boolean>>, at: Long)

    fun of(path: String): ItemMemory?

    /**
     * First-seen for many paths in one go, for a list. Paths with no record are absent.
     * One query per few hundred paths rather than one per row: this is called while a list is
     * being built, and a lookup per row is the slow version of that list.
     */
    fun firstSeen(paths: List<String>): Map<String, Long>

    /** The earliest first-seen of any live lineage under [dir], and whether it was a "found". */
    fun earliestUnder(dir: String): Pair<Long, Boolean>?

    /**
     * Run [block] as one transaction. The memory lives in shared storage, behind FUSE, where
     * every separate commit is an fsync; a directory of a thousand files written row by row
     * would turn a crawl that takes seconds into one that takes minutes.
     */
    fun <T> batch(block: () -> T): T

    fun close()
}

/**
 * One lineage, as the Details sheet reads it.
 *
 * @param found the first look at it was the memory's FIRST look at its volume: it was already
 *   there, so [firstSeen] is only "by then", and the sheet says "before".
 * @param events newest first.
 */
data class ItemMemory(
    val firstSeen: Long,
    val found: Boolean,
    val isDir: Boolean,
    val events: List<MemoryEvent>,
)

/**
 * What the Details sheet gets: the item's own record, and for a folder the earliest first-seen
 * of anything under it, which is evidence the folder was here by then too.
 */
data class MemoryView(val own: ItemMemory?, val earliestInside: Pair<Long, Boolean>?)

data class MemoryEvent(
    val at: Long,
    val kind: MemoryEventKind,
    val fromPath: String? = null,
    val toPath: String? = null,
    val oldSize: Long? = null,
    val newSize: Long? = null,
)

enum class MemoryEventKind(val key: String) {
    RENAMED("renamed"),
    MOVED("moved"),
    RESIZED("resized"),
    GONE("gone"),
    ;

    companion object {
        fun of(key: String): MemoryEventKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The decisions, kept out of SQL so they can be tested.
 */
object MemoryRules {

    /** Same folder, new name, is a rename; anything else is a move. */
    fun kindOf(from: String, to: String): MemoryEventKind =
        if (parentOf(from) == parentOf(to)) MemoryEventKind.RENAMED else MemoryEventKind.MOVED

    fun parentOf(path: String): String {
        val cut = path.trimEnd('/').lastIndexOf('/')
        return if (cut <= 0) path else path.substring(0, cut)
    }

    /**
     * Whether [path] belongs in the memory at all.
     *
     * Filet's own working storage never does. Beat maps, thumbnails, staged copies, the index
     * and this memory's own file are written, renamed and deleted constantly, and the first
     * launch after an update moves every one of them into the Filet folder - recording that
     * would fill the history with hundreds of moves the user never made before it had recorded
     * one they did. Decided by DESTINATION, so it needs nothing from whoever did the writing.
     *
     * @param ownRoots VPath strings of Filet's own folders, e.g. `local:///storage/emulated/0/Filet`.
     */
    fun records(path: String, ownRoots: List<String>): Boolean =
        ownRoots.none { root -> path == root || path.startsWith(root.trimEnd('/') + "/") }

    /**
     * Whether a crawl relink is certain enough to carry a lineage.
     *
     * Only an inode match. Zero is "this backend has no inodes", so a relink on such a volume
     * was necessarily a name-size-time guess.
     */
    fun relinkIsCertain(oldInode: Long, newInode: Long): Boolean = oldInode != 0L && oldInode == newInode

    /** `x/y` and everything under it, as a LIKE pattern with [esc] escaping, for re-keying a folder. */
    fun likeUnder(dir: String, esc: Char = '\\'): String {
        val base = dir.trimEnd('/')
        val sb = StringBuilder()
        for (c in base) {
            if (c == '%' || c == '_' || c == esc) sb.append(esc)
            sb.append(c)
        }
        return sb.append("/%").toString()
    }
}

/**
 * What the Details sheet's "On this phone since" row says.
 *
 * Every source here proves the same thing - "this file was on this phone by time T" - which is
 * the only reason taking the earliest is valid. A date of a different kind (EXIF, mtime) would
 * prove something else and is deliberately not an input; it gets its own row.
 */
sealed interface PhoneSince {
    /** Filet watched it arrive, or the system recorded it arriving, at [at]. */
    data class At(val at: Long, val bySystem: Boolean) : PhoneSince
    /** It was already here when Filet first looked, at [at]; that is all that is known. */
    data class Before(val at: Long) : PhoneSince
    /** Filet has no record yet, and the system has none either. Said, not papered over. */
    data object NotYetSeen : PhoneSince
}

object PhoneSinceRule {

    /**
     * @param memory Filet's record, or null when it has none.
     * @param systemAdded the media index's DATE_ADDED in ms, or null when there is no row.
     */
    fun of(memory: ItemMemory?, systemAdded: Long?): PhoneSince {
        val sys = systemAdded?.takeIf { it > 0 }
        if (memory == null) return if (sys != null) PhoneSince.At(sys, bySystem = true) else PhoneSince.NotYetSeen
        if (sys != null && sys <= memory.firstSeen) return PhoneSince.At(sys, bySystem = true)
        return if (memory.found) PhoneSince.Before(memory.firstSeen) else PhoneSince.At(memory.firstSeen, bySystem = false)
    }

    /** For a folder: the earliest of its own record and anything inside it. */
    fun merge(own: PhoneSince, inside: PhoneSince): PhoneSince {
        fun t(p: PhoneSince) = when (p) { is PhoneSince.At -> p.at; is PhoneSince.Before -> p.at; PhoneSince.NotYetSeen -> Long.MAX_VALUE }
        return if (t(inside) < t(own)) inside else own
    }
}

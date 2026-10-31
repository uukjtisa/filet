package dev.niccc2007.filet.browser

/**
 * What a folder holds: how many things, how many bytes, and which child is the biggest.
 *
 * Pure on purpose. The same numbers come from two places - a walk of the real folder and a
 * recursive query over the search index - and both go through [TallyBuilder], so there is one
 * piece of arithmetic to get right and one test file that proves it.
 *
 * [bytes] is APPARENT size: the sum of the files' lengths. The system Storage screen counts
 * disk blocks instead, so it will disagree - higher for thousands of tiny files, lower for
 * sparse ones. The sheet says "sum of file sizes" beside the number so the difference reads as
 * two different measurements rather than a bug.
 */
data class FolderTally(
    /** Every file under the folder, at any depth. Hard links count once per name. */
    val files: Long,
    /** Every folder under the folder, at any depth, not counting itself. */
    val folders: Long,
    val bytes: Long,
    /** Files directly inside, not in a subfolder. */
    val directFiles: Int,
    val directFolders: Int,
    /** The direct child holding the most bytes, file or folder, or null for an empty folder. */
    val largestName: String?,
    val largestBytes: Long,
    val largestIsDir: Boolean,
    /** Folders that could not be listed. Non-zero means every number is a floor. */
    val unreadable: Int,
    /** Names that were a second link to bytes already counted, so not added again. */
    val sharedLinks: Int,
) {
    /** True when something could not be read, so the sheet says "at least". */
    val partial: Boolean get() = unreadable > 0

    companion object {
        val EMPTY = FolderTally(0, 0, 0, 0, 0, null, 0, false, 0, 0)
    }
}

/**
 * Accumulates a [FolderTally] from a stream of entries, in any order.
 *
 * Each entry names the DIRECT child of the measured folder it sits under ([top]), which is
 * what makes the largest child fall out of the same pass instead of needing a second one.
 */
class TallyBuilder {
    private var files = 0L
    private var folders = 0L
    private var bytes = 0L
    private var directFiles = 0
    private var directFolders = 0
    private var unreadable = 0
    private var shared = 0
    private val perTop = HashMap<String, Long>()
    private val topIsDir = HashMap<String, Boolean>()
    private val seenLinks = HashSet<LinkKey>()

    /**
     * Hard-link identity.
     *
     * Conditional on [inode] being non-zero, because zero is the documented "this backend has
     * no inodes" value - SAF, archives, network shares - and treating every zero as the same
     * file would collapse a whole share into one entry. Size and mtime ride along with the
     * inode because a walk can cross a mount point, and inode numbers are only unique within
     * one filesystem; two links to one file always agree on both, two unrelated files that
     * happen to share an inode number on different mounts almost never do.
     */
    private data class LinkKey(val inode: Long, val size: Long, val mtime: Long)

    /**
     * @param direct whether this entry is itself a direct child (then [top] is its own name).
     * @param size a file's length; ignored for folders, whose bytes are their contents.
     */
    fun add(top: String, direct: Boolean, isDir: Boolean, size: Long, inode: Long, mtime: Long) {
        if (direct) topIsDir[top] = isDir
        if (isDir) {
            folders++
            if (direct) directFolders++
            perTop.putIfAbsent(top, 0L)
            return
        }
        files++
        if (direct) directFiles++
        val len = size.coerceAtLeast(0L)
        if (inode != 0L && !seenLinks.add(LinkKey(inode, len, mtime))) {
            shared++
            return
        }
        bytes += len
        perTop[top] = (perTop[top] ?: 0L) + len
    }

    /** A folder that refused a listing. Its contents are missing from every number. */
    fun unreadable() { unreadable++ }

    fun build(): FolderTally {
        // Ties go to the name that sorts first, so the same folder always names the same child.
        val top = perTop.entries.maxWithOrNull(compareBy<Map.Entry<String, Long>> { it.value }.thenByDescending { it.key })
        return FolderTally(
            files = files,
            folders = folders,
            bytes = bytes,
            directFiles = directFiles,
            directFolders = directFolders,
            largestName = top?.key,
            largestBytes = top?.value ?: 0L,
            largestIsDir = top?.let { topIsDir[it.key] } ?: false,
            unreadable = unreadable,
            sharedLinks = shared,
        )
    }
}

/**
 * Which folders a walk may enter.
 *
 * Symlinks are followed (the local provider stats through them, deliberately, so a link to a
 * folder lists as a folder), which means a link back up the tree is a cycle and an unguarded
 * walk never ends - and `/storage` has such links. The guard is the folder's inode: a second
 * arrival at an inode already walked is a loop or a second route to the same place, and either
 * way its contents are already counted.
 *
 * Backends with no inodes (zero) cannot form those cycles through Filet - SAF, archives and
 * network shares give no way to link a folder back into itself - so they fall through to the
 * depth cap alone, which exists so that a backend that lies cannot hang the sheet either.
 */
class LoopGuard(private val maxDepth: Int = MAX_DEPTH) {
    private val seen = HashSet<Long>()

    /** True if the folder at [depth] (0 = the measured folder) with [inode] should be walked. */
    fun enter(inode: Long, depth: Int): Boolean {
        if (depth > maxDepth) return false
        if (inode == 0L) return true
        return seen.add(inode)
    }

    companion object {
        /** Far past any real tree; Android's 4096-character path limit is hit long before. */
        const val MAX_DEPTH = 256
    }
}

/**
 * Where a cached figure came from, which the sheet names, because "last known" is only
 * honest with a "known when".
 */
enum class TallySource {
    /** The search index's last crawl. Survives restarts; can miss what the crawl skips. */
    INDEX,
    /** A walk finished earlier in this run of the app. */
    WALK,
}

data class CachedTally(val tally: FolderTally, val source: TallySource, val at: Long)

/**
 * The state of the live walk for one folder.
 */
sealed interface WalkState {
    /** Not started, and not going to start on its own: a remote folder, which costs a network
     *  round trip per directory and so is measured only when asked. */
    data object OnRequest : WalkState
    data class Running(val soFar: FolderTally) : WalkState
    data class Done(val tally: FolderTally, val at: Long) : WalkState
    data class Failed(val reason: String) : WalkState
}

/**
 * What the size rows on the sheet say.
 *
 * Four states rather than two, and the split between the first two is the point: "Measuring"
 * has no number to stand on yet and shows the running count, "Confirming" already has a last
 * known figure and keeps showing it until the walk replaces it. A sheet that blanked a known
 * number every time it was opened would be slower to read than having no cache at all.
 */
sealed interface SizeView {
    /** Nothing known yet; the walk is running. [soFar] is the count it has reached. */
    data class Measuring(val soFar: FolderTally) : SizeView
    /** A last-known figure, being re-checked now. */
    data class Confirming(val shown: CachedTally) : SizeView
    /** The walk finished; this is what is there. */
    data class Confirmed(val tally: FolderTally, val at: Long) : SizeView
    /** Not measured, and the sheet offers a button. [shown] is a cached figure if there is one. */
    data class NotMeasured(val shown: CachedTally?) : SizeView
    /** The walk failed. [shown] is still worth showing, marked as unconfirmed. */
    data class Unconfirmed(val shown: CachedTally?, val reason: String) : SizeView
}

object FolderSizes {

    /**
     * Whether a folder is walked as soon as its sheet opens.
     *
     * Decided by the provider's own `remote` flag rather than a list of schemes, so a provider
     * added later is classified by declaring what it is - it cannot be forgotten in a list here.
     */
    fun walksOnOpen(remote: Boolean): Boolean = !remote

    /**
     * The better of two cached figures: the most recent one.
     *
     * A walk from this session beats the index unless the index has crawled since, because a
     * walk sees everything the crawl was told to skip and is never older than its timestamp.
     */
    fun pick(walk: CachedTally?, index: CachedTally?): CachedTally? = when {
        walk == null -> index
        index == null -> walk
        index.at > walk.at -> index
        else -> walk
    }

    fun view(cached: CachedTally?, walk: WalkState): SizeView = when (walk) {
        is WalkState.Done -> SizeView.Confirmed(walk.tally, walk.at)
        is WalkState.Running -> if (cached != null) SizeView.Confirming(cached) else SizeView.Measuring(walk.soFar)
        is WalkState.Failed -> SizeView.Unconfirmed(cached, walk.reason)
        WalkState.OnRequest -> SizeView.NotMeasured(cached)
    }

    /** `142 files in 9 folders`, singular where it is one. */
    fun contentsLine(t: FolderTally): String {
        if (t.files == 0L && t.folders == 0L) return if (t.partial) "nothing readable" else "empty"
        val f = plural(t.files, "file")
        return if (t.folders == 0L) f else "$f in ${plural(t.folders, "folder")}"
    }

    /** `12 here: 3 folders, 9 files`. */
    fun directLine(t: FolderTally): String {
        val n = t.directFiles + t.directFolders
        if (n == 0) return "none"
        val parts = buildList {
            if (t.directFolders > 0) add(plural(t.directFolders.toLong(), "folder"))
            if (t.directFiles > 0) add(plural(t.directFiles.toLong(), "file"))
        }
        return parts.joinToString(", ")
    }

    fun plural(n: Long, word: String): String = "${grouped(n)} $word${if (n == 1L) "" else "s"}"

    /** Thousands separated, without a Locale: the sheet shows a number, not a currency. */
    fun grouped(n: Long): String {
        val t = n.toString()
        if (t.length <= 3) return t
        return buildString {
            for ((i, c) in t.withIndex()) {
                if (i > 0 && c != '-' && (t.length - i) % 3 == 0) append(',')
                append(c)
            }
        }
    }
}

package dev.niccc2007.filet.storage

/**
 * What one walk of a drive found: every folder's total, the largest files, and what kinds take the
 * space. The model behind Storage, the file list's disk-map mode. Pure, so every rule is a test.
 *
 * ## Why one map for the whole walk, not a figure per folder
 *
 * A total per folder, measured when the folder is shown, makes every step down the tree a walk of
 * its own - and the step down is the whole use of a disk map: the biggest folder, then the
 * biggest folder in that. TreeSize reads the drive once and then every folder in it answers at
 * once. So does this: a file read anywhere below a folder adds its bytes to every folder above it,
 * up to [root], and a folder shown later reads its total off the map.
 *
 * ## Settled, and not before
 *
 * A total is a floor until the walk has finished everything beneath the folder - see [settle].
 * The file list already draws a floor with a `+` (see `FolderTotal`), and this map is what tells
 * it which it has. A figure must never read as final while part of the folder is unread: a half
 * measured `Download` showing 700 MB of a real 39 GB is the fault that type was made for.
 *
 * Paths are plain strings with `/` separators, as `VPath.toString()` prints them; the map never
 * touches storage. Not thread-safe: the walk owns it, and readers take [snapshot]s.
 */
class SizeMap(val root: String, private val keepLargest: Int = LARGEST) {

    /** One folder's totals, everything beneath it included. */
    class Tally {
        var bytes = 0L
        var files = 0
        var folders = 0
        val kinds = LongArray(SizeKind.entries.size)
        var settled = false
    }

    /** A file among the largest. */
    data class Big(val path: String, val bytes: Long, val kind: SizeKind)

    private val tallies = HashMap<String, Tally>()

    /** The largest files read so far, largest first, at most [keepLargest]. */
    private val largest = ArrayList<Big>()

    /** Files read so far, for the strip's "12,340 files read". */
    var filesRead = 0
        private set

    init {
        tallies[norm(root)] = Tally()
    }

    /** Forgets everything, for a walk from scratch. */
    fun clear() {
        tallies.clear()
        largest.clear()
        filesRead = 0
        tallies[norm(root)] = Tally()
    }

    /** A file of [bytes] at [path], of [kind]: added to every folder from its own up to [root]. */
    fun addFile(path: String, bytes: Long, kind: SizeKind) {
        val size = bytes.coerceAtLeast(0)
        filesRead++
        for (folder in ancestors(path)) {
            val t = tallies.getOrPut(folder) { Tally() }
            t.bytes += size
            t.files++
            t.kinds[kind.ordinal] += size
        }
        keep(Big(path, size, kind))
    }

    /** A folder at [path] was found: counted in every folder above it, and given a tally of its own. */
    fun addFolder(path: String) {
        val p = norm(path)
        tallies.getOrPut(p) { Tally() }
        for (folder in ancestors(p)) tallies.getOrPut(folder) { Tally() }.folders++
    }

    /**
     * One folder's files at once: the same map as [addFile] for each in turn, with one climb up
     * the folders above for the whole listing rather than one per file.
     *
     * Bug identified here: the walk added a folder's files one at a time under the lock every
     * reader takes, and each one climbed every folder above it - a string cut and a hash lookup per
     * level, per file. A camera folder of 8,000 photos ten levels down was 80,000 of each with the
     * lock held, and a frame that asked the map for anything in that time waited for all of it.
     *
     * Files are expected to share one folder, as a listing's do. One that does not is added on its
     * own, so the result never depends on that holding.
     */
    fun addFiles(files: List<Big>) {
        if (files.isEmpty()) return
        val first = norm(files[0].path)
        val cut = first.lastIndexOf('/')
        var bytes = 0L
        var count = 0
        val kinds = LongArray(SizeKind.entries.size)
        for (f in files) {
            val p = norm(f.path)
            if (p.lastIndexOf('/') != cut || !p.regionMatches(0, first, 0, cut)) {
                addFile(f.path, f.bytes, f.kind)
                continue
            }
            val size = f.bytes.coerceAtLeast(0)
            filesRead++
            bytes += size
            count++
            kinds[f.kind.ordinal] += size
            keep(if (size == f.bytes) f else f.copy(bytes = size))
        }
        if (count == 0) return
        for (folder in ancestors(first)) {
            val t = tallies.getOrPut(folder) { Tally() }
            t.bytes += bytes
            t.files += count
            for (i in kinds.indices) t.kinds[i] += kinds[i]
        }
    }

    /** One folder's subfolders at once: the same map as [addFolder] for each. See [addFiles]. */
    fun addFolders(paths: List<String>) {
        if (paths.isEmpty()) return
        val first = norm(paths[0])
        val cut = first.lastIndexOf('/')
        var count = 0
        for (path in paths) {
            val p = norm(path)
            if (p.lastIndexOf('/') != cut || !p.regionMatches(0, first, 0, cut)) {
                addFolder(path)
                continue
            }
            tallies.getOrPut(p) { Tally() }
            count++
        }
        if (count == 0) return
        for (folder in ancestors(first)) tallies.getOrPut(folder) { Tally() }.folders += count
    }

    /**
     * Everything beneath [path] has been read, so its total is an answer rather than a floor.
     * The walk calls it on the way back up, once a folder and every folder in it are done.
     */
    fun settle(path: String) {
        tallies.getOrPut(norm(path)) { Tally() }.settled = true
    }

    /** The total under [path], or null when the walk has not reached it. */
    fun bytes(path: String): Long? = tallies[norm(path)]?.bytes

    /** Whether [path]'s total is final. False for a folder the walk has not finished. */
    fun settled(path: String): Boolean = tallies[norm(path)]?.settled == true

    fun files(path: String): Int = tallies[norm(path)]?.files ?: 0

    fun folders(path: String): Int = tallies[norm(path)]?.folders ?: 0

    /** What kinds take the space under [path], largest first, the empty ones left out. */
    fun kinds(path: String): List<Pair<SizeKind, Long>> {
        val t = tallies[norm(path)] ?: return emptyList()
        return SizeKind.entries.map { it to t.kinds[it.ordinal] }.filter { it.second > 0 }.sortedByDescending { it.second }
    }

    /** The largest files anywhere under [path], largest first, at most [limit]. */
    fun largestUnder(path: String, limit: Int = 100): List<Big> {
        val p = norm(path)
        val prefix = if (p.endsWith("/")) p else "$p/"
        return largest.asSequence().filter { it.path.startsWith(prefix) }.take(limit).toList()
    }

    /**
     * A file or folder at [path] is gone - deleted or moved away from the list. Its bytes come off
     * every folder above it at once, so the bars move with the delete rather than at the next
     * walk, and a folder's own tallies are dropped with everything inside it.
     */
    fun remove(path: String) {
        val p = norm(path)
        val own = tallies[p]
        val bytes: Long
        val files: Int
        val folders: Int
        val kinds: LongArray
        if (own != null) {
            bytes = own.bytes
            files = own.files
            folders = own.folders + 1
            kinds = own.kinds.copyOf()
            val prefix = "$p/"
            tallies.keys.removeAll { it == p || it.startsWith(prefix) }
            largest.removeAll { it.path.startsWith(prefix) }
        } else {
            val big = largest.firstOrNull { it.path == p }
            // A file too small to be among the largest is not remembered on its own; its size
            // comes from the caller's listing through [removeFile].
            if (big == null) return
            bytes = big.bytes
            files = 1
            folders = 0
            kinds = LongArray(SizeKind.entries.size).also { it[big.kind.ordinal] = big.bytes }
            largest.remove(big)
        }
        for (folder in ancestors(p)) {
            val t = tallies[folder] ?: continue
            t.bytes = (t.bytes - bytes).coerceAtLeast(0)
            t.files = (t.files - files).coerceAtLeast(0)
            t.folders = (t.folders - folders).coerceAtLeast(0)
            for (i in kinds.indices) t.kinds[i] = (t.kinds[i] - kinds[i]).coerceAtLeast(0)
        }
    }

    /** A file whose size the caller knows is gone. See [remove], which cannot know a small file's. */
    fun removeFile(path: String, bytes: Long, kind: SizeKind) {
        val p = norm(path)
        largest.removeAll { it.path == p }
        for (folder in ancestors(p)) {
            val t = tallies[folder] ?: continue
            t.bytes = (t.bytes - bytes).coerceAtLeast(0)
            t.files = (t.files - 1).coerceAtLeast(0)
            t.kinds[kind.ordinal] = (t.kinds[kind.ordinal] - bytes).coerceAtLeast(0)
        }
    }

    /**
     * The folders from [path]'s parent up to [root], nearest first. Empty for a path outside the
     * root: a file the walk was not asked about adds to nothing rather than to the wrong folder.
     */
    private fun ancestors(path: String): List<String> {
        val r = norm(root)
        val out = ArrayList<String>()
        var p = norm(path)
        if (p == r || !p.startsWith(if (r.endsWith("/")) r else "$r/")) return out
        while (true) {
            val cut = p.lastIndexOf('/')
            val parent = if (cut <= 0) "/" else p.substring(0, cut)
            // At or past the root's length is the root itself: `file:///a`'s parent prints as
            // `file://`, shorter than a root of `file:///`, and is still that root.
            if (parent.length <= r.length) {
                out += r
                break
            }
            out += parent
            p = parent
        }
        return out
    }

    private fun keep(big: Big) {
        if (largest.size >= keepLargest && big.bytes <= largest.last().bytes) return
        // Binary search for the place, largest first.
        var lo = 0
        var hi = largest.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (largest[mid].bytes >= big.bytes) lo = mid + 1 else hi = mid
        }
        largest.add(lo, big)
        if (largest.size > keepLargest) largest.removeAt(largest.lastIndex)
    }

    companion object {
        /** How many of the largest files are kept for the Largest files view. */
        const val LARGEST = 2_000

        /**
         * [path] without a trailing slash - except where the slash is part of the root itself, as
         * in `/` or `file:///`, which is any slash that follows another.
         */
        fun norm(path: String): String =
            if (path.length > 1 && path.endsWith("/") && path[path.length - 2] != '/') path.dropLast(1) else path
    }
}

/**
 * What a file is, for the By type view. Ordered as the view lists them when sizes tie. Read from
 * the one file type table, never from a list of extensions here - see [of].
 */
enum class SizeKind(val label: String) {
    VIDEOS("Videos"),
    PICTURES("Pictures"),
    AUDIO("Audio"),
    ARCHIVES("Archives"),
    PACKAGES("Packages"),
    DOCUMENTS("Documents"),
    OTHER("Everything else");

    companion object {
        /** The group a file of the table's [kind] counts under; an unknown one is Everything else. */
        fun of(kind: dev.niccc2007.filet.vfs.types.TypeKind?): SizeKind = when (kind) {
            dev.niccc2007.filet.vfs.types.TypeKind.VIDEO -> VIDEOS
            dev.niccc2007.filet.vfs.types.TypeKind.IMAGE -> PICTURES
            dev.niccc2007.filet.vfs.types.TypeKind.AUDIO -> AUDIO
            dev.niccc2007.filet.vfs.types.TypeKind.ARCHIVE -> ARCHIVES
            dev.niccc2007.filet.vfs.types.TypeKind.APK, dev.niccc2007.filet.vfs.types.TypeKind.DEX -> PACKAGES
            dev.niccc2007.filet.vfs.types.TypeKind.DOC, dev.niccc2007.filet.vfs.types.TypeKind.CODE -> DOCUMENTS
            dev.niccc2007.filet.vfs.types.TypeKind.BINARY, dev.niccc2007.filet.vfs.types.TypeKind.OTHER, null -> OTHER
        }
    }
}

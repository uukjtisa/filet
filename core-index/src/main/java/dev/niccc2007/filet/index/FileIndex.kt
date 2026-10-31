package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.flow.StateFlow

/**
 * A file's biography (PLAN.md §5.1).
 *
 * Android has no mark-of-the-web and no `kMDItemWhereFroms`: a file on your phone has no
 * memory of where it came from. Trawl already knows all of this and currently discards it.
 */
data class Provenance(
    /** Package name of whatever wrote this record. */
    val sourceApp: String,
    /** The URL or other origin the file came from. What `from:` searches. */
    val origin: String?,
    val pageTitle: String? = null,
    val uploader: String? = null,
    val format: String? = null,
    val at: Long = System.currentTimeMillis(),
    /** Anything the writer wants to keep, as JSON. Never parsed by Filet. */
    val extra: String? = null,
)

/**
 * One entry under a folder, streamed out of [FileIndex.subtree].
 *
 * Primitives rather than a row object: the measured folder can be the whole of internal
 * storage, and a hundred thousand allocations to compute one sum is the slow version.
 *
 * @param top the name of the measured folder's direct child this entry sits under (its own
 *   name when [direct]).
 */
/** One file or folder as the media store lists it, for [FileIndex.seed]. */
data class SeedEntry(val path: VPath, val isDir: Boolean, val size: Long, val mtime: Long)

fun interface SubtreeSink {
    fun accept(top: String, direct: Boolean, isDir: Boolean, size: Long, inode: Long, mtime: Long)
}

/** A candidate row from the index. Deliberately flat: the reranker touches 500 of these. */
data class IndexRow(
    val id: Long,
    val name: String,
    val nameFold: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val parentId: Long?,
    val volumeId: Long,
    val origin: String?,
    val opens: Int,
    val lastOpenAt: Long,
    val pinned: Boolean,
    /**
     * The generation this row was last written at.
     *
     * Carried out of the database so a result can say whether the running pass has reached it
     * yet. See [hitState].
     */
    val gen: Long = 0,
)

data class IndexStatus(
    val enabled: Boolean = true,
    val available: Boolean = false,
    val files: Long = 0,
    val lastRunAt: Long = 0,
    /**
     * When a pass was last STARTED, complete or not.
     *
     * Separate from [lastRunAt] because they answer different questions and merging them is
     * what produced a crawl that never stopped. What the user is told is when a pass last
     * finished; what decides whether to start another is when one was last tried. A tree too
     * big for one budget never finishes, so on the merged number it was permanently overdue.
     */
    val lastAttemptAt: Long = 0,
    val running: Boolean = false,
    val phase: String = "",
    val scanned: Long = 0,
    /**
     * The folder the crawl is reading right now, or empty between passes.
     *
     * Bug identified: the only thing on screen was a count, and a count moving in thousands is
     * indistinguishable from a count that has stopped - so a long crawl read as a hung app.
     * A path that keeps changing is the cheapest possible proof of life, and it costs one
     * string per forty directories.
     */
    val reading: String = "",
    /**
     * How many files were already indexed when the running pass started.
     *
     * The index was never being rebuilt - `scanned` counts THIS RUN, and it was the only
     * number on screen, so a fresh run reading 0 looked exactly like the index had been thrown
     * away. Keeping the previous total beside it makes that impossible to misread.
     */
    val knownAtStart: Long = 0,
    val ftsAccelerated: Boolean = false,
    /**
     * Whether anything is registered to look *inside* files.
     *
     * Without it `pkg:` `class:` `perm:` `label:` `inzip:` and `member:` cannot match, and the
     * search UI must not offer them - a field that always returns nothing is a dead switch
     * (PLAN.md R1).
     */
    val containerFacts: Boolean = false,
    /**
     * The query the running crawl has been bent toward, or null.
     *
     * Non-null only while a crawl is running and somebody has searched during it. The search
     * screen shows it, because a detour that happens silently looks like the index being
     * slow rather than the index working on your behalf.
     */
    val steeredFor: String? = null,
    /**
     * The generation the running pass is stamping, or the last complete one between passes.
     *
     * A row carrying an older generation has not been reached by the running pass yet, which
     * is exactly what [hitState] needs to know to label a result.
     */
    val writingGen: Long = 0,
) {
    /**
     * SEARCH.md §7.11 - the controls are only honest if the readout beside them is real.
     * Never says "up to date"; it says when it last ran, because that is what is known.
     */
    val headline: String
        get() = when {
            !enabled -> "Index off"
            // A pass in flight is reported BEFORE "no index yet", because during a first build
            // both are true and only one of them is useful. The old order said "No index yet"
            // for the entire length of the build that was fixing exactly that.
            //
            // Two different events, said differently. "Indexing" on a device that already has
            // thirty thousand files indexed is not what is happening, and saying it is what
            // made a running pass read as an index that had been thrown away.
            running && knownAtStart > 0 -> "Updating the index"
            running -> "Building the index"
            !available -> "No index yet"
            files == 0L -> "Index empty"
            else -> "$files files indexed"
        }

    /** Thousands separated: two bare five-digit numbers side by side are hard to compare. */
    private fun grouped(n: Long): String = if (n < 1000) n.toString() else {
        val t = n.toString()
        buildString { for ((i, c) in t.withIndex()) { if (i > 0 && (t.length - i) % 3 == 0) append(','); append(c) } }
    }

    val detail: String
        get() = buildString {
            if (running) {
                // The total that was already there stays on screen. Without it this line read
                // "0 scanned so far" the moment a pass started, under an index of thirty
                // thousand files, which is indistinguishable from having lost the lot.
                if (knownAtStart > 0) {
                    append("Checked ${grouped(scanned)} of ${grouped(knownAtStart)} so far. ")
                } else {
                    append("${grouped(scanned)} found so far. ")
                }
            } else if (lastRunAt > 0) {
                append("Last run ")
                append(agoText(lastRunAt))
                append(". ")
            }
            if (!enabled) {
                append("Search still works — it walks the filesystem instead, which is slower on a whole device.")
            } else if (!available) {
                append("Nothing has been crawled yet. Search falls back to walking the filesystem.")
            } else if (!ftsAccelerated) {
                append("Running without the full-text accelerator on this device; short queries still use the prefix index.")
            } else {
                append("The index is a candidate generator. Every result is re-checked against the filesystem before it is shown.")
            }
        }

    private fun agoText(at: Long): String {
        val d = System.currentTimeMillis() - at
        return when {
            d < 60_000 -> "just now"
            d < 3_600_000 -> "${d / 60_000} min ago"
            d < 86_400_000 -> "${d / 3_600_000} h ago"
            else -> "${d / 86_400_000} days ago"
        }
    }
}

data class CrawlResult(
    val visitedDirs: Int,
    val seenFiles: Int,
    val changedDirs: Int,
    val complete: Boolean,
    val elapsedMs: Long,
    /**
     * Files recognised as *moved* rather than deleted-and-recreated, and therefore carried
     * forward with their stable id intact. Every one of these is a pinned shortcut, a
     * provenance record and an open-count that survived (PLAN.md L5).
     */
    val movedFiles: Int = 0,
    /** Files whose insides were parsed this pass - APK manifests, archive directories. */
    val factsWritten: Int = 0,
    /**
     * Files the generation sweep removed because this pass did not find them.
     *
     * Reported so the UI can say "4 gone" rather than only a net change. A net number hides
     * the case that matters - 200 added and 200 removed looks identical to nothing happening.
     */
    val removedFiles: Int = 0,
    /** Files this pass indexed that were not in the index before it started. */
    val addedFiles: Int = 0,
)

/**
 * The index (PLAN.md L1).
 *
 * > The index is a candidate generator. The filesystem is the truth.
 *
 * Nothing here is required for correctness. Every method may return nothing, and search,
 * shortcuts and provenance all still work - just colder. That is deliberate: on Android any
 * design where "the index is right" depends on "the background job ran" is broken, and
 * spectacularly so on an aggressive OEM.
 */
interface FileIndex {

    val status: StateFlow<IndexStatus>

    /** ~500 rows for the reranker to sort through. Fast and dumb by design. */
    suspend fun candidates(req: SearchRequest, parsed: ParsedQuery): List<IndexRow>

    /**
     * The stable ID for a path, assigning one if the file is known.
     *
     * **A shortcut never stores a path. It stores this ID** (PLAN.md L5). Paths break on
     * every move and rename; the ID survives both because the crawler maintains the mapping.
     */
    suspend fun idFor(path: VPath): Long?

    /**
     * The generation currently stamped on each of these paths, for the ones the index holds.
     *
     * Bug identified: a result's state was computed once, when the row was emitted, and then
     * frozen. So a row found before the crawl reached it stayed labelled as being checked for
     * as long as the results were on screen, however long the crawl ran - the badge was a
     * snapshot of one instant rather than a thing that resolves while you watch.
     *
     * Reported exactly that way: the confirming badges never cleared in order as the pass
     * went past them.
     *
     * A page of results is a few dozen rows, so re-reading their generations on a slow tick is
     * cheap, and it is the only way the label can be true a second after it is drawn.
     */
    suspend fun generationsFor(paths: List<VPath>): Map<VPath, Long>

    /** Reassembles the current path for an ID by walking `parent_id`. Null when it is gone. */
    suspend fun pathFor(id: Long): VPath?

    /** Records a real interaction for frecency. Never called for a mere appearance in a list. */
    suspend fun recordOpen(path: VPath)

    suspend fun setPinned(path: VPath, pinned: Boolean)

    suspend fun putProvenance(path: VPath, record: Provenance)

    suspend fun provenanceOf(path: VPath): Provenance?

    /** Rows whose provenance origin matches, for `from:` queries. */
    suspend fun byOrigin(fragment: String, limit: Int): List<IndexRow>

    /** Facts about what is INSIDE a container: package name, classes, permissions, entries. */
    suspend fun putInner(path: VPath, kind: String, values: List<String>)

    suspend fun innerOf(id: Long, kind: String): List<String>

    /**
     * Crawl [roots], skipping directories whose mtime has not moved.
     *
     * @param budgetMs stop cleanly when exceeded. Every run is interruptible; a background
     *   crawl that must finish to be useful is a crawl that never finishes on a phone.
     *
     *   **Zero or negative means no deadline** - run until the tree is walked, or until the
     *   coroutine is cancelled. That is the mode a user-initiated "Index now" uses, because a
     *   truncated pass over the phone does NOT pick up where it left off: the walk restarts at
     *   the roots, and only the directory-mtime skip lets the next one get further. A deadline
     *   is a background courtesy, not a checkpoint.
     *
     *   The exception is a pass over ONE share, which keeps its frontier and resumes from it -
     *   SEARCH.md §14.7. A NAS is the tree least able to be walked in one sitting.
     *
     * A root may be a whole volume or a folder inside one already held, and the two conclude
     * different things: a folder pass deletes only under its folder and never marks a volume
     * complete. See [CrawlScope].
     */
    /**
     * @param onProgress how many files have been seen, and the folder currently being read.
     *   The path matters as much as the count: a count that moves in thousands looks exactly
     *   like a count that has stopped, and the difference between waiting and force-stopping
     *   the app is being able to see that something is still being opened.
     */
    suspend fun crawl(
        roots: List<VPath>,
        budgetMs: Long,
        onProgress: (Long, VPath?) -> Unit = { _, _ -> },
    ): CrawlResult

    /**
     * Bend a running crawl toward [query] for a while.
     *
     * Searching during the first crawl finds nothing, because the crawler is walking in its
     * own order and has not reached what you meant. This reorders what is still pending so
     * the likely folders are walked next, then lets go on its own after [DETOUR_MS].
     *
     * A reordering, never a filter: see `CrawlPriority.kt`. Nothing is skipped, and ending
     * the detour puts the remaining queue back into discovery order exactly.
     *
     * No-op when no crawl is running. Blank clears it.
     */
    fun steerCrawl(query: String)

    /**
     * Files that are byte-for-byte identical to at least one other file (FEATURES.md F62).
     *
     * > size group (SQL, no IO) -> head+tail 4 KB xxHash64 -> full hash. Reaches stage 3 with
     * > dozens of files, not thousands.
     *
     * @param minSize ignore anything smaller; a device has thousands of identical empty files
     *   and zero interest in them.
     * @return groups of paths, each group two or more files with identical content.
     */
    suspend fun duplicates(scope: VPath? = null, minSize: Long = 4096, limitGroups: Int = 200): List<List<VPath>>

    /**
     * Everything the index holds under [path], one call to [sink] per entry, in no order.
     *
     * The Details sheet's last-known size and counts: the index already has every row, so the
     * figure is a recursive query over `parent_id` rather than a walk. It is only as fresh as
     * the last crawl and misses whatever the crawl was told to skip, which is why the sheet
     * shows it as "last known" and confirms it with a walk.
     *
     * @return false when the folder is not in the index at all, so the caller can tell
     *   "nothing indexed here" from "indexed, and empty".
     */
    suspend fun subtree(path: VPath, sink: SubtreeSink): Boolean = false

    /**
     * What Filet remembers about [path] - see [FileMemory]. Null when there is no memory at all
     * (a test, the desktop, a memory that would not open) or the path is Filet's own storage.
     */
    suspend fun memoryOf(path: VPath): MemoryView? = null

    /** First-seen from the memory for a whole page of paths at once. Empty without a memory. */
    suspend fun firstSeenOf(paths: List<VPath>): Map<VPath, Long> = emptyMap()

    /** Forget one subtree, e.g. when a volume is unmounted or a folder is deleted. */
    suspend fun forget(path: VPath)

    /**
     * Drop every volume [keep] rejects, with every row under it. SEARCH.md §13.7: a deleted
     * share, an edit that changed what its paths mean, or a share whose indexing was turned off.
     *
     * @return how many volumes went.
     */
    suspend fun forgetVolumes(keep: (uuid: String) -> Boolean): Int = 0

    /**
     * The `volume.uuid` of every volume a complete pass has been through.
     *
     * What lets the live walk stand down for a drive the index already answers - SEARCH.md
     * §14.2, decision 15. A volume that has only ever had partial passes is not in it, and is
     * walked as well as searched.
     */
    suspend fun completeVolumes(): Set<String> = emptySet()

    /**
     * Write one folder's listing, read by something that is not a crawl. SEARCH.md §14.7.
     *
     * A search walking a share has already listed the folder over the network; writing the names
     * down costs nothing on the wire, and makes the next search of the same folder instant. The
     * listing is the folder's whole content, so a row the index held under [dir] and the listing
     * does not name is gone.
     *
     * Dropped rather than waited on while a crawl holds the index, and while [root] has a
     * complete pass of its own unfinished.
     *
     * @param root the volume [dir] is on. A share is registered as a volume the first time.
     * @param stillOwned asked at the moment of writing, so a share deleted since its folder was
     *   listed is not registered again.
     */
    suspend fun putListing(root: VPath, dir: VPath, children: List<VNode>, stillOwned: () -> Boolean = { true }) {}

    /**
     * Re-read [dirs] and write what is in them. One `readdir` each, SEARCH.md §14.6.
     *
     * For the media store's change reports and the generation catch-up. A folder on no volume
     * the index holds is skipped rather than registered, because a folder registered as a volume
     * is the defect [CrawlScope] exists for.
     *
     * @return how many folders were read.
     */
    suspend fun refreshFolders(dirs: Collection<VPath>): Int = 0

    /**
     * A first build from the media store's own table, before any pass has been. SEARCH.md §14.2.
     *
     * Only adds, never deletes or overwrites, and only for volumes that have never completed a
     * pass: what a crawl has read is better than what the media store believes. Written under the
     * generation BEFORE the next pass, so the first complete pass sweeps whatever it listed that
     * is not really there.
     *
     * @return how many rows were added.
     */
    suspend fun seed(roots: List<VPath>, entries: Sequence<SeedEntry>): Int = 0

    suspend fun clear()

    fun close()
}

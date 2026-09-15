package dev.niccc2007.filet.index

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
)

data class IndexStatus(
    val enabled: Boolean = true,
    val available: Boolean = false,
    val files: Long = 0,
    val lastRunAt: Long = 0,
    val running: Boolean = false,
    val phase: String = "",
    val scanned: Long = 0,
    /**
     * How many files were already indexed when the running pass started.
. The index was
     * never being rebuilt - `scanned` counts THIS RUN, and it was the only number on screen,
     * so a fresh run reading 0 looked exactly like the index had been thrown away. Keeping the
     * previous total beside it makes that impossible to misread.
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
) {
    /**
     * SEARCH.md §7.11 - the controls are only honest if the readout beside them is real.
     * Never says "up to date"; it says when it last ran, because that is what is known.
     */
    val headline: String
        get() = when {
            !enabled -> "Index off"
            !available -> "No index yet"
            running -> "Indexing — $phase"
            files == 0L -> "Index empty"
            else -> "$files files indexed"
        }

    val detail: String
        get() = buildString {
            if (running) {
                append("$scanned scanned so far. ")
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
     *   truncated pass does NOT pick up where it left off: the walk restarts at the roots
     *   every time, so a budget smaller than the whole tree indexes the same first N files
     *   forever and never reaches the rest. A deadline is a background courtesy, not a
     *   checkpoint.
     */
    suspend fun crawl(roots: List<VPath>, budgetMs: Long, onProgress: (Long) -> Unit = {}): CrawlResult

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

    /** Forget one subtree, e.g. when a volume is unmounted or a folder is deleted. */
    suspend fun forget(path: VPath)

    suspend fun clear()

    fun close()
}

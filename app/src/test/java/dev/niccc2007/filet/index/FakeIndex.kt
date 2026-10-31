package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An index that holds nothing, for the things that take one and are not about it.
 *
 * [FileIndex] is a wide interface on purpose - search, shortcuts, provenance and container facts
 * all hang off it - and a test about folder sizes needs exactly one of its methods. Written once
 * here rather than per test file, because a reflection proxy answering every method with a
 * default is the version that breaks confusingly: a method the implementation starts calling
 * silently returns null instead of failing to compile.
 *
 * Every method answers "nothing", the same way `NullIndex` does in the app. Open so a test can
 * override only what it is about; [subtree] is the one that is already configurable, through
 * [rows].
 */
open class FakeIndex(
    /**
     * How many 100-byte files [subtree] reports under any folder, or null for "not indexed",
     * which is what the real index returns for a folder its crawl has never reached.
     */
    var rows: Int? = null,
) : FileIndex {

    val state = MutableStateFlow(IndexStatus(available = true, lastRunAt = 500L, writingGen = 1))
    override val status: StateFlow<IndexStatus> get() = state

    /** How many times [subtree] has actually been streamed, for the memo tests. */
    var reads = 0
        private set

    override suspend fun subtree(path: VPath, sink: SubtreeSink): Boolean {
        reads++
        val n = rows ?: return false
        repeat(n) { sink.accept("f$it", true, false, 100L, it.toLong(), 1L) }
        return true
    }

    override suspend fun candidates(req: SearchRequest, parsed: ParsedQuery): List<IndexRow> = emptyList()
    override suspend fun idFor(path: VPath): Long? = null
    override suspend fun generationsFor(paths: List<VPath>): Map<VPath, Long> = emptyMap()
    override suspend fun pathFor(id: Long): VPath? = null
    override suspend fun recordOpen(path: VPath) = Unit
    override suspend fun setPinned(path: VPath, pinned: Boolean) = Unit
    override suspend fun putProvenance(path: VPath, record: Provenance) = Unit
    override suspend fun provenanceOf(path: VPath): Provenance? = null
    override suspend fun byOrigin(fragment: String, limit: Int): List<IndexRow> = emptyList()
    override suspend fun putInner(path: VPath, kind: String, values: List<String>) = Unit
    override suspend fun innerOf(id: Long, kind: String): List<String> = emptyList()
    override suspend fun duplicates(scope: VPath?, minSize: Long, limitGroups: Int): List<List<VPath>> = emptyList()
    override suspend fun crawl(
        roots: List<VPath>,
        budgetMs: Long,
        onProgress: (Long, VPath?) -> Unit,
    ): CrawlResult = CrawlResult(0, 0, 0, true, 0)

    override fun steerCrawl(query: String) = Unit
    override suspend fun forget(path: VPath) = Unit
    override suspend fun clear() = Unit
    override fun close() = Unit
}

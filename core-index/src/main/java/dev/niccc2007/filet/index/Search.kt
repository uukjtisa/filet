package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** How far a pane search reaches. Explicit, never guessed - SEARCH.md §4 of the round-4 log. */
enum class SearchScope(val label: String) {
    FOLDER("This folder"),
    SUBFOLDERS("Subfolders"),
    DEVICE("Whole device"),
    PROVENANCE("Provenance"),
}

data class SearchRequest(
    val query: String,
    val scope: SearchScope,
    /** Where the pane is standing. Also supplies the subtree boost in [Frecency]. */
    val origin: VPath?,
    val roots: List<VPath> = emptyList(),
    val showHidden: Boolean = false,
    val limit: Int = 500,
)

data class SearchHit(
    val node: VNode,
    val score: Int,
    /** Directory this was found in, shown under the name so a result is locatable. */
    val where: String,
) {
    val key: String get() = node.path.toString()
}

/** A place results can come from. The live walk is one; the SQLite index is the other. */
interface SearchSource {
    /** Emits hits as they are found. Must be cancellable at every step. */
    fun search(req: SearchRequest): Flow<SearchHit>

    /** True when this source can answer [req] at all. */
    fun handles(req: SearchRequest): Boolean
}

/**
 * Search by walking the filesystem through the VFS.
 *
 * Correct everywhere and fast enough for a folder or a modest subtree; too slow for a whole
 * device, which is what the index in M3 is for. It stays in the app after that as the
 * fallback for volumes with no index (a freshly mounted SMB share, an archive) and as the
 * thing that proves the index is not lying.
 *
 * Breadth-first so shallow matches - which are nearly always the wanted ones - arrive first.
 */
class WalkSearchSource(private val vfs: Vfs) : SearchSource {

    override fun handles(req: SearchRequest) = req.scope != SearchScope.PROVENANCE

    override fun search(req: SearchRequest): Flow<SearchHit> = flow {
        val parsed = QueryParser.parse(req.query)
        // One needle per word - the same rule the indexed path uses, so a query behaves
        // identically whether or not the index happens to be warm. A single needle with a
        // space in it can only match a name that also has a space in it.
        val needles = Fuzzy.fold(parsed.free).split(' ', '\t')
            .filter { it.isNotEmpty() }
            .map { it.toCharArray() }
        val roots = when (req.scope) {
            SearchScope.FOLDER, SearchScope.SUBFOLDERS -> listOfNotNull(req.origin)
            else -> req.roots.ifEmpty { listOfNotNull(req.origin) }
        }
        val deep = req.scope != SearchScope.FOLDER
        var emitted = 0

        val queue = ArrayDeque(roots)
        val seen = HashSet<String>()
        while (queue.isNotEmpty() && emitted < req.limit) {
            currentCoroutineContext().ensureActive()
            val dir = queue.removeFirst()
            if (!seen.add(dir.toString())) continue
            val children = runCatching { vfs.list(dir) }.getOrNull() ?: continue
            for (child in children) {
                currentCoroutineContext().ensureActive()
                if (child.hidden && !req.showHidden) continue
                if (deep && child.isDir) queue.addLast(child.path)
                val hit = scoreOf(child, needles, parsed) ?: continue
                emit(hit)
                if (++emitted >= req.limit) break
            }
        }
    }

    private fun scoreOf(node: VNode, needles: List<CharArray>, parsed: ParsedQuery): SearchHit? {
        val c = NodeCandidate(node)
        if (!QueryEval.matches(parsed.root, c)) return null
        if (needles.isEmpty()) return SearchHit(node, 0, node.path.parent?.path ?: "")

        val fold = Fuzzy.fold(node.name).toCharArray()
        val raw = node.name.toCharArray()
        var score = 0
        for (needle in needles) {
            var s = Fuzzy.score(needle, fold, raw, true, node.extension)
            if (s == Fuzzy.NO_MATCH) {
                // Typo repair, applied only to a candidate the subsequence pass rejected.
                val budget = Fuzzy.typoBudget(needle.size)
                val d = Fuzzy.editDistance(needle, fold, budget)
                if (d > budget) return null
                s = 200 - d * 60
            }
            score += s
        }
        return SearchHit(node, score, node.path.parent?.path ?: "")
    }
}

/** Adapts a [VNode] to the query evaluator without copying it. */
class NodeCandidate(val node: VNode, private val originOf: String? = null) : Candidate {
    override val name: String get() = node.name
    override val pathText: String get() = node.path.path
    override val isDir: Boolean get() = node.isDir
    override val size: Long get() = node.size
    override val mtime: Long get() = node.mtime
    override val extension: String get() = node.extension
    override val origin: String? get() = originOf
}

/**
 * Frecency, SEARCH.md §5.4.
 *
 * ```
 * boost = 1 + 0.8*ln(1+opens)*exp(-dt/tau)  + 0.5 in-subtree + 0.3 tracked + 1.2 pinned
 * ```
 *
 * Opens are recorded on real interactions only. Counting a mere appearance in a result list
 * makes the ranking eat itself: whatever ranked first yesterday ranks first forever.
 */
object Frecency {
    private const val TAU_MS = 14.0 * 24 * 3600 * 1000

    fun boost(
        opens: Int,
        lastAt: Long,
        now: Long,
        inCurrentSubtree: Boolean,
        inTrackedFolder: Boolean,
        pinned: Boolean,
    ): Double {
        var b = 1.0
        if (opens > 0 && lastAt > 0) {
            val dt = (now - lastAt).coerceAtLeast(0).toDouble()
            b += 0.8 * kotlin.math.ln(1.0 + opens) * kotlin.math.exp(-dt / TAU_MS)
        }
        if (inCurrentSubtree) b += 0.5
        if (inTrackedFolder) b += 0.3
        if (pinned) b += 1.2
        return b
    }
}

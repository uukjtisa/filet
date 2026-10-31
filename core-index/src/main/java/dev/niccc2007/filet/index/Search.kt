package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** How far a pane search reaches. Explicit, never guessed - SEARCH.md §4 of the round-4 log. */
data class SearchRequest(
    val query: String,
    /** How far this search covers. See [Reach] for what replaced `SearchScope` and why. */
    val reach: Reach,
    /** Where the pane is standing. Also supplies the subtree boost in [Frecency]. */
    val origin: VPath?,
    /** Every drive root currently available. Only [Reach.Everywhere] reads it. */
    val roots: List<VPath> = emptyList(),
    /**
     * Whether results may come from inside hidden folders. The search bar's own switch, not the
     * browser's show-hidden - see SEARCH.md §14.5 for why the two are separate questions.
     */
    val showHidden: Boolean = false,
    val limit: Int = 500,
    /**
     * Told how many folders each walker has read, for the status line and the share headers.
     *
     * Per request rather than a property of the source, because two panes can search at once
     * and one shared counter would report the sum of both as either.
     */
    val onWalked: ((root: VPath, folders: Int) -> Unit)? = null,
)

data class SearchHit(
    val node: VNode,
    val score: Int,
    /** Directory this was found in, shown under the name so a result is locatable. */
    val where: String,
    /**
     * Whether this is known to be there or is still being checked by a running pass.
     *
     * A walk answers from the filesystem, so what it returns it has just seen - those are
     * always [HitState.AVAILABLE]. Only the index can be behind.
     */
    val state: HitState = HitState.AVAILABLE,
    /**
     * The generation the index held for this row when it was emitted, or -1 for a hit that did
     * not come from the index.
     *
     * Kept so the state can be recomputed while the results are on screen. A walk hit is the
     * filesystem reporting what it has just seen, so it has no generation and never changes.
     */
    val gen: Long = -1,
    /**
     * The characters of the name that matched, for highlighting. SEARCH.md §14.4.
     *
     * Why a result is there should always be visible on the row. A name like
     * `Microsoft.MinecraftUWP_8wekyb3d8bbwe` reads as an unrelated hit for "minecraft" until the
     * matched piece is lit.
     */
    val spans: List<IntRange> = emptyList(),
) {
    val key: String get() = node.path.toString()
}

/**
 * The ranking score for a name the gate let in: its match kinds first, then the subsequence
 * scorer as a tiebreak inside a kind.
 *
 * The tiebreak is capped below the gap between two kinds, so it can order two contains-matches
 * but can never lift a contains-match over a prefix. The scorer used to BE the decision; now it
 * only breaks ties, which is the job it is good at.
 */
fun relevanceScore(verdict: Relevance.Verdict, needles: List<CharArray>, name: String, ext: String): Int {
    if (needles.isEmpty()) return verdict.weight
    val fold = Fuzzy.fold(name).toCharArray()
    val raw = name.toCharArray()
    var tie = 0
    for (n in needles) {
        val s = Fuzzy.score(n, fold, raw, true, ext)
        if (s != Fuzzy.NO_MATCH) tie += (s / 10).coerceIn(0, TIEBREAK_CAP)
    }
    return verdict.weight + tie.coerceAtMost(TIEBREAK_CAP)
}

/** Less than the smallest gap between two [Relevance.Kind] weights. */
private const val TIEBREAK_CAP = 99

/**
 * True when [path] sits under a hidden folder, or is hidden itself. SEARCH.md §14.5.
 *
 * Every segment, not just the name. The index used to test only a row's own name, so a file
 * inside `.hwthumbnails` passed with hidden folders off - which is how a thumbnail cache came to
 * be the first answer to a search.
 */
fun underHidden(path: VPath): Boolean = path.segments.any { it.startsWith(".") }

/** A place results can come from. The live walk is one; the SQLite index is the other. */
interface SearchSource {
    /** Emits hits as they are found. Must be cancellable at every step. */
    fun search(req: SearchRequest): Flow<SearchHit>

    /** True when this source can answer [req] at all. */
    fun handles(req: SearchRequest): Boolean

    /**
     * Which of the two kinds this is.
     *
     * Bug identified: the pane resolved a planned [SourceKind] by list position -
     * `firstOrNull` for the index and `lastOrNull` for the walk - and then called the result
     * without checking it accepted the request it was about to be given. With a single-folder
     * scope the index declines, so the plan asked for an index and got handed one anyway, with
     * a request it had already said it could not answer.
     *
     * A source declaring its own kind removes the guess. A plan asks for a kind and gets that
     * kind or nothing.
     */
    val kind: SourceKind
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
 *
 * ## One walker per drive
 *
 * Bug identified here: every root shared ONE breadth-first queue, so a folder on internal storage
 * waited its turn behind folders on a network share, each of which is a round trip over Wi-Fi.
 * Searching "minecraft" everywhere showed one local hit, then nothing for seconds while the queue
 * worked through a NAS, then the rest of internal storage. Each root now has its own walker, run
 * in parallel, so local results are independent of the slowest share by construction.
 *
 * @param onListed every directory a walk reads, with what was in it. How a share walked live by a
 *   search ends up indexed without being crawled - SEARCH.md §14.7. Called on the walker's own
 *   coroutine; a listener that throws is ignored, because indexing is never worth failing a
 *   search for.
 */
class WalkSearchSource(
    private val vfs: Vfs,
    private val onListed: suspend (dir: VPath, children: List<VNode>) -> Unit = { _, _ -> },
) : SearchSource {

    override val kind = SourceKind.WALK


    /**
     * Everything except a provenance query.
     *
     * Where a file came from is recorded in the index and is written nowhere on the
     * filesystem, so a walk cannot answer it however long it is given. That is a property of
     * the QUERY, not of the reach - which is why it is read from the parsed query here rather
     * than from a reach member, now that `SearchScope.PROVENANCE` is gone (SEARCH.md §13.4).
     */
    override fun handles(req: SearchRequest) = !QueryParser.parse(req.query).needsProvenance

    override fun search(req: SearchRequest): Flow<SearchHit> = channelFlow {
        val parsed = QueryParser.parse(req.query)
        // One needle per word - the same rule the indexed path uses, so a query behaves
        // identically whether or not the index happens to be warm. A single needle with a
        // space in it can only match a name that also has a space in it.
        val words = Relevance.wordsOf(parsed.free)
        val needles = words.map { it.toCharArray() }
        val roots = req.reach.walkRoots(req.origin, req.roots).distinctBy { it.toString() }
        val deep = req.reach.deep
        // Shared by every walker, so the limit is on the search and not on each drive.
        val emitted = java.util.concurrent.atomic.AtomicInteger(0)

        for (root in roots) launch {
            val queue = ArrayDeque(listOf(root))
            val seen = HashSet<String>()
            var folders = 0
            while (queue.isNotEmpty() && emitted.get() < req.limit) {
                ensureActive()
                val dir = queue.removeFirst()
                if (!seen.add(dir.toString())) continue
                val children = runCatching { vfs.list(dir) }.getOrNull() ?: continue
                folders++
                req.onWalked?.invoke(root, folders)
                runCatching { onListed(dir, children) }
                for (child in children) {
                    ensureActive()
                    if (child.hidden && !req.showHidden) continue
                    if (deep && child.isDir) queue.addLast(child.path)
                    val hit = scoreOf(child, needles, words, parsed) ?: continue
                    if (emitted.incrementAndGet() > req.limit) return@launch
                    send(hit)
                }
            }
        }
    }

    private fun scoreOf(
        node: VNode,
        needles: List<CharArray>,
        words: List<String>,
        parsed: ParsedQuery,
    ): SearchHit? {
        val c = NodeCandidate(node)
        if (!QueryEval.matches(parsed.root, c)) return null
        // Membership is the gate's. This used to be the subsequence scorer, which admits any
        // name holding the query's letters in order - see Relevance for the row that proved it.
        val verdict = Relevance.judge(words, node.name) ?: return null
        return SearchHit(
            node,
            relevanceScore(verdict, needles, node.name, node.extension),
            node.path.parent?.path ?: "",
            spans = verdict.spans,
        )
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
 * boost = 1 + 0.8*ln(1+opens)*exp(-dt/tau)  + 0.5 in-subtree + 1.2 pinned
 * ```
 *
 * Opens are recorded on real interactions only. Counting a mere appearance in a result list
 * makes the ranking eat itself: whatever ranked first yesterday ranks first forever.
 *
 * The `+ 0.3 tracked` term is gone with the feature it referred to. Tracked folders were
 * retired when New files became device-wide, so the only caller could pass nothing but
 * `false` - a parameter whose value is decided at the declaration is not a parameter, and one
 * that reads like a working ranking term is worse than none, because the next person to touch
 * the ranking will budget for it.
 */
object Frecency {
    private const val TAU_MS = 14.0 * 24 * 3600 * 1000

    fun boost(
        opens: Int,
        lastAt: Long,
        now: Long,
        inCurrentSubtree: Boolean,
        pinned: Boolean,
    ): Double {
        var b = 1.0
        if (opens > 0 && lastAt > 0) {
            val dt = (now - lastAt).coerceAtLeast(0).toDouble()
            b += 0.8 * kotlin.math.ln(1.0 + opens) * kotlin.math.exp(-dt / TAU_MS)
        }
        if (inCurrentSubtree) b += 0.5
        if (pinned) b += 1.2
        return b
    }
}

package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Search through the index: retrieve, rerank, then verify.
 *
 * > The index is a candidate generator. The filesystem is the truth.
 *
 * SQL is asked only to be fast and dumb - at most 500 rows. The scorer is allowed to be slow
 * and clever because it never sees more than that. And nothing reaches the screen without a
 * `stat` confirming it still exists, so a deleted file cannot be painted (SEARCH.md §4.3).
 */
class IndexSearchSource(
    private val index: FileIndex,
    private val vfs: Vfs,
) : SearchSource {

    /**
     * Only claims the scopes the index can answer *better* than a walk.
     *
     * A single folder is faster to just read - it is one `readdir` against a cold index
     * lookup plus a verification pass. Handing it to the index would be slower and could be
     * stale, for no gain.
     */
    override fun handles(req: SearchRequest): Boolean {
        if (!index.status.value.enabled || !index.status.value.available) return false
        return req.scope == SearchScope.DEVICE ||
            req.scope == SearchScope.PROVENANCE ||
            req.scope == SearchScope.SUBFOLDERS
    }

    override fun search(req: SearchRequest): Flow<SearchHit> = flow {
        val parsed = QueryParser.parse(req.query)
        val rows = index.candidates(req, parsed)
        // One needle per word, not one needle for the whole query.
        //
        // The scorer looks for its needle as a subsequence of the name, so a query with a
        // space in it needs a space in the name to match. `quarterly recon` would therefore
        // score zero against `quarterly-reconciliation-2026.xlsx` and be dropped - after SQL
        // had correctly retrieved it. Every word must match; the score is their sum, so more
        // of the query matching ranks higher.
        val needles = Fuzzy.fold(parsed.free).split(' ', '\t')
            .filter { it.isNotEmpty() }
            .map { it.toCharArray() }
        val now = System.currentTimeMillis()
        val originPrefix = req.origin?.path

        val scored = ArrayList<Pair<IndexRow, Double>>(rows.size)
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            if (row.isDir && req.scope == SearchScope.PROVENANCE) continue
            if (!req.showHidden && row.name.startsWith(".")) continue

            val fold = row.nameFold.toCharArray()
            val raw = row.name.toCharArray()
            val ext = extOf(row.name)

            var base = 0
            var rejected = false
            for (needle in needles) {
                var s = Fuzzy.score(needle, fold, raw, true, ext)
                if (s == Fuzzy.NO_MATCH) {
                    val budget = Fuzzy.typoBudget(needle.size)
                    val d = Fuzzy.editDistance(needle, fold, budget)
                    if (d > budget) { rejected = true; break }
                    s = 200 - d * 60
                }
                base += s
            }
            if (rejected) continue
            if (base <= 0) base = 1

            val boost = Frecency.boost(
                opens = row.opens,
                lastAt = row.lastOpenAt,
                now = now,
                // "What you are looking at is what you probably mean" beats any clever
                // relevance maths, so the subtree boost is applied before anything else.
                inCurrentSubtree = false,
                inTrackedFolder = false,
                pinned = row.pinned,
            )
            scored += row to base * boost
        }

        scored.sortByDescending { it.second }

        var emitted = 0
        for ((row, score) in scored) {
            currentCoroutineContext().ensureActive()
            val path = index.pathFor(row.id) ?: continue
            if (originPrefix != null && req.scope == SearchScope.SUBFOLDERS &&
                !path.path.startsWith(originPrefix)
            ) continue
            // Verify before display. A row the index still believes in but the filesystem
            // has dropped is exactly the stale result this whole design exists to prevent.
            val live = runCatching { vfs.stat(path) }.getOrNull() ?: continue
            emit(SearchHit(live, score.toInt(), path.parent?.path ?: ""))
            if (++emitted >= 200) break
        }
    }

    private fun extOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }
}

/**
 * Runs a live walk and the index together, preferring whichever answers.
 *
 * Not a merge: merging two ranked streams reshuffles the list under the user's thumb, which
 * SEARCH.md §5.3 names as the single most-hated behaviour in a search UI. The index answers
 * when it can, the walk answers when it cannot, and the seam is invisible because both feed
 * the same scorer.
 */
class LayeredSearch(private val sources: List<SearchSource>) : SearchSource {
    override fun handles(req: SearchRequest) = sources.any { it.handles(req) }
    override fun search(req: SearchRequest): Flow<SearchHit> =
        sources.first { it.handles(req) }.search(req)
}

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
    override val kind = SourceKind.INDEX

    override fun handles(req: SearchRequest): Boolean {
        if (!index.status.value.enabled || !index.status.value.available) return false
        // Everything but a single folder, which is one `readdir` against an index lookup plus
        // a verification pass - slower, and able to be stale, for no gain. The exception is a
        // provenance query, which the filesystem cannot answer at any reach.
        if (req.reach == Reach.Folder) return QueryParser.parse(req.query).needsProvenance
        return true
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
        val words = Relevance.wordsOf(parsed.free)
        val needles = words.map { it.toCharArray() }
        val now = System.currentTimeMillis()
        val originPrefix = req.origin?.path

        val scored = ArrayList<Triple<IndexRow, Double, List<IntRange>>>(rows.size)
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            // A directory has no origin of its own, so it can never satisfy `from:`.
            if (row.isDir && parsed.needsProvenance) continue
            if (!req.showHidden && row.name.startsWith(".")) continue

            // The same gate the walk uses, so a query cannot answer differently depending on
            // which source reached a file first. SQL has already narrowed to contiguous pieces;
            // this ranks them and carries the matched span to the row.
            val verdict = Relevance.judge(words, row.name) ?: continue
            var base = relevanceScore(verdict, needles, row.name, extOf(row.name))
            if (base <= 0) base = 1

            val boost = Frecency.boost(
                opens = row.opens,
                lastAt = row.lastOpenAt,
                now = now,
                // "What you are looking at is what you probably mean" beats any clever
                // relevance maths, so the subtree boost is applied before anything else.
                inCurrentSubtree = false,
                pinned = row.pinned,
            )
            scored += Triple(row, base * boost, verdict.spans)
        }

        scored.sortByDescending { it.second }

        var emitted = 0
        for ((row, score, spans) in scored) {
            currentCoroutineContext().ensureActive()
            val path = index.pathFor(row.id) ?: continue
            if (originPrefix != null && req.reach == Reach.Subtree &&
                !path.path.startsWith(originPrefix)
            ) continue
            // A row's own name is not the only way to be hidden. Inside `.hwthumbnails` a file
            // called `7_6M_views…jpg` is as hidden as the folder, and testing the name alone is
            // how a thumbnail cache became the first answer to a search. SEARCH.md §14.5.
            if (!req.showHidden && underHidden(path)) continue
            // Emitted unchecked, as Confirming. SEARCH.md §14.3.
            //
            // This used to stat every row before emitting it, one after another, so the first
            // paint waited on the filesystem and the label on top of that waited on a crawl -
            // which with an unbounded crawl on every open meant Confirming for minutes. The
            // check now runs beside the search in the pane, top rows first, and a row cannot be
            // opened until it has passed. What is shown here is what the index believes; what
            // can be acted on is what the filesystem confirmed.
            emit(
                SearchHit(
                    VNode(path, row.isDir, row.size, row.mtime),
                    score.toInt(), path.parent?.path ?: "",
                    state = HitState.CONFIRMING,
                    gen = row.gen,
                    spans = spans,
                ),
            )
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
    override val kind = SourceKind.INDEX
    override fun handles(req: SearchRequest) = sources.any { it.handles(req) }
    override fun search(req: SearchRequest): Flow<SearchHit> =
        sources.first { it.handles(req) }.search(req)
}

package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VPath

/**
 * Steering a running crawl toward whatever somebody just searched for.
 *
 * The gap Nic found: search during a first crawl finds nothing, because the crawler is walking
 * in its own order and has not reached the folder you meant yet. His fix, and it is the right
 * one - let the query bend the crawl for a while, then let it go back.
 *
 * Two rules make that safe, and both are what the tests here pin down:
 *
 * 1. **Nothing is dropped.** A detour is a reordering of the pending list, never a filter. The
 *    crawl still visits every directory it was going to visit.
 * 2. **The original order is recoverable.** Every pending entry carries the sequence number it
 *    was discovered with, so ending the detour is a sort back to discovery order rather than a
 *    hope that the permutation was harmless.
 */

/** One directory still to be walked, with the order it was discovered in. */
data class Pending(val order: Long, val nodeId: Long, val path: VPath)

/**
 * A query the crawl is currently bending toward.
 *
 * @param budgetMs how long the detour may last. It exists because a query that matches nothing
 *   would otherwise steer the crawl for the rest of the run: the reorder costs nothing after
 *   the first pass, but the deprioritised noise folders would sit at the back forever and the
 *   crawl would finish in an order nobody asked for.
 */
data class Detour(val query: String, val startedAt: Long, val budgetMs: Long = DETOUR_MS) {
    fun expired(now: Long): Boolean = now - startedAt >= budgetMs
}

/** Long enough to cover a search and the reading of its results, short enough to be a detour. */
const val DETOUR_MS = 45_000L

/**
 * How much a directory is worth looking in, given what was asked for.
 *
 * Higher comes first. The parts, in the order they matter:
 *
 * - **A segment that matches a query word.** The strongest signal there is, and it is why
 *   searching "invoice" during a crawl reaches `Documents/Invoices` almost at once.
 * - **Depth.** Shallow beats deep among equals. What people look for is usually a few levels
 *   down, and the deep tails are caches and package data.
 * - **Noise.** `Android/data`, `Android/obb`, `.thumbnails`, `node_modules` and friends are
 *   enormous and are almost never the answer, so they go to the back for the duration.
 *
 * @param query what the user typed, matched by word. An empty query scores everything zero,
 *   which is what makes [steer] with no query a no-op rather than a reshuffle.
 */
fun steerScore(path: VPath, query: String): Int {
    val words = query.lowercase().split(WORD_BREAK).filter { it.length >= 2 }
    if (words.isEmpty()) return 0
    val segments = path.segments
    var score = 0

    val last = segments.lastOrNull()?.lowercase()
    if (last != null) {
        for (w in words) {
            when {
                last == w -> score += 60
                last.startsWith(w) -> score += 40
                last.contains(w) -> score += 30
                else -> Unit
            }
        }
    }
    // An ancestor match is weaker but still real: everything under `Photos/2026` is worth
    // reaching early when somebody searched "photos".
    for (i in 0 until (segments.size - 1).coerceAtLeast(0)) {
        val s = segments[i].lowercase()
        for (w in words) if (s.contains(w)) score += 8
    }

    // Shallow first, gently: two points a level is enough to break ties without ever
    // outweighing a real name match.
    score += (12 - segments.size * 2).coerceAtLeast(-20)

    if (isNoise(path)) score -= 100
    return score
}

/** Folders that are large, machine-written, and almost never what a person searched for. */
private fun isNoise(path: VPath): Boolean {
    val lower = path.path.lowercase()
    return NOISE_FRAGMENTS.any { it in lower }
}

private val NOISE_FRAGMENTS = listOf(
    "/android/data", "/android/obb", "/.thumbnails", "/node_modules", "/.git/",
    "/cache/", "/caches/", "/.gradle", "/build/intermediates", "/lost.dir",
)

private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")

/**
 * Reorder the pending directories toward [query].
 *
 * A stable sort on the score, so entries that tie keep the order they were discovered in and
 * the crawl's own breadth-first shape survives inside each band. An empty or blank query gives
 * every entry the same score, which makes this the identity - so a caller that clears the
 * detour by passing "" gets discovery order back for free.
 */
fun steer(pending: List<Pending>, query: String): List<Pending> {
    if (query.isBlank()) return unsteer(pending)
    return pending.sortedWith(compareByDescending<Pending> { steerScore(it.path, query) }.thenBy { it.order })
}

/** Back to the order the crawl discovered things in, exactly. */
fun unsteer(pending: List<Pending>): List<Pending> = pending.sortedBy { it.order }

package dev.niccc2007.filet.index

/**
 * Why a user-initiated crawl ended.
 *
 * The user's complaint was "why did it stop?" — and the honest answer is that the old code
 * gave up after twenty-five seconds and said nothing anyone could act on. Every way a crawl
 * can end now has a name here, and the name is shown.
 *
 * Nothing in this list is a *silent* stop. If a crawl is not running, the reason it is not
 * running is on screen.
 */
enum class CrawlEnd(val label: String, val explanation: String) {
    DONE(
        "Finished",
        "The whole tree was walked. Files added or removed since are picked up by the watcher.",
    ),
    STOPPED(
        "Stopped by you",
        "It had not finished. Starting again walks the tree from the top — the index is never left in a half-state.",
    ),
    BATTERY(
        "Paused — battery low",
        "Below 15% and not charging. Plug in and start it again; a full crawl is the most expensive thing Filet does.",
    ),
    FAILED(
        "Stopped — error",
        "Something under the crawl threw. Whatever was indexed before it threw is kept.",
    ),
}

/**
 * A user-initiated crawl, as the UI needs to see it.
 *
 * Deliberately separate from [dev.niccc2007.filet.index.IndexStatus], which describes the
 * *index*. This describes one **run** — the thing with a Stop button next to it.
 */
data class IndexRun(
    val active: Boolean = false,
    /** Files seen in this run so far. Live while [active]. */
    val seen: Long = 0,
    val startedAt: Long = 0,
    /** Null until a run has ended in this process. */
    val endedBecause: CrawlEnd? = null,
    /** Extra detail for [CrawlEnd.FAILED]; empty otherwise. */
    val note: String = "",
) {
    /** One line, safe to show whether or not anything has ever run. */
    val line: String
        get() = when {
            active && seen > 0 -> "Indexing — $seen files so far"
            active -> "Indexing — starting"
            endedBecause == null -> ""
            note.isNotEmpty() -> "${endedBecause.label} — $note"
            else -> endedBecause.label
        }
}

package dev.niccc2007.filet.browser

import dev.niccc2007.filet.index.IndexStatus

/**
 * When an open search should ask the index again.
 *
 * ## The fault
 *
 * A search ran once, against the index as it stood. Rows the crawl wrote *after* that were never
 * fetched, so a query typed while a pass was running stayed short, and the only way to see the
 * rest was to retype it - which is retyping in order to re-run, not because anything changed.
 *
 * `watchConfirmations` was nearly the answer and stopped one step short: it re-read the STATE of
 * rows already on screen so the Confirming badges cleared in order, and when the pass ended it
 * flipped everything to Available and returned. It never re-asked the question, so the rows that
 * arrived in the meantime were not in the answer it was updating.
 *
 * ## Why this is a rule rather than a ticker
 *
 * Re-running on every change would be the obvious fix and the wrong one. A crawl writes
 * continuously; a list that re-queries with it is a list that moves while it is being read, and
 * rows sliding under a reaching thumb is worse than rows arriving late.
 *
 * So it re-asks at the two moments where something genuinely changed and then settled:
 *
 *  - **A pass finished.** Everything it found is in, and this is the moment the report is about.
 *  - **The index grew while nothing was running.** A background pass completed between ticks and
 *    its rows would otherwise wait for the next keystroke.
 *
 * Both are edges, not levels, so each fires once. And the re-run publishes *stably*: the rows
 * already painted keep their order and new ones append, which is the same guarantee the first
 * paint gives (SEARCH.md 5.3).
 */
object SearchRefresh {

    /**
     * @param before the status the last answer was built against, or null if none.
     * @param after the status now.
     */
    fun shouldRerun(before: IndexStatus?, after: IndexStatus?): Boolean {
        if (after == null) return false
        if (before == null) return false

        // A pass ended. The reported case: results found during the crawl were never fetched.
        if (before.running && !after.running) return true

        // The index grew while nothing was crawling, so a pass finished between two ticks.
        // Guarded on `!running` deliberately - the count climbs throughout a pass, and firing on
        // it there is the every-change ticker this exists to avoid.
        if (!before.running && !after.running && after.files > before.files) return true

        return false
    }

    /**
     * Whether the badges on screen can all be settled to Available.
     *
     * Separate from [shouldRerun] because they answer different questions: this is about the rows
     * already drawn, that is about whether to ask for more. Merging them is what left the fault -
     * the settle happened, returned, and took the re-ask with it.
     */
    fun shouldSettleBadges(after: IndexStatus?): Boolean = after != null && !after.running
}

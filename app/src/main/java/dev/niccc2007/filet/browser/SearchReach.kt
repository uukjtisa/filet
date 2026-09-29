package dev.niccc2007.filet.browser

import dev.niccc2007.filet.index.IndexStatus
import dev.niccc2007.filet.index.SearchScope

/**
 * Which search scopes a pane can actually answer, and what the index banner says.
 *
 * ## The fault this was written for
 *
 * Searching from Home with **Whole device** selected returned nothing at all, under a banner
 * reading "Still indexing". Two separate mistakes stacked into one dead feature:
 *
 *  - `searchPlan(HOME)` is `NONE`, so `runSearch` returned an empty result before building a
 *    request. The comment beside it says a search box on Home "cannot do anything, which is rule
 *    R1" - and then the search bar, the four scope chips and the field chips were all rendered on
 *    Home anyway. The control existed; only the answer was missing. That is the R1 violation the
 *    comment was trying to prevent, arrived at from the other direction.
 *  - The chips came from `SearchScope.entries`, every pane, every time. On Home there is no
 *    folder to stand in, so **This folder** and **Subfolders** cannot mean anything - and the bar
 *    opened with This folder ticked, which is a scope with no origin.
 *
 * Nothing downstream was broken. The index holds the files, it serves queries while a crawl is
 * running, and [dev.niccc2007.filet.index.HitState] already carries Available and Confirming.
 * Home simply could not reach any of it.
 */
object SearchReach {

    /**
     * The scopes worth offering on a pane standing at [cwd].
     *
     * Never an empty list: a pane that can search at all can always search the device, which is
     * what makes this safe to render directly.
     *
     * @param cwd where the pane is standing, or null for Home and the special screens.
     * @param provenance whether anything has recorded where files came from. Offering a scope
     *   that can only ever return nothing is the same dead switch in miniature.
     */
    fun scopesFor(cwd: Any?, provenance: Boolean = true): List<SearchScope> = buildList {
        if (cwd != null) {
            add(SearchScope.FOLDER)
            add(SearchScope.SUBFOLDERS)
        }
        add(SearchScope.DEVICE)
        if (provenance) add(SearchScope.PROVENANCE)
    }

    /**
     * The scope to actually search with.
     *
     * A folder-shaped scope with nowhere to stand is not a narrower search, it is an impossible
     * one - and the pane answered it with silence rather than saying so. Coerced to the whole
     * device, which is what somebody typing on Home means.
     */
    fun resolve(scope: SearchScope, cwd: Any?): SearchScope =
        if (cwd == null && (scope == SearchScope.FOLDER || scope == SearchScope.SUBFOLDERS)) {
            SearchScope.DEVICE
        } else {
            scope
        }

    /**
     * The line shown while a crawl is running.
     *
     * ## Why this is a function and not a string
     *
     * It read `"Still indexing — ${status.scanned} files"`, and `scanned` counts **this pass**,
     * not the index. So it started near zero on every launch and climbed, and read as the index
     * size - which made a restart look like the index had shrunk from 31k to 21k. It had not: the
     * database held 42,535 rows throughout.
     *
     * `IndexStatus.knownAtStart` was added for precisely this reason - a pass beginning at zero
     * is indistinguishable from an index that has been emptied, unless the previous total is
     * beside it. The remedy existed and this banner was never changed to use it.
     *
     * So the line now names both numbers and says which is which. The index total goes **first**,
     * because it is the one that must never look like it is going backwards.
     */
    fun indexingLine(status: IndexStatus, steeredTo: String? = null): String {
        val held = maxOf(status.files, status.knownAtStart)
        val checked = status.scanned
        val what = if (steeredTo != null) "Indexing \"$steeredTo\"" else "Indexing"
        return "$what · $held indexed · $checked checked this pass"
    }

    /**
     * Whether a pane is showing search results instead of its own contents.
     *
     * Bug identified, and it is the one that kept Home returning nothing after the plan and the
     * request were both fixed: the body was chosen by the pane's KIND alone
     * (`kind == PaneKind.FOLDER`). That was the same question for as long as only a folder pane
     * could search. The moment Home could, the two came apart - the search ran, the hits landed
     * in state, and the body still drew the Home overview, so a correct answer was never drawn.
     *
     * Only for a pane whose search reads the filesystem. A FILTER pane narrows rows it is already
     * drawing, so its own body stays right; swapping it would replace Settings with a file list.
     */
    fun showsResults(kind: PaneKind, searchActive: Boolean): Boolean =
        searchActive && searchPlan(kind) == SearchMode.FILESYSTEM
}

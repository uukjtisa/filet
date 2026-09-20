package dev.niccc2007.filet.browser

/**
 * What searching means on each kind of tab.
 *
 * Bug identified: search was written for the folder pane and every other pane inherited it
 * unchanged, so pressing search while looking at Settings walked the filesystem and returned
 * files. Nothing about that is a setting, and the pane it was started from could not use the
 * answer.
 *
 * The shape here is the one [refreshPlan] already uses for refresh, and for the same reason:
 * the failure is a pane kind nobody thought about, and a table can be checked for holes where
 * a `when` with an `else` silently absorbs them. `SearchPlanTest` fails the build when a new
 * pane kind arrives without an answer.
 */
enum class SearchMode {
    /** Walk the device. Only a folder pane can use this answer. */
    FILESYSTEM,

    /** Narrow the rows this pane is already showing. No disk is touched. */
    FILTER,

    /** Searching here means nothing, so the control is not offered. */
    NONE,
}

/**
 * What search does on [kind].
 *
 * Deliberately exhaustive with no `else`: adding a pane kind without deciding this will not
 * compile, rather than quietly inheriting a filesystem search it cannot use.
 */
fun searchPlan(kind: PaneKind): SearchMode = when (kind) {
    PaneKind.FOLDER -> SearchMode.FILESYSTEM

    // Every one of these is a list already on screen. Narrowing it is what somebody typing
    // here wants, and it is instant because nothing has to be read.
    PaneKind.SETTINGS -> SearchMode.FILTER
    PaneKind.SCRIPTS -> SearchMode.FILTER
    PaneKind.BOOKMARKS -> SearchMode.FILTER
    PaneKind.RECENT -> SearchMode.FILTER
    PaneKind.HISTORY -> SearchMode.FILTER
    PaneKind.SHORTCUTS -> SearchMode.FILTER
    PaneKind.REMOTES -> SearchMode.FILTER
    PaneKind.ACTIVITY -> SearchMode.FILTER
    PaneKind.NEARBY -> SearchMode.FILTER

    // Home is a handful of cards, About is a page of prose. A search box on either is a
    // control that cannot do anything, which is rule R1.
    PaneKind.HOME -> SearchMode.NONE
    PaneKind.ABOUT -> SearchMode.NONE
}

/** Whether the search control should appear at all on [kind]. */
fun searchOffered(kind: PaneKind): Boolean = searchPlan(kind) != SearchMode.NONE

/** What the search field should say it will do. */
fun searchHint(kind: PaneKind): String = when (searchPlan(kind)) {
    SearchMode.FILESYSTEM -> "Search this device"
    SearchMode.FILTER -> "Filter what is on this screen"
    SearchMode.NONE -> ""
}

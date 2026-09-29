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
    PaneKind.APPS -> SearchMode.FILTER

    // Home searches the device.
    //
    // Bug identified: this was NONE, with a comment saying a search box on Home "cannot do
    // anything, which is rule R1" - while the search bar, the four scope chips and the field
    // chips were all rendered on Home regardless. So the control was there and only the answer
    // was missing, which is the same R1 violation arrived at from the other side. Typing on Home
    // with Whole device selected returned nothing, under a banner saying the index was working.
    //
    // Home has no folder to stand in, so the folder-shaped scopes are not offered there and a
    // stale one is coerced to the device. See SearchReach.
    PaneKind.HOME -> SearchMode.FILESYSTEM
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

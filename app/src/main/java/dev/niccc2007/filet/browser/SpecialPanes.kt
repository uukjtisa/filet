package dev.niccc2007.filet.browser

/**
 * Every pane that is not a folder, declared once.
 *
 * ## The failure this closes
 *
 * A pane kind is not one edit. It is a route, an icon, a search mode, a refresh plan, a row in
 * the switcher (the only way to reach one on a phone) and a row in the rail (the only way on a
 * tablet). The Apps tab was added to the rail alone, and the rail exists above 720dp and
 * nowhere else - so on a phone it was unreachable while every check passed and the build was
 * green. Nothing could have caught it, because "reachable" was not written down anywhere.
 *
 * So it is written down here. A kind that belongs in the navigation surfaces appears in
 * [DESTINATIONS] once, and both surfaces are built from that list rather than from two
 * hand-maintained copies of it.
 *
 * ## What this deliberately does NOT absorb
 *
 * `searchPlan` and `refreshPlan` stay as exhaustive `when` expressions over the enum. They are
 * already the strongest form of this rule available - adding a kind without deciding them does
 * not compile - and folding them into a data table would trade a compiler error for a default.
 * A registry is the right answer where the compiler cannot help; where it can, it wins.
 *
 * The same reasoning says the route in `PaneView` stays a `when`: it returns a composable, and
 * a table of composables is a table of things the compiler cannot check for you.
 */
object SpecialPanes {

    /**
     * One reachable destination.
     *
     * @param label shown in both surfaces and used as the pane's title, so a tab cannot be
     *   called one thing in the switcher and another once it is open.
     */
    data class Destination(
        val kind: PaneKind,
        val label: String,
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        /** Which side-bar fold it lives in. Every destination lives in exactly one. */
        val fold: Fold,
        /**
         * Non-null for a destination that is the full version of what its fold already lists -
         * Bookmarks under Quick access, Opened under Recent - drawn as the fold's last row
         * ("All bookmarks") rather than as a row of its own beside the same items. That repeat
         * is the redundancy the side bar was redesigned to remove.
         */
        val seeAll: String? = null,
    )

    /**
     * The side bar's folds, top to bottom, the same in the portrait drawer and the landscape
     * rail. Drawn by `SideFolds`, which ends with [PINNED] as an always-open "Filet" card.
     */
    enum class Fold { QUICK, RECENT, DRIVES, COLLECTIONS, TOOLS }

    /**
     * The order both surfaces draw, top to bottom.
     *
     * Home, Activity, Settings and About are absent on purpose: Home is not a special pane, and
     * the other three are in [PINNED]. `SideFolds` reads both lists and both surfaces draw
     * `SideFolds` - `check-pinned.mjs` fails the build on a surface that draws a row by hand.
     */
    val DESTINATIONS: List<Destination> = listOf(
        Destination(PaneKind.BOOKMARKS, "Bookmarks", FiletIcons.Star, Fold.QUICK, seeAll = "All bookmarks"),
        Destination(PaneKind.RECENT, "Opened", FiletIcons.Clock, Fold.RECENT, seeAll = "Everything opened"),
        Destination(PaneKind.PLAYLISTS, "Playlists", FiletIcons.Audio, Fold.COLLECTIONS),
        Destination(PaneKind.RESUME, "Carry on", FiletIcons.Clock, Fold.COLLECTIONS),
        // Rows of their own in Tools, not a "see all" at the foot of Drives. Drives lists the
        // shares already saved; these two are where a share is found, added or hosted, and as
        // the last row of a fold of mounts they read as one more mount and were not found.
        Destination(PaneKind.REMOTES, "Remotes", FiletIcons.Device, Fold.TOOLS),
        Destination(PaneKind.NEARBY, "Nearby", FiletIcons.Wifi, Fold.TOOLS),
        Destination(PaneKind.APPS, "Apps", FiletIcons.Apk, Fold.TOOLS),
        Destination(PaneKind.SHORTCUTS, "Shortcuts", FiletIcons.Pin, Fold.TOOLS),
        Destination(PaneKind.SCRIPTS, "Scripts", FiletIcons.Script, Fold.TOOLS),
        Destination(PaneKind.SCAN, "Scan a code", FiletIcons.Scan, Fold.TOOLS),
    )

    /** One fold's destinations, in the declared order. */
    fun inFold(fold: Fold): List<Destination> = DESTINATIONS.filter { it.fold == fold }

    /**
     * The "Filet" card at the end of the side bar, in order.
     *
     * Activity, Settings and About were once written out twice, in the switcher and in the rail,
     * and a fourth entry is exactly the kind of addition that lands in one surface and not the
     * other - the Apps-tab defect again. So the list is declared here and drawn once, by
     * `SideFolds`. What each entry DOES is an exhaustive `when` in
     * `BrowserViewModel.onPinned`, so a fifth cannot be added without deciding that too.
     */
    enum class Pinned(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
        ACTIVITY("Activity", FiletIcons.Jobs),
        SETTINGS("Settings", FiletIcons.Cog),
        ABOUT("About", FiletIcons.Info),
        STOP("Stop Filet", FiletIcons.Power),
    }

    val PINNED: List<Pinned> = Pinned.entries

    /** The kinds that are reachable from the navigation surfaces. */
    val REACHABLE: Set<PaneKind> = DESTINATIONS.map { it.kind }.toSet()

    /**
     * Kinds that are opened from somewhere other than the destination list, with the reason.
     *
     * Named rather than omitted, so the reachability test can tell "opened another way" from
     * "forgotten" - which is the whole distinction the test exists to make.
     */
    val OPENED_ELSEWHERE: Map<PaneKind, String> = mapOf(
        PaneKind.FOLDER to "the ordinary case; opened by navigating",
        PaneKind.HOME to "the home button, and the start pane",
        PaneKind.HISTORY to
            "the Home overview's New files heading, and a bottom-bar entry of its own",
        PaneKind.ACTIVITY to "the Filet card at the end of the side bar, not a destination row",
        PaneKind.SETTINGS to "the Filet card at the end of the side bar",
        PaneKind.ABOUT to "the Filet card at the end of the side bar",
        PaneKind.CATEGORY to
            "the Categories section on the Home overview, one entry per kind of file",
    )
}

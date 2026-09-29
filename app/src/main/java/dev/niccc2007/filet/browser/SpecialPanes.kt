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
    )

    /**
     * The order both surfaces draw, top to bottom.
     *
     * Home, Activity, Settings and About are absent on purpose: Home is not a special pane, and
     * the other three are pinned to the bottom of the rail and the end of the switcher rather
     * than sitting in the destination list. They are still declared in their surfaces, which is
     * the one remaining hand-maintained pair and is noted here so it is not mistaken for
     * completeness.
     */
    val DESTINATIONS: List<Destination> = listOf(
        Destination(PaneKind.RECENT, "Recent", FiletIcons.Clock),
        Destination(PaneKind.BOOKMARKS, "Bookmarks", FiletIcons.Star),
        Destination(PaneKind.NEARBY, "Nearby", FiletIcons.Wifi),
        Destination(PaneKind.REMOTES, "Remotes", FiletIcons.Device),
        Destination(PaneKind.SHORTCUTS, "Shortcuts", FiletIcons.Pin),
        Destination(PaneKind.SCRIPTS, "Scripts", FiletIcons.Script),
        Destination(PaneKind.APPS, "Apps", FiletIcons.Apk),
    )

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
        PaneKind.HISTORY to "opened from the Home overview's New files heading",
        PaneKind.ACTIVITY to "pinned to the end of both surfaces, not a destination row",
        PaneKind.SETTINGS to "pinned to the end of both surfaces",
        PaneKind.ABOUT to "pinned to the end of both surfaces",
    )
}

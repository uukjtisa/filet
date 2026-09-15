package dev.niccc2007.filet.browser

/**
 * What the refresh button has to re-read, for each kind of pane.
 *
 * Bug identified: every pane except a folder held stale state until the app was killed and
 * restarted. The cause is one line - `PaneController.refresh()` began with
 * `if (s.kind == PaneKind.FOLDER)` and returned otherwise - so on Nearby, Home, Shortcuts,
 * Scripts, Recent and Settings the button was drawn, enabled, tappable, and did nothing at
 * all. That is precisely the dead control R1 exists to forbid, sitting in the toolbar for
 * seven rounds.
 *
 * A table rather than a `when` inside the controller, because the failure mode is a kind
 * nobody thought about, and a table can be checked for holes. [refreshPlan] answers for every
 * [PaneKind] there is, and `RefreshTest` fails the build if a new kind is added without one.
 */
enum class RefreshTarget {
    /** Re-list the folder the pane is showing. */
    LISTING,

    /** Volumes and free space: a card unmounted while the app was backgrounded. */
    VOLUMES,

    /** Recents, new files, and the tracked-folder overview. */
    HOME_FEED,

    /** The LAN server's own state, which is what makes the sharing button tell the truth. */
    NEARBY,

    /** Peer discovery, which goes stale the moment the network changes. */
    NEARBY_SCAN,

    /** The pinned-shortcut record. */
    SHORTCUTS,

    /** The script list and their approvals. */
    SCRIPTS,

    /** Bookmarks, which another pane or a widget may have changed. */
    BOOKMARKS,

    /** The recently-opened list. */
    RECENTS,

    /** Index counts and whether a crawl is running. */
    INDEX_STATUS,

    /** Saved network connections. */
    REMOTES,

    /** The job ledger. */
    JOBS,
}

/**
 * @param label what the toast says afterwards. Refreshing a pane that shows no obvious change
 *   is indistinguishable from the button being broken, which is the state this is fixing, so
 *   every refresh says what it just re-read.
 */
data class RefreshPlan(val targets: Set<RefreshTarget>, val label: String)

fun refreshPlan(kind: PaneKind): RefreshPlan = when (kind) {
    PaneKind.FOLDER -> RefreshPlan(setOf(RefreshTarget.LISTING), "Folder")

    // Home is the worst offender: it shows recents, new files, tracked folders and the
    // storage cards, and every one of them was frozen at whenever the tab was opened.
    PaneKind.HOME -> RefreshPlan(
        setOf(
            RefreshTarget.HOME_FEED,
            RefreshTarget.RECENTS,
            RefreshTarget.VOLUMES,
            RefreshTarget.BOOKMARKS,
        ),
        "Home",
    )

    PaneKind.NEARBY -> RefreshPlan(
        setOf(RefreshTarget.NEARBY, RefreshTarget.NEARBY_SCAN),
        "Nearby",
    )

    PaneKind.SHORTCUTS -> RefreshPlan(setOf(RefreshTarget.SHORTCUTS), "Shortcuts")
    PaneKind.SCRIPTS -> RefreshPlan(setOf(RefreshTarget.SCRIPTS), "Scripts")
    PaneKind.BOOKMARKS -> RefreshPlan(setOf(RefreshTarget.BOOKMARKS), "Bookmarks")
    PaneKind.RECENT -> RefreshPlan(setOf(RefreshTarget.RECENTS), "Recent")

    // The expanded new-files history is the same feed the home card shows, so refreshing it
    // re-runs the same pass rather than a second one of its own.
    PaneKind.HISTORY -> RefreshPlan(setOf(RefreshTarget.HOME_FEED), "New files")
    PaneKind.ACTIVITY -> RefreshPlan(setOf(RefreshTarget.JOBS), "Activity")
    PaneKind.REMOTES -> RefreshPlan(setOf(RefreshTarget.REMOTES), "Remotes")

    // Settings shows the index readout and the volume list, both of which move underneath it.
    PaneKind.SETTINGS -> RefreshPlan(
        setOf(RefreshTarget.INDEX_STATUS, RefreshTarget.VOLUMES),
        "Settings",
    )

    // About carries the version and the update state. Little to re-read, and re-reading
    // nothing would put the dead button back on one screen instead of seven.
    PaneKind.ABOUT -> RefreshPlan(setOf(RefreshTarget.INDEX_STATUS), "About")
}

package dev.niccc2007.filet.browser

/**
 * What sits in the bottom bar, and in what order.
 *
 * The bar was four fixed destinations. It is now chosen in Settings, with no cap: past six
 * entries it scrolls sideways rather than refusing the seventh. A cap would have to answer
 * "which one gets replaced", and that question has no good answer and no good screen.
 *
 * ## Why the stored value is normalised rather than trusted
 *
 * The bar is read from preferences at startup, and preferences outlive the code that wrote
 * them. A release that renames or removes an entry leaves a stored id nothing can resolve, and
 * the honest failure for that is *a bar missing one button*, not a crash on launch or an empty
 * strip. [BottomBarConfig.normalise] is the only way the stored value is ever read.
 */
enum class BarItem(val id: String, val label: String, val tab: TabTarget? = null) {
    // Tabs. Each is a TabTarget, whose id is the one stored here, so a bar saved before the
    // registry existed reads back the same.
    HOME(TabTarget.HOME),
    FILES(TabTarget.FILES),
    BOOKMARKS(TabTarget.BOOKMARKS),
    RECENT(TabTarget.OPENED),

    /**
     * Everything that has ARRIVED on the phone's own storage, newest first.
     *
     * Not the same thing as [RECENT], which is what you OPENED. The distinction is the whole
     * value: finding a file that an app has just written means knowing which folder that app
     * chose, and this is the list that removes that trip. It was already a screen, reachable
     * only from a heading on the Home overview - which is the same trip again.
     */
    NEW_FILES(TabTarget.NEW_FILES),
    PLAYLISTS(TabTarget.PLAYLISTS),

    /** What was part way through, with its position. See `ResumeStore`. */
    RESUME(TabTarget.CARRY_ON),
    PHOTOS(TabTarget.PHOTOS),
    VIDEOS(TabTarget.VIDEOS),
    AUDIO(TabTarget.AUDIO),
    PACKAGES(TabTarget.PACKAGES),
    DOCUMENTS(TabTarget.DOCUMENTS),
    ARCHIVES(TabTarget.ARCHIVES),

    /** Saved network locations - shares, servers, cloud accounts. The `Remotes` pane. */
    REMOTES(TabTarget.REMOTES),
    SHARING(TabTarget.SHARE),
    PORTAL(TabTarget.PORTAL),
    APPS(TabTarget.APPS),
    SHORTCUTS(TabTarget.SHORTCUTS),
    SCRIPTS(TabTarget.SCRIPTS),
    SCAN(TabTarget.SCAN),
    ACTIVITY(TabTarget.ACTIVITY),
    SETTINGS(TabTarget.SETTINGS),

    // Actions: a press does something rather than opening a tab.
    SEARCH("search", "Search"),
    SPLIT("split", "Split"),
    NEW_TAB("newtab", "New tab"),
    CLOSE_TABS("closetabs", "Close all tabs");

    constructor(tab: TabTarget) : this(tab.id, tab.bar, tab)

    /** The picker's heading for it: the tab's side-bar group, or null for an action. */
    val group: TabGroup? get() = tab?.group

    companion object {
        fun byId(id: String): BarItem? = entries.firstOrNull { it.id == id }

        /** The bar entry that opens [tab]. Every tab has one; `TabTargetsTest` holds that. */
        fun of(tab: TabTarget): BarItem = entries.first { it.tab == tab }
    }
}

object BottomBarConfig {

    /**
     * The bar for anyone who has never saved one, left to right.
     *
     * Places first and the two network destinations last: Home and Files are where browsing
     * starts, Activity and Bookmarks are what gets checked during it, and Share and Remotes are
     * how files leave or arrive from another device. Search is not here because every pane
     * header already carries it, one tap away.
     *
     * Decision: "never configured" is the absence of the stored key, not the length of what is
     * stored. [normalise] returns this only for a null or empty value; Settings writes the key
     * only when somebody changes the bar. So a bar somebody saved stays exactly as they saved
     * it, however short, and changing this list moves only the people who never set one. The
     * alternative, comparing the stored bar against the old default and replacing it if they
     * match, cannot tell somebody who chose those four from somebody who never looked.
     */
    val DEFAULT = listOf(
        BarItem.HOME,
        BarItem.FILES,
        BarItem.ACTIVITY,
        BarItem.BOOKMARKS,
        BarItem.SHARING,
        BarItem.REMOTES,
    )

    /**
     * Past this many the bar scrolls instead of squeezing.
     *
     * Six is where the labels stop being readable on a narrow phone - below it every entry can
     * be a fixed share of the width, above it they need their natural width and a scroll.
     */
    const val SCROLLS_PAST = 6

    /**
     * Read a stored bar, dropping anything that no longer exists.
     *
     * @param stored comma-separated ids, as written by [encode]. Null or empty gives [DEFAULT].
     *
     * Duplicates are collapsed rather than rejected: two copies of one button is a bar that
     * looks broken, and the stored value can pick them up from a bad edit or a merge.
     */
    fun normalise(stored: String?): List<BarItem> {
        val parsed = stored.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { BarItem.byId(it) }
            .distinct()
        // An empty bar is not a configuration, it is a mistake - most likely every stored id
        // having been renamed. Falling back beats showing a blank strip with no way back to
        // Settings from it.
        return parsed.ifEmpty { DEFAULT }
    }

    fun encode(items: List<BarItem>): String = items.joinToString(",") { it.id }

    /** Whether the bar has to scroll at this size. */
    fun scrolls(count: Int): Boolean = count > SCROLLS_PAST

    /**
     * Add or remove one entry.
     *
     * Removing the last entry is refused, because the result is a bar with nothing in it and
     * the Settings screen that fixes it is reached through the bar.
     */
    fun toggled(items: List<BarItem>, item: BarItem): List<BarItem> = when {
        item !in items -> items + item
        items.size <= 1 -> items
        else -> items - item
    }

    /**
     * Move one entry, for the up and down buttons in Settings.
     *
     * The bar's order used to be the order things were ticked in, which is an order nobody
     * chose - adding Playlists put it at the end whatever it was for, and the only way to
     * move it was to untick everything after it and tick them again.
     *
     * Out-of-range is a no-op rather than a clamp. A clamp would make the button at the top
     * of the list silently swap the first two, and a dead button is a clearer answer than a
     * button that does something else.
     */
    fun moved(items: List<BarItem>, from: Int, to: Int): List<BarItem> {
        if (from !in items.indices || to !in items.indices || from == to) return items
        val list = items.toMutableList()
        list.add(to, list.removeAt(from))
        return list
    }
}

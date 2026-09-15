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
enum class BarItem(val id: String, val label: String) {
    FILES("files", "Files"),
    SEARCH("search", "Search"),
    ACTIVITY("activity", "Activity"),
    BOOKMARKS("bookmarks", "Bookmarks"),
    HOME("home", "Home"),
    RECENT("recent", "Recent"),
    SHARING("sharing", "Share"),
    SCRIPTS("scripts", "Scripts"),
    SHORTCUTS("shortcuts", "Shortcuts"),
    SPLIT("split", "Split"),
    NEW_TAB("newtab", "New tab"),
    SETTINGS("settings", "Settings");

    companion object {
        fun byId(id: String): BarItem? = entries.firstOrNull { it.id == id }
    }
}

object BottomBarConfig {

    /** What the bar held before it was configurable. Anyone who never opens Settings sees this. */
    val DEFAULT = listOf(BarItem.FILES, BarItem.SEARCH, BarItem.ACTIVITY, BarItem.BOOKMARKS)

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
}

package dev.niccc2007.filet.shortcuts

import dev.niccc2007.filet.browser.TabTarget

/**
 * Which four things the menu on Filet's held icon offers, chosen from every tab and the actions.
 *
 * Stored as ids, comma-separated, in the order they show: an action's own word (`search`,
 * `index`, `share`) or a tab as `tab:<id>`. Sharing is one entry, not two: it says Start or Stop
 * by what is running when the menu is published, so the menu never offers both and never offers
 * the wrong one.
 *
 * Like the bottom bar, the stored value is only ever read through [normalise]: a release that
 * drops an id leaves a shorter menu, not a broken one.
 */
object HoldMenu {

    const val SEARCH = "search"
    const val INDEX = "index"
    const val SHARE = "share"

    /** Today's four, for anybody who never chose: the same entries the menu always had. */
    val DEFAULT: List<String> = listOf(SEARCH, TAB_PREFIX + TabTarget.BOOKMARKS.id, SHARE, INDEX)

    /** Everything that can go in, actions first, then every tab in the side bar's order. */
    val CHOICES: List<String> = listOf(SEARCH, INDEX, SHARE) + TabTarget.entries.map { TAB_PREFIX + it.id }

    /** One entry as the launcher shows it, and what it asks Filet to do. */
    data class Entry(
        val id: String,
        /** At most ten letters show under a launcher's icon row, so the bar's short name. */
        val short: String,
        val long: String,
        /** The tab icon mock's id for its drawing. */
        val art: String,
        /** What the shortcut hands Filet: an action's name, or `tab:<id>`. */
        val route: String,
    )

    /** The chosen ids, known ones only, each once, at most [LauncherShortcuts.MAX]. None gives [DEFAULT]. */
    fun normalise(stored: String?): List<String> {
        val ids = stored.orEmpty().split(',').map { it.trim() }.filter { it in CHOICES }.distinct()
            .take(LauncherShortcuts.MAX)
        return ids.ifEmpty { DEFAULT }
    }

    fun encode(ids: List<String>): String = ids.joinToString(",")

    /** The menu as published now: [sharing] decides whether the share entry starts or stops. */
    fun entriesFor(stored: String?, sharing: Boolean): List<Entry> = normalise(stored).mapNotNull { entryOf(it, sharing) }

    fun entryOf(id: String, sharing: Boolean): Entry? {
        tabIdOrNull(id)?.let { tabId ->
            val tab = TabTarget.byId(tabId) ?: return null
            return Entry(id, tab.bar, tab.label, tab.art, TAB_PREFIX + tab.id)
        }
        val action = when (id) {
            SEARCH -> AppAction.SEARCH
            INDEX -> AppAction.INDEX_NOW
            SHARE -> if (sharing) AppAction.STOP_SHARING else AppAction.SHARE_NEARBY
            else -> return null
        }
        return Entry(id, action.label, action.label, action.art, action.name)
    }

    /** Add [id] at the end. Refused when the menu is full: past four a launcher drops entries of its own choosing. */
    fun added(ids: List<String>, id: String): List<String> =
        if (id in ids || id !in CHOICES || ids.size >= LauncherShortcuts.MAX) ids else ids + id

    /** Take [id] out. The last one stays: an empty menu would read back as the default, which is not what was asked. */
    fun removed(ids: List<String>, id: String): List<String> =
        if (id !in ids || ids.size <= 1) ids else ids - id

    /** Move one entry, for the drag and for a screen reader's Move up and Move down. Out of range does nothing. */
    fun moved(ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices || to !in ids.indices || from == to) return ids
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }
}

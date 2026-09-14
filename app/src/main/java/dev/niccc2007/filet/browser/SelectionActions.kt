package dev.niccc2007.filet.browser

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * What you can do with a selection, as data.
 *
 * Nic asked for the selection's actions to appear as a context menu that fits the screen,
 * made the default, with the old scrolling bar kept as an option in Settings.
 *
 * Two renderings of the same thing is exactly how the two drift apart: an action added to the
 * menu and forgotten in the bar, or blocked in one and live in the other. So the list is built
 * once, here, with no Compose in it, and both renderings are just paint over it. Adding an
 * action means adding one entry and it appears in both.
 *
 * The list is also why the menu can be honest about *why* something is unavailable. A phone has
 * no hover, so a greyed-out row with no explanation leaves somebody pressing a dead control and
 * guessing; every blocked action carries the sentence it answers with.
 */
data class SelectionAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    /** Why this cannot run right now, or null when it can. */
    val blocked: String? = null,
    /** Destructive, so it is drawn apart and in the warning colour. */
    val danger: Boolean = false,
    /** Actions are grouped; a divider is drawn where the group changes. */
    val group: Group = Group.FILE,
    val run: () -> Unit,
) {
    enum class Group { FILE, EDIT, PLACE }
}

/**
 * Every selection action, in order, with its blocked reason already resolved.
 *
 * @param count how many items are selected.
 * @param readOnly why the volume cannot be written to, or null.
 */
fun selectionActions(
    count: Int,
    readOnly: String?,
    icons: SelectionIcons,
    on: SelectionCallbacks,
): List<SelectionAction> {
    val single = count == 1
    // Written out rather than inlined three times: these two sentences are the whole of what a
    // user gets told, and having one copy of each is how they stay consistent.
    val needsOne = if (single) null else "Pick one file — this works on a single file"
    val needsOneName = if (single) null else "Pick one file — rename takes one name at a time"
    val needsOneInfo = if (single) null else "Pick one file — details describe one file"

    return listOf(
        SelectionAction("copy", "Copy", icons.copy, run = on.copy),
        SelectionAction("move", "Move", icons.cut, blocked = readOnly, run = on.move),
        SelectionAction("send", "Send", icons.share, run = on.send),
        SelectionAction(
            "compress", "Compress", icons.zip,
            blocked = readOnly, group = SelectionAction.Group.EDIT, run = on.compress,
        ),
        SelectionAction(
            "rename", "Rename", icons.rename,
            blocked = readOnly ?: needsOneName,
            group = SelectionAction.Group.EDIT, run = on.rename,
        ),
        SelectionAction(
            "openwith", "Open with", icons.open,
            blocked = needsOne, group = SelectionAction.Group.EDIT, run = on.openWith,
        ),
        SelectionAction(
            "details", "Details", icons.info,
            blocked = needsOneInfo, group = SelectionAction.Group.EDIT, run = on.details,
        ),
        SelectionAction(
            "bookmark", "Bookmark", icons.star,
            group = SelectionAction.Group.PLACE, run = on.bookmark,
        ),
        SelectionAction(
            "nearby", "Nearby", icons.wifi,
            group = SelectionAction.Group.PLACE, run = on.nearby,
        ),
        SelectionAction(
            "shortcut", "Shortcut", icons.home,
            group = SelectionAction.Group.PLACE, run = on.shortcut,
        ),
        // Last, and on its own, because it is the one that cannot be undone.
        SelectionAction(
            "delete", "Delete", icons.delete,
            blocked = readOnly, danger = true,
            group = SelectionAction.Group.PLACE, run = on.delete,
        ),
    )
}

/** The icons, passed in so this file stays free of the icon set and testable on the JVM. */
data class SelectionIcons(
    val copy: ImageVector,
    val cut: ImageVector,
    val share: ImageVector,
    val delete: ImageVector,
    val zip: ImageVector,
    val rename: ImageVector,
    val open: ImageVector,
    val info: ImageVector,
    val star: ImageVector,
    val wifi: ImageVector,
    val home: ImageVector,
)

/** What each action does. Separate from the list so the list can be built in a test. */
data class SelectionCallbacks(
    val copy: () -> Unit,
    val move: () -> Unit,
    val send: () -> Unit,
    val delete: () -> Unit,
    val compress: () -> Unit,
    val rename: () -> Unit,
    val openWith: () -> Unit,
    val details: () -> Unit,
    val bookmark: () -> Unit,
    val nearby: () -> Unit,
    val shortcut: () -> Unit,
)

/** How the selection's actions are presented. */
enum class SelectionStyle {
    /** A popup menu. The default: it fits the screen and never hides an action off an edge. */
    MENU,

    /** The scrolling bar along the bottom. Kept because some people prefer one tap. */
    BAR;

    companion object {
        fun valueOfOr(raw: String?, fallback: SelectionStyle): SelectionStyle =
            entries.firstOrNull { it.name == raw } ?: fallback
    }
}

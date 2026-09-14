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
 * @param archive true when the selection is exactly one archive Filet can read. The extract
 *   rows are absent rather than blocked when it is false - "Extract" on a photo is not a thing
 *   that is unavailable, it is a thing that makes no sense, and blocking it would fill the menu
 *   with sentences nobody needs.
 * @param otherPane true when the view is split, so there is another pane to extract into.
 * @param picking true when Filet is running as another app's file picker. Delete is dropped:
 *   somebody who reached this screen through a chooser came to CHOOSE a file, and a destructive
 *   action one tap from the one they meant is a screen failing them rather than a mistake they
 *   made. Everything non-destructive stays, because a picker that cannot even show properties
 *   is a worse file manager than the one it replaced.
 */
fun selectionActions(
    count: Int,
    readOnly: String?,
    icons: SelectionIcons,
    on: SelectionCallbacks,
    archive: Boolean = false,
    otherPane: Boolean = false,
    picking: Boolean = false,
): List<SelectionAction> {
    val single = count == 1
    // Written out rather than inlined three times: these two sentences are the whole of what a
    // user gets told, and having one copy of each is how they stay consistent.
    val needsOne = if (single) null else "Pick one file — this works on a single file"
    val needsOneName = if (single) null else "Pick one file — rename takes one name at a time"
    val needsOneInfo = if (single) null else "Pick one file — details describe one file"

    return buildList {
        add(SelectionAction("copy", "Copy", icons.copy, run = on.copy))
        add(SelectionAction("move", "Move", icons.cut, blocked = readOnly, run = on.move))
        add(SelectionAction("send", "Send", icons.share, run = on.send))
        add(
            SelectionAction(
                "compress", "Compress", icons.zip,
                blocked = readOnly, group = SelectionAction.Group.EDIT, run = on.compress,
            ),
        )
        // The three destinations, next to Compress because they are its other direction. Each
        // opens the same preview; the only difference is where it is aimed. Present only for an
        // archive, because that is the only selection for which any of them means anything.
        if (archive) {
            add(
                SelectionAction(
                    "extracthere", "Extract here", icons.zip,
                    blocked = readOnly, group = SelectionAction.Group.EDIT, run = on.extractHere,
                ),
            )
            add(
                SelectionAction(
                    "extractto", "Extract to\u2026", icons.open,
                    group = SelectionAction.Group.EDIT, run = on.extractTo,
                ),
            )
            if (otherPane) {
                add(
                    SelectionAction(
                        "extractother", "Extract to the other pane", icons.share,
                        group = SelectionAction.Group.EDIT, run = on.extractToOtherPane,
                    ),
                )
            }
        }
        add(
            SelectionAction(
                "rename", "Rename", icons.rename,
                blocked = readOnly ?: needsOneName,
                group = SelectionAction.Group.EDIT, run = on.rename,
            ),
        )
        add(
            SelectionAction(
                "openwith", "Open with", icons.open,
                blocked = needsOne, group = SelectionAction.Group.EDIT, run = on.openWith,
            ),
        )
        add(
            SelectionAction(
                "details", "Details", icons.info,
                blocked = needsOneInfo, group = SelectionAction.Group.EDIT, run = on.details,
            ),
        )
        add(
            SelectionAction(
                "bookmark", "Bookmark", icons.star,
                group = SelectionAction.Group.PLACE, run = on.bookmark,
            ),
        )
        add(
            SelectionAction(
                "nearby", "Nearby", icons.wifi,
                group = SelectionAction.Group.PLACE, run = on.nearby,
            ),
        )
        add(
            SelectionAction(
                "shortcut", "Shortcut", icons.home,
                group = SelectionAction.Group.PLACE, run = on.shortcut,
            ),
        )
        // Last, and on its own, because it is the one that cannot be undone - which is also
        // why it is absent entirely while picking rather than merely blocked.
        if (!picking) {
            add(
                SelectionAction(
                    "delete", "Delete", icons.delete,
                    blocked = readOnly, danger = true,
                    group = SelectionAction.Group.PLACE, run = on.delete,
                ),
            )
        }
    }
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
    val extractHere: () -> Unit = {},
    val extractTo: () -> Unit = {},
    val extractToOtherPane: () -> Unit = {},
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

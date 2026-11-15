package dev.niccc2007.filet.handlers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.niccc2007.filet.apk.BundleRoute
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.SelectionAction
import dev.niccc2007.filet.browser.SelectionCallbacks
import dev.niccc2007.filet.browser.SelectionIcons
import dev.niccc2007.filet.browser.SelectionShape
import dev.niccc2007.filet.browser.selectionActions
import dev.niccc2007.filet.locker.LockerPaths
import dev.niccc2007.filet.media.MediaAnnounce
import dev.niccc2007.filet.metadata.MetadataSupport
import dev.niccc2007.filet.ui.dialogs.PanelHeading
import dev.niccc2007.filet.ui.dialogs.PanelPopover
import dev.niccc2007.filet.ui.dialogs.PanelRow
import dev.niccc2007.filet.ui.dialogs.PanelRule
import dev.niccc2007.filet.ui.dialogs.PanelSide
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode

/**
 * A viewer's More: what can be done to the file it has open, and whatever the viewer adds about
 * how it shows it.
 *
 * The file's rows are [selectionActions], the list the hold menu and the selection bar draw, so
 * an action added there is here too and gated the same way. What differs is only what each row
 * acts on: the viewer's own file, never the selection in the pane behind it. Borrowing that
 * selection would leave it changed when the viewer closes, and an action that reads the
 * selection when its dialogue is confirmed would act on whatever was selected by then.
 *
 * @param side [PanelSide.BELOW] for a button in the top bar, [PanelSide.ABOVE] for one at the
 *   bottom.
 * @param unsaved the viewer holds an edit not yet saved. The rows that would close the viewer or
 *   change which file it is stay listed and say to save first, rather than losing the edit.
 * @param onGone what happens once the file is deleted: out of the viewer, or on to the next
 *   picture in a run.
 * @param view the viewer's own rows, drawn first under [viewHeading]. Each is given `close`.
 */
@Composable
internal fun ViewerMore(
    vm: BrowserViewModel,
    node: VNode,
    side: PanelSide = PanelSide.BELOW,
    unsaved: Boolean = false,
    onGone: () -> Unit = { vm.closeHandler() },
    viewHeading: String = "View",
    /** Told when the panel opens and closes, for a viewer whose controls wait on its menus. */
    onOpen: (Boolean) -> Unit = {},
    view: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        // A top bar's buttons are bare icons, so the three dots are one of them, the same size
        // and on the same line. Only a bottom bar, where every button carries its caption,
        // gives it one.
        val show = {
            open = true
            onOpen(true)
        }
        if (side == PanelSide.ABOVE) ViewerBarButton(FiletIcons.More, "More", onClick = show) else ViewerAction(FiletIcons.More, "More", active = open, onClick = show)
        if (open) {
            val close = {
                open = false
                onOpen(false)
            }
            val actions = remember(node.path, unsaved) { viewerActions(vm, node, unsaved, onGone) }
            PanelPopover(side = side, onDismiss = close, heading = if (view != null) viewHeading else FILE) {
                if (view != null) {
                    view(close)
                    PanelRule()
                    PanelHeading(FILE, first = false)
                }
                FileRows(actions, close, vm::toast)
            }
        }
    }
}

/**
 * The file's rows inside a panel a screen already has: the music player's, whose file is the
 * track playing now rather than the one it was opened on.
 */
@Composable
internal fun ColumnScope.ViewerFileRows(
    vm: BrowserViewModel,
    node: VNode,
    close: () -> Unit,
    onGone: () -> Unit = { vm.closeHandler() },
) {
    val actions = remember(node.path) { viewerActions(vm, node, unsaved = false, onGone = onGone) }
    FileRows(actions, close, vm::toast)
}

/**
 * The same rows as items of a [dev.niccc2007.filet.ui.dialogs.PopMenu], for the video player,
 * whose three-dot menu is one already and carries the player's own choices above them.
 */
@Composable
internal fun ColumnScope.ViewerFilePopRows(
    vm: BrowserViewModel,
    node: VNode,
    close: () -> Unit,
    onGone: () -> Unit = { vm.closeHandler() },
) {
    val actions = remember(node.path) { viewerActions(vm, node, unsaved = false, onGone = onGone) }
    var last: SelectionAction.Group? = null
    for (a in actions) {
        if (last != null && a.group != last) dev.niccc2007.filet.ui.dialogs.PopSeparator()
        last = a.group
        dev.niccc2007.filet.ui.dialogs.PopRow(a.icon, a.label, detail = a.blocked, danger = a.danger) {
            val why = a.blocked
            if (why != null) {
                vm.toast(why)
            } else {
                close()
                a.run()
            }
        }
    }
}

/** The file's rows, a rule wherever the group changes, as the hold menu divides them. */
@Composable
private fun ColumnScope.FileRows(actions: List<SelectionAction>, close: () -> Unit, say: (String) -> Unit) {
    val colors = Filet.colors
    var last: SelectionAction.Group? = null
    for (a in actions) {
        if (last != null && a.group != last) PanelRule()
        last = a.group
        PanelRow(
            a.label,
            tint = colors.accent,
            on = null,
            icon = a.icon,
            ink = if (a.danger) colors.bad else null,
            dim = a.blocked != null,
        ) {
            // A blocked row answers with its reason and leaves the panel open.
            val why = a.blocked
            if (why != null) {
                say(why)
            } else {
                close()
                a.run()
            }
        }
    }
}

/**
 * [selectionActions] for one open file, each row bound to [node].
 *
 * The gates are worked out from the file itself, as the pane works them out from a selection
 * of one. "Open location" is always offered: from a viewer the folder is always somewhere else.
 */
internal fun viewerActions(
    vm: BrowserViewModel,
    node: VNode,
    unsaved: Boolean,
    onGone: () -> Unit,
): List<SelectionAction> {
    val one = listOf(node)
    val all = selectionActions(
        count = 1,
        readOnly = vm.writeBlockReason(node.path.parent),
        icons = VIEWER_ICONS,
        on = SelectionCallbacks(
            copy = { vm.copyNodes(one) },
            move = { vm.cutNodes(one) },
            send = { vm.shareToApps(one) },
            delete = { vm.confirmDeleteOf(one) { onGone() } },
            // Not offered here; see NOT_IN_VIEWERS.
            compress = {},
            // Out of the viewer first: it would go on showing, and saving to, the old name.
            rename = {
                vm.closeHandler()
                vm.askRename(node)
            },
            openWith = { vm.openChooser(node) },
            details = { vm.showProperties(node) },
            bookmark = { vm.bookmarkNodes(one) },
            nearby = { vm.shareNearby(one) },
            shortcut = { vm.shortcutOne(node) },
            fastShare = { vm.openFastShare(one) },
            install = { vm.installApk(node) },
            metadata = { vm.openWith(node, HandlerId.METADATA, remember = false) },
            announce = { vm.announceToMedia(one) },
            addToPlaylist = { vm.askAddToPlaylist(one) },
            makeBeatMaps = { vm.makeBeatMaps(one) },
            enqueue = { vm.enqueue(one) },
            copyPath = { vm.copyPathsOf(one) },
            // Out of every viewer, not back one: the folder is where this is going.
            reveal = {
                vm.leaveHandlers()
                vm.revealInFolder(node)
            },
            toLocker = {
                vm.closeHandler()
                vm.moveToLocker(one)
            },
            release = {
                vm.closeHandler()
                vm.release(one)
            },
        ),
        installable = !node.isDir && BundleRoute.installable(node.name),
        hasMetadata = !node.isDir && MetadataSupport.forExtension(node.extension).isNotEmpty(),
        announceable = MediaAnnounce.worthAnnouncing(node.path),
        shape = SelectionShape.of(one),
        elsewhere = true,
        locker = LockerPaths.isPrivate(node.path),
    )
    return forViewer(all, unsaved)
}

/** The registry's rows as a viewer offers them. Pure, so the rule is tested without a screen. */
internal fun forViewer(all: List<SelectionAction>, unsaved: Boolean): List<SelectionAction> =
    all.filter { it.id !in NOT_IN_VIEWERS }.map { a ->
        if (unsaved && a.id in LOSES_EDITS) a.copy(blocked = SAVE_FIRST) else a
    }

/**
 * Rows a viewer leaves out. Invert selection and Change icon mean nothing for one open file.
 * Extract walks the browser to where the files go, and the viewer covers the browser. Compress
 * asks for a name in a dialogue that reads the pane's selection when it is confirmed, so from a
 * viewer it would compress whatever is selected behind it.
 */
internal val NOT_IN_VIEWERS = setOf("invert", "foldericon", "compress", "extract", "extractother")

/**
 * Rows that close the viewer or change which file it is. With an edit unsaved, each would either
 * drop it or leave it to be saved under a name that no longer exists.
 */
internal val LOSES_EDITS = setOf("reveal", "rename", "delete", "metadata", "tolocker", "release")

internal const val SAVE_FIRST = "Save or discard your changes first."

private const val FILE = "File"

private val VIEWER_ICONS = SelectionIcons(
    copy = FiletIcons.Copy, cut = FiletIcons.Cut, share = FiletIcons.Share,
    delete = FiletIcons.Delete, zip = FiletIcons.Zip, rename = FiletIcons.Rename,
    open = FiletIcons.Open, info = FiletIcons.Info, star = FiletIcons.Star,
    wifi = FiletIcons.Wifi, home = FiletIcons.Home,
    apk = FiletIcons.Apk, cog = FiletIcons.Cog,
    audio = FiletIcons.Audio, rows = FiletIcons.Rows,
    folder = FiletIcons.FolderOpen, invert = FiletIcons.Check,
    fast = FiletIcons.Scan, lock = FiletIcons.Lock,
)

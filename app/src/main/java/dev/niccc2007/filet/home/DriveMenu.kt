package dev.niccc2007.filet.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.VolumeInfo
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.data.DriveNames
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgKv
import dev.niccc2007.filet.ui.dialogs.DlgPick
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.vfs.provider.RootProvider
import dev.niccc2007.filet.vfs.provider.SafProvider

/** Which of a drive's dialogues is up. One at a time, so one value. */
internal enum class DriveDialog { MENU, RENAME, DETAILS }

/**
 * What holding a storage tile on Home offers.
 *
 * The same kit and the same shape as a bookmark's menu in the side bar, so a held drive and a
 * held bookmark answer alike. Hide is what the tile's x does, here as well because the x is
 * small and a menu is where somebody looks for it.
 */
@Composable
internal fun DriveMenu(
    vm: BrowserViewModel,
    v: VolumeInfo,
    onPick: (DriveDialog?) -> Unit,
) {
    val close = { onPick(null) }
    Dlg(onDismiss = close) {
        DlgHeader(
            icon = if (v.remote) FiletIcons.Wifi else FiletIcons.Storage,
            title = v.label,
            sub = DriveDetails.subtitle(v),
            onClose = close,
        )
        DlgBody {
            DlgPick(FiletIcons.Rename, "Rename") { onPick(DriveDialog.RENAME) }
            DlgPick(FiletIcons.EyeOff, "Hide") {
                close()
                vm.prefs.hideCard(v.node.path.toString())
            }
            DlgPick(FiletIcons.Plus, "Open in new tab") {
                close()
                vm.addTab(at = v.node.path)
            }
            DlgPick(FiletIcons.Info, "Details") { onPick(DriveDialog.DETAILS) }
        }
    }
}

/**
 * One field, filled with the current name. Empty, or the system's own name, gives the drive
 * its system name back - the field is the reset, so there is no separate button for it.
 */
@Composable
internal fun RenameDriveDialog(vm: BrowserViewModel, v: VolumeInfo, onClose: () -> Unit) {
    var typed by remember(v.node.path) { mutableStateOf(v.label) }
    val save = {
        val name = typed.trim().takeUnless { it == v.systemLabel }.orEmpty()
        vm.prefs.setDriveName(DriveNames.idOf(v.node.path), name)
        onClose()
    }
    Dlg(onDismiss = onClose) {
        DlgHeader(FiletIcons.Rename, "Rename drive", sub = v.systemLabel, onClose = onClose)
        DlgBody {
            DlgField("Name", typed, { typed = it.take(MAX_NAME) }, hint = v.systemLabel, tight = true)
            DlgCaption("Leave empty to use the system name.")
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Cancel", onClick = onClose)
            DlgBtn("Save", kind = BtnKind.PRIMARY) { save() }
        }
    }
}

/** The figures the card already holds, laid out. Nothing here asks the drive anything new. */
@Composable
internal fun DriveDetailsDialog(v: VolumeInfo, onClose: () -> Unit) {
    Dlg(onDismiss = onClose) {
        DlgHeader(
            icon = if (v.remote) FiletIcons.Wifi else FiletIcons.Storage,
            title = v.label,
            onClose = onClose,
        )
        DlgBody {
            for ((key, value) in DriveDetails.rows(v, ::humanSize)) {
                DlgKv(key, value, mono = key == DriveDetails.MOUNT_POINT)
            }
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Close", onClick = onClose)
        }
    }
}

/** Long enough for any real name; short enough that a tile heading is still a heading. */
private const val MAX_NAME = 40

/**
 * The Details rows for a drive. Pure, so what is shown and what is left out is a test.
 *
 * A row with nothing true to say is left out rather than shown as a dash. The file system is
 * only named where the scheme already says it - a share's protocol. For a disk on the phone it
 * would take reading the mount table, which is the storage layer's business and not Home's,
 * so it is not guessed at here.
 */
internal object DriveDetails {

    const val MOUNT_POINT = "Mount point"

    /** Under the name in the menu: the system name once renamed, else where it is mounted. */
    fun subtitle(v: VolumeInfo): String? = when {
        v.label != v.systemLabel -> v.systemLabel
        else -> mountPoint(v)
    }

    fun rows(v: VolumeInfo, size: (Long) -> String): List<Pair<String, String>> = buildList {
        add("Name" to v.label)
        if (v.label != v.systemLabel) add("System name" to v.systemLabel)
        val total = v.total
        val free = v.free
        if (total != null && total > 0) {
            add("Size" to size(total))
            if (free != null) add("Used" to size((total - free).coerceAtLeast(0)))
        }
        if (free != null) add("Free" to size(free))
        if (total == null && free == null) add("Size" to if (v.remote) "Not answering" else "Unknown")
        fileSystem(v.node.path.scheme)?.let { add("File system" to it) }
        mountPoint(v)?.let { add(MOUNT_POINT to it) }
    }

    /** The protocol, for a share. Null for anything whose file system is not in its address. */
    fun fileSystem(scheme: String): String? = when (scheme) {
        "smb" -> "SMB"
        "sftp" -> "SFTP"
        "ftp" -> "FTP"
        "dav" -> "WebDAV"
        "peer" -> "Filet share"
        else -> null
    }

    /**
     * The folder it is mounted at, for a drive on this phone. A share's path starts with its
     * saved connection's id, and a granted folder's is a content URI - neither is a place
     * anybody could type, so neither is shown.
     */
    fun mountPoint(v: VolumeInfo): String? = when {
        v.remote -> null
        v.node.path.scheme == SafProvider.SCHEME -> null
        v.node.path.scheme == RootProvider.SCHEME || v.node.path.scheme == "local" -> v.node.path.path
        else -> null
    }
}

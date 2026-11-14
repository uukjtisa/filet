package dev.niccc2007.filet.nearby

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.ago
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgPick
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode

/** "stops in 3 h", for a share with an expiry; null for one that never stops. */
fun stopsIn(expires: Long, now: Long): String? {
    if (expires <= 0) return null
    val left = expires - now
    if (left <= 0) return "stopped"
    val min = left / 60_000
    return when {
        min < 1 -> "stops in under a minute"
        min < 60 -> "stops in $min min"
        min < 48 * 60 -> "stops in ${(min + 30) / 60} h"
        else -> "stops in ${(min + 12 * 60) / (24 * 60)} days"
    }
}

/**
 * The line under a shared file's name: the real name when it was renamed for others, then its
 * size, its lock, its expiry and pause, and its downloads with when the last one was.
 */
fun sharedLine(e: SharedEntry, size: Long?, isDir: Boolean, now: Long): String = listOfNotNull(
    e.path.name.takeIf { it != e.alias },
    if (isDir) "folder · zip in a browser" else size?.takeIf { it >= 0 }?.let { humanSize(it) },
    "locked".takeIf { e.locked },
    "paused".takeIf { e.paused },
    "one-time".takeIf { e.oneTime },
    stopsIn(e.expires, now),
    when (e.downloads) {
        0 -> null
        1 -> "1 download, ${ago(e.lastDownload, now)}"
        else -> "${e.downloads} downloads, last ${ago(e.lastDownload, now)}"
    },
).joinToString(" · ")

/**
 * One shared file on the Share page. Tap for its QR, or tick it while selecting; hold for its
 * menu. The QR button sits at the end.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SharedFileRow(
    e: SharedEntry,
    selecting: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    onQr: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val colors = Filet.colors
    val context = LocalContext.current
    val node by produceState<VNode?>(null, e.path) {
        value = runCatching { FiletApp.graphOf(context).vfs.stat(e.path) }.getOrNull()
    }
    val n = node
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) colors.sel else androidx.compose.ui.graphics.Color.Transparent)
                .combinedClickable(onClick = onTap, onLongClick = onHold)
                .padding(start = 15.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(colors.sunken),
                contentAlignment = Alignment.Center,
            ) {
                val icon: ImageVector = when {
                    selecting && selected -> FiletIcons.Check
                    n?.isDir == true -> FiletIcons.Folder
                    else -> FileKind.of(VNode(e.path, isDir = false, size = -1, mtime = 0)).icon
                }
                Icon(icon, null, tint = if (selected) colors.accent else colors.fg2, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (e.locked) {
                        Icon(FiletIcons.Lock, "Locked", tint = colors.warn, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        e.alias, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (e.paused) colors.fg3 else androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    sharedLine(e, n?.size, n?.isDir == true, System.currentTimeMillis()),
                    fontSize = 10.5.sp, color = colors.fg3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!selecting) {
                IconButton(onClick = onQr) {
                    Icon(FiletIcons.Scan, "Show QR and link", tint = colors.accent, modifier = Modifier.size(19.dp))
                }
            }
        }
        menu()
    }
}

/** A shared file's hold menu: the mock's seven, in its order. */
@Composable
internal fun SharedFileMenu(
    e: SharedEntry,
    open: Boolean,
    onDismiss: () -> Unit,
    onQr: () -> Unit,
    onRename: () -> Unit,
    onExpiry: () -> Unit,
    onPause: () -> Unit,
    onOneTime: () -> Unit,
    onLock: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = Filet.colors
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        @Composable
        fun item(icon: ImageVector, label: String, bad: Boolean = false, go: () -> Unit) = DropdownMenuItem(
            text = { Text(label, fontSize = 13.sp, color = if (bad) colors.bad else androidx.compose.material3.MaterialTheme.colorScheme.onSurface) },
            leadingIcon = { Icon(icon, null, tint = if (bad) colors.bad else colors.fg2, modifier = Modifier.size(17.dp)) },
            onClick = { onDismiss(); go() },
        )
        item(FiletIcons.Scan, "Show QR and link", go = onQr)
        item(FiletIcons.Rename, "Rename for others", go = onRename)
        item(FiletIcons.Clock, "Expiry", go = onExpiry)
        item(FiletIcons.Pause, if (e.paused) "Resume" else "Pause", go = onPause)
        item(FiletIcons.Download, if (e.oneTime) "One-time link: on" else "One-time link", go = onOneTime)
        item(FiletIcons.Lock, if (e.locked) "Unlock" else "Lock", go = onLock)
        item(FiletIcons.Close, "Stop sharing", bad = true, go = onStop)
    }
}

/** Rename for others: the name others see. The file itself keeps its name. */
@Composable
internal fun RenameForOthers(e: SharedEntry, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(e.token) { mutableStateOf(e.alias) }
    Dlg(onDismiss) {
        DlgHeader(FiletIcons.Rename, "Rename for others", sub = e.path.name, onClose = onDismiss)
        DlgBody {
            DlgField("Name others see", name, { name = it }, tight = true)
            DlgCaption("Your file keeps its own name. Empty goes back to it.")
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn("Rename", BtnKind.PRIMARY) { onDone(name) }
        }
    }
}

/** How long a shared file stays shared. A fast share starts at a day; an added file at Never. */
@Composable
internal fun ExpiryPicker(e: SharedEntry, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val now = System.currentTimeMillis()
    Dlg(onDismiss) {
        DlgHeader(FiletIcons.Clock, "Expiry", sub = stopsIn(e.expires, now) ?: "Never stops", onClose = onDismiss)
        DlgBody {
            for ((label, ms) in EXPIRIES) {
                DlgPick(FiletIcons.Clock, label, selected = false) { onPick(if (ms == 0L) 0L else now + ms) }
            }
        }
    }
}

private val EXPIRIES = listOf(
    "1 hour" to 60 * 60_000L,
    "3 hours" to 3 * 60 * 60_000L,
    "1 day" to 24 * 60 * 60_000L,
    "1 week" to 7 * 24 * 60 * 60_000L,
    "Never" to 0L,
)

/** For the page: what each menu entry does, with its toast. */
internal fun sharedActions(vm: BrowserViewModel) = SharedActions(vm)

internal class SharedActions(private val vm: BrowserViewModel) {
    private val set = vm.nearby.shared
    fun pause(e: SharedEntry) {
        set.setPaused(e.token, !e.paused)
        vm.nearby.log(if (e.paused) "Resumed ${e.alias}" else "Paused ${e.alias}")
        vm.toast(if (e.paused) "Sharing ${e.alias} again" else "Paused: it keeps its link")
    }
    fun oneTime(e: SharedEntry) {
        set.setOneTime(e.token, !e.oneTime)
        vm.toast(if (e.oneTime) "No longer one-time" else "The first finished download stops it")
    }
    fun lock(e: SharedEntry) {
        set.setLocked(e.token, !e.locked)
        vm.toast(if (e.locked) "Unlocked" else "Locked: it asks for the lock code ${set.lockCode}")
    }
    fun stop(tokens: Set<String>, names: String) {
        set.revokeMany(tokens)
        vm.nearby.log("Stopped sharing $names")
    }
}

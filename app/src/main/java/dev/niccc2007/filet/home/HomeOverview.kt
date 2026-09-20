@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.niccc2007.filet.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.PaneKind
import dev.niccc2007.filet.browser.CapacityBar
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.PaneController
import dev.niccc2007.filet.browser.SectionLabel
import dev.niccc2007.filet.browser.ago
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode

/**
 * The landing page: storage, what just arrived, and what you touched last.
 *
 * Always a list, and scaled on its own damped curve rather than the pane's view step - Home
 * must not follow the grid's jump to a 56px icon (mock round 3).
 *
 * Sections with nothing in them are not rendered. An empty "New downloads" heading is a dead
 * switch (PLAN.md R1) dressed up as a layout.
 */
@Composable
fun HomeOverview(vm: BrowserViewModel, pane: PaneController) {
    val app by vm.state.collectAsState()
    val downloads by vm.home.downloads.collectAsState()
    val recents by vm.recents.items.collectAsState()
    val tracked by vm.tracked.folders.collectAsState()
    val hideStorage by vm.prefs.hideStorage.collectAsState()
    val hiddenCards by vm.prefs.hiddenCards.collectAsState()
    val hideTermux by vm.prefs.hideTermux.collectAsState()
    val colors = Filet.colors
    var menuFor by remember { mutableStateOf<VNode?>(null) }

    LaunchedEffect(app.revision) { vm.home.refresh() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        // Hideable, because on a phone with one volume the card is a fifth of the first
        // screen saying something you already know. The heading stays either way, so turning
        // it off does not look like the cards failed to load.
        val shown = app.volumes.filter { it.node.path.toString() !in hiddenCards }
        val hiddenCount = app.volumes.size - shown.size
        item {
            Row(
                Modifier.fillMaxWidth().padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Storage", Modifier.weight(1f))
                // Bringing back what was hidden, one card or the section. Without this the
                // per-card X is a one-way door, which is not a control, it is a trap.
                if (hiddenCount > 0) {
                    Text(
                        "$hiddenCount hidden - show",
                        fontSize = 10.sp,
                        color = colors.fg3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { vm.prefs.showAllCards() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Text(
                    if (hideStorage) "Show all" else "Hide all",
                    fontSize = 10.sp,
                    color = colors.fg3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { vm.prefs.setHideStorage(!hideStorage) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (!hideStorage) {
            items(shown.size) { i ->
                val v = shown[i]
                DriveCard(
                    label = v.label,
                    free = v.free,
                    total = v.total,
                    onClick = { pane.navigateTo(v.node.path) },
                    onHide = { vm.prefs.hideCard(v.node.path.toString()) },
                )
            }
        }

        // Termux, if it is here. Shown only when installed, so this is not a permanent
        // advertisement for an app somebody does not have - and it disappears once its tree is
        // granted, because at that point it is a volume in the list above like any other.
        if (vm.termuxInstalled && !hideTermux && app.volumes.none { dev.niccc2007.filet.integrations.Termux.isTermuxTree(android.net.Uri.parse(it.node.path.path)) || it.label.startsWith("Termux") }) {
            item { SectionLabel("Termux") }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
                        .background(colors.raised)
                        .clickable { vm.connectTermux(home = true) }
                        .padding(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(FiletIcons.Terminal, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Connect Termux",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Its home and usr/bin, as a volume you can drag files into. " +
                                "Android needs you to grant it once.",
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = colors.fg3,
                        )
                    }
                    Text(
                        "×",
                        fontSize = 15.sp,
                        color = colors.fg3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { vm.prefs.setHideTermux(true) }
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }
        }

        if (downloads.isNotEmpty()) {
            // The name has moved several times, each time to describe what the list can
            // actually contain: "New downloads" (a file pushed over Nearby is not a download),
            // "New files and folders" (the feed stopped listing folders), briefly "Files"
            // (which claims the whole device when this watches a handful of tracked folders),
            // then "New files".
            //
            // Bug identified: "New files" and "Recent" sat one above the other and could not be
            // told apart, because neither said what made an entry appear in it. One is files
            // that TURNED UP in a folder being watched; the other is files YOU OPENED. Both now
            // say which, in the header, because a subtitle under a section header is not read.
            item {
                SectionHeaderWithAction(
                    label = HomeSections.ARRIVED,
                    action = "Expand",
                    onAction = { pane.openSpecial(PaneKind.HISTORY, HomeSections.ARRIVED) },
                )
            }
            items(downloads.size) { i ->
                val d = downloads[i]
                FeedRow(
                    node = d.node,
                    when_ = ago(d.at),
                    sub = d.origin,
                    onClick = { vm.openHomeEntry(d.node) },
                    onLongClick = { menuFor = d.node },
                )
            }
        }

        // What is being watched, and a way to stop. Without this the tracked list was
        // write-only: you could add a folder from its menu and never see the set again.
        if (tracked.isNotEmpty()) {
            item { SectionLabel("Tracked folders (${tracked.size})") }
            items(tracked.size) { i ->
                val t = tracked[i]
                TrackedRow(
                    folder = t,
                    onOpen = { pane.navigateTo(t.path) },
                    onToggleDeep = { vm.tracked.setRecursive(t.path, !t.recursive); vm.home.refresh() },
                    onStop = { vm.tracked.remove(t.path); vm.home.refresh() },
                )
            }
        }

        if (recents.isNotEmpty()) {
            item { SectionLabel(HomeSections.OPENED) }
            items(minOf(recents.size, 8)) { i ->
                val r = recents[i]
                val node = VNode(r.path, r.isDir, -1, r.at)
                FeedRow(
                    node = node,
                    when_ = ago(r.at),
                    sub = r.path.parent?.path,
                    onClick = { if (r.isDir) pane.navigateTo(r.path) else vm.openHomeEntry(node) },
                    onLongClick = { menuFor = node },
                )
            }
        }

        if (app.volumes.isEmpty() && downloads.isEmpty() && recents.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No storage is reachable yet.\nGrant all-files access, or pick a folder.",
                        fontSize = 13.sp, color = colors.fg3,
                    )
                }
            }
        }
    }

    // What a Home row can do besides open.
    //
    // "Go to containing folder" is the one that was missing and the one that is wanted most:
    // Home shows you a file out of context, and the next thought is almost always "where does
    // this live".
    menuFor?.let { node ->
        HomeRowSheet(
            node = node,
            onDismiss = { menuFor = null },
            onReveal = { menuFor = null; vm.revealInFolder(node) },
            onOpen = { menuFor = null; vm.openHomeEntry(node) },
            onShare = { menuFor = null; vm.shareOne(node) },
            onBookmark = { menuFor = null; vm.bookmarkOne(node) },
            onShortcut = { menuFor = null; vm.shortcutOne(node) },
            onForget = { menuFor = null; vm.forgetHomeEntry(node) },
        )
    }
}

/** The actions a Home row offers. Kept flat: a sheet of seven is already a lot. */
@Composable
private fun HomeRowSheet(
    node: VNode,
    onDismiss: () -> Unit,
    onReveal: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onBookmark: () -> Unit,
    onShortcut: () -> Unit,
    onForget: () -> Unit,
) {
    val colors = Filet.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(node.name, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                SheetAction("Go to containing folder", onReveal)
                SheetAction(if (node.isDir) "Open folder" else "Open", onOpen)
                SheetAction("Share", onShare)
                SheetAction("Bookmark", onBookmark)
                SheetAction("Add to home screen", onShortcut)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Remove from this list",
                    fontSize = 13.sp,
                    color = colors.bad,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onForget)
                        .padding(vertical = 10.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    )
}

@Composable
private fun DriveCard(
    label: String,
    free: Long?,
    total: Long?,
    onClick: () -> Unit,
    onHide: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .background(colors.raised)
            .clickable(onClick = onClick)
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FiletIcons.Storage, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(5.dp))
            // Used of total, because free alone answers the wrong question. "12 GB free" says
            // nothing about whether that is a nearly-empty card or a nearly-full phone; the
            // denominator is what makes the number mean something.
            //
            // Still only drawn when both figures are real - a bar at an invented percentage is
            // a lie that looks like data.
            if (free != null && total != null && total > 0) {
                val used = (total - free).coerceAtLeast(0)
                Text(
                    "${humanSize(used)} / ${humanSize(total)}",
                    fontSize = 10.5.sp, color = colors.fg2,
                )
                Spacer(Modifier.height(5.dp))
                val fraction = (used.toFloat() / total).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth().height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.high)
                ) {
                    Box(
                        Modifier.fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .clip(RoundedCornerShape(2.dp))
                            // Amber past 90%: the point of the bar is to be noticed before the
                            // phone starts refusing to save things.
                            .background(if (fraction > 0.9f) colors.warn else colors.accent)
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text("${humanSize(free)} free", fontSize = 9.5.sp, color = colors.fg3)
            } else if (free != null) {
                Text("${humanSize(free)} free", fontSize = 10.sp, color = colors.fg3)
            } else {
                // "Tap to open" was a promise this card could not keep. A volume with no
                // readable size is usually one of /storage's pseudo-directories - `media` on
                // this phone - and tapping it did nothing at all, which is what was reported.
                // Saying so, and offering the X, beats an invitation that fails.
                Text("Size unknown - may not be readable", fontSize = 10.sp, color = colors.fg3)
            }
        }
        Text(
            "×",
            fontSize = 15.sp,
            color = colors.fg3,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onHide)
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun FeedRow(
    node: VNode,
    when_: String,
    sub: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FileKind.of(node).icon, null, tint = colors.fg2, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            if (!sub.isNullOrEmpty()) {
                Text(sub, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.fg3, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(when_, fontSize = 10.sp, color = colors.fg3)
    }
}

/**
 * One tracked folder, with the two things you ever want to do to it.
 *
 * Subfolders is a per-folder switch rather than a global one because the cost is per folder:
 * the tracked list is also the inotify watch list, inotify charges one watch per directory,
 * and the ceiling is around 8192. Tracking Download deeply is free; tracking the volume root
 * deeply is the whole budget.
 */
@Composable
private fun TrackedRow(
    folder: dev.niccc2007.filet.home.TrackedFolder,
    onOpen: () -> Unit,
    onToggleDeep: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FiletIcons.Eye, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                folder.path.name.ifEmpty { folder.path.scheme },
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                folder.path.path,
                fontSize = 9.5.sp,
                color = colors.fg3,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (folder.recursive) "Subfolders on" else "Subfolders off",
            fontSize = 9.5.sp,
            color = if (folder.recursive) colors.accent else colors.fg3,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggleDeep)
                .padding(horizontal = 7.dp, vertical = 4.dp),
        )
        Icon(
            FiletIcons.Close, "Stop tracking", tint = colors.fg3,
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onStop)
                .padding(5.dp),
        )
    }
}

/**
 * A section heading with one thing you can do to it.
 *
 * Used for New files, whose full history is a screen of its own - the card shows the newest
 * eight and "Expand" opens the rest. A row rather than a chevron on the heading because the
 * action has to name itself: an unlabelled affordance on a heading reads as decoration.
 */
@Composable
private fun SectionHeaderWithAction(label: String, action: String, onAction: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { SectionLabel(label) }
        Text(
            action,
            fontSize = 11.sp,
            color = colors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onAction)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

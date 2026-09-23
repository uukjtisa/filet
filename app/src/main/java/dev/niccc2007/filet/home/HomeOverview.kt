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
import dev.niccc2007.filet.ui.tabs.EmptyTab
import dev.niccc2007.filet.ui.tabs.HomeSearchBar
import dev.niccc2007.filet.ui.tabs.StorageTile
import dev.niccc2007.filet.ui.tabs.Tiles
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
        // The header is the search field. There is no "Home" title above it - the tab strip
        // already says which tab this is, and the word was the whole of what the heading said.
        item {
            HomeSearchBar("Search this device", "everywhere") { pane.openSearch(true) }
        }

        val shown = app.volumes.filter { it.node.path.toString() !in hiddenCards }
        val hiddenCount = app.volumes.size - shown.size
        val showTermux = vm.termuxInstalled && !hideTermux && app.volumes.none {
            dev.niccc2007.filet.integrations.Termux.isTermuxTree(android.net.Uri.parse(it.node.path.path)) ||
                it.label.startsWith("Termux")
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Storage", Modifier.weight(1f))
                // Bringing back what was hidden, one card or the section. Without this the
                // per-card X is a one-way door, which is not a control, it is a trap.
                if (hiddenCount > 0) {
                    Text(
                        "$hiddenCount hidden - show",
                        fontSize = 10.sp,
                        color = colors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { vm.prefs.showAllCards() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Text(
                    if (hideStorage) "Show all" else "Hide all",
                    fontSize = 10.sp,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { vm.prefs.setHideStorage(!hideStorage) }
                        .padding(start = 8.dp, end = 15.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
        if (!hideStorage) {
            // One flow row rather than one full-width card each. A row of cards that does not
            // divide evenly used to leave a hole on the last line; weighting inside the flow
            // makes each line share the width it actually has, so three volumes read as two
            // and one-that-fills rather than two and a gap.
            item {
                Tiles {
                    shown.forEach { v ->
                        tile {
                            val used = if (v.free != null && v.total != null && v.total > 0)
                                (v.total - v.free).coerceAtLeast(0) else null
                            StorageTile(
                                icon = FiletIcons.Storage,
                                kind = v.label,
                                // Free is the number being looked for, with the denominator
                                // underneath. "12 GB free" alone says nothing about whether
                                // that is an empty card or a full phone.
                                value = v.free?.let { humanSize(it) } ?: "—",
                                caption = when {
                                    used != null && v.total != null ->
                                        "free of ${humanSize(v.total)} · ${humanSize(used)} used"
                                    v.free != null -> "free"
                                    // "Tap to open" was a promise this card could not keep. A
                                    // volume with no readable size is usually one of /storage's
                                    // pseudo-directories, and tapping it did nothing at all.
                                    else -> "Size unknown - may not be readable"
                                },
                                // Only drawn when both figures are real. A bar at an invented
                                // percentage is a lie that looks like data.
                                fraction = if (used != null && v.total != null)
                                    used.toFloat() / v.total else null,
                                onHide = { vm.prefs.hideCard(v.node.path.toString()) },
                                onClick = { pane.navigateTo(v.node.path) },
                            )
                        }
                    }
                    // Termux, if it is here. Shown only when installed, so this is not a
                    // permanent advertisement for an app somebody does not have - and it
                    // disappears once its tree is granted, because at that point it is a volume
                    // in the row above like any other.
                    if (showTermux) {
                        tile {
                            StorageTile(
                                icon = FiletIcons.Terminal,
                                kind = "Termux",
                                value = "Connect",
                                caption = "its home and usr/bin, as a volume",
                                onHide = { vm.prefs.setHideTermux(true) },
                                onClick = { vm.connectTermux(home = true) },
                            )
                        }
                    }
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
                    onAction = { pane.openSpecial(PaneKind.HISTORY, HomeSections.ARRIVED_TAB) },
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
            // Adding one needs somewhere to start browsing from, so the verb is only offered
            // when there is a volume to start in. Offering it with nothing to open would be a
            // control that silently does nothing, which is the one thing R1 forbids.
            val startAt = app.volumes.firstOrNull()?.node?.path
            item {
                SectionHeaderWithAction(
                    label = "Tracked folders (${tracked.size})",
                    action = if (startAt != null) "Add a folder" else null,
                    onAction = {
                        startAt ?: return@SectionHeaderWithAction
                        vm.pickFolder("Track a folder", "Track this folder", startAt) { dest ->
                            vm.tracked.add(dest); vm.home.refresh()
                        }
                    },
                )
            }
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
            item {
                SectionHeaderWithAction(
                    label = HomeSections.OPENED,
                    action = "Expand",
                    onAction = { pane.openSpecial(PaneKind.RECENT, HomeSections.OPENED) },
                )
            }
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
                EmptyTab(
                    FiletIcons.Storage,
                    "No storage is reachable yet",
                    "Grant all-files access, or pick a folder.",
                )
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

/**
 * The actions a Home row offers. Kept flat: a sheet of seven is already a lot.
 *
 * Not private, because the expanded tab shows the same rows and must offer the same actions.
 * Two copies of this list would drift, and the drift would be silent - a row that answers a
 * long press differently depending on which screen it is on is worse than one that does not
 * answer at all.
 */
@Composable
fun HomeRowSheet(
    node: VNode,
    onDismiss: () -> Unit,
    onReveal: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onBookmark: () -> Unit,
    onShortcut: () -> Unit,
    /**
     * Null where there is no list to be removed from.
     *
     * A search result is not an entry in a kept list - it is a file that matched - so offering
     * to remove it from one would either do nothing or delete the file, and both of those are
     * worse than not offering it.
     */
    onForget: (() -> Unit)? = null,
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
                if (onForget != null) {
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
private fun SectionHeaderWithAction(label: String, action: String?, onAction: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { SectionLabel(label) }
        // A heading whose verb is unavailable draws no verb at all, rather than a greyed one.
        // Greying says "this exists and you may not have it", which is a different claim.
        if (action != null) {
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
}

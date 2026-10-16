@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.niccc2007.filet.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.CapacityBar
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.PaneController
import dev.niccc2007.filet.browser.PaneKind
import dev.niccc2007.filet.browser.SectionLabel
import dev.niccc2007.filet.browser.ago
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgAction
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCtxHead
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
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
    val folds by vm.prefs.homeFolds.collectAsState()
    val scanning by vm.home.loading.collectAsState()
    val hideStorage by vm.prefs.hideStorage.collectAsState()
    val hiddenCards by vm.prefs.hiddenCards.collectAsState()
    val hideTermux by vm.prefs.hideTermux.collectAsState()
    val colors = Filet.colors
    /**
     * The row a long press opened, and whether it came from the OPENED feed.
     *
     * Both feeds share one sheet, and the sheet was always offering "Remove from this list" -
     * which is an action on the recents list and means nothing for a file that turned up in
     * a tracked folder. There is no list to remove it from; the file is simply there, and
     * the only way to stop seeing it is to stop tracking the folder or to delete it. So the
     * action is absent for an arrival rather than present and lying about what it does.
     */
    var menuFor by remember { mutableStateOf<Pair<VNode, Boolean>?>(null) }

    LaunchedEffect(app.revision) { vm.home.refresh() }

    // Re-read the volumes while this tab is up, so a mounted share that comes back online
    // updates its card instead of staying Offline for the rest of the session.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val job = vm.watchVolumes()
        onDispose { job.cancel() }
    }

    // No discovery on this tab.
    //
    // A mounted place is a volume and appears as a storage card like any other, with its size
    // read through the VFS. Listing what is merely ON the network is a different question and it
    // belongs on Remotes, where adding one is the thing somebody came to do.

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
                    // A NETWORK drive that is not answering is not shown at all.
                    //
                    // It was drawn as an "Offline" card, which reads as a fault to go and fix
                    // and takes a full tile to say nothing actionable. With several addresses
                    // per remote there is also no longer a single thing to report as down: the
                    // card comes back by itself the moment any address answers.
                    //
                    // The test used to be "local, or it has figures", and that is wider than it
                    // reads: `root:` and `saf:` are not the local scheme either, so a superuser
                    // shell that was busy or a document provider that declined for a moment took
                    // those cards off Home as well. That is the reported fault - *the home drives
                    // information disappears as if Filet got disconnected from my device* - and it
                    // needed nothing more than one slow measurement. A volume on this device is
                    // shown whether or not its size is known; only something reached over the
                    // network has to prove it is there.
                    val live = shown.filter {
                        !it.remote || (it.free != null && it.total != null)
                    }
                    live.forEach { v ->
                        tile {
                            val used = if (v.free != null && v.total != null && v.total > 0)
                                (v.total - v.free).coerceAtLeast(0) else null
                            // A network volume is reachable or it is not, and that is a live fact
                            // rather than a stored setting. A local one is simply there.
                            //
                            // `remote`, NOT a scheme test. This line read
                            // `v.node.path.scheme != "local"` and that is the same mistake the
                            // filter above was fixed for, left behind in the renderer: `root:`
                            // and `saf:` are not the local scheme and are not network drives
                            // either. The visible result was `/data`, `/system` and a granted
                            // media tree drawn with a Wi-Fi glyph, one of them captioned
                            // "Network drive not answering" - about a partition on this phone.
                            //
                            // `VolumeInfo.remote` exists precisely to be the one answer to this,
                            // and its own KDoc says so. Two readings of the same question is how
                            // the filter got fixed and the icon did not.
                            val net = v.remote
                            val reachable = v.free != null && v.total != null
                            StorageTile(
                                icon = if (net) FiletIcons.Wifi else FiletIcons.Storage,
                                live = if (net) reachable else null,
                                kind = v.label,
                                // Free is the number being looked for, with the denominator
                                // underneath. "12 GB free" alone says nothing about whether
                                // that is an empty card or a full phone.
                                value = v.free?.let { humanSize(it) }
                                    ?: if (net) "Offline" else "—",
                                caption = when {
                                    used != null && v.total != null ->
                                        "free of ${humanSize(v.total)} · ${humanSize(used)} used"
                                    v.free != null -> "free"
                                    // A network drive that does not answer is not a drive with an
                                    // unreadable size - it is a drive that is not there, and
                                    // saying so names something the reader can act on.
                                    net -> "Network drive not answering"
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
                    // Shown rather than left to be inferred from rows changing under you.
                    // The feed draws its last answer immediately now, so without this there
                    // is no way to tell a finished list from one about to be corrected -
                    // which is worse than the wait it replaced, not better.
                    busy = scanning,
                    folded = HomeFolds.ARRIVED in folds,
                    onFold = { vm.prefs.toggleHomeFold(HomeFolds.ARRIVED) },
                    onAction = { pane.openSpecial(PaneKind.HISTORY, HomeSections.ARRIVED_TAB) },
                )
            }
            items(if (HomeFolds.ARRIVED in folds) 0 else downloads.size) { i ->
                val d = downloads[i]
                FeedRow(
                    node = d.node,
                    when_ = ago(d.at),
                    sub = d.origin,
                    onClick = { vm.openHomeEntry(d.node) },
                    onLongClick = { menuFor = d.node to false },
                )
            }
        }

        // ---- Network -------------------------------------------------------------------
        //
        // Saved places, and anything advertising on the LAN this second. A phone that turns
        // hosting on appears here by itself, and disappears when it stops - which is the whole
        // point: a share is a thing that is happening, not a thing that was configured once.
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
                    folded = HomeFolds.TRACKED in folds,
                    onFold = { vm.prefs.toggleHomeFold(HomeFolds.TRACKED) },
                    onAction = {
                        startAt ?: return@SectionHeaderWithAction
                        vm.pickFolder("Track a folder", "Track this folder", startAt) { dest ->
                            vm.tracked.add(dest); vm.home.refresh()
                        }
                    },
                )
            }
            items(if (HomeFolds.TRACKED in folds) 0 else tracked.size) { i ->
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
                    folded = HomeFolds.OPENED in folds,
                    onFold = { vm.prefs.toggleHomeFold(HomeFolds.OPENED) },
                    onAction = { pane.openSpecial(PaneKind.RECENT, HomeSections.OPENED) },
                )
            }
            items(if (HomeFolds.OPENED in folds) 0 else minOf(recents.size, 8)) { i ->
                val r = recents[i]
                val node = VNode(r.path, r.isDir, -1, r.at)
                FeedRow(
                    node = node,
                    when_ = ago(r.at),
                    sub = r.path.parent?.path,
                    onClick = { if (r.isDir) pane.navigateTo(r.path) else vm.openHomeEntry(node) },
                    onLongClick = { menuFor = node to true },
                )
            }
        }

        if (app.volumes.isEmpty() && downloads.isEmpty() && recents.isEmpty()) {
            item {
                EmptyTab(
                    FiletIcons.Storage,
                    "No storage is reachable yet",
                    "Grant all-files access, or pick a folder",
                )
            }
        }
    }

    // What a Home row can do besides open.
    //
    // "Go to containing folder" is the one that was missing and the one that is wanted most:
    // Home shows you a file out of context, and the next thought is almost always "where does
    // this live".
    menuFor?.let { (node, fromOpened) ->
        HomeRowSheet(
            node = node,
            onDismiss = { menuFor = null },
            onReveal = { menuFor = null; vm.revealInFolder(node) },
            onOpen = { menuFor = null; vm.openHomeEntry(node) },
            onShare = { menuFor = null; vm.share(node) },
            onBookmark = { menuFor = null; vm.bookmarkOne(node) },
            onShortcut = { menuFor = null; vm.shortcutOne(node) },
            // Only the opened list has anything to forget. See `menuFor`.
            onForget = if (fromOpened) ({ menuFor = null; vm.forgetHomeEntry(node) }) else null,
        )
    }
}

/**
 * The actions a Home row offers.
 *
 * Not private, because the expanded tab and the search results show the same rows and must
 * offer the same actions. Two copies of this list would drift, and the drift would be silent -
 * a row that answers a long press differently depending on which screen it is on is worse than
 * one that does not answer at all.
 *
 * ## What the redesign changed here
 *
 * It was a stock `AlertDialog` holding six unadorned 13sp `Text` rows and a "Close" button.
 * Nothing about it was decided; it was the Material default with labels dropped in, which is
 * why it looked like a different app from the screen that opened it.
 *
 * Now on the same kit as every other dialogue, and the header earns its space: the name, then
 * the **path**, which on this sheet is the whole point. Every list that opens this one - New
 * files, Recent, the Home feed, a search result - shows files from somewhere other than where
 * you are standing, so "which IMG_2043.jpg is this" is the first question and it used to be
 * unanswerable without leaving.
 *
 * The verbs keep icons for the same reason the context menu's row does: five bare sentences
 * read slower than five glyphs, and the rows here are the same five verbs.
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
    Dlg(onDismiss = onDismiss) {
        DlgCtxHead(
            name = node.name,
            path = node.path.path.substringBeforeLast('/'),
            meta = if (node.isDir) "Folder" else humanSize(node.size),
            thumb = node,
            onClose = onDismiss,
        )
        DlgBody(padded = false) {
            Spacer(Modifier.height(6.dp))
            DlgAction(FiletIcons.FolderOpen, "Go to containing folder", onClick = onReveal)
            DlgAction(
                if (node.isDir) FiletIcons.Folder else FiletIcons.Open,
                if (node.isDir) "Open folder" else "Open",
                onClick = onOpen,
            )
            DlgAction(FiletIcons.Share, "Share", onClick = onShare)
            DlgAction(FiletIcons.Star, "Bookmark", onClick = onBookmark)
            DlgAction(FiletIcons.Home, "Add to home screen", onClick = onShortcut)
            if (onForget != null) {
                androidx.compose.material3.HorizontalDivider(
                    color = Filet.colors.lineSoft,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                // Separated and red, because it is the one row here that changes something
                // rather than going somewhere.
                DlgAction(
                    FiletIcons.Close, "Remove from this list",
                    danger = true, onClick = onForget,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        // No footer. The close is in the corner and the scrim dismisses, so a row holding one
        // button that says the same thing is a row of nothing.
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
        // The same avatar the expanded history uses, so the card and the full screen show the
        // same file the same way rather than one drawing a picture and the other a glyph.
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.sunken),
            contentAlignment = Alignment.Center,
        ) {
            dev.niccc2007.filet.browser.FileThumb(
                node = node,
                size = 30.dp,
                fallbackTint = colors.fg2,
                glyphSize = 15.dp,
            )
        }
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
private fun SectionHeaderWithAction(
    label: String,
    action: String?,
    busy: Boolean = false,
    folded: Boolean? = null,
    onFold: (() -> Unit)? = null,
    onAction: () -> Unit,
) {
    val colors = Filet.colors
    val turn by animateFloatAsState(if (folded == true) -90f else 0f, label = "fold")
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onFold != null) Modifier.clickable(onClick = onFold) else Modifier)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The whole heading folds the section, not a chevron beside it. Two targets for one
        // action means the small one is the one that works.
        if (folded != null) {
            Icon(
                FiletIcons.Down, null,
                tint = colors.fg3,
                modifier = Modifier
                    .padding(start = 14.dp)
                    .size(13.dp)
                    .graphicsLayer { rotationZ = turn },
            )
        }
        // NOT `weight(if (busy) 0f else 1f)`. A zero weight throws - "invalid weight; must be
        // greater than zero" - and it threw on the Home screen, which is the first thing the
        // app draws, so the app would not open at all. The label simply takes no weight when
        // the dots are beside it, and the spacer after them does the pushing instead.
        if (busy) {
            SectionLabel(label)
            Spacer(Modifier.width(8.dp))
            ScanningDots()
            Spacer(Modifier.weight(1f))
        } else {
            Box(Modifier.weight(1f)) { SectionLabel(label) }
        }
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

/**
 * Three dots, travelling, while the tracked folders are being read.
 *
 * A spinner was the obvious choice and is the wrong one: a spinner over a list that already
 * has rows in it says "this is not ready", and it is - those rows are real, they are simply
 * the previous answer. Dots beside the heading say "still looking" without contradicting
 * what is underneath them.
 */
@Composable
private fun ScanningDots() {
    val clock = rememberInfiniteTransition(label = "scan")
    val t = clock.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dots",
    )
    val colour = Filet.colors.accent
    Canvas(Modifier.size(width = 22.dp, height = 8.dp)) {
        // Read in the draw lambda: a value read in composition would rebuild the whole
        // heading every frame. See LivingGlow for the version of this that cost real heat.
        val at = t.value
        for (i in 0 until 3) {
            val near = 1f - kotlin.math.min(kotlin.math.abs(at - i), 1f)
            drawCircle(
                color = colour.copy(alpha = 0.25f + 0.6f * near),
                radius = size.height * (0.22f + 0.1f * near),
                center = androidx.compose.ui.geometry.Offset(
                    size.width * (0.18f + 0.32f * i),
                    size.height / 2f,
                ),
            )
        }
    }
}

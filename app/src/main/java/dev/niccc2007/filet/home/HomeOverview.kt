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
    val colors = Filet.colors
    var menuFor by remember { mutableStateOf<VNode?>(null) }

    LaunchedEffect(app.revision) { vm.home.refresh() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item { SectionLabel("Storage") }
        items(app.volumes.size) { i ->
            val v = app.volumes[i]
            DriveCard(
                label = v.label,
                free = v.free,
                total = v.total,
                onClick = { pane.navigateTo(v.node.path) },
            )
        }

        if (downloads.isNotEmpty()) {
            item { SectionLabel("New downloads · tracked") }
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

        if (recents.isNotEmpty()) {
            item { SectionLabel("Recent") }
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
private fun DriveCard(label: String, free: Long?, total: Long?, onClick: () -> Unit) {
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
                Text("Tap to open", fontSize = 10.sp, color = colors.fg3)
            }
        }
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

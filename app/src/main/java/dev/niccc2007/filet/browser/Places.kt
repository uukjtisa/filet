package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.tabs.DayBuckets
import dev.niccc2007.filet.ui.tabs.DayLabel
import dev.niccc2007.filet.ui.tabs.EmptyTab
import dev.niccc2007.filet.ui.tabs.TRow
import dev.niccc2007.filet.ui.tabs.TabButton
import dev.niccc2007.filet.ui.tabs.TabHeader
import dev.niccc2007.filet.ui.tabs.Trailing
import dev.niccc2007.filet.ui.theme.Filet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Starred locations. Tapping one navigates the pane it is shown in, not some other pane.
 *
 * A bookmark can be a file - bookmarks can point at a .pptx - so a tap goes through
 * [BrowserViewModel.openPlace] rather than straight to `navigateTo`.
 */
@Composable
fun BookmarksBody(vm: BrowserViewModel, pane: PaneController) {
    val items by vm.bookmarks.items.collectAsState()
    LazyColumn(Modifier.fillMaxSize()) {
        item { TabHeader("Bookmarks") }
        if (items.isEmpty()) {
            item {
                EmptyTab(
                    FiletIcons.Star,
                    "No bookmarks yet",
                    "Tap the star in any folder",
                )
            }
        }
        items(items, key = { it.path.toString() }) { b ->
            TRow(
                // A star on every row said "this is a place" and nothing about what it is.
                // Unknown keeps the star, which is honest: nobody recorded it yet.
                icon = when (b.isDir) {
                    true -> FiletIcons.Folder
                    false -> FiletIcons.File
                    null -> FiletIcons.Star
                },
                name = b.label,
                sub = b.path.path,
                accent = b.isDir == true,
                onClick = { vm.openPlace(b.path, b.isDir, pane) },
                onLongClick = { vm.bookmarks.remove(b.path); vm.toast("Bookmark removed") },
            )
        }
    }
}

/** Recently opened, newest first. Survives with no index at all, which is why it exists. */
@Composable
fun RecentBody(vm: BrowserViewModel, pane: PaneController) {
    val items by vm.recents.items.collectAsState()
    // Read once per list rather than per row, so a list that straddles midnight while it is on
    // screen cannot put two entries from the same minute under two different headings.
    val now = remember(items) { System.currentTimeMillis() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            TabHeader("Recently opened") {
                TabButton("Clear", FiletIcons.Delete, enabled = items.isNotEmpty()) {
                    vm.recents.clear(); vm.toast("Recent list cleared")
                }
            }
        }
        if (items.isEmpty()) {
            item {
                EmptyTab(
                    FiletIcons.Clock,
                    "Nothing opened yet",
                )
            }
        }
        // Newest first is the order the store already keeps, so headings are emitted where the
        // run changes rather than by sorting the list a second time into buckets.
        var lastKey: String? = null
        items.forEach { r ->
            val key = DayBuckets.keyOf(r.at, now)
            if (key != lastKey) {
                lastKey = key
                item(key = "day-" + key) { DayLabel(DayBuckets.label(key, r.at)) }
            }
            item(key = r.path.toString()) {
                TRow(
                    icon = if (r.isDir) FiletIcons.Folder else FiletIcons.File,
                    name = r.label.ifEmpty { r.path.name },
                    sub = r.path.parent?.path ?: r.path.path,
                    accent = r.isDir,
                    thumbOf = if (r.isDir) null
                    else dev.niccc2007.filet.vfs.VNode(r.path, isDir = false, size = -1, mtime = r.at),
                    onClick = {
                        if (r.isDir) pane.navigateTo(r.path)
                        else vm.openNode(
                            dev.niccc2007.filet.vfs.VNode(r.path, isDir = false, size = -1, mtime = r.at)
                        )
                    },
                    // Symmetric with the bookmarks list above, which removes on a long press.
                    // This one claimed the gesture and dropped it, so a stale entry could only
                    // be cleared by clearing every one of them.
                    onLongClick = { vm.recents.remove(r.path); vm.toast("Removed from recents") },
                    trailing = { Trailing(ago(r.at, now)) },
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaceRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    trailing: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.fg3, fontFamily = FontFamily.Monospace)
        }
        if (trailing.isNotEmpty()) {
            Text(trailing, fontSize = 10.sp, color = colors.fg3)
        }
    }
}

/** A section heading used by the Home overview and the rail. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.US),
        fontSize = 9.5.sp,
        letterSpacing = 0.9.sp,
        fontWeight = FontWeight.Medium,
        color = Filet.colors.fg3,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** A capacity bar. Turns warm past 85% because that is when it starts to matter. */
@Composable
fun CapacityBar(fraction: Float, modifier: Modifier = Modifier) {
    val colors = Filet.colors
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colors.lineSoft)
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(if (f > 0.85f) colors.warn else colors.accent)
        )
    }
}

private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0) return ""
    val d = now - at
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000} min ago"
        d < 86_400_000 -> "${d / 3_600_000} h ago"
        d < 172_800_000 -> "yesterday"
        d < 604_800_000 -> "${d / 86_400_000} days ago"
        else -> STAMP.format(Date(at))
    }
}

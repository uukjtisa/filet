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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    if (items.isEmpty()) {
        EmptyNote("No bookmarks yet.\nOpen a folder and tap the star.", Modifier.fillMaxSize())
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.path.toString() }) { b ->
            PlaceRow(
                title = b.label,
                subtitle = b.path.path,
                // A star on every row said "this is a place" and nothing about what it is.
                // Unknown keeps the star, which is honest: nobody recorded it yet.
                icon = when (b.isDir) {
                    true -> FiletIcons.Folder
                    false -> FiletIcons.File
                    null -> FiletIcons.Star
                },
                trailing = "",
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
    if (items.isEmpty()) {
        EmptyNote("Nothing opened yet.", Modifier.fillMaxSize())
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.path.toString() }) { r ->
            PlaceRow(
                title = r.label.ifEmpty { r.path.name },
                subtitle = r.path.parent?.path ?: r.path.path,
                icon = if (r.isDir) FiletIcons.Folder else FiletIcons.File,
                trailing = ago(r.at),
                onClick = {
                    if (r.isDir) pane.navigateTo(r.path)
                    else vm.openNode(
                        dev.niccc2007.filet.vfs.VNode(r.path, isDir = false, size = -1, mtime = r.at)
                    )
                },
                // Symmetric with the bookmarks list above, which removes on a long press.
                // This one claimed the gesture and dropped it, so a stale entry could only be
                // cleared by clearing every one of them.
                onLongClick = { vm.recents.remove(r.path); vm.toast("Removed from recents") },
            )
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

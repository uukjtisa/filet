package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.data.SortKey
import dev.niccc2007.filet.data.ViewStep
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.provider.ArchiveProvider

/**
 * The View popover: one slider, the live step name, ticks, and sort.
 *
 * The slider used to live in the toolbar and crowded it to the point of being unreadable on
 * a phone (mock round 3). One button, one panel, everything view-shaped inside it.
 */
@Composable
fun ViewPopover(vm: BrowserViewModel, onDismiss: () -> Unit) {
    val colors = Filet.colors
    val step by vm.prefs.viewStep.collectAsState()
    val sort by vm.prefs.sort.collectAsState()
    val hidden by vm.prefs.showHidden.collectAsState()

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(248.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("View", fontSize = 11.sp, color = colors.fg3, modifier = Modifier.weight(1f))
                Text(ViewStep.of(step).label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(FiletIcons.Rows, null, tint = colors.fg3, modifier = Modifier.size(15.dp))
                Slider(
                    value = step.toFloat(),
                    onValueChange = { vm.prefs.setViewStep(it.toInt()) },
                    valueRange = 1f..6f,
                    steps = 4,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                Icon(FiletIcons.Grid, null, tint = colors.fg3, modifier = Modifier.size(15.dp))
            }

            HorizontalDivider(color = colors.lineSoft)
            Spacer(Modifier.height(6.dp))
            Text("Sort by", fontSize = 11.sp, color = colors.fg3)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, colors.lineSoft, RoundedCornerShape(7.dp)),
            ) {
                listOf("Name" to SortKey.NAME, "Date" to SortKey.MODIFIED, "Size" to SortKey.SIZE, "Type" to SortKey.TYPE)
                    .forEach { (label, key) ->
                        val on = sort.key == key
                        Text(
                            label,
                            fontSize = 11.sp,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                            modifier = Modifier
                                .background(if (on) colors.accent else Color.Transparent)
                                .clickable {
                                    // Tapping the current key flips the direction, which is
                                    // what every desktop file list does.
                                    vm.prefs.setSort(
                                        if (on) sort.copy(descending = !sort.descending) else sort.copy(key = key)
                                    )
                                }
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                        )
                    }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().clickable {
                    vm.prefs.setSort(sort.copy(descending = !sort.descending))
                }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(FiletIcons.Sort, null, tint = colors.fg2, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (sort.descending) "Descending" else "Ascending", fontSize = 12.sp)
            }
            Row(
                Modifier.fillMaxWidth().clickable {
                    vm.prefs.setShowHidden(!hidden)
                }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (hidden) FiletIcons.Check else FiletIcons.Eye, null, tint = colors.fg2, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("Show hidden files", fontSize = 12.sp)
            }
        }
    }
}

/**
 * The overflow menu.
 *
 * Entries appear only when they would do something: no greyed-out rows, per PLAN.md R1.
 * "Extract here" is absent unless the selection is actually an archive.
 */
@Composable
fun MoreMenu(
    vm: BrowserViewModel,
    pane: PaneController?,
    s: PaneState?,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    if (!expanded || pane == null || s == null) {
        DropdownMenu(expanded = false, onDismissRequest = onDismiss) {}
        return
    }
    val colors = Filet.colors
    val tracked by vm.tracked.paths.collectAsState()
    val selection = pane.selectedNodes()
    val inFolder = s.kind == PaneKind.FOLDER && s.cwd != null
    val bookmarked = s.cwd?.let { vm.bookmarks.contains(it) } == true

    // Read-only locations - an archive, an APK - refuse every write at the provider. Dropping
    // the write entries here is what keeps the refusal out of an error message, and the note
    // is what stops half a menu going missing looking like a bug.
    val readOnly = vm.writeBlockReason(s.cwd)

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        if (inFolder) {
            if (readOnly == null) {
                Item(FiletIcons.NewFolder, "New folder") { onDismiss(); vm.askNewFolder() }
                Item(FiletIcons.File, "New file") { onDismiss(); vm.askNewFile() }
            }
            Item(FiletIcons.Refresh, "Refresh") { onDismiss(); pane.refresh() }
            HorizontalDivider(color = colors.lineSoft)
            Item(FiletIcons.Check, "Select all") { onDismiss(); pane.selectAll() }
            if (s.selecting) {
                Item(FiletIcons.Check, "Invert selection") { onDismiss(); pane.invertSelection() }
            }
        }
        if (selection.isNotEmpty()) {
            HorizontalDivider(color = colors.lineSoft)
            if (selection.size == 1) {
                if (readOnly == null) {
                    Item(FiletIcons.Rename, "Rename") { onDismiss(); vm.askRename(selection[0]) }
                }
                Item(FiletIcons.Info, "Properties") { onDismiss(); vm.showProperties(selection[0]) }
            }
            if (readOnly == null) {
                Item(FiletIcons.Zip, "Compress to zip") { onDismiss(); vm.askCompress() }
                if (selection.size == 1 && selection[0].extension in ArchiveProvider.EXTENSIONS) {
                    Item(FiletIcons.Archive, "Extract here") { onDismiss(); vm.extract(selection[0]) }
                }
            }
            Item(FiletIcons.Share, "Share") { onDismiss(); vm.shareSelection() }
        }
        if (inFolder && readOnly != null) {
            HorizontalDivider(color = colors.lineSoft)
            Note(readOnly)
        }
        if (inFolder) {
            HorizontalDivider(color = colors.lineSoft)
            Item(
                if (bookmarked) FiletIcons.StarFilled else FiletIcons.Star,
                if (bookmarked) "Remove bookmark" else "Bookmark this folder",
            ) { onDismiss(); s.cwd?.let { vm.toggleBookmark(it) } }
            val isTracked = s.cwd in tracked
            Item(
                FiletIcons.Clock,
                if (isTracked) "Stop tracking this folder" else "Track this folder",
            ) {
                onDismiss()
                s.cwd?.let { if (isTracked) vm.tracked.remove(it) else vm.tracked.add(it) }
            }
            if (vm.canPaste()) {
                Item(FiletIcons.Paste, "Paste here") { onDismiss(); vm.paste() }
            }
        }
        HorizontalDivider(color = colors.lineSoft)
        Item(FiletIcons.Cog, "Settings") { onDismiss(); pane.openSpecial(PaneKind.SETTINGS, "Settings") }
        Item(FiletIcons.Info, "About") { onDismiss(); pane.openSpecial(PaneKind.ABOUT, "About") }
    }
}

/** Not an action - the sentence that explains why the actions above it are not there. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        color = Filet.colors.fg3,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp).width(210.dp),
    )
}

@Composable
private fun Item(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 12.5.sp) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(16.dp), tint = Filet.colors.fg2) },
        onClick = onClick,
    )
}

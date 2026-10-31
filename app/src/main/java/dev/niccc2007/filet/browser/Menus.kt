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
import dev.niccc2007.filet.ui.dialogs.PopGroup
import dev.niccc2007.filet.ui.dialogs.PopMenu
import dev.niccc2007.filet.ui.dialogs.PopRow
import dev.niccc2007.filet.ui.dialogs.PopSeparator
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.provider.ArchiveProvider

/**
 * The View popover: one slider, the live step name, ticks, and sort.
 *
 * The slider used to live in the toolbar and crowded it to the point of being unreadable on
 * a phone (mock round 3). One button, one panel, everything view-shaped inside it.
 *
 * On the app's own card ([PopMenu]) like every other menu, with its switches as lit rows: a
 * switch that is on is drawn in the accent, the way a mode is everywhere else, rather than by
 * swapping its icon for a tick that says nothing about what it is.
 */
@Composable
fun ViewPopover(vm: BrowserViewModel, onDismiss: () -> Unit) {
    val colors = Filet.colors
    val step by vm.prefs.viewStep.collectAsState()
    val sort by vm.prefs.sort.collectAsState()
    val hidden by vm.prefs.showHidden.collectAsState()
    val tree by vm.prefs.treeView.collectAsState()

    PopMenu(expanded = true, onDismiss = onDismiss) {
        Column(Modifier.width(248.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PopGroup("View") }
                Text(
                    ViewStep.of(step).label,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 10.dp, top = 3.dp),
                )
            }
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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

            PopSeparator()
            PopGroup("Sort by")
            Row(
                Modifier
                    .padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, colors.lineSoft, RoundedCornerShape(9.dp)),
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
            Spacer(Modifier.height(4.dp))
            PopRow(FiletIcons.Sort, SortOrder.label(sort.key, sort.descending)) {
                vm.prefs.setSort(sort.copy(descending = !sort.descending))
            }
            // ── folders on their own key ──────────────────────────────────────────
            //
            // Folders are easiest to find alphabetically and new files are easiest to find by
            // date, so one order cannot serve both - issue #10. Off by default, which leaves
            // the uniform A-to-Z order everything has always had.
            //
            // Hidden entirely when folders are mixed in among the files, because then there is
            // one sequence and a sequence cannot be in two orders at once. A visible control
            // that silently does nothing is the thing R1 exists to stop.
            if (sort.foldersFirst) {
                PopRow(FiletIcons.Folder, "Sort folders separately", active = sort.folderKey != null) {
                    vm.prefs.setSort(
                        // Turning it ON starts folders on Name, which is the order they
                        // are wanted in nine times out of ten and is also where they
                        // already were - so switching it on changes nothing until the
                        // FILE key is moved, which is the point.
                        if (sort.folderKey == null) {
                            sort.copy(folderKey = SortKey.NAME, folderDescending = true)
                        } else {
                            sort.copy(folderKey = null, folderDescending = null)
                        },
                    )
                }
                val folderKey = sort.folderKey
                if (folderKey != null) {
                    // Under the row's label rather than its icon: the row's padding, its icon
                    // column and the gap after it.
                    Row(
                        Modifier
                            .padding(start = 40.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .border(1.dp, colors.lineSoft, RoundedCornerShape(9.dp)),
                    ) {
                        listOf(
                            "Name" to SortKey.NAME,
                            "Date" to SortKey.MODIFIED,
                            "Size" to SortKey.SIZE,
                            "Type" to SortKey.TYPE,
                        ).forEach { (label, key) ->
                            val on = folderKey == key
                            Text(
                                label,
                                fontSize = 10.5.sp,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                                modifier = Modifier
                                    .background(if (on) colors.accent else Color.Transparent)
                                    .clickable {
                                        val dir = sort.folderDescending ?: sort.descending
                                        vm.prefs.setSort(
                                            if (on) {
                                                sort.copy(folderDescending = !dir)
                                            } else {
                                                sort.copy(folderKey = key)
                                            },
                                        )
                                    }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Folders: " + SortOrder.label(
                            folderKey,
                            sort.folderDescending ?: sort.descending,
                        ),
                        fontSize = 10.sp,
                        color = colors.fg3,
                        modifier = Modifier.padding(start = 40.dp, top = 2.dp, bottom = 4.dp),
                    )
                }
            }
            PopSeparator()
            PopRow(FiletIcons.Eye, "Show hidden files", active = hidden) { vm.prefs.setShowHidden(!hidden) }
            // Here rather than in Settings. It is a way of looking at a folder, which is what
            // every other control in this panel is, and it is the kind of thing somebody
            // turns on for one folder and off again - three taps away in Settings is the
            // wrong distance for that.
            PopRow(FiletIcons.Rows, "Tree view", active = tree) { vm.prefs.setTreeView(!tree) }
            // Only while the tree is on, and only when there is something to act on. A
            // greyed-out row is against R1, and both of these do nothing on a folded tree.
            //
            // The pane is asked for here rather than taken as a parameter: this popover is
            // opened from the toolbar of whichever pane has focus, so the focused pane IS the
            // one it is about, and threading it through would let the two disagree.
            val pane = vm.focusedPane()
            val paneState = pane?.state?.collectAsState()?.value
            if (tree && paneState != null) {
                if (paneState.open.isNotEmpty()) {
                    PopRow(FiletIcons.Up, "Fold everything away") { pane.collapseTree() }
                }
                // No "Show where this is" any more. A tree is always rooted at its drive, so
                // every folder in it already shows where it is - see `TreeShape`.
            }
        }
    }
}

/**
 * The overflow menu: the folder and the app, never the selection.
 *
 * Entries appear only when they would do something: no greyed-out rows, per PLAN.md R1.
 *
 * Bug identified here: through a selection this menu carried Select all, Invert selection, New
 * file, New folder and a copy of half the selection's own actions - so a selection had two menus,
 * the top one offering to make a new file in the middle of choosing files. What acts on the
 * selection is the selection bar's list (`selectionActions`), drawn by the bar, its More and the
 * long-press menu; this one keeps to what is true of the folder whether anything is selected or
 * not, and drops the create and select rows while a selection is open.
 */
@Composable
fun MoreMenu(
    vm: BrowserViewModel,
    pane: PaneController?,
    s: PaneState?,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    if (!expanded || pane == null || s == null) return
    val homeFolder by vm.prefs.homeFolder.collectAsState()
    val selection = pane.selectedNodes()
    val inFolder = s.kind == PaneKind.FOLDER && s.cwd != null
    // "This folder" is the folder being acted in - the open branch in tree mode, else the path
    // bar's. Bug identified here: these read `cwd`, which in tree mode is the volume root, so
    // "Bookmark this folder" and "Set as home" pinned the root of the drive instead.
    val here = s.target
    val bookmarked = here?.let { vm.bookmarks.contains(it) } == true

    // Read-only locations - an archive, an APK - refuse every write at the provider. Dropping
    // the write entries here is what keeps the refusal out of an error message, and the note
    // is what stops half a menu going missing looking like a bug.
    val readOnly = vm.writeBlockReason(s.cwd)
    val split = vm.state.collectAsState().value.split != SplitMode.OFF

    val tabCount = vm.tabs.collectAsState().value.size

    PopMenu(expanded = true, onDismiss = onDismiss) {
        if (inFolder) {
            if (readOnly == null && !s.selecting) {
                Item(FiletIcons.NewFolder, "New folder") { onDismiss(); vm.askNewFolder() }
                Item(FiletIcons.File, "New file") { onDismiss(); vm.askNewFile() }
            }
            Item(FiletIcons.Refresh, "Refresh") { onDismiss(); pane.refresh() }
            // The way INTO a selection. Once one is open the bar's count does this.
            if (!s.selecting) {
                Item(FiletIcons.Check, "Select all") { onDismiss(); pane.selectAll() }
            }
        }
        if (inFolder && readOnly != null) {
            PopSeparator()
            Note(readOnly)
        }
        if (inFolder) {
            PopSeparator()
            Item(
                if (bookmarked) FiletIcons.StarFilled else FiletIcons.Star,
                if (bookmarked) "Remove bookmark" else "Bookmark this folder",
            ) { onDismiss(); here?.let { vm.toggleBookmark(it) } }
            // Home can be this folder instead of the overview. The reminder that it is
            // reversible fires once in the app's life and names where to reverse it.
            val isHome = here != null && here.toString() == homeFolder
            Item(
                FiletIcons.Home,
                if (isHome) "Stop using this as Home" else "Set as home",
            ) {
                onDismiss()
                here?.let { vm.setHomeFolder(if (isHome) null else it) }
            }
            if (vm.canPaste()) {
                Item(FiletIcons.Paste, "Paste here") { onDismiss(); vm.paste() }
            }
        }
        if (inFolder) PopSeparator()
        // Here, in the top corner's menu, beside the split switch: both decide how many panes
        // there are. It was a side-bar row first, in a fold of tools, and then a button of its own
        // on the busiest row in the app. Only with something to close.
        if (tabCount > 1) {
            Item(FiletIcons.CloseAll, "Close all tabs") { onDismiss(); vm.closeAllTabs() }
        }
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
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).width(210.dp),
    )
}

@Composable
private fun Item(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    PopRow(icon, label, onClick = onClick)
}

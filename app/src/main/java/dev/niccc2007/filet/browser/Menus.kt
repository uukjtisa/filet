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
    val tree by vm.prefs.treeView.collectAsState()

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
                Text(SortOrder.label(sort.key, sort.descending), fontSize = 12.sp)
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
                Row(
                    Modifier.fillMaxWidth().clickable {
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
                    }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (sort.folderKey != null) FiletIcons.Check else FiletIcons.Folder,
                        null,
                        tint = colors.fg2,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Sort folders separately", fontSize = 12.sp)
                }
                val folderKey = sort.folderKey
                if (folderKey != null) {
                    Spacer(Modifier.height(2.dp))
                    Row(
                        Modifier
                            .padding(start = 22.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .border(1.dp, colors.lineSoft, RoundedCornerShape(7.dp)),
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
                        modifier = Modifier.padding(start = 22.dp, bottom = 2.dp),
                    )
                }
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
            // Here rather than in Settings. It is a way of looking at a folder, which is what
            // every other control in this panel is, and it is the kind of thing somebody
            // turns on for one folder and off again - three taps away in Settings is the
            // wrong distance for that.
            Row(
                Modifier.fillMaxWidth().clickable {
                    vm.prefs.setTreeView(!tree)
                }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (tree) FiletIcons.Check else FiletIcons.Rows, null,
                    tint = colors.fg2, modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Tree view", fontSize = 12.sp)
            }
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
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            pane.collapseTree()
                        }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            FiletIcons.Up, null,
                            tint = colors.fg2, modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Fold everything away", fontSize = 12.sp)
                    }
                }
                // The other direction, and the one that answers a trunkless tree: somebody
                // deep in a flat list who turns the tree on gets a tree rooted where they
                // stand, which is the same thing a shortcut used to do. See
                // `PaneController.expandToHere`.
                val here = paneState.cwd
                val root = here?.let { at ->
                    pane.rootsForDevice.firstOrNull { it.contains(at) && it != at }
                }
                if (root != null) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            pane.expandToHere(root)
                        }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            FiletIcons.Folder, null,
                            tint = colors.fg2, modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Show where this is", fontSize = 12.sp)
                    }
                }
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
    val homeFolder by vm.prefs.homeFolder.collectAsState()
    val colors = Filet.colors
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
                // Reachable from a search result too, which is where it matters most: finding
                // a hit used to be the end of the road rather than the start of doing
                // something with it.
                Item(FiletIcons.FolderOpen, "Show in folder") { onDismiss(); vm.revealInFolder(selection[0]) }
                Item(FiletIcons.Info, "Properties") { onDismiss(); vm.showProperties(selection[0]) }
            }
            // Works on a folder as well as a file, and on any number of them: several paths
            // one per line is what you want when you are about to paste them into a script.
            Item(FiletIcons.Copy, "Copy as path") { onDismiss(); vm.copyPathsOfSelection() }
            if (readOnly == null) {
                Item(FiletIcons.Zip, "Compress") { onDismiss(); vm.askCompress() }
                if (selection.size == 1 && dev.niccc2007.filet.vfs.provider.Archives.canList(selection[0].name)) {
                    // Three destinations, one code path: each opens the same preview, and the
                    // only difference between them is where it is aimed. Identified in review:
                    // a single "Extract here" makes the current folder the only answer,
                    // so extracting anywhere else meant extracting then moving.
                    Item(FiletIcons.Archive, "Extract here") { onDismiss(); vm.extract(selection[0]) }
                    Item(FiletIcons.FolderOpen, "Extract to\u2026") { onDismiss(); vm.extractToPicked(selection[0]) }
                    if (split) {
                        // The one a two-pane file manager should always have had: the other
                        // side is on screen, and it is usually where the files are going.
                        Item(FiletIcons.Forward, "Extract to the other pane") {
                            onDismiss(); vm.extractToOtherPane(selection[0])
                        }
                    }
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

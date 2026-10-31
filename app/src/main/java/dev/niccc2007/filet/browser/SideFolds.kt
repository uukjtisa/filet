package dev.niccc2007.filet.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import dev.niccc2007.filet.data.SectionLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The side bar, drawn the same in the portrait drawer and the landscape rail.
 *
 * One composable for both, because two hand-built surfaces drift: the rail once had flat
 * sections of its own that looked nothing like the drawer it is the landscape version of. The
 * folds use the drawer's [SwitcherGroup] and share its remembered open/closed state, so folding
 * one in portrait folds it in landscape.
 *
 * Each fold lists its things once and ends with one row into their full tab
 * ([SpecialPanes.Destination.seeAll]):
 *
 *  - **Quick access** - Home, then bookmarked folders. See [QuickAccess].
 *  - **Recent** - Opened or New files, switched in the heading, six rows of either.
 *  - **Drives** - the phone's storage, then each saved share by name.
 *  - **Collections** - New files, Playlists, Carry on.
 *  - **Tools** - Apps, Shortcuts, Scripts, and Trawl when it is installed.
 *  - **Filet** - [SpecialPanes.PINNED]: Activity, Settings, About, Stop Filet. Always open, so
 *    Settings and Stop are never behind a shut fold. At the end of the scroll in both surfaces:
 *    pinned below the rail, a titled card costs half the height of a phone in landscape.
 *
 * A fold's heading takes a hold: Rearrange or Hide, see [SectionMenu]. Rearrange turns the folds
 * into the [SectionEditor], the same one Home uses. The Filet card moves but never hides - it
 * holds Settings and Stop Filet, and a side bar without them has no way back to either. Rows
 * inside a fold take no hold; Quick access is managed from the Bookmarks list.
 *
 * @param onGo called after any row is acted on, so the drawer can close itself.
 */
@Composable
internal fun SideFolds(vm: BrowserViewModel, app: AppState, reduced: Boolean, onGo: () -> Unit) {
    val prefs = vm.prefs
    val bookmarks by vm.bookmarks.items.collectAsState()
    val visits by vm.folderVisits.items.collectAsState()
    val recents by vm.recents.items.collectAsState()
    val arrived by vm.home.recent.collectAsState()
    val jobs by vm.ledger.jobs.collectAsState()
    val remotesRevision by vm.remotesRevision.collectAsState()
    val connections = remember(remotesRevision) { vm.connections.all() }
    val trawl = remember { vm.trawlInstalled() }
    val newMode by prefs.drawerRecentNew.collectAsState()

    // New files is read by the Home overview; a side bar opened before Home ever was would
    // otherwise show an empty list that looks like a phone with nothing new on it.
    LaunchedEffect(newMode) { if (newMode && arrived.isEmpty()) vm.home.refresh() }

    val quick = remember(bookmarks, visits) {
        val used = visits.associateBy { it.path }
        QuickAccess.quick(
            // `!= false`: a bookmark saved before the flag existed is unknown, and most of
            // those are folders. One that turns out to be a file opens as one.
            bookmarks.filter { it.isDir != false }.map { b ->
                val u = used[b.path]
                QuickAccess.Entry(b.path, b.label, b.quickAt, u?.visits ?: 0, u?.at ?: 0)
            },
        )
    }

    val storedOrder by prefs.drawerOrder.collectAsState()
    val storedHidden by prefs.drawerHidden.collectAsState()
    val layout = SectionLayout.of(DRAWER_CARDS.map { it.id }, storedOrder, storedHidden, DRAWER_LOCKED)
    var editing by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<SectionSpec?>(null) }
    menuFor?.let { spec ->
        SectionMenu(
            spec,
            onRearrange = { editing = true },
            onHide = { prefs.setDrawerLayout(layout.hide(spec.id, DRAWER_LOCKED)) },
            onClose = { menuFor = null },
        )
    }
    if (editing) {
        SectionEditor(DRAWER_CARDS, layout, DRAWER_LOCKED, prefs::setDrawerLayout, onDone = { editing = false })
        return
    }
    val hold = { id: String -> { menuFor = DRAWER_CARDS.first { it.id == id } } }

    // The stagger index, shared so rows animate in reading order across the folds.
    var i = 0

    for (id in layout.visible) {
        if (id == FILET_CARD) {
            SwitcherGroup("Filet", detail = "", open = true, onToggle = {}, foldable = false, onLongClick = hold(id)) {
                for (p in SpecialPanes.PINNED) {
                    SwitcherRow(
                        p.icon, p.label, i++, reduced,
                        count = if (p == SpecialPanes.Pinned.ACTIVITY) {
                            jobs.count { it.running }.takeIf { it > 0 }?.toString()
                        } else {
                            null
                        },
                    ) {
                        onGo()
                        vm.onPinned(p)
                    }
                }
            }
            continue
        }
        val fold = SpecialPanes.Fold.entries.firstOrNull { cardIdOf(it) == id } ?: continue
        val tabs = SpecialPanes.inFold(fold)
        val seeAll = tabs.filter { it.seeAll != null }
        val rows = tabs.filter { it.seeAll == null }
        // Exhaustive: another fold does not compile until it says what it draws.
        when (fold) {
            SpecialPanes.Fold.QUICK -> {
                val open by prefs.drawerQuick.collectAsState()
                SwitcherGroup("Quick access", detail = "", open = open, onToggle = { prefs.setDrawerQuick(!open) }, onLongClick = hold(id)) {
                    SwitcherRow(FiletIcons.Home, "Home", i++, reduced) { vm.focusedPane()?.openHome(); onGo() }
                    for (q in quick) {
                        SwitcherRow(FiletIcons.Folder, q.label, i++, reduced) {
                            vm.focusedPane()?.jumpTo(q.path); onGo()
                        }
                    }
                    SeeAll(seeAll, vm, i++, reduced, onGo)
                }
            }

            SpecialPanes.Fold.RECENT -> {
                val open by prefs.drawerRecent.collectAsState()
                SwitcherGroup(
                    "Recent",
                    detail = "",
                    open = open,
                    onToggle = { prefs.setDrawerRecent(!open) },
                    trailing = { RecentSwitch(newMode) { prefs.setDrawerRecentNew(it) } },
                    onLongClick = hold(id),
                ) {
                    if (!newMode) {
                        if (recents.isEmpty()) Hint("Nothing opened yet.")
                        for (entry in recents.take(RECENT_ROWS)) {
                            SwitcherRow(
                                if (entry.isDir) FiletIcons.Folder else FiletIcons.File,
                                entry.label,
                                i++,
                                reduced,
                                trailing = shortAgo(entry.at),
                                thumb = if (entry.isDir) null
                                else dev.niccc2007.filet.vfs.VNode(entry.path, isDir = false, size = -1, mtime = entry.at),
                            ) { vm.openRecent(entry); onGo() }
                        }
                        SeeAll(seeAll, vm, i++, reduced, onGo)
                    } else {
                        if (arrived.isEmpty()) Hint("Nothing new yet.")
                        for (item in arrived.take(RECENT_ROWS)) {
                            SwitcherRow(
                                FileKind.of(item.node).icon,
                                item.node.name,
                                i++,
                                reduced,
                                trailing = shortAgo(item.listedAt),
                                thumb = item.node.takeUnless { it.isDir },
                            ) { vm.openHomeEntry(item.node); onGo() }
                        }
                        SwitcherRow(FiletIcons.Download, "All new files", i++, reduced, quiet = true) {
                            openNewFiles(vm); onGo()
                        }
                    }
                }
            }

            SpecialPanes.Fold.DRIVES -> {
                val open by prefs.drawerStorage.collectAsState()
                SwitcherGroup(
                    "Drives",
                    detail = plural(app.volumes.size + connections.size, "drive", "drives"),
                    open = open,
                    onToggle = { prefs.setDrawerStorage(!open) },
                    onLongClick = hold(id),
                ) {
                    for (v in app.volumes) {
                        SwitcherRow(FiletIcons.Storage, v.label, i++, reduced) {
                            // Arriving from outside the pane, so a tree opens the trunk rather
                            // than re-rooting at the volume.
                            vm.focusedPane()?.jumpTo(v.node.path); onGo()
                        }
                    }
                    for (c in connections.take(MAX_SHARES)) {
                        SwitcherRow(FiletIcons.Device, c.label.ifEmpty { c.host }, i++, reduced) {
                            vm.openConnection(c); onGo()
                        }
                    }
                    SeeAll(seeAll, vm, i++, reduced, onGo)
                }
            }

            SpecialPanes.Fold.COLLECTIONS -> {
                val open by prefs.drawerPlaces.collectAsState()
                SwitcherGroup("Collections", detail = "", open = open, onToggle = { prefs.setDrawerPlaces(!open) }, onLongClick = hold(id)) {
                    SwitcherRow(FiletIcons.Download, "New files", i++, reduced) { openNewFiles(vm); onGo() }
                    Rows(rows, vm, bookmarks.size, i, reduced, onGo)
                    i += rows.size
                }
            }

            SpecialPanes.Fold.TOOLS -> {
                val open by prefs.drawerTools.collectAsState()
                SwitcherGroup(
                    "Tools",
                    detail = plural(rows.size + if (trawl) 1 else 0, "tool", "tools"),
                    open = open,
                    onToggle = { prefs.setDrawerTools(!open) },
                    onLongClick = hold(id),
                ) {
                    Rows(rows, vm, bookmarks.size, i, reduced, onGo)
                    i += rows.size
                    // Trawl is a different app, so it sits with the tools; only when installed,
                    // because an entry that opens a store page is an advertisement.
                    if (trawl) SwitcherRow(FiletIcons.Trawl, "Trawl", i++, reduced) { vm.openTrawl(); onGo() }
                }
            }
        }
    }
}

/** The side bar's cards, in their default order. A fold's id is its name; see [cardIdOf]. */
private const val FILET_CARD = "filet"

internal val DRAWER_CARDS = listOf(
    SectionSpec("quick", "Quick access"),
    SectionSpec("recent", "Recent"),
    SectionSpec("drives", "Drives"),
    SectionSpec("collections", "Collections"),
    SectionSpec("tools", "Tools"),
    SectionSpec(FILET_CARD, "Filet", hideable = false),
)

/** Never hidden: Settings and Stop Filet live in it. */
internal val DRAWER_LOCKED = setOf(FILET_CARD)

internal fun cardIdOf(fold: SpecialPanes.Fold): String = when (fold) {
    SpecialPanes.Fold.QUICK -> "quick"
    SpecialPanes.Fold.RECENT -> "recent"
    SpecialPanes.Fold.DRIVES -> "drives"
    SpecialPanes.Fold.COLLECTIONS -> "collections"
    SpecialPanes.Fold.TOOLS -> "tools"
}

/**
 * Opened or New files, in the Recent heading. Takes its own taps; the rest of the heading folds.
 *
 * The words step down to 9sp rather than cut when the heading is narrow, since a switch reading
 * "New fi" is no switch at all.
 *
 * Bug identified here: the size only ever stepped down. With a fixed-width bar that was enough,
 * but the bar can be dragged now, and narrowing it and widening it again left the switch at 9sp
 * in a bar with room for 10.5. The size is re-keyed on the bar's width, in 8dp steps so a drag
 * does not restart the fit on every frame.
 */
@Composable
private fun RecentSwitch(newMode: Boolean, onPick: (Boolean) -> Unit) {
    val colors = Filet.colors
    var size by remember((LocalSideBarWidth.current / 8f).toInt()) { mutableFloatStateOf(10.5f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        for ((label, isNew) in listOf(OPENED to false, NEW to true)) {
            if (isNew) Text("·", fontSize = size.sp, color = colors.fg3)
            val on = isNew == newMode
            Text(
                label,
                fontSize = size.sp,
                maxLines = 1,
                softWrap = false,
                onTextLayout = { if (it.hasVisualOverflow && size > 9f) size -= 0.5f },
                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                color = if (on) MaterialTheme.colorScheme.onSurface else colors.fg3,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onPick(isNew) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun Rows(
    rows: List<SpecialPanes.Destination>,
    vm: BrowserViewModel,
    bookmarkCount: Int,
    from: Int,
    reduced: Boolean,
    onGo: () -> Unit,
) {
    rows.forEachIndexed { k, d ->
        SwitcherRow(
            d.icon, d.label, from + k, reduced,
            count = if (d.kind == PaneKind.BOOKMARKS) bookmarkCount.takeIf { it > 0 }?.toString() else null,
        ) { vm.focusedPane()?.openSpecial(d.kind, d.label); onGo() }
    }
}

/** A fold's way into its full tab: "All bookmarks", "Everything opened". */
@Composable
private fun SeeAll(
    destinations: List<SpecialPanes.Destination>,
    vm: BrowserViewModel,
    index: Int,
    reduced: Boolean,
    onGo: () -> Unit,
) {
    for (d in destinations) {
        SwitcherRow(d.icon, d.seeAll ?: d.label, index, reduced, quiet = true) {
            vm.focusedPane()?.openSpecial(d.kind, d.label); onGo()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = Filet.colors.fg3,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
    )
}

private fun openNewFiles(vm: BrowserViewModel) {
    vm.focusedPane()?.openSpecial(PaneKind.HISTORY, dev.niccc2007.filet.home.HomeSections.ARRIVED_TAB)
}

/** `now`, `5m`, `3h`, `2d`, then a date: short enough to sit at the end of a row in the narrowest side bar. */
internal fun shortAgo(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0) return ""
    val d = (now - at).coerceAtLeast(0)
    return when {
        d < 60_000 -> "now"
        d < 3_600_000 -> "${d / 60_000}m"
        d < 86_400_000 -> "${d / 3_600_000}h"
        d < 7 * 86_400_000L -> "${d / 86_400_000}d"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(at))
    }
}

private const val OPENED = "Opened"
private const val NEW = "New files"

/** Six of either list: the drawer is a way back to the last few, not the list itself. */
private const val RECENT_ROWS = 6

/** Shares listed by name before the rest are left to the Network tab. */
private const val MAX_SHARES = 8

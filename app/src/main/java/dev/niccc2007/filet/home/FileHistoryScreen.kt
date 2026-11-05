package dev.niccc2007.filet.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.PaneController
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.rotate
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.ui.tabs.EmptyTab
import dev.niccc2007.filet.ui.tabs.SortPair
import dev.niccc2007.filet.ui.tabs.TRow
import dev.niccc2007.filet.ui.tabs.TabButton
import dev.niccc2007.filet.ui.tabs.TabHeader
import dev.niccc2007.filet.ui.tabs.Trailing
import dev.niccc2007.filet.ui.theme.Filet
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Every file on the phone's own storage, by the day it first appeared.
 *
 * The expanded form of the home screen's **New files** card, grouped date by date, with the
 * option of a plain ungrouped list - taking Windows Explorer's Downloads view as a starting
 * point and asking for better than it.
 *
 * What is better than it, and all of it visible on screen:
 *
 * - **Real dates rather than vague buckets.** Explorer's "Earlier this year" can cover four
 *   months; here Today and Yesterday are named and everything older carries its date.
 * - **A count and a size on every header**, so a day is worth opening or it is not, before you
 *   open it.
 * - **Two orderings.** When a file first APPEARED on the phone, or when it was last CHANGED. A
 *   download written last year turns up under today in one and last year in the other.
 *
 * Where the entries come from - the media index, paged, bounded - is `HomeFeed`, and why that is
 * the clock is in its KDoc. The arithmetic is [FileHistory], with tests on it. This file is only
 * the drawing, plus the one piece of lifecycle that must be exact: the media observer is
 * registered while this tab is on screen and the app started, and unregistered the moment
 * either stops being true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileHistoryScreen(vm: BrowserViewModel, pane: PaneController) {
    val colors = Filet.colors
    val everything by vm.home.all.collectAsState()
    // Narrowed by the pane's search box - name, where it came from, its folder - before it is
    // grouped, so the days and the counts are the narrowed list's own.
    val query = dev.niccc2007.filet.ui.LocalPaneQuery.current
    val all = remember(everything, query) {
        if (query.isBlank()) everything
        else everything.filter {
            dev.niccc2007.filet.ui.TextMatch.matches(query, it.node.name, it.origin, it.node.path.parent?.path)
        }
    }
    val scanning by vm.home.loading.collectAsState()
    val outcome by vm.home.outcome.collectAsState()
    val capped by vm.home.capped.collectAsState()
    val windowFrom by vm.home.windowFrom.collectAsState()

    // A setting, not screen state: the Home card is cut from the same list, so the choice made
    // here decides what the card shows too, and it survives leaving the tab.
    val sort by vm.prefs.newFilesOrder.collectAsState()
    // Kept with the tab, the paging included: a position forty pages down can only be put back
    // into a list that has loaded forty pages.
    var grouped by dev.niccc2007.filet.browser.rememberKept("history.grouped", true, save = { it.toString() }, load = { it.toBooleanStrictOrNull() })
    var shown by dev.niccc2007.filet.browser.rememberKept("history.shown", PAGE, save = { it.toString() }, load = { it.toIntOrNull()?.coerceAtLeast(PAGE) })
    var collapsed by dev.niccc2007.filet.browser.rememberKeptSet("history.collapsed")
    var picking by remember { mutableStateOf(false) }
    // The same sheet the Home overview opens, from the same rows. One definition, not two -
    // a row that answers a long press differently depending on which screen it is on is worse
    // than one that does not answer at all.
    var menuFor by remember { mutableStateOf<dev.niccc2007.filet.vfs.VNode?>(null) }
    val scope = rememberCoroutineScope()

    // Read on open and on every return to the app, and listen to the media index for exactly
    // as long as this tab is visible. ON_START/ON_STOP rather than only enter/leave: the tab
    // can stay composed behind another app, and an observer left registered then is a standing
    // cost for a list nobody is reading - the shape of issue #13.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        var handle: AutoCloseable? = null
        val watch = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    if (handle == null) handle = vm.home.watchIndex()
                    vm.home.refresh()
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    handle?.close()
                    handle = null
                }
                else -> Unit
            }
        }
        // addObserver replays the current state, so a tab opened while the app is already
        // started gets its ON_START here and does not wait for the next one.
        lifecycle.addObserver(watch)
        onDispose {
            lifecycle.removeObserver(watch)
            handle?.close()
            handle = null
        }
    }

    // Read once per list, not per frame: `now` feeds the day labels and the today tint, and a
    // fresh clock on every recomposition re-derived both for nothing.
    val now = remember(all) { System.currentTimeMillis() }
    val zone = remember { TimeZone.getDefault() }
    // Which key counts as today, so a row can be tinted without re-deriving the day per row.
    val todayKey = remember(now, zone) { FileHistory.dayKey(now, zone) }

    // Off the main thread, like the categories, and that is the whole lag fix. Opening this tab
    // from the Home card arrives with the full first page already held - two thousand rows and
    // more - and grouping them (a Calendar and a formatted key per row) ran inside composition,
    // so the tab could not draw its first frame until every day had been built. The first
    // QUICK rows are built in place, which fills the screen, and the rest is grouped behind it.
    val quick = remember(all, sort, grouped) {
        dev.niccc2007.filet.log.FiletLog.span("newfiles", "quick ${minOf(all.size, QUICK)}") {
            NewFilesView.of(all.take(QUICK), sort, grouped, now, zone)
        }
    }
    val view by produceState(quick, all, sort, grouped, now) {
        value = withContext(Dispatchers.Default) {
            dev.niccc2007.filet.log.FiletLog.span("newfiles", "group ${all.size}") {
                NewFilesView.of(all, sort, grouped, now, zone)
            }
        }
    }
    // A view built for the other mode is never drawn: a sort or By day/Flat flip shows the quick
    // build of the new mode until the full one lands, rather than a frame of the wrong list.
    val current = if (view.sort == sort && view.grouped == grouped) view else quick
    val entries = current.entries
    val groups = current.groups
    val flat = current.flat
    // The rows as drawn, folded days left out, for the pane's selection to range over and count.
    // Every row held rather than the page drawn so far: paging is how the list is drawn, not
    // what is in it, and Select all stopping at row eighty would be a selection of an accident.
    val drawnEntries = remember(current, collapsed) {
        if (current.grouped) current.groups.filter { it.key !in collapsed }.flatMap { it.entries } else current.flat
    }
    val pick = rememberListPick(vm, pane, drawnEntries)

    val listState = dev.niccc2007.filet.browser.rememberKeptListState("history")

    // Load as you scroll, chosen over a row of buttons. The count of what is left
    // is on screen either way - it is the thing that tells you whether to keep scrolling.
    LaunchedEffect(listState, entries.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collectLatest { last ->
                if (last >= shown - LOAD_AHEAD) shown = (shown + PAGE).coerceAtMost(entries.size + PAGE)
                // And the next page of the index once what is held is nearly drawn. Bounded by
                // the feed: past its cap this does nothing and the date jump takes over.
                if (shown >= entries.size) vm.home.loadMore()
            }
    }

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = now)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    val chosen = state.selectedDateMillis ?: return@TextButton
                    // The picker hands back UTC midnight for the day tapped, so it is read in
                    // UTC and re-keyed - reading it in the local zone shifts the answer by a
                    // day for everyone not on Greenwich.
                    val key = FileHistory.dayKey(chosen, TimeZone.getTimeZone("UTC"))
                    // A JUMP, not a filter. The history stays either side of the day asked for,
                    // and a day with nothing in it lands between its neighbours rather than
                    // appearing to do nothing. The day is loaded first if it is older than what
                    // is held - by paging, or past the cap by a range query for that day.
                    scope.launch {
                        val start = chosen - zone.getOffset(chosen)
                        vm.home.reach(start, start + 86_400_000L)
                        listState.scrollToItem(FileHistory.jumpIndex(groups, key).coerceAtMost(shown))
                    }
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state, title = null)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Four flat labels became two shaped controls. They were indistinguishable from each
        // other: two of them were an exclusive pair, one was a momentary button that opened a
        // dialog, and one was a switch that flipped its own label - all drawn identically, so
        // the screen never said which press would change what.
        // The same "still looking" mark the Home card carries. This tab draws the last
        // answer immediately now, so without it there is no way to tell a finished list from
        // one about to be corrected.
        TabHeader(
            if (scanning) HomeSections.ARRIVED_TAB + "  ·  looking" else HomeSections.ARRIVED_TAB,
        ) {
            SortPair(
                listOf(ADDED_LABEL, MODIFIED_LABEL),
                if (sort == HistorySort.DATE_ADDED) ADDED_LABEL else MODIFIED_LABEL,
            ) {
                val next = if (it == ADDED_LABEL) HistorySort.DATE_ADDED else HistorySort.DATE_MODIFIED
                if (next != sort) {
                    vm.prefs.setNewFilesOrder(next)
                    // A different list, not a re-sort of this one: it is read again.
                    vm.home.refresh()
                }
            }
            // Only offered while the list is grouped: jumping to a day in a flat list would
            // land somewhere with nothing on screen to say which day you had reached.
            if (grouped) TabButton("Date", FiletIcons.Clock) { picking = true }
            TabButton(
                if (grouped) "By day" else "Flat",
                if (grouped) FiletIcons.Rows else FiletIcons.Grid,
                primary = true,
            ) { grouped = !grouped }
        }

        // Says where each list's dates come from, so the two orders are not a mystery pair.
        // Date added is corrected by Filet's memory; date modified is the system's alone.
        Text(
            if (sort == HistorySort.DATE_ADDED) {
                "Uses Filet's memory of when each file first arrived, so edits don't make it new."
            } else {
                "Uses the system's last-modified date, so edited files come back to the top."
            },
            fontSize = 10.5.sp,
            color = colors.fg3,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        )

        if (windowFrom != null) {
            Text(
                "Showing from the date picked. Back to the newest",
                fontSize = 11.sp,
                color = colors.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.home.toNewest() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (entries.isEmpty() && query.isNotBlank() && everything.isNotEmpty()) {
            dev.niccc2007.filet.ui.NoMatch(query)
            return@Column
        }
        if (entries.isEmpty()) {
            when (val r = outcome) {
                // Three different answers, drawn as three. See `CategoryResult`.
                is CategoryResult.Refused -> EmptyTab(FiletIcons.Clock, "Filet needs storage access to list these")
                is CategoryResult.Failed -> {
                    EmptyTab(FiletIcons.Clock, "The media index could not be read")
                    Text(
                        r.reason,
                        fontSize = 10.5.sp,
                        maxLines = 2,
                        color = colors.fg3,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                null -> EmptyTab(FiletIcons.Clock, "Looking")
                else -> EmptyTab(FiletIcons.Clock, "No files on the phone's own storage yet")
            }
            return@Column
        }

        // What a viewer opened from a row swipes through: the rows in the order they are drawn,
        // without the days folded away. Built on the tap, not on every frame.
        val openedFrom = { drawnEntries.asNodes() }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (grouped) {
                var drawn = 0
                for (g in groups) {
                    if (drawn >= shown) break
                    item(key = "h:${g.key}") {
                        DayHeader(g, g.key in collapsed) {
                            collapsed = if (g.key in collapsed) collapsed - g.key else collapsed + g.key
                        }
                    }
                    if (g.key in collapsed) continue
                    val take = g.entries.take((shown - drawn).coerceAtLeast(0))
                    drawn += take.size
                    val isToday = g.key == todayKey
                    items(take.size, key = { "e:${g.key}:${take[it].path}" }) { i ->
                        HistoryRow(take[i], sort, vm, isToday, from = openedFrom, pick = pick) { menuFor = it }
                    }
                }
            } else {
                val take = flat.take(shown)
                items(take.size, key = { "f:${take[it].path}" }) { i ->
                    HistoryRow(
                        take[i], sort, vm,
                        FileHistory.dayKey(take[i].timeFor(sort), zone) == todayKey,
                        from = openedFrom,
                        pick = pick,
                    ) { menuFor = it }
                }
            }

            val remaining = entries.size - shown
            if (remaining <= 0 && capped) {
                item(key = "capped") {
                    Text(
                        "The newest ${"%,d".format(entries.size)} files. Pick a date to go further back.",
                        fontSize = 10.5.sp,
                        color = colors.fg3,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    )
                }
            } else if (remaining > 0) {
                item(key = "more") {
                    Text(
                        "$remaining more below",
                        fontSize = 10.5.sp,
                        color = colors.fg3,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    )
                }
            } else {
                item(key = "end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    menuFor?.let { node ->
        HomeRowSheet(
            node = node,
            onDismiss = { menuFor = null },
            onReveal = { menuFor = null; vm.revealInFolder(node) },
            onOpen = { menuFor = null; vm.openHomeEntry(node) },
            onShare = { menuFor = null; vm.share(node) },
            onBookmark = { menuFor = null; vm.bookmarkOne(node) },
            onShortcut = { menuFor = null; vm.shortcutOne(node) },
            onForget = { menuFor = null; vm.forgetHomeEntry(node) },
            // Offered here and nowhere else: this is the list a folder gets blocked out of.
            onBlockFolder = { menuFor = null; vm.blockHomeFolder(node) },
        )
    }
}

/**
 * A day, with what is in it.
 *
 * Keyed by date rather than by label, because "Today" names a different day tomorrow and a
 * group collapsed by label would reopen itself at midnight.
 */
@Composable
internal fun DayHeader(group: DayGroup, collapsed: Boolean, onToggle: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
            .clickable(onClick = onToggle)
            .padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FiletIcons.Next,
            null,
            tint = colors.fg3,
            // One icon rather than two: pointing right when the group is shut and down when it
            // is open is the same affordance every tree in the app already uses.
            modifier = Modifier.size(11.dp).rotate(if (collapsed) 0f else 90f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            group.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = colors.fg2,
        )
        Spacer(Modifier.weight(1f))
        // The part Explorer's own headers leave out. It counts the whole group and not the
        // rows drawn, which differ the moment the group is shut or the page is capped.
        Text(
            "${group.count} file${if (group.count == 1) "" else "s"} · ${bytes(group.bytes)}",
            fontSize = 10.sp,
            color = colors.fg3,
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun HistoryRow(
    entry: HistoryEntry,
    sort: HistorySort,
    vm: BrowserViewModel,
    today: Boolean,
    /** The rows this one sits among, for the viewer to swipe through. See `ViewerRun`. */
    from: (() -> List<dev.niccc2007.filet.vfs.VNode>)? = null,
    /**
     * The pane's selection, for a list that takes part in one. With it a hold selects and a hold
     * on a selected row is what opens [onLongPress]'s sheet - see [ListPick].
     */
    pick: ListPick? = null,
    onLongPress: (dev.niccc2007.filet.vfs.VNode) -> Unit,
) {
    val node = remember(entry.path) {
        dev.niccc2007.filet.vfs.VNode(
            dev.niccc2007.filet.vfs.VPath.parse(entry.path),
            false,
            entry.bytes,
            entry.modified,
        )
    }
    // The size moves onto the subtitle beside where the file came from, which is how the row
    // fits one line without an ellipsis eating the name. The time stays on the right, because
    // it is the column the eye runs down when the list is grouped by day.
    val sub = remember(entry.bytes, entry.origin) {
        listOfNotNull(bytes(entry.bytes), entry.origin).joinToString(" · ")
    }
    val open = { vm.openHomeEntry(node, from?.invoke()) }
    TRow(
        icon = FileKind.of(node).icon,
        name = entry.name,
        sub = sub,
        accent = today,
        thumbOf = node,
        selected = pick?.has(node.path) ?: false,
        // Bug identified: this list answered a tap and nothing else, while the very same rows
        // on the Home overview answer a long press with a sheet. The actions that exist
        // everywhere else in the app were missing exactly where a new file is most likely to
        // need them.
        onClick = { if (pick != null) pick.tap(node, open) else open() },
        onLongClick = { if (pick != null) pick.hold(node, onLongPress) else onLongPress(node) },
        trailing = { Trailing(clock(entry.timeFor(sort))) },
    )
}

/** These entries as the files they name, for a viewer's run. */
internal fun List<HistoryEntry>.asNodes(): List<dev.niccc2007.filet.vfs.VNode> = mapNotNull { e ->
    runCatching {
        dev.niccc2007.filet.vfs.VNode(dev.niccc2007.filet.vfs.VPath.parse(e.path), false, e.bytes, e.modified)
    }.getOrNull()
}

/** `9:43pm`, so a row says the time and the header says the day. */
private fun clock(at: Long): String =
    java.text.SimpleDateFormat("h:mma", java.util.Locale.getDefault()).format(java.util.Date(at)).lowercase()

private fun bytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.0f KB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024)
    else -> "%.2f GB".format(n / 1024.0 / 1024 / 1024)
}

/**
 * The two questions the list can answer, as the control writes them.
 *
 * Constants because [SortPair] selects on the label it was handed: a string typed twice is a
 * control that silently stops selecting anything the moment one of them is edited.
 */
private const val ADDED_LABEL = "By date added"
private const val MODIFIED_LABEL = "By date modified"

/** How many rows arrive at a time, and how close to the end to fetch the next lot. */
private const val PAGE = 80

/** Rows grouped in place for the first frame; the rest are grouped off the main thread. */
private const val QUICK = 120

/** One grouping of the held rows, for one sort and one mode. Built off the main thread. */
private class NewFilesView(
    val sort: HistorySort,
    val grouped: Boolean,
    val entries: List<HistoryEntry>,
    val groups: List<DayGroup>,
    val flat: List<HistoryEntry>,
) {
    companion object {
        fun of(all: List<FeedItem>, sort: HistorySort, grouped: Boolean, now: Long, zone: TimeZone): NewFilesView {
            val entries = all.map {
                HistoryEntry(
                    path = it.node.path.toString(),
                    name = it.node.name,
                    bytes = it.node.size.coerceAtLeast(0L),
                    added = it.arrived,
                    modified = it.at,
                    origin = it.origin ?: it.node.path.parent?.name,
                )
            }
            return NewFilesView(
                sort, grouped, entries,
                if (grouped) FileHistory.group(entries, sort, now, zone) else emptyList(),
                if (grouped) emptyList() else FileHistory.flat(entries, sort),
            )
        }
    }
}
private const val LOAD_AHEAD = 20

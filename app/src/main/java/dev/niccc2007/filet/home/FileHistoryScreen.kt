package dev.niccc2007.filet.home

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
import dev.niccc2007.filet.ui.theme.Filet
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Everything in the tracked folders, by day.
 *
 * The expanded form of the home screen's **New files** card. Nic asked for the card to open
 * into a full history, grouped date by date, with the option of a plain ungrouped list - taking
 * Windows Explorer's Downloads view as a starting point and asking for better than it.
 *
 * What is better than it, and all of it visible on screen:
 *
 * - **Real dates rather than vague buckets.** Explorer's "Earlier this year" can cover four
 *   months; here Today and Yesterday are named and everything older carries its date.
 * - **A count and a size on every header**, so a day is worth opening or it is not, before you
 *   open it.
 * - **Two orderings.** When a file was first SEEN in a tracked folder, or when it was last
 *   CHANGED. A download written last year turns up under today in one and last year in the
 *   other, which is the distinction he asked for and which no file browser offers.
 *
 * The arithmetic - which day, what a day weighs, where a picked date lands - is in
 * [FileHistory] with tests on it. This file is only the drawing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileHistoryScreen(vm: BrowserViewModel, pane: PaneController) {
    val colors = Filet.colors
    val all by vm.home.all.collectAsState()

    var sort by remember { mutableStateOf(HistorySort.FIRST_SEEN) }
    var grouped by remember { mutableStateOf(true) }
    var shown by remember { mutableIntStateOf(PAGE) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var picking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Record first-seen for whatever the feed found, so the ordering has something to sort on,
    // and prune what is gone. Both are cheap and idempotent - the store only writes when
    // something is genuinely new.
    LaunchedEffect(all) {
        // Passed WITH their modification times, because the very first pass has to seed from
        // those rather than from the clock - otherwise every file already on the device reads
        // as having turned up the moment this screen was first opened.
        val mtimes = all.associate { it.node.path.toString() to it.at }
        vm.firstSeen.record(mtimes, System.currentTimeMillis())
        vm.firstSeen.prune(mtimes.keys)
    }

    val entries = remember(all) {
        all.map {
            val p = it.node.path.toString()
            HistoryEntry(
                path = p,
                name = it.node.name,
                bytes = it.node.size.coerceAtLeast(0L),
                // A file that predates this feature has no recorded date. Falling back to its
                // mtime keeps it in a sensible place; zero would pin every pre-existing file to
                // 1970 at the bottom of the list forever.
                firstSeen = vm.firstSeen.of(p) ?: it.at,
                lastChanged = it.at,
                origin = it.origin ?: it.node.path.parent?.name,
            )
        }
    }

    val now = System.currentTimeMillis()
    val zone = remember { TimeZone.getDefault() }
    val groups = remember(entries, sort, grouped) {
        if (grouped) FileHistory.group(entries, sort, now, zone) else emptyList()
    }
    val flat = remember(entries, sort, grouped) {
        if (grouped) emptyList() else FileHistory.flat(entries, sort)
    }

    val listState = rememberLazyListState()

    // Load as you scroll, which was his pick over a row of buttons. The count of what is left
    // is on screen either way - it is the thing that tells you whether to keep scrolling.
    LaunchedEffect(listState, entries.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collectLatest { last ->
                if (last >= shown - LOAD_AHEAD) shown = (shown + PAGE).coerceAtMost(entries.size + PAGE)
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
                    // appearing to do nothing.
                    scope.launch { listState.scrollToItem(FileHistory.jumpIndex(groups, key).coerceAtMost(shown)) }
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state, title = null)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Toggle("First seen", sort == HistorySort.FIRST_SEEN) { sort = HistorySort.FIRST_SEEN }
            Spacer(Modifier.width(6.dp))
            Toggle("Last changed", sort == HistorySort.LAST_CHANGED) { sort = HistorySort.LAST_CHANGED }
            Spacer(Modifier.weight(1f))
            // Only offered while the list is grouped: jumping to a day in a flat list would
            // land somewhere with nothing on screen to say which day you had reached.
            if (grouped) {
                Toggle("Date", false) { picking = true }
                Spacer(Modifier.width(2.dp))
            }
            Toggle(if (grouped) "By day" else "Flat", true) { grouped = !grouped }
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing in the tracked folders yet.",
                    fontSize = 12.sp,
                    color = colors.fg3,
                )
            }
            return@Column
        }

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
                    items(take.size, key = { "e:${g.key}:${take[it].path}" }) { i ->
                        HistoryRow(take[i], sort, vm)
                    }
                }
            } else {
                val take = flat.take(shown)
                items(take.size, key = { "f:${take[it].path}" }) { i -> HistoryRow(take[i], sort, vm) }
            }

            val remaining = entries.size - shown
            if (remaining > 0) {
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
}

/**
 * A day, with what is in it.
 *
 * Keyed by date rather than by label, because "Today" names a different day tomorrow and a
 * group collapsed by label would reopen itself at midnight.
 */
@Composable
private fun DayHeader(group: DayGroup, collapsed: Boolean, onToggle: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FiletIcons.Next,
            null,
            tint = colors.fg3,
            // One icon rather than two: pointing right when the group is shut and down when it
            // is open is the same affordance every tree in the app already uses.
            modifier = Modifier.size(14.dp).rotate(if (collapsed) 0f else 90f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            group.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        // The part Explorer's own headers leave out.
        Text(
            "${group.count} file${if (group.count == 1) "" else "s"} · ${bytes(group.bytes)}",
            fontSize = 10.sp,
            color = colors.fg3,
        )
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, sort: HistorySort, vm: BrowserViewModel) {
    val colors = Filet.colors
    val node = remember(entry.path) {
        dev.niccc2007.filet.vfs.VNode(
            dev.niccc2007.filet.vfs.VPath.parse(entry.path),
            false,
            entry.bytes,
            entry.lastChanged,
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.openHomeEntry(node) }
            .padding(start = 26.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FileKind.of(node).icon, null, tint = colors.fg2, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            entry.origin?.let {
                Text(it, fontSize = 9.5.sp, color = colors.fg3, maxLines = 1, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(clock(entry.timeFor(sort)), fontSize = 10.sp, color = colors.fg3)
        Spacer(Modifier.width(10.dp))
        Text(bytes(entry.bytes), fontSize = 10.sp, color = colors.fg3)
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = Filet.colors
    Text(
        label,
        fontSize = 11.sp,
        color = if (on) colors.accent else colors.fg3,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
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

/** How many rows arrive at a time, and how close to the end to fetch the next lot. */
private const val PAGE = 80
private const val LOAD_AHEAD = 20

package dev.niccc2007.filet.playback

import dev.niccc2007.filet.ui.Labels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.dialogs.PopMenu
import dev.niccc2007.filet.ui.dialogs.PopRow
import dev.niccc2007.filet.ui.dialogs.PopSeparator
import dev.niccc2007.filet.ui.tabs.EmptyTab
import dev.niccc2007.filet.ui.tabs.SectionRow
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.niccc2007.filet.ui.dialogs.PopGroup
import dev.niccc2007.filet.ui.tabs.DayBuckets
import java.util.Locale

/**
 * What you were part way through, and where you got to.
 *
 * ## One tab, not two
 *
 * Decision: one tab with two filters rather than a Music tab and a Video tab. Two destinations
 * that differ only by a filter double the cost of reaching the same list, and the navigation
 * surfaces are already eight entries deep before this one; meanwhile the question somebody has
 * when they open this is "what was I in the middle of", which is answered by one list in time
 * order and made worse by splitting it in half. The filters are there for the times the answer
 * is "the film", and the choice is remembered for the session.
 *
 * If this turns out to be wrong, two destinations is a smaller change than merging them later:
 * the pane kind is declared in `SpecialPanes` and the screen takes a filter already.
 *
 * ## Why the list is cheap
 *
 * Every row is drawn from what `ResumeStore` wrote down - title, length, position - so nothing
 * here stats a file or reads a tag. That matters because the alternative is a list whose
 * opening cost grows with how much media somebody has half-finished, and because a track that
 * has since been deleted should still be listed and should fail when it is TAPPED, with a
 * sentence, rather than silently vanishing from a list of things you remember doing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResumeScreen(vm: BrowserViewModel) {
    val store = vm.playback.resume
    // Two signals. The store's own moves the moment any player writes a stopped position or a
    // row arrives or leaves - see `ResumeStore.changes`. The view model's moves on a pull-down
    // refresh. Neither is a poll: both move only when something did.
    val revision by vm.resumeRevision.collectAsStateWithLifecycle()
    val changed by store.changes.collectAsStateWithLifecycle()
    var filter by dev.niccc2007.filet.browser.rememberKeptEnum("resume.filter", ResumeFilter.ALL)
    // Bug identified here: every stop was kept, the end included, so after a few days of
    // listening the finished rows outnumbered the unfinished ones and buried them. Finished rows
    // still have a use as a record of what was watched, so they are filtered rather than dropped,
    // and the list opens on the unfinished ones because that is what it is for.
    var progress by dev.niccc2007.filet.browser.rememberKeptEnum("resume.progress", ResumeProgress.UNFINISHED)
    val all = remember(revision, changed) { store.all() }
    // The filters first, then the pane's search box: title or where it is.
    val query = dev.niccc2007.filet.ui.LocalPaneQuery.current
    val rows = remember(all, filter, progress, query) {
        all.filter(filter::accepts).filter(progress::accepts)
            .filter { dev.niccc2007.filet.ui.TextMatch.matches(query, it.title, it.path) }
    }
    // Several at once. Started from Select on the filter row, or from a row's hold menu; while it
    // is on, a tap toggles instead of playing. Keyed by path, the store's own key. Choosing is a
    // state of its own rather than "something is chosen", so Select can open it with nothing
    // chosen yet, and unticking the last row does not drop out of it under the finger.
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    // A row that leaves the list (forgotten elsewhere, or filtered out) leaves the selection too,
    // so the count never includes something that is not on screen.
    LaunchedEffect(rows) {
        val shown = rows.mapTo(HashSet()) { it.path }
        if (!shown.containsAll(selected)) selected = selected.filterTo(HashSet()) { it in shown }
    }
    val stopSelecting = { selecting = false; selected = emptySet() }
    dev.niccc2007.filet.browser.InnerBack(vm, enabled = selecting) { stopSelecting() }
    val toggle = { path: String -> selected = if (path in selected) selected - path else selected + path }

    // Forgetting is undone from the bar at the bottom for a few seconds. Bug identified here:
    // Forget everything cleared the whole list at once with no question and no way back, from a
    // quiet word at the very end of the list that was easy to press while scrolling to it.
    var undo by remember { mutableStateOf<List<ResumeStore.Point>?>(null) }
    LaunchedEffect(undo) {
        if (undo != null) {
            kotlinx.coroutines.delay(UNDO_MS)
            undo = null
        }
    }
    val forget: (List<ResumeStore.Point>) -> Unit = { gone ->
        if (gone.isNotEmpty()) {
            if (gone.size == all.size) store.clear() else gone.forEach { store.forget(it.path) }
            vm.bumpResume()
            undo = gone
        }
    }

    val now = remember(all) { System.currentTimeMillis() }
    // The top card is the newest row the filters and the search let through, and it is not
    // repeated below. Not while choosing several: a card cannot be ticked like a row.
    val top = if (selecting) null else rows.firstOrNull()
    val rest = if (top != null) rows.drop(1) else rows
    val days = remember(rest, now) { resumeDays(rest, now) }
    // A tap carries on at once. Decision: no Continue / From the start question in between. The
    // row IS the request to carry on, and a dialog asking it again made every visit two taps;
    // starting over is on the top card and in the row's hold menu, and a finished row starts
    // over on its own.

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = dev.niccc2007.filet.browser.rememberKeptListState("resume")) {
            item {
                ResumeHeader(
                    all = all,
                    rows = rows,
                    filter = filter,
                    progress = progress,
                    onFilter = { filter = it },
                    onProgress = { progress = it },
                    selecting = selecting,
                    chosen = selected.size,
                    onSelect = { selecting = true },
                    onCancel = stopSelecting,
                    onAll = {
                        selected = if (selected.size == rows.size) emptySet() else rows.mapTo(HashSet()) { it.path }
                    },
                    onForgetChosen = {
                        forget(rows.filter { it.path in selected })
                        stopSelecting()
                    },
                    onForgetAll = { forget(all) },
                )
            }

            if (rows.isEmpty() && query.isNotBlank() && all.isNotEmpty()) {
                item { dev.niccc2007.filet.ui.NoMatch(query) }
            } else if (rows.isEmpty()) {
                item {
                    EmptyTab(
                        FiletIcons.Clock,
                        if (all.isEmpty()) "Nothing to carry on with" else "Nothing here with these filters",
                        if (all.isEmpty()) "Anything you stop part way through shows up here." else null,
                    )
                }
            }

            top?.let { p -> item(key = "top") { TopCard(vm, p, now, query) } }

            for ((label, points) in days) {
                item { SectionRow(label, note = "${points.size}") }
                items(points.size) { i ->
                    val point = points[i]
                    ResumeRow(
                        vm = vm,
                        point = point,
                        query = query,
                        selecting = selecting,
                        chosen = point.path in selected,
                        onToggle = { toggle(point.path) },
                        onSelect = {
                            selecting = true
                            toggle(point.path)
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }

        UndoBar(
            gone = undo,
            onUndo = {
                undo?.let { store.restore(it) }
                vm.bumpResume()
                undo = null
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** How long Undo stays after a forget. */
private const val UNDO_MS = 5_000L

/** `.go` / `.over` and the snackbar's rise: ease-out, as CSS spells it. */
private val EaseOut = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * The page's one header: the title and its count, Forget all at the top right, and under them
 * the two filters as drop-downs with Select at the end of the same row.
 *
 * Bug identified here: the filters were two rows of chips three wide, so half of each row was
 * empty, the title row held nothing but the title, and Forget everything sat after the last row.
 * While choosing several, the filter row becomes the selection's own row in place, so nothing
 * below it moves when choosing starts or stops.
 */
@Composable
private fun ResumeHeader(
    all: List<ResumeStore.Point>,
    rows: List<ResumeStore.Point>,
    filter: ResumeFilter,
    progress: ResumeProgress,
    onFilter: (ResumeFilter) -> Unit,
    onProgress: (ResumeProgress) -> Unit,
    selecting: Boolean,
    chosen: Int,
    onSelect: () -> Unit,
    onCancel: () -> Unit,
    onAll: () -> Unit,
    onForgetChosen: () -> Unit,
    onForgetAll: () -> Unit,
) {
    val colors = Filet.colors
    val part = all.count { !Finished.at(it.positionMs, it.durationMs) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.background)),
            )
            .padding(start = 15.dp, end = 12.dp, top = 14.dp, bottom = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Carry on",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.24).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (all.isEmpty()) "Nothing kept" else "$part part way · ${all.size - part} finished",
                    fontSize = 11.sp,
                    color = colors.fg3,
                )
            }
            Spacer(Modifier.width(10.dp))
            ForgetAllButton(enabled = all.isNotEmpty(), onClick = onForgetAll)
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (selecting) {
                QuietButton(FiletIcons.Close, null, describe = "Stop selecting", onClick = onCancel)
                Text(
                    "$chosen selected",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                QuietButton(null, if (rows.isNotEmpty() && chosen == rows.size) "None" else "All", onClick = onAll)
                QuietButton(FiletIcons.Delete, "Forget", danger = true, enabled = chosen > 0, onClick = onForgetChosen)
            } else {
                FilterDrop(
                    title = "Show",
                    options = ResumeFilter.entries,
                    current = filter,
                    default = ResumeFilter.ALL,
                    label = { it.label },
                    icon = ::kindIcon,
                    count = { k -> all.count { k.accepts(it) && progress.accepts(it) } },
                    onPick = onFilter,
                )
                FilterDrop(
                    title = "How far",
                    options = ResumeProgress.entries,
                    current = progress,
                    default = ResumeProgress.UNFINISHED,
                    label = { it.label },
                    icon = ::progressIcon,
                    count = { k -> all.count { filter.accepts(it) && k.accepts(it) } },
                    onPick = onProgress,
                )
                Spacer(Modifier.weight(1f))
                QuietButton(FiletIcons.SelectBox, "Select", enabled = rows.isNotEmpty(), onClick = onSelect)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
}

private fun kindIcon(f: ResumeFilter): androidx.compose.ui.graphics.vector.ImageVector = when (f) {
    ResumeFilter.ALL -> FiletIcons.Layers
    ResumeFilter.MUSIC -> FiletIcons.Audio
    ResumeFilter.VIDEO -> FiletIcons.Video
}

private fun progressIcon(p: ResumeProgress): androidx.compose.ui.graphics.vector.ImageVector = when (p) {
    ResumeProgress.UNFINISHED -> FiletIcons.ProgressPart
    ResumeProgress.FINISHED -> FiletIcons.ProgressDone
    ResumeProgress.BOTH -> FiletIcons.ProgressAny
}

/** `.fa`: Forget all, quiet until pressed, faded and inert with nothing kept. */
@Composable
private fun ForgetAllButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = Filet.colors
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier
            .height(30.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(colors.raised)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 9.dp, end = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FiletIcons.Delete, null, tint = colors.fg2, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("Forget all", fontSize = 11.5.sp, color = colors.fg2, maxLines = 1)
    }
}

/** `.qb`: a quiet verb in the filter row - Select, All, Forget, and the cross that stops choosing. */
@Composable
private fun QuietButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    text: String?,
    describe: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val ink = if (danger) colors.bad else colors.fg2
    Row(
        Modifier
            .height(32.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, if (text == null) describe else null, tint = ink, modifier = Modifier.size(15.dp))
            if (text != null) Spacer(Modifier.width(6.dp))
        }
        if (text != null) Text(text, fontSize = 11.5.sp, color = ink, maxLines = 1)
    }
}

/**
 * `.dd`: one filter as a drop-down. Lit when it is off its default, so a short list never looks
 * like a missing one. Its menu counts what each choice would show, with the other filter as it
 * is, and ticks the current one.
 */
@Composable
private fun <T> FilterDrop(
    title: String,
    options: List<T>,
    current: T,
    default: T,
    label: (T) -> String,
    icon: (T) -> androidx.compose.ui.graphics.vector.ImageVector,
    count: (T) -> Int,
    onPick: (T) -> Unit,
) {
    val colors = Filet.colors
    var open by remember { mutableStateOf(false) }
    val set = current != default
    val turn by androidx.compose.animation.core.animateFloatAsState(
        if (open) 180f else 0f,
        androidx.compose.animation.core.tween(160, easing = EaseOut),
        label = "caret",
    )
    val shape = RoundedCornerShape(9.dp)
    val ink = if (set) colors.accent else colors.fg2
    Box {
        Row(
            Modifier
                .height(32.dp)
                .clip(shape)
                .background(if (set) colors.accent.copy(alpha = 0.14f) else colors.sunken)
                .border(1.dp, if (set) colors.accent.copy(alpha = 0.35f) else colors.lineSoft, shape)
                .clickable { open = true }
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon(current), null, tint = ink, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                label(current),
                fontSize = 12.sp,
                maxLines = 1,
                color = if (set) colors.accent else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                FiletIcons.CaretDown,
                null,
                tint = if (set) colors.accent else colors.fg3,
                modifier = Modifier.size(12.dp).rotate(turn),
            )
        }
        PopMenu(expanded = open, onDismiss = { open = false }, below = 36.dp) {
            PopGroup(title)
            for (o in options) {
                ChoiceRow(icon(o), label(o), count(o), on = o == current) {
                    open = false
                    onPick(o)
                }
            }
        }
    }
}

/**
 * A drop-down's row: the choice, how many rows it gives, and a tick on the current one.
 * Not PopRow, which lights the whole label of an active row and has no place for the count.
 */
@Composable
private fun ChoiceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int,
    on: Boolean,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (on) colors.accent else colors.fg2, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text("$count", fontSize = 11.sp, color = colors.fg3)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
            if (on) Icon(FiletIcons.Check, "Shown now", tint = colors.accent, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * The last thing played, large: its picture with the time left on it and how far in along its
 * bottom edge, and both ways back in. A finished one says Play again and has no Start over,
 * since playing it again IS starting over.
 */
@Composable
private fun TopCard(vm: BrowserViewModel, point: ResumeStore.Point, now: Long, query: String) {
    val colors = Filet.colors
    val done = Finished.at(point.positionMs, point.durationMs)
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 2.dp)
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(colors.raised)
            .border(1.dp, colors.lineSoft, shape)
            .clickable { vm.resumePoint(point) }
            .padding(10.dp),
    ) {
        Box(Modifier.size(width = 132.dp, height = 82.dp).clip(RoundedCornerShape(9.dp))) {
            ResumeThumb(vm, point, width = 132.dp, height = 82.dp, corner = 9.dp, glyph = 24.dp, big = true)
            if (point.durationMs > 0L) {
                Text(
                    if (done) "Finished" else dev.niccc2007.filet.handlers.clockOf(point.durationMs - point.positionMs) + " left",
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 4.dp, bottom = 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.67f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.6f)),
                ) {
                    Box(Modifier.fillMaxWidth(resumeFraction(point)).fillMaxHeight().background(colors.accent))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(
                "LAST PLAYED · " + agoOf(now - point.at).uppercase(Locale.US),
                fontSize = 9.5.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.fg3,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                dev.niccc2007.filet.ui.highlighted(point.title, query, colors.accent),
                fontSize = 14.sp,
                lineHeight = 17.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(resumeWhere(point), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = colors.fg3, maxLines = 1)
            Spacer(Modifier.weight(1f).heightIn(min = 8.dp))
            Row {
                CardButton(FiletIcons.PlaySolid, if (done) "Play again" else "Carry on", filled = true) { vm.resumePoint(point) }
                if (!done) {
                    Spacer(Modifier.width(6.dp))
                    CardButton(FiletIcons.Prev, "Start over", filled = false) { vm.resumePoint(point, fromStart = true) }
                }
            }
        }
    }
}

/** `.go` and `.over`: the top card's two ways back in. */
@Composable
private fun CardButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val shape = RoundedCornerShape(9.dp)
    val ink = if (filled) MaterialTheme.colorScheme.onPrimary else colors.fg2
    Row(
        Modifier
            .height(30.dp)
            .clip(shape)
            .then(
                if (filled) Modifier.background(colors.accent)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape),
            )
            .clickable(onClick = onClick)
            .padding(start = if (filled) 9.dp else 8.dp, end = if (filled) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 12.sp, fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal, color = ink, maxLines = 1)
    }
}

/**
 * One row: its picture, its title and where it got to, and at the right edge how far in and how
 * long is left. Bug identified here: the percentage sat straight after a column three-quarters
 * wide, so on a short title it floated in the middle of the row instead of lining up with the
 * others.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResumeRow(
    vm: BrowserViewModel,
    point: ResumeStore.Point,
    query: String,
    selecting: Boolean,
    chosen: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    val colors = Filet.colors
    var menu by remember(point.path) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (chosen) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(
                onClick = { if (selecting) onToggle() else vm.resumePoint(point) },
                onLongClick = { if (selecting) onToggle() else menu = true },
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Anchored at the row's start, so the card opens under the picture that was held; it
        // turns upwards on its own for a row near the bottom of the screen.
        Box {
            PopMenu(expanded = menu, onDismiss = { menu = false }, below = 8.dp) {
                PopRow(FiletIcons.Play, "Carry on") { menu = false; vm.resumePoint(point) }
                PopRow(FiletIcons.Prev, "Start from the beginning") {
                    menu = false
                    vm.resumePoint(point, fromStart = true)
                }
                PopRow(FiletIcons.FolderOpen, Labels.OPEN_LOCATION) {
                    menu = false
                    runCatching { vm.revealPathInFolder(dev.niccc2007.filet.vfs.VPath.parse(point.path)) }
                }
                PopSeparator()
                PopRow(FiletIcons.Check, "Select") { menu = false; onSelect() }
                PopRow(FiletIcons.Close, "Forget this one") {
                    menu = false
                    vm.playback.resume.forget(point.path)
                    vm.bumpResume()
                }
            }
        }
        ResumeThumb(vm, point, width = 64.dp, height = 40.dp, corner = 7.dp, glyph = 17.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            dev.niccc2007.filet.browser.ListNameText(
                text = dev.niccc2007.filet.ui.highlighted(point.title, query, colors.accent),
                color = MaterialTheme.colorScheme.onSurface,
                startSp = 13f,
                lines = 1,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            dev.niccc2007.filet.browser.ListNameText(
                name = resumeLine(point),
                color = colors.fg3,
                startSp = 10.5f,
                isDir = true,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.width(12.dp))
        val tag = finishedTag(point)
        when {
            selecting -> CheckCircle(chosen)
            tag != null -> FinishedTag(tag)
            else -> Column(Modifier.widthIn(min = 58.dp), horizontalAlignment = Alignment.End) {
                Text(resumePercent(point), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.fg2, maxLines = 1)
                resumeLeft(point)?.let { Text(it, fontSize = 9.5.sp, color = colors.fg3, maxLines = 1) }
            }
        }
    }
    // A hairline of how far in it is, under the row. The number says it exactly and this says it
    // at a glance, which is the thing a list is scanned for.
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(colors.sunken),
        )
        Box(
            Modifier
                .fillMaxWidth(resumeFraction(point))
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(colors.accent.copy(alpha = 0.7f)),
        )
    }
    Spacer(Modifier.height(4.dp))
}

/** `.ck`: a row's tick while choosing several, round so it is not mistaken for a setting. */
@Composable
private fun CheckCircle(on: Boolean) {
    val colors = Filet.colors
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .then(
                if (on) Modifier.background(colors.accent)
                else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(FiletIcons.Check, "Chosen", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
    }
}

/** `.snack`: what was forgotten, and Undo, for [UNDO_MS]. */
@Composable
private fun UndoBar(gone: List<ResumeStore.Point>?, onUndo: () -> Unit, modifier: Modifier) {
    val colors = Filet.colors
    // Kept while the bar leaves, so the words do not go blank during the fade.
    var last by remember { mutableStateOf(gone) }
    if (gone != null) last = gone
    val rise = with(LocalDensity.current) { 4.dp.roundToPx() }
    androidx.compose.animation.AnimatedVisibility(
        visible = gone != null,
        modifier = modifier,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160, easing = EaseOut)) +
            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(160, easing = EaseOut)) { rise },
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160, easing = EaseOut)),
    ) {
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                .fillMaxWidth()
                .shadow(8.dp, shape)
                .clip(shape)
                .background(colors.high)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Forgot ${last?.size ?: 0}",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Undo",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onUndo)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * "99% FINISHED" for a row played to its end, or null for one still part way. Pure, so the
 * wording and where it starts are a test. Where "finished" starts is [Finished]'s, so the tag
 * and the player that opens the row at 0:00 cannot disagree.
 */
fun finishedTag(point: ResumeStore.Point): String? =
    if (Finished.at(point.positionMs, point.durationMs)) {
        "${Finished.percent(point.positionMs, point.durationMs)}% FINISHED"
    } else {
        null
    }

/**
 * A finished row's tag, in place of its percentage: the settings page's BETA tag in shape, in
 * the colour that means done. Not a percentage pill with a word after it - two numbers saying
 * the same thing side by side reads as two facts.
 */
@Composable
private fun FinishedTag(text: String) {
    val colors = Filet.colors
    Text(
        text,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        maxLines = 1,
        color = colors.good,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.good.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** "23:41 of 48:02", or "23:41" when the length was never known. Pure. */
fun resumeWhere(point: ResumeStore.Point): String {
    val where = dev.niccc2007.filet.handlers.clockOf(point.positionMs)
    return if (point.durationMs > 0L) {
        where + " of " + dev.niccc2007.filet.handlers.clockOf(point.durationMs)
    } else {
        where
    }
}

/**
 * A row's picture, or the top card's: a frame from where the video was left, or an audio
 * file's own artwork.
 *
 * Generated, not looked up. A video with no cached thumbnail is the common case for a file
 * copied over by hand, and a typed glyph in its place was the gap in this list. The frame is
 * taken at the SAVED position rather than at zero - a better picture than a title card, and a
 * reminder of where they were.
 *
 * The typed icon is only the fallback for a file that yields nothing: audio with no embedded
 * art, a file on a share or inside an archive (see [ResumeThumbs.load]), or a container the
 * platform cannot decode.
 *
 * @param big the top card's size, decoded at [ResumeThumbs.BIG_W] x [ResumeThumbs.BIG_H] so it
 *   is not a row's picture stretched to twice its width.
 */
@Composable
private fun ResumeThumb(
    vm: BrowserViewModel,
    point: ResumeStore.Point,
    width: Dp,
    height: Dp,
    corner: Dp,
    glyph: Dp,
    big: Boolean = false,
) {
    val colors = Filet.colors
    val context = LocalContext.current
    val key = ResumeThumbs.key(point, big)
    var image by remember(key) { mutableStateOf(ResumeThumbs.cached(key, big)) }
    var tried by remember(key) { mutableStateOf(image != null || ResumeThumbs.failed(key)) }
    LaunchedEffect(key) {
        if (tried) return@LaunchedEffect
        image = ResumeThumbs.load(context.applicationContext, vm, point, big)
        tried = true
    }
    Box(
        Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(corner))
            .background(colors.sunken),
        contentAlignment = Alignment.Center,
    ) {
        val shown = image
        if (shown != null) {
            androidx.compose.foundation.Image(
                shown,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (tried) {
            Icon(
                if (point.video) FiletIcons.Video else FiletIcons.Audio,
                null,
                tint = colors.fg2,
                modifier = Modifier.size(glyph),
            )
        }
    }
}

/**
 * Generated thumbnails for the Carry on list, held in memory.
 *
 * - **Bound:** [MAX] pictures of at most [W] x [H] pixels in RGB_565 - about 32 KB each, under
 *   1.6 MB for the whole cache. Least recently used is evicted first ([Lru]). The top card's
 *   larger picture has a cache of its own, [BIG_MAX] of at most [BIG_W] x [BIG_H] - about
 *   200 KB each - so it never pushes the rows' pictures out.
 * - **Keyed** on the path and the saved position in half-minute steps, so a film left further
 *   on gets a new frame and one reopened in the same spot does not.
 * - **One at a time.** Extraction runs on a single IO lane. Twenty rows opening twenty decoders
 *   at once is exactly the burst that warms a phone; one lane fills the rows in order.
 * - **Failures are remembered** per key, so a file that will not decode is not retried on every
 *   scroll.
 */
internal object ResumeThumbs {
    const val MAX = 48
    const val W = 160
    const val H = 100

    /** The top card: one shown at a time, a few kept so a filter flicked back is instant. */
    const val BIG_MAX = 4
    const val BIG_W = 400
    const val BIG_H = 250

    private val cache = Lru<String, ImageBitmap>(MAX)
    private val bigCache = Lru<String, ImageBitmap>(BIG_MAX)
    private val failures = Lru<String, Boolean>(MAX * 2)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val lane = Dispatchers.IO.limitedParallelism(1)

    fun key(point: ResumeStore.Point, big: Boolean = false): String =
        point.path + "@" + point.positionMs / 30_000 + if (big) "#big" else ""

    fun cached(key: String, big: Boolean = false): ImageBitmap? = (if (big) bigCache else cache)[key]

    fun failed(key: String): Boolean = failures[key] == true

    suspend fun load(
        context: android.content.Context,
        vm: BrowserViewModel,
        point: ResumeStore.Point,
        big: Boolean = false,
    ): ImageBitmap? = withContext(lane) {
        val key = key(point, big)
        val into = if (big) bigCache else cache
        into[key] ?: run {
            val (w, h) = if (big) Pair(BIG_W, BIG_H) else Pair(W, H)
            val made = runCatching { extract(context, vm, point, w, h) }.getOrNull()
            if (made == null) failures.put(key, true) else into.put(key, made)
            made
        }
    }

    private suspend fun extract(
        context: android.content.Context,
        vm: BrowserViewModel,
        point: ResumeStore.Point,
        w: Int,
        h: Int,
    ): ImageBitmap? {
        val path = dev.niccc2007.filet.vfs.VPath.parse(point.path)
        // Only a file already on this device. A share or an archive entry would have to be
        // copied out first, and copying a two-gigabyte film to draw a 56dp picture is not a
        // trade anybody would make knowingly.
        if (vm.vfs.isRemote(path) || vm.vfs.osPath(path) == null) return null
        val node = vm.vfs.stat(path) ?: return null
        val uri = vm.localUriFor(node) ?: return null
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val raw: android.graphics.Bitmap = (if (point.video) frameOf(r, point.positionMs, w, h) else artOf(r, w, h)) ?: return null
            val (fw, fh) = fitInside(raw.width, raw.height, w, h)
            val sized = if (raw.width == fw && raw.height == fh) {
                raw
            } else {
                android.graphics.Bitmap.createScaledBitmap(raw, fw, fh, true).also { if (it !== raw) raw.recycle() }
            }
            val small = if (sized.config == android.graphics.Bitmap.Config.RGB_565) {
                sized
            } else {
                sized.copy(android.graphics.Bitmap.Config.RGB_565, false)?.also { sized.recycle() } ?: sized
            }
            return small.asImageBitmap()
        } finally {
            runCatching { r.release() }
        }
    }

    /** A frame at the saved position, scaled by the decoder where it can be; at zero if that fails. */
    private fun frameOf(r: android.media.MediaMetadataRetriever, positionMs: Long, w: Int, h: Int): android.graphics.Bitmap? {
        val opt = android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val us = positionMs * 1000
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            r.getScaledFrameAtTime(us, opt, w, h) ?: r.getScaledFrameAtTime(0, opt, w, h)
        } else {
            r.getFrameAtTime(us, opt) ?: r.getFrameAtTime(0, opt)
        }
    }

    /** Embedded artwork, decoded at a fraction of its size rather than whole and then shrunk. */
    private fun artOf(r: android.media.MediaMetadataRetriever, w: Int, h: Int): android.graphics.Bitmap? {
        val bytes = r.embeddedPicture ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, w, h)
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}

/** The largest power-of-two step that still leaves a picture at least [w] x [h]. Pure. */
fun sampleFor(srcW: Int, srcH: Int, w: Int, h: Int): Int {
    var n = 1
    while (srcW / (n * 2) >= w && srcH / (n * 2) >= h) n *= 2
    return n
}

/** [srcW] x [srcH] scaled down to fit inside [w] x [h], aspect kept, never scaled up. Pure. */
fun fitInside(srcW: Int, srcH: Int, w: Int, h: Int): Pair<Int, Int> {
    if (srcW <= 0 || srcH <= 0) return Pair(w, h)
    val k = minOf(1f, w.toFloat() / srcW, h.toFloat() / srcH)
    // Rounded, then clamped: truncating turned 1920 * (100 / 1920f) = 99.99999 into 99.
    return Pair(
        kotlin.math.round(srcW * k).toInt().coerceIn(1, w),
        kotlin.math.round(srcH * k).toInt().coerceIn(1, h),
    )
}

/** A least-recently-used map of fixed size. Thread-safe. Pure. */
class Lru<K, V>(val max: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun keys(): List<K> = map.keys.toList()

    val size: Int
        @Synchronized get() = map.size
}

/**
 * How far through: the rows still part way, the ones played to the end, or both. Where "the end"
 * starts is [Finished]'s, the same line the tag and the players use.
 */
enum class ResumeProgress(val label: String) {
    UNFINISHED("Unfinished"),
    FINISHED("Finished"),
    BOTH("Both");

    fun accepts(point: ResumeStore.Point): Boolean = when (this) {
        UNFINISHED -> !Finished.at(point.positionMs, point.durationMs)
        FINISHED -> Finished.at(point.positionMs, point.durationMs)
        BOTH -> true
    }
}

/** The two halves of the media this app plays, plus everything. */
enum class ResumeFilter(val label: String) {
    ALL("Everything"),
    MUSIC("Music"),
    VIDEO("Video");

    fun accepts(point: ResumeStore.Point): Boolean = when (this) {
        ALL -> true
        MUSIC -> !point.video
        VIDEO -> point.video
    }
}

/** "12:40 of 1:38:02  ·  yesterday". Pure, so the wording can be tested. */
fun resumeLine(point: ResumeStore.Point, now: Long = System.currentTimeMillis()): String {
    val where = dev.niccc2007.filet.handlers.clockOf(point.positionMs)
    val total = if (point.durationMs > 0L) {
        " of " + dev.niccc2007.filet.handlers.clockOf(point.durationMs)
    } else {
        ""
    }
    return where + total + "  ·  " + agoOf(now - point.at)
}

/** How far through, as a percentage, or a dash when the length was never known. */
fun resumePercent(point: ResumeStore.Point): String =
    if (point.durationMs <= 0L) "—" else "${(resumeFraction(point) * 100).toInt()}%"

fun resumeFraction(point: ResumeStore.Point): Float =
    if (point.durationMs <= 0L) {
        0f
    } else {
        (point.positionMs.toFloat() / point.durationMs).coerceIn(0f, 1f)
    }

/**
 * How long is left, in words, for the line under a row's percentage: "under a minute",
 * "12 min left", "1 h 5 min left". Rounded to the nearest minute. Null when the length was never
 * known, rather than a guess. Pure.
 */
fun resumeLeft(point: ResumeStore.Point): String? {
    if (point.durationMs <= 0L) return null
    val minutes = ((point.durationMs - point.positionMs).coerceAtLeast(0L) + 30_000L) / 60_000L
    return when {
        minutes < 1L -> "under a minute"
        minutes < 60L -> "$minutes min left"
        else -> "${minutes / 60} h ${minutes % 60} min left"
    }
}

/**
 * The rows under their day headings, newest first: Today, Yesterday, each day for a fortnight,
 * then the rest of this year, then a heading per year. The headings are [DayBuckets]', so this
 * list dates things the way the rest of the app does. Pure.
 */
fun resumeDays(
    points: List<ResumeStore.Point>,
    now: Long,
    zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
): List<Pair<String, List<ResumeStore.Point>>> {
    val keyed = LinkedHashMap<String, MutableList<ResumeStore.Point>>()
    for (p in points) keyed.getOrPut(DayBuckets.keyOf(p.at, now, zone)) { ArrayList() } += p
    return keyed.map { (key, rows) -> DayBuckets.label(key, rows.first().at, zone) to rows }
}

/**
 * How long ago, in words.
 *
 * Coarse on purpose. "Three days ago" is what somebody wants from this list; the minute it
 * happened is a fact about a log, and printing it invites reading the list as one.
 */
fun agoOf(elapsedMs: Long): String {
    val minutes = elapsedMs / 60_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "${minutes}m ago"
        minutes < 60L * 24 -> "${minutes / 60}h ago"
        minutes < 60L * 48 -> "yesterday"
        minutes < 60L * 24 * 30 -> "${minutes / (60 * 24)}d ago"
        else -> "a while ago"
    }
}

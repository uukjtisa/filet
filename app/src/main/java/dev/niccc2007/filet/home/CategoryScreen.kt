package dev.niccc2007.filet.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.tabs.EmptyTab
import dev.niccc2007.filet.ui.tabs.SortPair
import dev.niccc2007.filet.ui.tabs.TabHeader
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath

/**
 * One category's files, newest first, grouped by day. Issue #4.
 *
 * ## Why it reads like the New files tab
 *
 * Because it is the same question asked of a different set. New files asks "what turned up in
 * the folders I watch"; this asks "what is on this phone, of this kind". Both are read the same
 * way - newest first, in days, fold a day away once you have seen it - so they share their
 * shape on purpose. A second layout for the same reading habit is a second thing to maintain
 * and a second thing to learn.
 *
 * The grouping and the day labels are [FileHistory]'s, which is already tested: "Today" names a
 * different day tomorrow, so a fold is keyed on the day and not on its label.
 */
@Composable
fun CategoryScreen(vm: BrowserViewModel, pane: dev.niccc2007.filet.browser.PaneController, category: MediaCategory) {
    val colors = Filet.colors
    val context = LocalContext.current
    var collapsed by dev.niccc2007.filet.browser.rememberKeptSet("category.collapsed:" + category.id)
    var menuFor by remember(category) { mutableStateOf<VNode?>(null) }
    var peekFor by remember(category) { mutableStateOf<VNode?>(null) }

    // The refresh button's half of it: the media scanner adds rows while this pane is open,
    // so a photo taken and then come back to must be able to appear. See `RefreshTarget.CATEGORY`.
    val revision by vm.categoryRevision.collectAsStateWithLifecycle()
    // And Filet's own writes, once the index has them. The last read stays drawn while the next
    // one runs, so this is a quiet swap, never a spinner.
    val indexed by vm.mediaIndexed.collectAsStateWithLifecycle()

    // produceState: the read happens off the main thread, and the screen shows a spinner rather
    // than an empty list while the cursor is walked. Null means "not read yet", which is a
    // different thing from "read and found nothing" - and conflating those is how an empty
    // category comes to look like a failure.
    // Four outcomes, not two. Bug identified here: a refused permission and a provider error
    // both arrived as an empty list, so a phone full of videos said "nothing of this kind" -
    // which reads as the category being broken, and in fact was.
    // ── why this is two reads and not one ─────────────────────────────────────────────────
    //
    // Opening a category was slow the first time, every time, and the cause was not the query.
    // It was the SIZE of it: `readResult` defaults to `CategoryQuery.LIMIT`, which is two
    // thousand rows, and nothing appeared until all two thousand had been walked out of the
    // cursor, turned into nodes, mapped into history entries and grouped into day buckets.
    // The first screen holds about a dozen of them.
    //
    // So: a small page first, which is what somebody actually sees, and the rest behind it.
    // The second read only happens when the first came back full - a category with thirty
    // files does one query and stops.
    //
    // The grouping moved in here with it. It used to sit in a `remember` in the composition,
    // so two thousand allocations and a sort ran on the main thread, during a frame, every
    // time the row list changed identity. It is pure and it has no business being there.
    val view by produceState<CategoryView?>(null, category, revision, indexed) {
        val quick = withContext(Dispatchers.IO) {
            CategoryQuery.readResult(context, category, FIRST_PAGE)
        }
        value = withContext(Dispatchers.Default) { CategoryView(quick, groupsOf(quick)) }
        val got = (quick as? CategoryResult.Found)?.nodes?.size ?: 0
        if (got >= FIRST_PAGE) {
            val full = withContext(Dispatchers.IO) { CategoryQuery.readResult(context, category) }
            value = withContext(Dispatchers.Default) { CategoryView(full, groupsOf(full)) }
        }
    }
    val result = view?.result
    // Bug identified here: a delete left its row on screen, because this list re-reads only when
    // asked and the media index drops a row only once the scanner gets to it. Rows Filet has
    // just removed are left out here, and a day emptied by it goes with them.
    val gone by vm.justRemoved.collectAsStateWithLifecycle()
    val read = view?.groups.orEmpty()
    val everything = remember(read, gone) {
        if (gone.isEmpty()) read
        else read.mapNotNull { g ->
            g.copy(entries = dev.niccc2007.filet.media.JustRemoved.without(g.entries, gone) { dev.niccc2007.filet.media.JustRemoved.plain(it.path) })
                .takeIf { it.entries.isNotEmpty() }
        }
    }
    // Narrowed by the pane's search box, name or folder, keeping the days that still have rows.
    val query = dev.niccc2007.filet.ui.LocalPaneQuery.current
    val groups = remember(everything, query) {
        if (query.isBlank()) everything
        else everything.mapNotNull { g ->
            g.copy(entries = g.entries.filter { dev.niccc2007.filet.ui.TextMatch.matches(query, it.name, it.origin) })
                .takeIf { it.entries.isNotEmpty() }
        }
    }
    val narrowedAway = groups.isEmpty() && everything.isNotEmpty()
    // The rows as drawn, folded days left out: what the pane's selection ranges over and counts.
    val drawnEntries = remember(groups, collapsed) { groups.filter { it.key !in collapsed }.flatMap { it.entries } }
    val pick = rememberListPick(vm, pane, drawnEntries)

    if (view == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
        return
    }

    val stored by vm.prefs.categoryLayouts.collectAsStateWithLifecycle()
    val layout = CategoryLayout.of(stored, category)
    val header: @Composable () -> Unit = {
        TabHeader(category.label) {
            // The same exclusive-pair control New files uses for its two orders, because it is
            // the same kind of choice: one of two ways to see one list, kept until changed.
            if (CategoryLayout.offeredFor(category)) {
                SortPair(CategoryLayout.entries.map { it.label }, layout.label) { picked ->
                    val next = CategoryLayout.entries.first { it.label == picked }
                    if (next != layout) vm.prefs.setCategoryLayout(category.id, next)
                }
            }
        }
    }
    val fold: (DayGroup) -> Unit = { g ->
        collapsed = if (g.key in collapsed) collapsed - g.key else collapsed + g.key
    }
    val todayKey = remember(view) { FileHistory.dayKey(System.currentTimeMillis()) }

    // Exhaustive on purpose: a third layout does not compile until it says how it is drawn.
    when (layout) {
        CategoryLayout.LIST -> LazyColumn(Modifier.fillMaxSize(), state = dev.niccc2007.filet.browser.rememberKeptListState("category:" + category.id)) {
            item { header() }
            if (narrowedAway) item { dev.niccc2007.filet.ui.NoMatch(query) }
            else if (groups.isEmpty()) item { CategoryEmpty(category, result) }

            for (g in groups) {
                // The New files tab's own day header and row, not copies of them. The two lists
                // are the same reading habit over different sets, and two implementations of one
                // row is two places for it to drift.
                item(key = "h:" + g.key) { DayHeader(g, g.key in collapsed) { fold(g) } }
                if (g.key in collapsed) continue
                items(g.entries.size, key = { i -> "e:" + g.key + ":" + g.entries[i].path }) { i ->
                    HistoryRow(
                        entry = g.entries[i],
                        sort = HistorySort.DATE_MODIFIED,
                        vm = vm,
                        today = g.key == todayKey,
                        // The category as drawn, so a swipe in the viewer reaches the next row.
                        from = { drawnEntries.asNodes() },
                        pick = pick,
                        onLongPress = { menuFor = it },
                    )
                }
            }

            item(key = "end") { Spacer(Modifier.height(28.dp + dev.niccc2007.filet.browser.LocalPickInset.current)) }
        }

        CategoryLayout.GALLERY -> CategoryGallery(
            vm = vm,
            category = category,
            groups = groups,
            collapsed = collapsed,
            header = header,
            empty = { if (narrowedAway) dev.niccc2007.filet.ui.NoMatch(query) else CategoryEmpty(category, result) },
            onFold = fold,
            pick = pick,
            // A held tile lifts into its peek; the row sheet is one tap on from it (More).
            onPeek = { peekFor = it },
            onUnpeek = { peekFor = null },
        )
    }

    // The gallery's peek. Select first, as in the folder grid: it is how a selection starts
    // from a gallery without the slide. A tile already selected offers Deselect in its place.
    // More is the sheet a list row's hold opens, keeping Open location, Bookmark and Shortcut
    // one tap away.
    peekFor?.let { node ->
        val chosen = node.path in pane.selectionNow()
        dev.niccc2007.filet.handlers.TilePeek(
            vm = vm,
            node = node,
            actions = listOf(
                if (chosen) {
                    dev.niccc2007.filet.handlers.PeekAction(FiletIcons.Close, "Deselect") { pick.toggle(node) }
                } else {
                    dev.niccc2007.filet.handlers.PeekAction(FiletIcons.Check, "Select") {
                        if (pane.state.value.selecting) pick.toggle(node) else pick.selectOnly(node)
                    }
                },
                dev.niccc2007.filet.handlers.PeekAction(FiletIcons.Open, "Open") {
                    vm.openHomeEntry(node, groups.filter { it.key !in collapsed }.flatMap { it.entries }.asNodes())
                },
                dev.niccc2007.filet.handlers.PeekAction(FiletIcons.Share, "Share") { vm.share(node) },
                dev.niccc2007.filet.handlers.PeekAction(FiletIcons.Delete, "Delete", danger = true) {
                    vm.confirmDeleteOf(listOf(node))
                },
                dev.niccc2007.filet.handlers.PeekAction(FiletIcons.More, "More") { menuFor = node },
            ),
            onDismiss = { peekFor = null },
        )
    }

    // The same sheet the Home feed and the New files tab use, so a long press means one thing
    // everywhere. Reveal is the row that matters here: a category is a list of files with their
    // folders deliberately out of the way, so "where is this actually" is the question it
    // creates and has to answer.
    menuFor?.let { node ->
        HomeRowSheet(
            node = node,
            onDismiss = { menuFor = null },
            onReveal = { menuFor = null; vm.revealInFolder(node) },
            onOpen = { menuFor = null; vm.openHomeEntry(node) },
            onShare = { menuFor = null; vm.share(node) },
            onBookmark = { menuFor = null; vm.bookmarkOne(node) },
            onShortcut = { menuFor = null; vm.shortcutOne(node) },
            onForget = null,
        )
    }
}

/**
 * What an empty category says. Three different answers, drawn as three - a refused permission
 * and a provider error both used to arrive as an empty list, so a phone full of videos said
 * "nothing of this kind", which reads as the category being broken.
 */
@Composable
private fun CategoryEmpty(category: MediaCategory, result: CategoryResult?) {
    val colors = Filet.colors
    Column(Modifier.fillMaxWidth()) {
        when (result) {
            is CategoryResult.Refused -> EmptyTab(category.icon, "Filet needs storage access to list these")
            is CategoryResult.Failed -> {
                EmptyTab(category.icon, "The media index could not be read")
                Text(
                    result.reason,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    color = colors.fg3,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            else -> {
                EmptyTab(category.icon, "Nothing of this kind on the phone's own storage")
                // Said plainly, because an empty category invites the wrong conclusion. This
                // comes from the system's media index, which covers shared storage - so empty
                // here means empty there, rather than Filet having failed to look.
                Text(
                    "Reads the phone's media index. Not shares, archives or other apps.",
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = colors.fg3,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * A category's rows and their day buckets, produced together off the main thread.
 *
 * One value rather than two states, so a half-updated screen cannot exist: the grouping always
 * belongs to the result beside it. Holding them separately is how a list and its headers come
 * to disagree for a frame.
 */
private class CategoryView(val result: CategoryResult, val groups: List<DayGroup>)

/**
 * How many rows the first read asks for.
 *
 * About ten screens' worth. Big enough that scrolling does not reach the end before the full
 * read lands, small enough that the query, the mapping and the grouping are all over before
 * anybody notices there was a wait.
 */
private const val FIRST_PAGE = 120

private fun groupsOf(result: CategoryResult): List<DayGroup> =
    FileHistory.group(
        (result as? CategoryResult.Found)?.nodes.orEmpty().map {
            HistoryEntry(
                path = it.path.toString(),
                name = it.name,
                bytes = it.size,
                // A category listing has only the media index's own dates, and both columns
                // here are the same one: these rows are grouped by when the file changed, not
                // by when it arrived. Filet's memory does not reach this screen - see
                // `HomeFeed.firstSeenLookup`, which is where the corrected arrival date lives.
                added = it.mtime,
                modified = it.mtime,
            )
        },
        HistorySort.DATE_MODIFIED,
        System.currentTimeMillis(),
    )

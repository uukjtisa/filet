package dev.niccc2007.filet.home

import androidx.compose.foundation.layout.Box
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
fun CategoryScreen(vm: BrowserViewModel, category: MediaCategory) {
    val colors = Filet.colors
    val context = LocalContext.current
    var collapsed by remember(category) { mutableStateOf(setOf<String>()) }
    var menuFor by remember(category) { mutableStateOf<VNode?>(null) }

    // The refresh button's half of it: the media scanner adds rows while this pane is open,
    // so a photo taken and then come back to must be able to appear. See `RefreshTarget.CATEGORY`.
    val revision by vm.categoryRevision.collectAsStateWithLifecycle()

    // produceState: the read happens off the main thread, and the screen shows a spinner rather
    // than an empty list while the cursor is walked. Null means "not read yet", which is a
    // different thing from "read and found nothing" - and conflating those is how an empty
    // category comes to look like a failure.
    val rows by produceState<List<VNode>?>(null, category, revision) {
        value = CategoryQuery.read(context, category)
    }

    val groups = remember(rows) {
        FileHistory.group(
            rows.orEmpty().map {
                HistoryEntry(
                    path = it.path.toString(),
                    name = it.name,
                    bytes = it.size,
                    firstSeen = it.mtime,
                    lastChanged = it.mtime,
                )
            },
            HistorySort.LAST_CHANGED,
            System.currentTimeMillis(),
        )
    }

    if (rows == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { TabHeader(category.label) }

        if (groups.isEmpty()) {
            item { EmptyTab(category.icon, "Nothing of this kind on the phone's own storage") }
            item {
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

        for (g in groups) {
            // The New files tab's own day header and row, not copies of them. The two lists
            // are the same reading habit over different sets, and two implementations of one
            // row is two places for it to drift.
            item(key = "h:" + g.key) {
                DayHeader(g, g.key in collapsed) {
                    collapsed = if (g.key in collapsed) collapsed - g.key else collapsed + g.key
                }
            }
            if (g.key in collapsed) continue
            items(g.entries.size, key = { i -> "e:" + g.key + ":" + g.entries[i].path }) { i ->
                HistoryRow(
                    entry = g.entries[i],
                    sort = HistorySort.LAST_CHANGED,
                    vm = vm,
                    today = g.key == FileHistory.dayKey(System.currentTimeMillis()),
                    onLongPress = { menuFor = it },
                )
            }
        }

        item(key = "end") { Spacer(Modifier.height(28.dp)) }
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

package dev.niccc2007.filet.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.LongPressAction
import dev.niccc2007.filet.browser.PaneController
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * A list with no folder behind it taking part in the pane's selection: a category, as rows or as
 * a gallery, and New files.
 *
 * Bug identified here: these lists answered a tap by opening and a hold with a sheet, and had no
 * selection at all, so six photos were six trips through the same sheet and nothing could be
 * copied, moved or deleted together. The pane already holds a selection, its bar and every action
 * on it; what these screens lacked was a way in. This is that way, with the folder list's rules:
 *
 * - a tap while selecting toggles the row, and opens it otherwise;
 * - a hold on an unselected row selects it, or with a selection running extends to it;
 * - a hold on a row already selected asks about that row - its sheet, its peek.
 *
 * The gallery's hold differs only when nothing is selected, where it lifts the tile into its peek
 * (with Select first) rather than selecting, and a slide after the hold selects the run. See
 * `CategoryGallery`.
 */
@Stable
internal class ListPick(
    private val vm: BrowserViewModel,
    val pane: PaneController,
    /** The real selection, read only inside a row - see `SelectionReads`. */
    val selection: State<Set<VPath>>,
) {
    /** A tap on [node]: about the selection while one runs, [open] otherwise. */
    fun tap(node: VNode, open: () -> Unit) {
        if (pane.state.value.selecting) {
            vm.focusPane(pane)
            pane.toggleSelect(node)
        } else {
            open()
        }
    }

    /** A hold on [node]. [ask] is the row's own sheet, for a row that is already selected. */
    fun hold(node: VNode, ask: (VNode) -> Unit) {
        vm.focusPane(pane)
        when (pane.holdAction(node.path)) {
            LongPressAction.MENU -> pane.selectOnly(node)
            LongPressAction.EXTEND -> pane.extendSelectionTo(node)
            LongPressAction.MENU_FOR_SELECTION -> ask(node)
        }
    }

    /** Start a selection on [node] alone - the peek's Select. */
    fun selectOnly(node: VNode) {
        vm.focusPane(pane)
        pane.selectOnly(node)
    }

    fun toggle(node: VNode) {
        vm.focusPane(pane)
        pane.toggleSelect(node)
    }
}

/**
 * The pane's selection for a list whose drawn rows are [entries], in the order they are drawn.
 *
 * The rows are handed to the pane so a range, Select all and the bar's total work over what is on
 * this screen - a pane with no folder has no listing of its own to count. Turned into files off
 * the main thread: a category is two thousand rows, and the gallery builds each tile's file only
 * when the tile is first drawn for exactly that reason.
 */
@Composable
internal fun rememberListPick(vm: BrowserViewModel, pane: PaneController, entries: List<HistoryEntry>): ListPick {
    val selection = remember(pane) {
        pane.state.map { it.selected }.distinctUntilChanged()
    }.collectAsState(pane.state.value.selected)
    LaunchedEffect(pane, entries) {
        val rows = withContext(Dispatchers.Default) { entries.asNodes() }
        pane.showRows(rows)
    }
    // Gone with the screen: another screen on this pane draws other rows, or none.
    DisposableEffect(pane) { onDispose { pane.showRows(emptyList()) } }
    return remember(vm, pane, selection) { ListPick(vm, pane, selection) }
}

/** Whether [path] is selected, as a read scoped to the one row asking. */
@Composable
internal fun ListPick.has(path: VPath): Boolean {
    val on by remember(path, selection) { derivedStateOf { path in selection.value } }
    return on
}

/** Whether a selection is running, read so only a change in the answer recomposes. */
@Composable
internal fun ListPick.running(): Boolean {
    val on by remember(selection) { derivedStateOf { selection.value.isNotEmpty() } }
    return on
}

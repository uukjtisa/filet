package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.about.AboutPage
import dev.niccc2007.filet.data.ViewStep
import dev.niccc2007.filet.home.HomeOverview
import dev.niccc2007.filet.index.IndexStatus
import dev.niccc2007.filet.index.SearchScope
import dev.niccc2007.filet.settings.SettingsPage
import dev.niccc2007.filet.ui.HScroll
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode

/**
 * One pane: header with its own search, scope chips, and the listing.
 *
 * Laid out against its OWN width rather than the screen's, so a 180dp pane in a split sheds
 * the same columns it would shed on a 180dp phone. That is why the width comes from
 * [BoxWithConstraints] and not from a global window-size class.
 */
@Composable
fun PaneView(
    pane: PaneController,
    vm: BrowserViewModel,
    side: Side,
    focused: Boolean,
    registry: DropRegistry,
    onGhost: (Offset?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by pane.state.collectAsState()
    val app by vm.state.collectAsState()
    val viewStep by vm.prefs.viewStep.collectAsState()
    val colors = Filet.colors
    val paneKey = "pane-${pane.id}"

    DisposableEffect(paneKey) { onDispose { registry.remove(paneKey) } }

    // Armed when the finger is over this pane's own background - not over a folder row inside
    // it, which highlights itself and would otherwise light up twice.
    val receiving = (app.drag?.over as? DropTarget.Pane)?.paneId == pane.id
    val refused = receiving && app.drag?.drop?.refusal != null

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthDp = maxWidth.value.toInt()
        val metrics = PaneMetrics.forWidth(widthDp, ViewStep.of(viewStep))

        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .then(
                    if (!receiving) Modifier
                    else Modifier.border(2.dp, if (refused) colors.bad else colors.accent)
                )
                .onGloballyPositioned { c ->
                    registry.put(
                        key = paneKey,
                        rect = Rect(c.positionInRoot(), Size(c.size.width.toFloat(), c.size.height.toFloat())),
                        target = DropTarget.Pane(pane.id),
                        depth = 0,
                    )
                }
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { vm.focusSide(side) },
        ) {
            PaneHeader(
                pane, s, focused, widthDp,
                sideLabel = if (app.split == SplitMode.OFF) null else side.name,
            )

            if (s.search.open) {
                val index = vm.indexStatus.collectAsState().value
                ScopeChips(s.search.scope) { pane.setScope(it) }
                FieldChips(
                    containerFacts = index.containerFacts,
                    onPick = { token -> pane.setQuery(appendToken(s.search.query, token)) },
                )
                CrawlNotice(index)
            }

            Box(Modifier.weight(1f)) {
                when {
                    s.loading && s.entries.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                    }
                    s.kind == PaneKind.FOLDER ->
                        FolderBody(pane, vm, s, app, metrics, side, registry, onGhost)
                    else -> SpecialBody(pane, vm, s)
                }
                // Floating, and in EVERY pane rather than only the focused one. Put plainly:
                // the per-pane part is what makes split view unambiguous,
                // because "Paste" then means THIS side and there is nothing to work out.
                PastePill(pane, vm, s, app, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

@Composable
private fun PaneHeader(
    pane: PaneController,
    s: PaneState,
    focused: Boolean,
    widthDp: Int,
    sideLabel: String?,
) {
    val colors = Filet.colors
    Column(Modifier.fillMaxWidth().background(colors.raised)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The focus dot: with two panes, "which one do the toolbar buttons act on" must
            // be answerable without tapping something to find out.
            Box(
                Modifier.size(6.dp).clip(CircleShape)
                    .background(if (focused) colors.accent else colors.lineSoft)
            )
            Spacer(Modifier.width(7.dp))
            // A or B, on the pane itself.
            //
            // The tabs carry the badge, but the tab strip is a row away from the panes and
            // says which TAB is which side - not which half of the screen. With two panes
            // open you read the pane, so the pane is where the letter has to be.
            if (sideLabel != null) {
                Text(
                    sideLabel,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (focused) MaterialTheme.colorScheme.onPrimary else colors.fg3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (focused) colors.accent else colors.high)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(7.dp))
            }
            if (s.search.open) {
                PaneSearchField(pane, s, Modifier.weight(1f))
            } else {
                Text(
                    text = s.cwd?.path ?: s.title,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.fg2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (widthDp >= 200) {
                    Text(
                        countLabel(s), fontSize = 10.sp, color = colors.fg3,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                Icon(
                    FiletIcons.Search, "Search this pane", tint = colors.fg2,
                    modifier = Modifier.size(26.dp).clip(RoundedCornerShape(6.dp))
                        .clickable { pane.openSearch(true) }.padding(4.dp),
                )
            }
        }
        HorizontalDivider(color = colors.lineSoft)
    }
}

@Composable
private fun PaneSearchField(pane: PaneController, s: PaneState, modifier: Modifier) {
    val colors = Filet.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(FiletIcons.Search, null, tint = colors.accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (s.search.query.isEmpty()) {
                Text(
                    "search ${s.title} — or from:youtube",
                    fontSize = 13.sp, color = colors.fg3,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = s.search.query,
                onValueChange = { pane.setQuery(it) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        if (s.search.running) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = colors.accent)
            Spacer(Modifier.width(6.dp))
        } else if (s.search.query.isNotBlank()) {
            Text("${s.search.hits.size}", fontSize = 10.sp, color = colors.fg3)
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            FiletIcons.Close, "Close search", tint = colors.fg2,
            modifier = Modifier.size(22.dp).clip(RoundedCornerShape(5.dp))
                .clickable { pane.openSearch(false) }.padding(3.dp),
        )
    }
}

/**
 * What the index is doing, while you are searching it.
 *
 * Searching during the first crawl used to look like the search being broken: the folder you
 * wanted had not been walked yet, so it returned nothing and said nothing. The crawl is now
 * steered toward the query (`CrawlPriority.kt`), and this is the half of that the user can
 * see - because a detour nobody is told about is indistinguishable from a slow index.
 */
@Composable
private fun CrawlNotice(index: dev.niccc2007.filet.index.IndexStatus) {
    if (!index.running) return
    val colors = Filet.colors
    val steered = index.steeredFor
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.sel)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp, color = colors.accent)
        Spacer(Modifier.width(9.dp))
        Text(
            if (steered == null) {
                "Still indexing — ${index.scanned} files so far. Results may be incomplete."
            } else {
                // Named rather than generic: the point of the detour is that it is working on
                // THIS query, and saying which one is what makes the wait legible.
                "Indexing rerouted to \"$steered\" — ${index.scanned} files so far. " +
                    "Results keep arriving as the scan reaches them."
            },
            fontSize = 9.5.sp,
            color = colors.fg2,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The scope chips are not decoration: tapping one is how the query language gets discovered
 * (SEARCH.md §6.2). Scope is explicit rather than guessed, because the second ambiguity
 * after "which pane" is "how far".
 */
@Composable
private fun ScopeChips(current: SearchScope, onPick: (SearchScope) -> Unit) {
    val colors = Filet.colors
    HScroll(
        modifier = Modifier.background(colors.raised).padding(vertical = 5.dp),
        ground = colors.raised,
        contentPadding = 8.dp,
        spacing = 5.dp,
    ) {
        for (scope in SearchScope.entries) {
            val on = scope == current
            Text(
                text = scope.label,
                fontSize = 10.sp,
                color = if (on) colors.accent else colors.fg2,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) colors.sel else Color.Transparent)
                    .border(1.dp, if (on) colors.accent else colors.lineSoft, RoundedCornerShape(20.dp))
                    .clickable { onPick(scope) }
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * The query language, as things you can tap.
 *
 * Nobody discovers `inzip:` by guessing. A chip writes the token into the box and leaves the
 * caret after it, so the field is learned by using it once - the same trick the scope chips
 * play, extended from "how far" to "by what".
 *
 * The container fields are listed **only when something is registered to populate them**
 * (`IndexStatus.containerFacts`). A chip that inserts `pkg:` into an index that has never
 * looked inside an APK returns an empty list and reads as a broken search, which is exactly
 * what R1 exists to prevent.
 */
@Composable
private fun FieldChips(containerFacts: Boolean, onPick: (String) -> Unit) {
    val colors = Filet.colors
    val fields = buildList {
        addAll(listOf("type:", "ext:", "size:>", "modified:<", "name:", "from:"))
        if (containerFacts) addAll(listOf("pkg:", "class:", "perm:", "label:", "inzip:"))
        add("dup:1")
    }
    HScroll(
        modifier = Modifier.background(colors.raised).padding(vertical = 4.dp),
        ground = colors.raised,
        contentPadding = 8.dp,
        spacing = 5.dp,
    ) {
        for (field in fields) {
            Text(
                text = field,
                fontSize = 10.sp,
                color = colors.fg2,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .border(1.dp, colors.lineSoft, RoundedCornerShape(5.dp))
                    .clickable { onPick(field) }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

/** Append a token without duplicating it, and without eating what is already typed. */
private fun appendToken(query: String, token: String): String {
    if (query.contains(token)) return query
    return if (query.isBlank()) token else query.trimEnd() + " " + token
}

@Composable
private fun FolderBody(
    pane: PaneController,
    vm: BrowserViewModel,
    s: PaneState,
    app: AppState,
    metrics: PaneMetrics,
    side: Side,
    registry: DropRegistry,
    onGhost: (Offset?) -> Unit,
) {
    // Where a held press landed, so the menu opens at the finger rather than at the top of the
    // screen. Null when no menu is open.
    var menuAt by remember { mutableStateOf<Pair<VNode, Offset>?>(null) }
    // True when the menu selected the row itself rather than being opened on a real selection -
    // dismissing without choosing then puts the selection back how it was.
    var menuSelectedIt by remember { mutableStateOf(false) }

    val onHeldStill: (VNode, Offset) -> Unit = { node, at ->
        val current = pane.state.value
        when (longPressAction(current.selected.size, node.path in current.selected)) {
            // A range extension is its own feedback: the rows light up and the bar counts them.
            // Opening a menu on top of that would bury the thing the gesture just did.
            LongPressAction.EXTEND -> Unit
            LongPressAction.MENU -> { menuSelectedIt = true; menuAt = node to at }
            LongPressAction.MENU_FOR_SELECTION -> { menuSelectedIt = false; menuAt = node to at }
        }
    }

    menuAt?.let { (node, at) ->
        PaneContextMenu(
            pane = pane,
            vm = vm,
            node = node,
            at = at,
            onDismiss = {
                if (menuSelectedIt) pane.clearSelection()
                menuAt = null
            },
        )
    }

    val rows = s.visible
    if (rows.isEmpty()) {
        EmptyNote(
            if (s.search.active) emptySearchNote(s.search.scope, vm.indexStatus.collectAsState().value)
            else "This folder is empty",
            Modifier.fillMaxSize(),
        )
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(s.cwd) { listState.scrollToItem(0) }

    if (metrics.step.isGrid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(metrics.step.tile!!.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(6.dp),
        ) {
            items(rows, key = { it.path.toString() }) { node ->
                FileTile(
                    node = node,
                    metrics = metrics,
                    selected = node.path in s.selected,
                    dropTarget = (app.drag?.over as? DropTarget.Folder)?.path == node.path,
                    modifier = dragModifier(node, pane, vm, side, registry, onGhost, onHeldStill),
                    thumbnails = metrics.step.icon >= 20,
                    onClick = { rowClick(pane, vm, side, node) },
                    // No long-click here on purpose: the drag detector in `dragModifier` owns
                    // the long press and selects in its onDragStart. Two long-press detectors
                    // on one tile means the loser never runs.
                    onLongClick = {},
                    onDoubleClick = { vm.openChooser(node) },
                )
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(rows, key = { it.path.toString() }) { node ->
                if (s.search.active) {
                    SearchResultRow(
                        node = node,
                        where = s.search.hits.firstOrNull { it.node.path == node.path }?.where ?: "",
                        metrics = metrics,
                        selected = node.path in s.selected,
                        onClick = { rowClick(pane, vm, side, node) },
                        onLongClick = { pane.toggleSelect(node) },
                    )
                } else {
                    FileRow(
                        node = node,
                        metrics = metrics,
                        selected = node.path in s.selected,
                        dropTarget = (app.drag?.over as? DropTarget.Folder)?.path == node.path,
                        modifier = dragModifier(node, pane, vm, side, registry, onGhost, onHeldStill),
                        // Below ~20 dp the glyph is a few pixels across and a photo in it is
                        // an unreadable smear; the icon says more at that size.
                        thumbnails = metrics.step.icon >= 20,
                        onClick = { rowClick(pane, vm, side, node) },
                        // See the tile above: the drag detector owns the long press.
                        onLongClick = {},
                        onDoubleClick = { vm.openChooser(node) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpecialBody(pane: PaneController, vm: BrowserViewModel, s: PaneState) {
    when (s.kind) {
        PaneKind.HOME -> HomeOverview(vm, pane)
        PaneKind.ABOUT -> AboutPage(vm)
        PaneKind.SETTINGS -> SettingsPage(vm)
        PaneKind.BOOKMARKS -> BookmarksBody(vm, pane)
        PaneKind.RECENT -> RecentBody(vm, pane)
        PaneKind.HISTORY -> dev.niccc2007.filet.home.FileHistoryScreen(vm, pane)
        PaneKind.SCRIPTS -> dev.niccc2007.filet.script.ScriptsScreen(vm)
        PaneKind.NEARBY -> dev.niccc2007.filet.nearby.NearbyScreen(vm)
        PaneKind.REMOTES -> dev.niccc2007.filet.remotes.RemotesScreen(vm)
        PaneKind.SHORTCUTS -> dev.niccc2007.filet.shortcuts.ShortcutsScreen(vm)
        else -> EmptyNote("Nothing here yet", Modifier.fillMaxSize())
    }
}

/**
 * What an empty result list should actually say.
 *
 * The old text was one sentence for every case, and in the case that matters it was wrong:
 * standing on Whole device with a half-built index, it said "Try Subfolders or Whole device"
 * - advice to do the thing you are already doing, on a search that returned nothing for a
 * reason the app knew and did not mention.
 *
 * `lastRunAt` is only stamped after a crawl that RAN TO COMPLETION, so it is exactly the
 * "has the index ever seen the whole device" flag this needs.
 */
private fun emptySearchNote(scope: SearchScope, index: IndexStatus): String = when {
    scope == SearchScope.FOLDER -> "No match here.\nTry Subfolders or Whole device."
    !index.enabled ->
        "No match.\nSearch is walking the filesystem — the index is off in Settings."
    index.running ->
        "No match yet.\nThe index is still building; results appear as it goes."
    index.lastRunAt == 0L ->
        "No match.\nThe index has never finished a full pass, so it does not know " +
            "about everything on the device yet.\nSettings ▸ Index now."
    else -> "No match."
}

private fun rowClick(pane: PaneController, vm: BrowserViewModel, side: Side, node: VNode) {
    vm.focusSide(side)
    if (pane.state.value.selecting) pane.toggleSelect(node) else pane.open(node)
}

/**
 * Long-press to pick up, drag to a folder row or the other pane, release to move.
 *
 * Written by hand rather than with `dragAndDropSource`, which wants a `ClipData` transfer
 * and gives one coarse callback per target. A file list needs a cheap hit test over hundreds
 * of rows and a ghost that tracks the finger, which is what [DropRegistry] provides.
 *
 * A long press that never moves is an ordinary long press - it selects and the drag is
 * cancelled - so the two gestures share one detector without fighting over the timeout.
 */
@Composable
private fun dragModifier(
    node: VNode,
    pane: PaneController,
    vm: BrowserViewModel,
    side: Side,
    registry: DropRegistry,
    onGhost: (Offset?) -> Unit,
    onHeldStill: (VNode, Offset) -> Unit,
): Modifier {
    // Read live. A row's `pointerInput` is keyed on `node.path`, so scrolling a list recycles
    // the composable and hands it new callbacks while the launched block keeps the old ones -
    // the same freeze that killed pinch-to-zoom and tab dragging this round. Caught by
    // tools/check-deadswitch.mjs rather than by anybody noticing a misplaced drag ghost.
    val liveGhost = rememberUpdatedState(onGhost)
    val liveHeldStill = rememberUpdatedState(onHeldStill)
    var origin by remember(node.path) { mutableStateOf(Offset.Zero) }
    var pointer by remember(node.path) { mutableStateOf(Offset.Zero) }
    var moved by remember(node.path) { mutableStateOf(false) }
    val rowKey = "row-${node.path}"

    DisposableEffect(rowKey) { onDispose { registry.remove(rowKey) } }

    return Modifier
        .onGloballyPositioned { c ->
            origin = c.positionInRoot()
            if (node.isDir) {
                registry.put(
                    key = rowKey,
                    rect = Rect(origin, Size(c.size.width.toFloat(), c.size.height.toFloat())),
                    target = DropTarget.Folder(node.path),
                    depth = 1,
                )
            }
        }
        .pointerInput(node.path) {
            detectDragGesturesAfterLongPress(
                onDragStart = { local ->
                    moved = false
                    pointer = origin + local
                    vm.focusSide(side)
                    val current = pane.state.value
                    // What a long press means depends on what is already selected. Decided by
                    // `longPressAction` rather than inline, because a press that quietly does
                    // the other thing reads as the app ignoring you.
                    when (longPressAction(current.selected.size, node.path in current.selected)) {
                        LongPressAction.EXTEND -> pane.extendSelectionTo(node)
                        LongPressAction.MENU -> pane.selectOnly(node)
                        LongPressAction.MENU_FOR_SELECTION -> Unit
                    }
                    val items = pane.selectedNodes().ifEmpty { listOf(node) }
                    vm.beginDrag(items, pane.id)
                    liveGhost.value(pointer)
                },
                onDrag = { change, delta ->
                    change.consume()
                    moved = true
                    pointer += delta
                    liveGhost.value(pointer)
                    vm.dragOver(registry.hitTest(pointer))
                },
                onDragEnd = {
                    liveGhost.value(null)
                    // Move or copy is the plan's call, not the caller's - same volume moves,
                    // a different one copies, exactly as a desktop file manager does.
                    if (moved) {
                        vm.endDrag()
                    } else {
                        // Long-pressed and held STILL. That is the right-click gesture, and it
                        // is free: the drag detector already had to tell the two apart to know
                        // whether a drop happened. Press and move drags, press and hold opens
                        // the menu - which is what press-and-hold means everywhere else on a
                        // touchscreen.
                        vm.cancelDrag()
                        liveHeldStill.value(node, pointer)
                    }
                },
                onDragCancel = { liveGhost.value(null); vm.cancelDrag() },
            )
        }
}

private fun countLabel(s: PaneState): String = when {
    s.search.active -> "${s.search.hits.size} hits"
    // A special pane has no count, and echoing its own title beside itself reads as a bug.
    s.kind != PaneKind.FOLDER -> ""
    s.total != s.entries.size -> "${s.entries.size} of ${s.total}"
    else -> "${s.entries.size} items"
}

/**
 * The context menu for whatever the press landed on.
 *
 * Built from the same `selectionActions` list the selection bar and the Actions menu use, so an
 * action cannot exist in one surface and be missing from another. The extras below it are the
 * ones that only make sense from an item: Select, which starts a multi-selection without needing
 * a second gesture, and Copy path.
 */
@Composable
private fun PaneContextMenu(
    pane: PaneController,
    vm: BrowserViewModel,
    node: VNode,
    at: Offset,
    onDismiss: () -> Unit,
) {
    val state = pane.state.value
    val count = state.selected.size.coerceAtLeast(1)
    val readOnly = vm.writeBlockReason(state.cwd)

    val base = selectionActions(
        count = count,
        readOnly = readOnly,
        icons = SelectionIcons(
            copy = FiletIcons.Copy, cut = FiletIcons.Cut, share = FiletIcons.Share,
            delete = FiletIcons.Delete, zip = FiletIcons.Zip, rename = FiletIcons.Rename,
            open = FiletIcons.Open, info = FiletIcons.Info, star = FiletIcons.Star,
            wifi = FiletIcons.Wifi, home = FiletIcons.Home,
        ),
        on = SelectionCallbacks(
            copy = { vm.copySelection() },
            move = { vm.cutSelection() },
            send = { vm.shareSelection() },
            delete = { vm.confirmDelete() },
            compress = { vm.askCompress() },
            rename = { vm.renameSelection() },
            openWith = { vm.openWithSelection() },
            details = { vm.detailsForSelection() },
            bookmark = { vm.bookmarkSelection() },
            nearby = { vm.shareSelectionNearby() },
            shortcut = { vm.shortcutSelection() },
            extractHere = { vm.extractSelection() },
            extractTo = { vm.extractSelectionToPicked() },
            extractToOtherPane = { vm.extractSelectionToOtherPane() },
        ),
        archive = vm.selectionIsArchive(),
        otherPane = vm.isSplit(),
        picking = vm.picking,
    )

    val extras = listOf(
        // Keeps the row selected instead of clearing it on dismiss, which is the whole point:
        // it is the way into a multi-selection that does not require knowing a gesture.
        menuAction("select", "Select", FiletIcons.Check) { },
        menuAction("copypath", "Copy path", FiletIcons.Copy) { vm.copyPathsOfSelection() },
    )
    val (quick, rest) = splitForContextMenu(base)

    ContextMenu(
        title = if (state.selected.size > 1) "${state.selected.size} items" else node.name,
        subtitle = if (state.selected.size > 1) null else node.path.path.substringBeforeLast('/'),
        quick = quick,
        rest = extras + rest,
        onDismiss = onDismiss,
        onBlocked = vm::toast,
        offsetX = at.x.toInt(),
        offsetY = at.y.toInt(),
    )
}

/**
 * Where a copy or a move finishes.
 *
. He is right, and the failure
 * is worse than one of discoverability: after Copy, the app holds state that nothing on screen
 * mentions. A clipboard you cannot see is a clipboard you forget you filled.
 *
 * So the moment something is copied or cut, every pane grows a pill that says how many and
 * offers to put them here. Compact rather than a full-width bar, because it sits over a file
 * list somebody is still reading; per-pane rather than focused-pane, because "here" has to be
 * a place and not a guess.
 *
 * It refuses with a reason on a read-only volume rather than disappearing - a control that
 * vanishes leaves you wondering whether the copy survived.
 */
@Composable
private fun PastePill(
    pane: PaneController,
    vm: BrowserViewModel,
    s: PaneState,
    app: AppState,
    modifier: Modifier = Modifier,
) {
    val clip = app.clipboard ?: return
    if (s.kind != PaneKind.FOLDER || s.cwd == null) return
    val colors = Filet.colors
    val readOnly = vm.writeBlockReason(s.cwd)
    val verb = if (clip.op == dev.niccc2007.filet.ops.PendingOp.MOVE) "Move" else "Paste"
    val count = clip.items.size

    Row(
        modifier
            .padding(end = 12.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(if (readOnly == null) colors.accent else colors.high)
            .clickable {
                if (readOnly != null) vm.toast(readOnly) else vm.pasteInto(pane)
            }
            .padding(start = 14.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // Names the folder, so in split view there is no question which side it lands in.
            "$verb $count here",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (readOnly == null) MaterialTheme.colorScheme.onPrimary else colors.fg3,
        )
        Spacer(Modifier.width(4.dp))
        // Cancelling has to be as reachable as pasting, or the pill is something you have to
        // obey rather than something you can dismiss.
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(50))
                .clickable { vm.clearClipboard() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "×",
                fontSize = 14.sp,
                color = if (readOnly == null) MaterialTheme.colorScheme.onPrimary else colors.fg3,
            )
        }
    }
}

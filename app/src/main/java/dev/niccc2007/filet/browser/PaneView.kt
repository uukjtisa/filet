package dev.niccc2007.filet.browser

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.ui.WarmAhead
import dev.niccc2007.filet.about.AboutPage
import dev.niccc2007.filet.data.ViewStep
import dev.niccc2007.filet.home.HomeOverview
import dev.niccc2007.filet.index.IndexStatus
import dev.niccc2007.filet.index.Reach
import dev.niccc2007.filet.settings.SettingsPage
import dev.niccc2007.filet.ui.HScroll
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath

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
    // The pane's copy of its state hides WHICH files are selected, so a tap that selects one
    // does not recompose the whole pane. Rows read the real selection themselves - see
    // SelectionReads and `rowSelected`.
    val s by remember(pane) {
        pane.state.map(SelectionReads::forPane).distinctUntilChanged()
    }.collectAsState(SelectionReads.forPane(pane.state.value))
    // Taken off the screen with the pane standing still: the next showing is a return, and comes
    // back where it was. See PaneController.markReturn.
    androidx.compose.runtime.DisposableEffect(pane) { onDispose { pane.markReturn() } }
    val app by vm.state.collectAsState()
    val viewStep by vm.prefs.viewStep.collectAsState()
    val treeWanted by vm.prefs.treeView.collectAsState()
    val reach by vm.prefs.reachMode.collectAsState()
    val colors = Filet.colors
    val paneKey = "pane-${pane.id}"

    DisposableEffect(paneKey) { onDispose { registry.remove(paneKey) } }

    // Armed when the finger is over this pane's own background - not over a folder row inside
    // it, which highlights itself and would otherwise light up twice.
    val receiving = (app.drag?.over as? DropTarget.Pane)?.paneId == pane.id
    val refused = receiving && app.drag?.drop?.refusal != null

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthDp = maxWidth.value.toInt()
        // A tree is a LIST, whatever the view slider says.
        //
        // The grid steps draw tiles, which have nowhere to put an indent or a guide line, and
        // the big list steps give a row most of the screen - which at four levels deep leaves
        // no room for the name. So the tree clamps to the three list steps and keeps whichever
        // of them is nearest to what was asked for. The slider still works; it just cannot
        // take the tree somewhere a tree cannot go.
        val asked = ViewStep.of(viewStep)
        val step = if (treeWanted) ViewStep.entries[asked.ordinal.coerceAtMost(TREE_MAX_STEP)] else asked
        // Issue #11: a long name wraps rather than scrolling past. The row's own shape still
        // has the last word - see `nameLineCount`.
        val nameLines by vm.prefs.nameLines.collectAsStateWithLifecycle()
        val metrics = PaneMetrics.forWidth(widthDp, step, nameLines)

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
            // Reach mode puts the header and its search chips UNDER the listing, so the path, the
            // count and the search glyph are where the thumb is. The chips go above the field
            // rather than below it: they belong to the field, and at the bottom of the pane
            // "above" is the side that is still next to it.
            val header: @Composable () -> Unit = {
                PaneHeader(
                    pane, s, focused, widthDp,
                    sideLabel = if (app.split == SplitMode.OFF) null else side.name,
                    registry = registry,
                    paneKey = paneKey,
                    paneId = pane.id,
                    atBottom = reach,
                )
            }
            val chips: @Composable () -> Unit = {
                if (s.search.open) {
                    val index = vm.indexStatus.collectAsState().value
                    var reachSheet by remember { mutableStateOf(false) }
                    val currentReach = ReachLadder.resolve(s.search.reach, s.cwd, pane.reachDrives)
                    if (reachSheet) {
                        ReachSheet(
                            drives = pane.reachDrives,
                            current = currentReach,
                            onPick = { pane.setReach(it); reachSheet = false },
                            onDismiss = { reachSheet = false },
                        )
                    }
                    ScopeChips(
                        // The ladder this pane can actually climb. All four of the old scopes
                        // were drawn on every pane, so Home opened with "This folder" ticked
                        // and no folder to stand in - and the rung above it named a set nobody
                        // could point at. See ReachLadder.
                        offered = ReachLadder.rungsFor(s.cwd, pane.reachDrives),
                        current = currentReach,
                        // Only worth offering with something to choose between.
                        canChoose = pane.reachDrives.size > 1,
                        onChoose = { reachSheet = true },
                        nativeOnly = s.search.nativeOnly,
                        nativeMatters = s.search.nativeMatters,
                        origin = SearchReach.statusLine(
                            origin = s.search.origin,
                            running = s.search.running,
                            checking = s.search.checking,
                            walked = s.search.walked,
                            drives = pane.reachDrives,
                        ),
                        hidden = vm.prefs.searchHidden.collectAsState().value,
                        onHidden = { pane.setSearchHidden(it) },
                        onPick = { pane.setReach(it) },
                        onNative = { pane.setNativeOnly(it) },
                    )
                    FieldChips(
                        containerFacts = index.containerFacts,
                        onPick = { token -> pane.setQuery(appendToken(s.search.query, token)) },
                    )
                    CrawlNotice(index)
                }
            }
            if (!reach) {
                header()
                chips()
            }

            Box(Modifier.weight(1f)) {
                // Measured once here and read by both the listing underneath and the dock
                // beside it, so neither has to guess how much of the pane the pick card is
                // sitting on. See `LocalPickInset`.
                // TWO numbers, summed, not one shared by both cards.
                //
                // Sharing one was a bug: the pick card reports zero on its way out through a
                // DisposableEffect, and the extract card reports nothing at all when it leaves
                // composition - so after an extraction finished the listing kept padding the
                // bottom for a card that was no longer on screen. Separate states make the
                // dispose of each one unambiguous and remove the ordering question between
                // them entirely.
                var pickHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                var extractHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                val pickInset = with(androidx.compose.ui.platform.LocalDensity.current) {
                    (pickHeight + extractHeight).toDp()
                }
                androidx.compose.runtime.CompositionLocalProvider(LocalPickInset provides pickInset) {
                when {
                    s.loading && s.entries.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                    }
                    // A pane showing search results draws the result list, whatever kind it is.
                    //
                    // This used to read `s.kind == PaneKind.FOLDER`, which was the same question
                    // for as long as only a folder pane could search. Once Home could, the two
                    // came apart: the search ran, the hits landed in state, and the body still
                    // drew the Home overview - so a correct answer was never on screen.
                    // `s.visible` already returns the hits during a search, so FolderBody needs
                    // no cwd and needs no special case.
                    s.kind == PaneKind.FOLDER || SearchReach.showsResults(s.kind, s.search.active) ->
                        FolderBody(pane, vm, s, app, metrics, side, registry, onGhost, reach)
                    else -> SpecialBody(pane, vm, s, widthDp)
                }
                }
                // Floating, and in EVERY pane rather than only the focused one. The
                // per-pane part is what makes split view unambiguous: "Paste" then means
                // THIS side and there is nothing to work out.
                // Lifted clear of the pick card when one is up, so the two floating things do
                // not land on top of each other. New folder stays reachable while choosing a
                // destination, which is exactly when somebody wants it.
                Dock(pane, vm, s, app, liftBy = pickInset)
                // The pick, as a floating card in the listing it is asking about rather than a
                // band of chrome above it. It reports its own height so the dock clears it
                // exactly. See `PickDock`.
                PickDock(vm, pane) { pickHeight = it }
                // The extraction's settings, in the same place and measured the same way. Only
                // in the pane it is about: in split view the other side is an ordinary browser
                // and a card there would describe a folder it is not changing.
                val pendingExtract by vm.extractPlan.collectAsState()
                pendingExtract?.let { p ->
                    if (pane === vm.focusedPane()) {
                        // Reported back to zero when the card goes, so the listing stops
                        // reserving room for it.
                        androidx.compose.runtime.DisposableEffect(Unit) {
                            onDispose { extractHeight = 0 }
                        }
                        ExtractCard(
                            plan = p.plan,
                            destinationName = p.into.path,
                            busy = p.busy,
                            onWrap = {
                                vm.adjustExtract {
                                    it.copy(
                                        wrap = if (p.plan.wrapFolder != null) {
                                            dev.niccc2007.filet.vfs.provider.WrapChoice.FORCE_OFF
                                        } else {
                                            dev.niccc2007.filet.vfs.provider.WrapChoice.FORCE_ON
                                        },
                                    )
                                }
                            },
                            // One level at a time, either way. Both are explicit numbers rather
                            // than a toggle, because the answer is "how many", not "yes or no".
                            onStripLess = {
                                vm.adjustExtract {
                                    it.copy(stripLevels = (p.plan.strippedFolders.size - 1).coerceAtLeast(0))
                                }
                            },
                            onStripMore = {
                                vm.adjustExtract {
                                    it.copy(
                                        stripLevels = (p.plan.strippedFolders.size + 1)
                                            .coerceAtMost(p.plan.strippableLevels),
                                    )
                                }
                            },
                            onCollision = { c -> vm.adjustExtract { it.copy(onCollision = c) } },
                            onConfirm = vm::confirmExtract,
                            onDismiss = vm::cancelExtract,
                            // Asked of the DESTINATION, not of the archive's own folder.
                            // Extracting OUT of a read-only volume into a writable one is the
                            // case the merge had to keep working.
                            readOnly = vm.writeBlockReason(p.into),
                        ) { extractHeight = it }
                    }
                }
                // Only when there are two panes: alone on screen there is nowhere to drag
                // between, so a drop target for "this pane's own folder" is a control with
                // nothing to do.
                if (app.split != SplitMode.OFF) {
                    DropHerePane(
                        pane, app, registry, paneKey,
                        Modifier.align(Alignment.CenterEnd),
                        here = s.cwd?.name?.ifEmpty { null } ?: s.title,
                    )
                }
            }
            if (reach) {
                chips()
                header()
            }
        }
    }
}

/**
 * A strip down the right edge meaning "this folder, not one inside it".
 *
 * Only while a drag is in flight. A permanent strip would cost horizontal width in split view,
 * which on a phone is the scarcest thing on the screen - each pane gets a little over half a
 * portrait width, and spending 44dp of it forever on a problem that exists for two seconds at
 * a time is a bad trade. This appears exactly when it is the answer to something.
 *
 * It overlays the list rather than taking layout width, because reflowing the rows mid-drag
 * would move the row out from under the finger.
 *
 * No label on it: the drag ghost already reads "Copy to <folder>" from the resolved plan, so
 * the band would be repeating in 9sp what the ghost says in full beside the finger.
 */
@Composable
private fun DropHerePane(
    pane: PaneController,
    app: AppState,
    registry: DropRegistry,
    paneKey: String,
    modifier: Modifier,
    /** The folder this pane is showing, which is exactly what dropping here means. */
    here: String,
) {
    val dragging = app.drag != null
    val bandKey = "$paneKey-band"
    // Keyed on `dragging` as well as the key, so the rectangle is withdrawn the moment the
    // band stops being drawn. A registered rectangle with nothing drawn over it is a target
    // that accepts drops onto something invisible.
    DisposableEffect(bandKey, dragging) { onDispose { registry.remove(bandKey) } }
    if (!dragging) return

    val colors = Filet.colors
    val over = (app.drag?.over as? DropTarget.Pane)?.paneId == pane.id
    val refused = over && app.drag?.drop?.refusal != null

    Column(
        modifier
            .fillMaxHeight()
            .width(44.dp)
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
            .background(
                when {
                    refused -> colors.bad.copy(alpha = 0.22f)
                    over -> colors.accent.copy(alpha = 0.22f)
                    else -> colors.raised.copy(alpha = 0.92f)
                },
            )
            .onGloballyPositioned { c ->
                registry.put(
                    key = bandKey,
                    rect = Rect(c.positionInRoot(), Size(c.size.width.toFloat(), c.size.height.toFloat())),
                    target = DropTarget.Pane(pane.id),
                    // Above the folder rows it sits on top of, or it would never be reachable -
                    // which is the entire bug this exists to fix.
                    depth = 2,
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            FiletIcons.FolderOpen,
            null,
            tint = if (refused) colors.bad else colors.accent,
            modifier = Modifier.size(20.dp),
        )
        // Only once the finger is actually on it. The ghost beside the finger says the verb
        // ("Copy to ..."); this says WHERE, because the whole point of the band is that it
        // means the folder you are looking at rather than any of the folders listed in it.
        if (over) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (refused) "Cannot drop here" else "Drop in\n" + here,
                fontSize = 8.5.sp,
                lineHeight = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = if (refused) colors.bad else colors.fg2,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 3.dp),
            )
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
    registry: DropRegistry,
    paneKey: String,
    paneId: Int,
    /** Drawn under the listing in reach mode, so the divider goes on the side facing the rows. */
    atBottom: Boolean = false,
) {
    val colors = Filet.colors
    // The header is a drop target for the folder it names.
    //
    // The pane's background already means "this folder", but in a folder holding only folders
    // every row is a target and the rows cover every pixel - so the background never wins the
    // hit test and there is no way to drop HERE rather than one level down. The header is the
    // one strip that is always visible, always this pane's, and never a row.
    val headerKey = "$paneKey-header"
    DisposableEffect(headerKey) { onDispose { registry.remove(headerKey) } }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.raised)
            .onGloballyPositioned { c ->
                registry.put(
                    key = headerKey,
                    rect = Rect(c.positionInRoot(), Size(c.size.width.toFloat(), c.size.height.toFloat())),
                    target = DropTarget.Pane(paneId),
                    // Above a folder row, which is depth 1. The header does not overlap a row
                    // today; saying so here keeps the ordering true if the layout changes.
                    depth = 2,
                )
            },
    ) {
        if (atBottom) HorizontalDivider(color = colors.lineSoft)
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
                // On every tab that searches, Home included. Home used to draw a full search
                // card instead, and the card cost a row of drives to say what this glyph says.
                // Gated on the plan rather than on a list of kinds: a glyph on a tab with
                // nothing to search is a control that does nothing, and About is one.
                if (searchOffered(s.kind)) {
                    Icon(
                        FiletIcons.Search, "Search this pane", tint = colors.fg2,
                        modifier = Modifier.size(26.dp).clip(RoundedCornerShape(6.dp))
                            .clickable { pane.openSearch(true) }.padding(4.dp),
                    )
                }
            }
        }
        if (!atBottom) HorizontalDivider(color = colors.lineSoft)
    }
}

@Composable
private fun PaneSearchField(pane: PaneController, s: PaneState, modifier: Modifier) {
    val colors = Filet.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Column(modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(FiletIcons.Search, null, tint = colors.accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (s.search.query.isEmpty()) {
                Text(
                    // Home has no folder to name; what it searches is the whole device.
                    if (s.kind == PaneKind.HOME) searchHint(s.kind) else "search ${s.title} — or from:youtube",
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
        // The count is always shown once there is a query; whether it is final is the line
        // below's job. A spinner in its place hid the number while it was the thing changing.
        if (s.search.query.isNotBlank()) {
            Text("${s.search.hits.size}", fontSize = 10.sp, color = colors.fg3)
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            FiletIcons.Close, "Close search", tint = colors.fg2,
            modifier = Modifier.size(22.dp).clip(RoundedCornerShape(5.dp))
                .clickable { pane.openSearch(false) }.padding(3.dp),
        )
    }
    // One thin line while ANYTHING is still working - the sources, the walkers, the checker.
    // SEARCH.md §14.8. The old spinner followed the sources alone, so it stopped while rows were
    // still arriving and the answer looked final when it was not. A spacer of the same height
    // when idle, so the field does not jump as the line comes and goes.
    if (s.search.busy) {
        androidx.compose.material3.LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(1.5.dp),
            color = colors.accent,
            trackColor = colors.lineSoft,
        )
    } else {
        Spacer(Modifier.padding(top = 4.dp).height(1.5.dp))
    }
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
            // Both numbers, each labelled.
            //
            // Bug identified: this printed `scanned` and called them "files". `scanned` counts
            // THIS PASS, so it starts near zero on every launch - which made a restart look like
            // the index had shrunk from 31k to 21k. It had not: the database held 42,535 rows
            // throughout, and a pass had completed. See SearchReach.indexingLine.
            SearchReach.indexingLine(index, steered),
            fontSize = 9.5.sp,
            color = colors.fg2,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The reach chips are not decoration: tapping one is how the query language gets discovered
 * (SEARCH.md §6.2). Reach is explicit rather than guessed, because the second ambiguity after
 * "which pane" is "how far" - and the chips are in ladder order, each one strictly wider than
 * the last, so widening a search is always the next chip to the right.
 */
@Composable
private fun ScopeChips(
    current: Reach,
    nativeOnly: Boolean,
    nativeMatters: Boolean,
    origin: String,
    offered: List<Rung>,
    onPick: (Reach) -> Unit,
    onNative: (Boolean) -> Unit,
    canChoose: Boolean = false,
    onChoose: () -> Unit = {},
    hidden: Boolean = false,
    onHidden: (Boolean) -> Unit = {},
) {
    val colors = Filet.colors
    HScroll(
        modifier = Modifier.background(colors.raised).padding(vertical = 5.dp),
        ground = colors.raised,
        contentPadding = 8.dp,
        spacing = 5.dp,
    ) {
        for (rung in offered) {
            val on = rung.reach == current
            Text(
                text = rung.label,
                fontSize = 10.sp,
                color = if (on) colors.accent else colors.fg2,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) colors.sel else Color.Transparent)
                    .border(1.dp, if (on) colors.accent else colors.lineSoft, RoundedCornerShape(20.dp))
                    .clickable { onPick(rung.reach) }
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        // The precise path, after the fast one. Lit when the reach in force is a set of drives
        // no rung names - otherwise nothing in the row would be ticked and the chips would read
        // as having lost track of what is being searched.
        if (canChoose) {
            val custom = offered.none { it.reach == current }
            Text(
                text = if (custom) driveCount(current) else "Drives…",
                fontSize = 10.sp,
                color = if (custom) colors.accent else colors.fg2,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (custom) colors.sel else Color.Transparent)
                    .border(1.dp, if (custom) colors.accent else colors.lineSoft, RoundedCornerShape(20.dp))
                    .clickable { onChoose() }
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        // Hidden folders, in the search bar itself and saved on its own. SEARCH.md §14.5. With
        // the browser's show-hidden on, the first answer to "minecraft" was a thumbnail inside
        // .hwthumbnails; whether to SEE dot-folders while browsing is not whether a cache belongs
        // in an answer, so the two are separate switches.
        Text(
            text = "Hidden folders",
            fontSize = 10.sp,
            color = if (hidden) colors.accent else colors.fg2,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (hidden) colors.sel else Color.Transparent)
                .border(1.dp, if (hidden) colors.accent else colors.lineSoft, RoundedCornerShape(20.dp))
                .clickable { onHidden(!hidden) }
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )
        // The native switch sits with the scopes because it answers the same question they do:
        // how hard to look. An index can be cold and nobody can see that from the outside, so
        // "look again properly" is a control rather than something to be guessed at.
        //
        // Dimmed where it cannot matter. For a single folder the index declines anyway, so the
        // walk is already the only source and turning this on removes nothing - which is
        // exactly what it looked like when it was drawn the same as everywhere else.
        Text(
            text = if (nativeMatters) "Native search" else "Native search · already",
            fontSize = 10.sp,
            color = when {
                !nativeMatters -> colors.fg3
                nativeOnly -> colors.accent
                else -> colors.fg2
            },
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (nativeOnly && nativeMatters) colors.sel else Color.Transparent)
                .border(
                    1.dp,
                    if (nativeOnly && nativeMatters) colors.accent else colors.lineSoft,
                    RoundedCornerShape(20.dp),
                )
                .clickable(enabled = nativeMatters) { onNative(!nativeOnly) }
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )
        // What actually answered. Without it a re-run that finds the same rows reads as a
        // control that is not connected to anything.
        if (origin.isNotEmpty()) {
            Text(origin, fontSize = 9.5.sp, color = colors.fg3, modifier = Modifier.padding(start = 3.dp))
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun FolderBody(
    pane: PaneController,
    vm: BrowserViewModel,
    s: PaneState,
    app: AppState,
    metrics: PaneMetrics,
    side: Side,
    registry: DropRegistry,
    onGhost: (Offset?) -> Unit,
    reach: Boolean = false,
) {
    // The real selection, as a State that is only ever READ inside a row. Read here, it would
    // put FolderBody back in the path of every tap.
    val selection = remember(pane) {
        pane.state.map { it.selected }.distinctUntilChanged()
    }.collectAsState(pane.state.value.selected)

    // Where a held press landed, so the menu opens at the finger rather than at the top of the
    // screen. Null when no menu is open.
    var menuAt by remember { mutableStateOf<Pair<VNode, Offset>?>(null) }
    // True when the menu selected the row itself rather than being opened on a real selection -
    // dismissing without choosing then puts the selection back how it was.
    var menuSelectedIt by remember { mutableStateOf(false) }

    // `action` is what the press found when it STARTED. Read here instead, it always saw the
    // row the press had just selected, and the menu opened on every long press - covering the
    // rows somebody was about to select next, which is how it was reported.
    val onHeldStill: (VNode, Offset, LongPressAction) -> Unit = { node, at, action ->
        when (action) {
            // A range extension is its own feedback: the rows light up and the bar counts them.
            // Opening a menu on top of that would bury the thing the gesture just did.
            LongPressAction.EXTEND -> Unit
            // A press on something unselected selects it, and that is all. The menu would cover
            // the next rows to pick; it is a double tap, or a press on the selection, away.
            LongPressAction.MENU -> Unit
            // Pressing what is already selected is asking about it: the menu, for all of them.
            LongPressAction.MENU_FOR_SELECTION -> { menuSelectedIt = false; menuAt = node to at }
        }
    }

    // A double tap selects the row - adding it to a running selection, or starting one - and
    // opens the menu on it. The selection stays after the menu closes: it was asked for.
    val onDoubleTapRow: (VNode, Offset) -> Unit = { node, at ->
        vm.focusSide(side)
        val current = pane.state.value
        if (node.path !in current.selected) {
            if (current.selected.isEmpty()) pane.selectOnly(node) else pane.toggleSelect(node)
        }
        menuSelectedIt = false
        menuAt = node to at
    }

    // The icon, and a grid tile's corner circle: a tap there toggles the selection whether or
    // not one is running. The rest of the row keeps meaning open.
    val onIconTap: (VNode) -> Unit = { node ->
        vm.focusSide(side)
        pane.toggleSelect(node)
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

    // A grid tile held still, lifted. Select comes first: it is what the hold used to do, now
    // one tap further away rather than gone. Delete only where the folder can be written.
    var peekFor by remember { mutableStateOf<VNode?>(null) }
    peekFor?.let { node ->
        val writable = vm.writeBlockReason(s.cwd ?: node.path.parent) == null
        dev.niccc2007.filet.handlers.TilePeek(
            vm = vm,
            node = node,
            actions = listOfNotNull(
                dev.niccc2007.filet.handlers.PeekAction(FiletIcons.Check, "Select") {
                    vm.focusSide(side)
                    pane.selectOnly(node)
                },
                dev.niccc2007.filet.handlers.PeekAction(FiletIcons.Open, "Open") { rowClick(pane, vm, side, node) },
                dev.niccc2007.filet.handlers.PeekAction(FiletIcons.Share, "Share") { vm.share(node) },
                if (writable) {
                    dev.niccc2007.filet.handlers.PeekAction(FiletIcons.Delete, "Delete", danger = true) {
                        vm.confirmDeleteOf(listOf(node))
                    }
                } else {
                    null
                },
            ),
            onDismiss = { peekFor = null },
        )
    }
    val peekTile: (VNode) -> Unit = { peekFor = it }
    val unpeekTile: () -> Unit = { peekFor = null }

    val rows = s.visible
    // The tree is the same list with the opened folders spliced into it, and only when the
    // view is on and nothing is being searched - a search result's row already says where it
    // is, and indenting hits under folders they are not in would be a lie. See `TreeRows`.
    val treeOn by vm.prefs.treeView.collectAsState()
    val tree = treeOn && !s.search.active
    val treeRows = remember(rows, s.open, s.branches, tree) {
        if (tree) TreeRows.flatten(rows, s.open, s.branches) else emptyList()
    }
    val listKeys = remember(tree, rows, treeRows) { rowKeys(tree, rows, treeRows) }
    // Which folder a drop on each tree row lands in. See `TreeDrop`.
    val treeHomes = remember(treeRows, s.cwd) { TreeDrop.homes(treeRows, s.cwd) }
    // The run of rows the working folder's outline goes round: the folder itself, then
    // everything under it, which in a flattened tree is always one contiguous block.
    //
    // A `loading` placeholder carries its PARENT's node, so its path equals the active
    // folder's rather than sitting under it - without allowing for that the box would stop
    // at the row saying "Reading…" and leave the branch being read outside the frame.
    val frame = remember(treeRows, s.active) {
        val at = s.active
        val first = if (at == null) -1 else with(TreeRows) {
            treeRows.indexOfFirst { it.isRowOf(at) && !it.loading }
        }
        if (first < 0 || at == null) {
            null
        } else {
            var last = first
            var i = first + 1
            while (i < treeRows.size) {
                val row = treeRows[i]
                // TreeBranch.under, so the rail runs on through an archive open inside it.
                if (!row.loading && (row.node.path == at || !TreeBranch.under(at, row.node.path))) break
                last = i
                i++
            }
            first..last
        }
    }
    // How far this tree may step in, given how wide the pane is and how deep it is open
    // RIGHT NOW. One value for every row - see `TreeScale` for why it cannot be per row,
    // and why the guides read it rather than a constant of their own.
    val steps = remember(metrics.widthDp, treeRows) {
        TreeScale.of(metrics.widthDp, treeRows.maxOfOrNull { it.depth } ?: 0)
    }
    if (rows.isEmpty()) {
        EmptyNote(
            if (s.search.active) {
                val current = ReachLadder.resolve(s.search.reach, s.cwd, pane.reachDrives)
                val rungs = ReachLadder.rungsFor(s.cwd, pane.reachDrives)
                emptySearchNote(
                    reach = current,
                    wider = rungs.dropWhile { it.reach != current }.drop(1).firstOrNull()?.label,
                    index = vm.indexStatus.collectAsState().value,
                )
            }
            else "This folder is empty",
            Modifier.fillMaxSize(),
        )
        return
    }

    // One scroll position per TAB and per FOLDER, kept by the pane.
    //
    // Bug identified here, twice. The list state was first remembered by this composable, which
    // is the same one whichever tab it is drawing, so tab 2 inherited tab 1's scroll; it was then
    // parked per pane, but only for the folder the pane was in, so Back put the folder being
    // returned to at its first row. The pane now files a position for every folder it has been
    // in (see ScrollMemory), and a folder RETURNED to - Back, Forward, Up, or a tab put back at
    // launch - is scrolled back to it. See `PaneState.resume`.
    //
    // Put back AS THE STATE IS MADE only for a return whose row is already here - a tab shown
    // again, whose rows never left - so it is drawn in place with no jump.
    //
    // Bug identified here, twice over. These states are made again every time a folder starts
    // loading, because the listing returns early while it has no rows. They used to take the
    // folder's last position unconditionally: so a folder opened FRESH, from Home or the side
    // bar, came up wherever it was left some earlier time; and a long folder returned to by Back
    // was handed an index its first chunk of rows did not reach yet, was clamped short of it, and
    // was then marked as handled - so the effect below that waits for the row never ran, and Back
    // out of an archive landed near the top. A return whose row has not arrived starts at the top
    // and leaves the position to that effect.
    val place = pane.placeOf(s)
    val parked = remember(pane.id) {
        if (!s.resume) return@remember null
        val marks = place?.let { pane.scroll.at(it) } ?: return@remember null
        val grid = metrics.step.isGrid
        val mark = (if (grid) marks.grid else marks.list) ?: return@remember null
        val settled = !s.loading && (!tree || s.cwd == null || TreeRows.foldsRead(s.cwd, s.open, s.branches))
        val keys: List<Any> = if (grid) rows.map { it.path.toString() } else listKeys
        (ScrollMemory.resolve(mark, keys, settled) as? ScrollMemory.Outcome.Scroll)?.let { grid to it }
    }
    val listState = remember(pane.id) {
        val at = parked?.takeIf { !it.first }?.second
        androidx.compose.foundation.lazy.LazyListState(at?.index ?: 0, at?.offset ?: 0)
    }
    val gridState = remember(pane.id) {
        val at = parked?.takeIf { it.first }?.second
        androidx.compose.foundation.lazy.grid.LazyGridState(at?.index ?: 0, at?.offset ?: 0)
    }
    // The folder a parked position was put back for by being remembered above. The "new folder
    // starts at the top" effect skips exactly that one landing.
    var resumedIn by remember(pane.id) { mutableStateOf(if (parked != null) s.cwd else null) }

    // Filed as it changes, so leaving the folder by any route - a tap, Back, a tab switch, the
    // app closing - finds its position already written. The first value is dropped: it is the
    // position the folder arrived at, which is either the top, the one just put back, or for a
    // moment the PREVIOUS folder's, since the list state is the pane's and not the folder's.
    LaunchedEffect(pane.id, s.cwd, s.resume) {
        val at = place ?: return@LaunchedEffect
        if (s.resume) return@LaunchedEffect
        snapshotFlow {
            val moving = listState.isScrollInProgress || gridState.isScrollInProgress
            moving to ScrollMemory.Marks(list = markOf(listState), grid = markOf(gridState))
        }
            .drop(1)
            .collect { (moving, marks) -> if (!moving) pane.parkScroll(at, marks) }
    }

    // The row a long press in the search results opened a menu for. The same sheet every other
    // list uses, so the actions do not differ by which screen you found the file on.
    var searchMenuFor by remember { mutableStateOf<VNode?>(null) }


    // A new folder starts at the top - unless something asked to be revealed in it, in which
    // case scrolling to the top is exactly what would undo the reveal, or it is being returned
    // to, which the effect after this one answers.
    LaunchedEffect(pane.id, s.cwd) {
        val resumed = resumedIn != null && resumedIn == s.cwd
        resumedIn = null
        if (resumed) {
            pane.resumeHandled()
            return@LaunchedEffect
        }
        if (s.resume) return@LaunchedEffect
        if (s.revealTarget == null) {
            listState.scrollToItem(0)
            gridState.scrollToItem(0)
            // Filed as well: the position kept for this folder is the one it was last SEEN at,
            // so a later return comes back to the top it was opened at rather than to a visit
            // before that.
            place?.let { pane.parkScroll(it, ScrollMemory.Marks(list = ScrollMemory.Mark(0, 0), grid = ScrollMemory.Mark(0, 0))) }
        }
    }

    // A folder returned to goes back to the row it was scrolled to.
    //
    // Waited for the way a reveal is: a folder arrives in chunks and a tree's branches after it,
    // so the row a position names may not be there yet, and scrolling early lands on whichever
    // file happens to be at that index so far. ScrollMemory.resolve says scroll or wait.
    LaunchedEffect(pane.id, s.cwd, s.resume, listKeys, s.loading, s.branches) {
        if (!s.resume) return@LaunchedEffect
        val grid = metrics.step.isGrid
        val marks = place?.let { pane.scroll.at(it) }
        val mark = if (grid) marks?.grid else marks?.list
        // Nothing to go back to, or something else owns the scroll: a reveal, or a search whose
        // rows are not the folder's.
        if (mark == null || s.revealTarget != null || s.search.active) {
            if (s.revealTarget == null) {
                listState.scrollToItem(0)
                gridState.scrollToItem(0)
            }
            pane.resumeHandled()
            return@LaunchedEffect
        }
        // A drag on the list means they are reading it themselves now.
        if (listState.isScrollInProgress || gridState.isScrollInProgress) {
            pane.resumeHandled()
            return@LaunchedEffect
        }
        val treeRead = !tree || s.cwd == null || TreeRows.foldsRead(s.cwd, s.open, s.branches)
        val keysNow: List<Any> = if (grid) rows.map { it.path.toString() } else listKeys
        when (val o = ScrollMemory.resolve(mark, keysNow, settled = !s.loading && treeRead)) {
            ScrollMemory.Outcome.Wait -> return@LaunchedEffect
            is ScrollMemory.Outcome.Scroll -> {
                if (grid) gridState.scrollToItem(o.index, o.offset) else listState.scrollToItem(o.index, o.offset)
                pane.resumeHandled()
            }
        }
    }

    // Scroll a revealed file into the middle, once the listing has finished arriving.
    //
    // Keyed on `loading` as well as the rows, and that key is the fix: a folder now arrives in
    // chunks, so the effect fires several times per listing and the last of those firings is the
    // one that can be trusted. See RevealScroll.act for why waiting is the answer to both
    // "is it here" and "where is it".
    //
    // In a tree the target is usually INSIDE a branch, so it is looked for in the flattened rows
    // and "settled" waits for the branches as well as the listing. Bug identified here: the
    // search ran over the pane's own listing only, so a jump that opened the tree down to a
    // folder found nothing at the top level, gave up the moment the volume's listing finished,
    // and left the folder it had just opened out of sight. See `TreeRows.foldsRead`.
    LaunchedEffect(s.revealTarget, rows, treeRows, s.loading) {
        val target = s.revealTarget ?: return@LaunchedEffect
        val index = if (tree) TreeRows.revealIndex(treeRows, target) else rows.indexOfFirst { it.path == target }
        val count = if (tree) treeRows.size else rows.size
        val treeRead = !tree || s.cwd == null || TreeRows.foldsRead(s.cwd, s.open, s.branches)
        // A focus put back by Back or Forward scrolls only when its row is off screen: the
        // rail moving is the whole answer otherwise.
        if (s.revealIfHidden && index >= 0) {
            val shown = if (metrics.step.isGrid) gridState.layoutInfo.visibleItemsInfo.map { it.index }
            else listState.layoutInfo.visibleItemsInfo.map { it.index }
            if (index in shown) {
                pane.revealHandled()
                return@LaunchedEffect
            }
        }
        val act = RevealScroll.act(
            settled = !s.loading && treeRead,
            rowCount = count,
            targetIndex = index,
            // A drag on the list means they are reading it themselves now.
            userTookOver = listState.isScrollInProgress || gridState.isScrollInProgress,
        )
        when (act) {
            RevealScroll.Act.WAIT -> return@LaunchedEffect
            RevealScroll.Act.GIVE_UP -> {
                pane.revealHandled()
                return@LaunchedEffect
            }
            RevealScroll.Act.SCROLL_AGAIN, RevealScroll.Act.SCROLL_DONE -> Unit
        }
        if (metrics.step.isGrid) {
            val info = gridState.layoutInfo
            val perRow = info.visibleItemsInfo.count { it.row == 0 }.coerceAtLeast(1)
            val rowsUp = RevealScroll.rowsOnScreen(info.viewportSize.height, info.visibleItemsInfo.firstOrNull()?.size?.height ?: 0)
            gridState.scrollToItem(
                RevealScroll.firstVisibleFor(index, rowsUp * perRow, rows.size)
            )
        } else {
            val info = listState.layoutInfo
            val onScreen = RevealScroll.rowsOnScreen(
                info.viewportSize.height,
                info.visibleItemsInfo.firstOrNull()?.size ?: 0,
            )
            listState.scrollToItem(RevealScroll.firstVisibleFor(index, onScreen, count))
        }
        // Only once the listing is final. Letting it go on the first hit would leave the row
        // wherever the partial list put it when the rest arrived and moved it.
        if (act == RevealScroll.Act.SCROLL_DONE) pane.revealHandled()
    }

    // Pulling past either end re-lists the folder. Both ends: the top is conventional, and
    // the bottom is where you already are when a long listing has gone stale under you.
    // Asked of whichever container is actually on screen. Combining both happens to give the
    // right answer today - an unattached lazy state reports it cannot scroll either way, so
    // the idle one is neutral under an AND - but that is a coincidence of the default, not a
    // statement about which list the finger is on.
    val display = vm.prefs.entry.collectAsState().value

    val grid = metrics.step.isGrid
    val pull = Modifier.edgePullRefresh(
        atTop = { if (grid) !gridState.canScrollBackward else !listState.canScrollBackward },
        atBottom = { if (grid) !gridState.canScrollForward else !listState.canScrollForward },
        onRefresh = { vm.refreshPane(pane) },
    )

    // Room kept clear for the things drawn over this list. A list that fills its box and a
    // bar drawn on top of it are each correct alone and together they hide the last row -
    // scrolling does not help, because at maximum scroll the last row's bottom edge IS the
    // viewport's bottom edge, which is where the floating thing is.
    // The dock is always there on a folder pane, and it grows a paste pill above itself
    // once something is on the clipboard - so the room to keep clear has two sizes, not two
    // states of one thing. See PaneInsets.
    val dock = s.kind == PaneKind.FOLDER && s.cwd != null && !s.selecting
    val padBottom = PaneInsets.bottom(dock, loaded = app.clips.totalItems > 0).dp
    // Zero: the band overlays rather than reserving. See PaneInsets.end for why both of the
    // alternatives were worse.
    val padEnd = PaneInsets.end(app.split != SplitMode.OFF).dp

    // Reach mode's empty top. See `ThumbReach` for why this is a gap and not a reversed list.
    //
    // LATCHED, per folder and per width: it is computed once from the viewport the folder was
    // first drawn in and then held. The viewport changes height under a reader all the time - a
    // selection bar, the now-playing strip, the keyboard - and a gap recomputed from the live
    // height would move every row on each of those. Width is in the key so a rotation, which
    // genuinely changes how much room there is, does get a new one.
    //
    // It is content padding, so it scrolls away with the rows and returns only when the reader
    // scrolls back to the top, or when a new folder starts at the top. Nothing re-applies it.
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var viewportDp by remember { mutableFloatStateOf(0f) }
    val gap = remember(pane.id, s.cwd, metrics.widthDp, reach, viewportDp > 0f) {
        ThumbReach.gapDp(viewportDp, reach)
    }.dp
    val measure = Modifier.onGloballyPositioned { c ->
        if (viewportDp == 0f) viewportDp = c.size.height / density
    }
    // A listing shorter than the screen sits on the bottom edge rather than hanging under the
    // gap with empty space below it - which is the one case where "the first row is low" would
    // otherwise not be true.
    val anchor = if (reach) Arrangement.Bottom else Arrangement.Top

    if (metrics.step.isGrid) {
        // A tile asks for a picture several times the area of a row's and a grid shows three
        // times as many at once, so the pre-render matters more here than in the list.
        val warmCtx = LocalContext.current
        val warmPx = (metrics.step.icon * 2.5f).toInt().coerceIn(48, 320)
        WarmAhead(state = gridState, count = rows.size) { i ->
            val node = rows.getOrNull(i)
            if (node != null && !node.isDir && Thumbnails.canPreview(node.extension)) {
                runCatching {
                    Thumbnails.load(
                        warmCtx, vm.vfs, node.path, node.size, node.mtime, warmPx, ahead = true,
                    )
                }
            }
        }
        LazyVerticalGrid(
            columns = TileGrid.cells(metrics.step.tile!!.dp),
            modifier = Modifier.fillMaxSize().then(measure).then(pull),
            state = gridState,
            verticalArrangement = anchor,
            contentPadding = PaddingValues(
                start = 6.dp,
                top = 6.dp + gap,
                end = 6.dp + padEnd,
                bottom = 6.dp + padBottom,
            ),
        ) {
            items(rows, key = { it.path.toString() }) { node ->
                FileTile(
                    node = node,
                    metrics = metrics,
                    selected = rowSelected(node.path, selection),
                    dropTarget = (app.drag?.over as? DropTarget.Folder)?.path == node.path,
                    modifier = dragModifier(node, pane, vm, side, registry, onGhost, onHeldStill, peekTile, unpeekTile)
                        .then(pickDim(vm, node)),
                    thumbnails = metrics.step.icon >= 20,
                    selecting = s.selecting,
                    onIconClick = { onIconTap(node) },
                    onClick = { rowClick(pane, vm, side, node) },
                    // No long-click here on purpose: the drag detector in `dragModifier` owns
                    // the long press - a peek here with nothing selected, otherwise it selects -
                    // and two long-press detectors on one tile means the loser never runs.
                    onLongClick = {},
                    onDoubleClick = { at -> onDoubleTapRow(node, at) },
                )
            }
        }
    } else {
        // The selection bar takes layout space, so the pane shrinks the moment something is
        // selected - and a list anchors its FIRST visible item, so everything below slides out
        // of view. Selecting the last row therefore hid the row you had just selected.
        //
        // Give the scroll position back the height the viewport lost. Measured rather than
        // assumed from the bar's size: the bar is one line or two depending on what is
        // selected, and a hard-coded height would be wrong half the time.
        var lastViewport by remember { mutableStateOf(0) }
        LaunchedEffect(listState) {
            snapshotFlow { listState.layoutInfo.viewportSize.height }
                .collect { height ->
                    if (height == 0) return@collect
                    val previous = lastViewport
                    lastViewport = height
                    val lost = previous - height
                    // Only a shrink, only a real one, and only when there is somewhere to go -
                    // at the top of a short list there is nothing to compensate with and
                    // scrolling would move content that was not going anywhere.
                    if (previous > 0 && lost > 0 && listState.canScrollForward) {
                        listState.scrollBy(lost.toFloat())
                    }
                }
        }

        // ── what the fling costs, precomputed ──────────────────────────────────────────────
        //
        // Reported as the list being choppy when scrolled fast, and these are the two things
        // that were being done per row per frame rather than once per listing.
        //
        // The KEYS. `VPath.toString()` concatenates a scheme and a path into a new String, and
        // the key lambda is called for every item that enters or leaves the viewport - which
        // during a hard fling is most of the list, repeatedly. That is a few thousand
        // short-lived strings a second handed to the collector, and a collection in the middle
        // of a fling is exactly the stutter being described. Built once per listing instead,
        // where it is one pass over a list that just changed anyway.
        val keys = listKeys
        // And the search hits. The row looked its own hit up with `firstOrNull` over every hit
        // in the result set, TWICE - once for the location and once for the state - so drawing
        // a screenful of a thousand-hit search was a million comparisons per frame.
        val hits = remember(s.search.hits) { s.search.hits.associateBy { it.node.path } }

        // One flat list unless a share is in reach, and then a header per drive. See
        // ResultGroups for why local drives are never split from each other.
        val reachNow = ReachLadder.resolve(s.search.reach, s.cwd, pane.reachDrives)
        val groups = if (s.search.active && !tree) {
            remember(rows, pane.reachDrives, reachNow, s.search.running, s.search.unreachable, s.search.walked) {
                ResultGroups.of(
                    rows, pane.reachDrives, reachNow, s.search.running, s.search.unreachable,
                    walked = s.search.walked.mapKeys { (root, _) -> dev.niccc2007.filet.index.VolumeKey(root) },
                )
            }
        } else {
            null
        }

        // One description of a result row, used by the flat list and the grouped one alike,
        // so the two cannot drift into rows that behave differently depending on whether a
        // share happened to be in reach.
        val hitRow: @Composable (VNode) -> Unit = { node ->
            val hit = hits[node.path]
            SearchResultRow(
                node = node,
                where = hit?.where ?: "",
                state = hit?.state ?: dev.niccc2007.filet.index.HitState.AVAILABLE,
                spans = hit?.spans.orEmpty(),
                metrics = metrics,
                selected = rowSelected(node.path, selection),
                onClick = { rowClick(pane, vm, side, node) },
                // Bug identified: a long press here only selected the row, so the one
                // action a search result needs more than any other - go to where this
                // actually is - was reachable from every list in the app except the
                // one whose whole point is that the file is somewhere else.
                onLongClick = { searchMenuFor = node },
            )
        }

        // What a pick is about to put HERE, drawn at the top of this folder's own listing.
        // Only in the pick's own pane: the preview belongs to the folder being chosen, and
        // showing it in the pane somebody picked FROM would describe a different place.
        val livePick = vm.inAppPick.collectAsState().value
        // During a pick the ghosts belong to the pick's own pane. For an extraction there is
        // no pick at all and they belong to the pane that is about to receive them, which is
        // the focused one.
        val preview = vm.pickPreview.collectAsState().value
            .takeIf { if (livePick != null) livePick.pane === pane else pane === vm.focusedPane() }
        // Scrolled to, the moment they appear. They are inserted at the TOP of the listing, and
        // a listing somebody has scrolled down is a listing where the whole preview lands off
        // screen - so the one thing the preview exists to show would need hunting for.
        LaunchedEffect(preview != null) {
            if (preview != null) runCatching { listState.animateScrollToItem(0) }
        }

        // The rows just outside the screen, warmed once the list stops moving. This is the LOW
        // tier of the same queue the visible rows use, so a pre-render can never delay a row
        // somebody is looking at - see LoadQueue. Skipped at the two compact steps, where the
        // glyph is a few pixels wide and a decoded picture would not be visible in it.
        if (metrics.step.icon >= 20) {
            val warmCtx = LocalContext.current
            val warmPx = (metrics.step.icon * 2.5f).toInt().coerceIn(48, 320)
            val ghosts = if (preview == null) 0 else {
                preview.entries.size + if (preview.extra > 0) 1 else 0
            }
            WarmAhead(
                state = listState,
                count = if (tree) treeRows.size else rows.size,
                offset = ghosts,
            ) { i ->
                val node = if (tree) treeRows.getOrNull(i)?.node else rows.getOrNull(i)
                if (node != null && !node.isDir && Thumbnails.canPreview(node.extension)) {
                    runCatching {
                        Thumbnails.load(
                            warmCtx, vm.vfs, node.path, node.size, node.mtime, warmPx,
                            ahead = true,
                        )
                    }
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().then(measure).then(pull),
            state = listState,
            verticalArrangement = anchor,
            // The pick card's height is added to the bottom inset rather than drawn over the
            // list. Reported as the last row being barely tappable while choosing a
            // destination - the card floats above the listing, so without this the rows it
            // covers simply cannot be reached.
            //
            // Padding rather than a draggable card. A panel somebody has to move is a control
            // to discover, a place to park it uselessly, and a gesture arbitrating against
            // every tap in the list beneath it; the actual problem is only that the list did
            // not know something was sitting on it.
            contentPadding = PaddingValues(
                top = gap, end = padEnd, bottom = padBottom + LocalPickInset.current,
            ),
        ) {
            if (preview != null) {
                items(preview.entries.size, key = { "ghost:" + preview.entries[it].name }) { g ->
                    GhostRow(preview.entries[g])
                }
                if (preview.extra > 0) {
                    item(key = "ghost:more") {
                        Text(
                            "and ${preview.extra} more",
                            fontSize = 10.5.sp,
                            color = Filet.colors.accent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 56.dp, top = 2.dp, bottom = 8.dp),
                        )
                    }
                }
            }
            if (groups != null) {
                for (g in groups) {
                    // Sticky, so which drive a result belongs to is answerable at every scroll
                    // position without going back up for it.
                    stickyHeader(key = "drive:" + g.key) { DriveHeader(g) }
                    items(
                        count = g.nodes.size,
                        key = { j -> "hit:" + g.key + ":" + g.nodes[j].path },
                    ) { j -> hitRow(g.nodes[j]) }
                }
            }
            items(
                // Empty while grouped: the groups above have drawn every row already.
                count = if (groups != null) 0 else if (tree) treeRows.size else rows.size,
                // A tree repeats a path only as a "still reading" placeholder under the
                // folder it belongs to, so the depth goes in the key as well.
                key = { i -> keys.getOrElse(i) { i } },
            ) { i ->
                val row = if (tree) treeRows[i] else null
                val node = row?.node ?: rows[i]
                // Only asked in a tree, and only of folders: everywhere else the answer is
                // not used and the read would be waste.
                val emptyFolder = tree && node.isDir &&
                    rememberFolderPeek(vm.folderPeek, node.path, node.mtime, true).value.empty
                // What this row opens into in a tree: a folder, or an archive's mount.
                val branch = if (tree) TreeBranch.keyOf(node) else null
                if (row?.loading == true) {
                    BranchLoading(row.depth, steps, dropZone(registry, "branch-${row.node.path}", branch ?: row.node.path))
                } else if (row?.empty == true) {
                    BranchEmpty(row.depth, steps, dropZone(registry, "branch-${row.node.path}", branch ?: row.node.path))
                } else if (s.search.active) {
                    hitRow(node)
                } else {
                    FileRow(
                        node = node,
                        metrics = metrics,
                        selected = rowSelected(node.path, selection),
                        included = rowIncluded(node.path, selection),
                        dropTarget = (app.drag?.over as? DropTarget.Folder)?.path == (branch ?: node.path),
                        modifier = dragModifier(
                            node, pane, vm, side, registry, onGhost, onHeldStill,
                            into = if (row != null) treeHomes.getOrNull(i) else node.path.takeIf { node.isDir },
                        ).then(pickDim(vm, node)),
                        // Below ~20 dp the glyph is a few pixels across and a photo in it is
                        // an unreadable smear; the icon says more at that size.
                        thumbnails = metrics.step.icon >= 20,
                        display = display,
                        // Only folders, and only when something on the row asks for the figure.
                        // Asking for a file's child count would be a directory read per file in
                        // the list.
                        //
                        // Bug identified here: the gate was `display.measure`, the old right-hand
                        // column's own switch. So "Item count" on the line under the name was
                        // fetched only while that column happened to be on - a part somebody had
                        // deliberately added rendered empty for a reason with no visible
                        // connection to it, and nothing failed. `RowDetails.needsCount` asks the
                        // whole configuration instead, which is the only thing that can answer.
                        count = rememberFolderCount(
                            vfs = vm.vfs,
                            path = node.path,
                            mtime = node.mtime,
                            enabled = node.isDir && RowDetails.needsCount(display),
                        ),
                        treeRow = tree,
                        steps = steps,
                        edge = frame?.let {
                            when {
                                i !in it -> null
                                it.first == it.last -> ActiveEdge.ONLY
                                i == it.first -> ActiveEdge.TOP
                                i == it.last -> ActiveEdge.BOTTOM
                                else -> ActiveEdge.MIDDLE
                            }
                        },
                        depth = row?.depth ?: 0,
                        spines = row?.spines.orEmpty(),
                        lastOfSiblings = row?.last == true,
                        // No arrow on a folder with nothing in it.
                        //
                        // The listing has already been read for the icon's own preview, so
                        // this costs nothing extra - and an arrow that cannot do anything is
                        // worse here than usual: you tap it, nothing moves, and you cannot
                        // tell whether the folder is empty or the app is broken.
                        openInPlace = if (branch != null && !emptyFolder) {
                            branch in s.open
                        } else {
                            null
                        },
                        onOpenInPlace = if (branch != null && !emptyFolder) {
                            // Decided by the pane when tapped, not here: a lambda that read
                            // `s.open` was a new lambda every time the state moved, and a new
                            // lambda is a row that cannot skip - see `flipBranch`.
                            ({ pane.flipBranch(branch) })
                        } else {
                            null
                        },
                        // In a tree, a tap on a row FOCUSES its branch - see `treeRowClick`.
                        onClick = if (tree) {
                            ({ treeRowClick(pane, vm, side, node) })
                        } else {
                            ({ rowClick(pane, vm, side, node) })
                        },
                        // See the tile above: the drag detector owns the long press.
                        onLongClick = {},
                        selecting = s.selecting,
                        onIconClick = { onIconTap(node) },
                        onDoubleClick = { at -> onDoubleTapRow(node, at) },
                    )
                }
            }
        }
    }

    if (gap > 0.dp) {
        ReachTitle(
            title = s.title,
            sub = countLabel(s),
            height = gap,
            scrolled = {
                if (grid) {
                    if (gridState.firstVisibleItemIndex > 0) null else gridState.firstVisibleItemScrollOffset
                } else {
                    if (listState.firstVisibleItemIndex > 0) null else listState.firstVisibleItemScrollOffset
                }
            },
        )
    }

    searchMenuFor?.let { node ->
        dev.niccc2007.filet.home.HomeRowSheet(
            node = node,
            onDismiss = { searchMenuFor = null },
            // The reason this menu exists. A result is by definition somewhere other than
            // where you are standing.
            onReveal = { searchMenuFor = null; vm.revealInFolder(node) },
            onOpen = { searchMenuFor = null; rowClick(pane, vm, side, node) },
            onShare = { searchMenuFor = null; vm.share(node) },
            onBookmark = { searchMenuFor = null; vm.bookmarkOne(node) },
            onShortcut = { searchMenuFor = null; vm.shortcutOne(node) },
            // No list to be removed from: this is a match, not a kept entry.
            onForget = null,
        )
    }
}

/**
 * Everything a pane can show that is not a folder listing.
 *
 * It takes the pane's own width and publishes it, because these pages were all written as
 * full-screen screens and in a split they are not: see `LocalPaneWidth` for what that was
 * doing to Settings and Home. The folder listing does not need it - `PaneMetrics` has
 * measured against the pane's width since the beginning - which is exactly why this was the
 * half of the app that stayed broken.
 */
@Composable
private fun SpecialBody(pane: PaneController, vm: BrowserViewModel, s: PaneState, widthDp: Int) {
    // The same pull, and the same indicator, the folder list has - once, here, for every tab,
    // rather than inside each screen. Whatever list a screen draws dispatches its leftover
    // scroll up through this box, and only a delta the list could NOT consume reaches it, so
    // "at the end" is already decided by the list itself: both edge questions are answered by
    // the delta's direction. A screen that does not scroll never sends any, and never pulls.
    val pull = if (pullsToRefresh(s.kind)) {
        Modifier.edgePullRefresh(
            atTop = { true },
            atBottom = { true },
            onRefresh = { vm.refreshPane(pane) },
        )
    } else {
        Modifier
    }
    CompositionLocalProvider(
        dev.niccc2007.filet.ui.LocalPaneWidth provides widthDp,
        // What this pane's search box holds, for the screens that narrow their own rows.
        dev.niccc2007.filet.ui.LocalPaneQuery provides if (s.search.open) s.search.query.trim() else "",
        // Where every screen in this tab keeps its scroll and its own view. See PaneMemory.
        LocalPaneMemory provides pane,
    ) {
        Box(Modifier.fillMaxSize().then(pull)) { SpecialBodyContent(pane, vm, s) }
    }
}

@Composable
private fun SpecialBodyContent(pane: PaneController, vm: BrowserViewModel, s: PaneState) {
    when (s.kind) {
        // Home is the overview unless a folder was set as Home and still exists. Resolved on
        // every composition rather than once at launch, because the card can be removed or the
        // folder deleted while the app is open.
        PaneKind.HOME -> when (val target = vm.homeResolution()) {
            is HomeTarget.Resolution.Overview -> HomeOverview(vm, pane)
            is HomeTarget.Resolution.Folder -> {
                // Arriving, not walking: a folder set as Home is reached from outside the pane, so
                // with the tree on it opens the volume's trunk down to it rather than becoming
                // the tree's root. push = false as before - pushing would put the Home kind
                // behind it, and Back would land on Home and be redirected straight here again.
                LaunchedEffect(target.path) { pane.jumpTo(target.path, push = false) }
                HomeOverview(vm, pane)
            }
        }
        PaneKind.ABOUT -> AboutPage(vm)
        PaneKind.SETTINGS -> SettingsPage(vm)
        PaneKind.BOOKMARKS -> BookmarksBody(vm, pane)
        PaneKind.PLAYLISTS -> dev.niccc2007.filet.playback.PlaylistsScreen(vm, pane)
        PaneKind.RESUME -> dev.niccc2007.filet.playback.ResumeScreen(vm)
        PaneKind.CATEGORY -> {
            val category = s.category
            if (category == null) {
                // Cannot happen through `openCategory`, which always sets one - but a pane
                // restored from a stale tab record could arrive without it, and a null here
                // must not be a crash on a screen somebody is looking at.
                pane.openHome()
            } else {
                dev.niccc2007.filet.home.CategoryScreen(vm, category)
            }
        }
        PaneKind.RECENT -> RecentBody(vm, pane)
        PaneKind.HISTORY -> dev.niccc2007.filet.home.FileHistoryScreen(vm, pane)
        PaneKind.SCRIPTS -> dev.niccc2007.filet.script.ScriptsScreen(vm)
        PaneKind.APPS -> dev.niccc2007.filet.apk.AppsScreen(vm, pane)
        PaneKind.NEARBY -> dev.niccc2007.filet.nearby.NearbyScreen(vm)
        PaneKind.REMOTES -> dev.niccc2007.filet.remotes.RemotesScreen(vm)
        PaneKind.SHORTCUTS -> dev.niccc2007.filet.shortcuts.ShortcutsScreen(vm)
        PaneKind.SCAN -> dev.niccc2007.filet.qr.ScanScreen(vm, pane)
        // Tapping a finished job goes where its work landed - the folder an archive was
        // extracted into, the archive that was written. See `JobLedger.Job.opened`.
        PaneKind.ACTIVITY -> dev.niccc2007.filet.jobs.ActivityBody(
            vm.ledger,
            remember(vm) {
                dev.niccc2007.filet.jobs.ActivityActions(
                    go = vm::openJobDestination,
                    open = vm::openJobResult,
                    showInFolder = vm::showJobInFolder,
                    copy = { vm.copyText(it, "Details copied") },
                )
            },
        )
        else -> EmptyNote("Nothing here yet", Modifier.fillMaxSize())
    }
}

/**
 * What an empty result list should actually say.
 *
 * The old text was one sentence for every case, and in the case that matters it was wrong: at
 * the widest reach with a half-built index, it said "Try Subfolders or Whole device" - advice
 * to do the thing you are already doing, on a search that returned nothing for a reason the app
 * knew and did not mention.
 *
 * `lastRunAt` is only stamped after a crawl that RAN TO COMPLETION, so it is exactly the
 * "has the index ever seen the whole device" flag this needs.
 *
 * @param wider the label of the next rung up, or null at the top of the ladder. Named rather
 *   than hardcoded, because the rung above "this folder" is a different drive on every device
 *   and advice that names the wrong one is worse than no advice.
 */
/** What the drives chip says when it is the reach in force. */
private fun driveCount(reach: Reach): String {
    val n = reach.volumes()?.size ?: return "Drives…"
    return if (n == 1) "1 drive" else "$n drives"
}

private fun emptySearchNote(reach: Reach, wider: String?, index: IndexStatus): String = when {
    // Every drive this reach named has gone - a card pulled, a share deleted. Saying "no match"
    // would blame the query for it, which is the §13.3 requirement that a narrowed reach is
    // visible rather than silent.
    reach.isEmpty -> "That drive is not here any more.\nPick a wider reach above."
    reach == Reach.Folder && wider != null -> "No match here.\nTry $wider."
    !index.enabled ->
        "No match.\nSearch is walking the filesystem — the index is off in Settings."
    index.running ->
        "No match yet.\nThe index is still building; results appear as it goes."
    index.lastRunAt == 0L ->
        "No match.\nThe index hasn't finished a full pass yet.\nSettings ▸ Search ▸ Build index."
    wider != null -> "No match.\nTry $wider."
    else -> "No match."
}

private fun rowClick(pane: PaneController, vm: BrowserViewModel, side: Side, node: VNode) {
    vm.focusSide(side)
    if (pane.state.value.selecting) pane.toggleSelect(node) else pane.open(node)
}

/**
 * A tap on a tree row: stand in its branch. A folder row is focused and nothing else - it is
 * neither opened nor closed, which is the chevron's job alone. A file row focuses the folder it
 * is in and then opens as it always did.
 *
 * Bug identified here: the tap on a folder row opened or CLOSED it, and opening was the only
 * thing that moved the focus. So pointing New folder or Paste at a folder that was already open
 * meant tapping it shut and open again, and tapping a folder just to aim at it folded away
 * everything that was being looked at inside it.
 *
 * While something is selected, a tap is about the selection, here as everywhere: it toggles the
 * row and moves nothing.
 */
private fun treeRowClick(pane: PaneController, vm: BrowserViewModel, side: Side, node: VNode) {
    if (pane.state.value.selecting) {
        rowClick(pane, vm, side, node)
        return
    }
    vm.focusSide(side)
    // A tap on the folder already focused opens or closes it, and keeps it focused: closing a
    // branch otherwise hands the focus back to the root, and the next tap would only re-focus.
    // An archive is a folder here, so the same: a tap stands in it and the next one opens it.
    val branch = TreeBranch.keyOf(node)
    if (branch != null && pane.state.value.active == branch) {
        pane.flipBranch(branch)
        pane.focusPath(branch, record = false)
        return
    }
    pane.focusRow(node)
    if (branch == null) pane.open(node)
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
    onHeldStill: (VNode, Offset, LongPressAction) -> Unit,
    /**
     * A grid tile's peek: a hold with nothing selected lifts the tile (see `TilePeek`) instead of
     * selecting it. Null for list rows, whose hold still selects. The finger moving after the
     * peek has opened puts it away and becomes the drag it always was, so dragging out of a grid
     * still works; with a selection running, a hold does exactly what it did.
     */
    peek: ((VNode) -> Unit)? = null,
    unpeek: () -> Unit = {},
    /**
     * The folder a drop on this row goes into, or null for none. A folder row is its own; in a
     * tree a file row is its branch's, which is worked out by `TreeDrop`.
     */
    into: VPath? = node.path.takeIf { node.isDir },
): Modifier {
    // Read live. A row's `pointerInput` is keyed on `node.path`, so scrolling a list recycles
    // the composable and hands it new callbacks while the launched block keeps the old ones -
    // the same freeze that killed pinch-to-zoom and tab dragging this round. Caught by
    // tools/check-deadswitch.mjs rather than by anybody noticing a misplaced drag ghost.
    val liveGhost = rememberUpdatedState(onGhost)
    val liveHeldStill = rememberUpdatedState(onHeldStill)
    val livePeek = rememberUpdatedState(peek)
    val liveUnpeek = rememberUpdatedState(unpeek)
    var peeking by remember(node.path) { mutableStateOf(false) }
    var origin by remember(node.path) { mutableStateOf(Offset.Zero) }
    var pointer by remember(node.path) { mutableStateOf(Offset.Zero) }
    /**
     * How far the finger has travelled since the long press, in pixels.
     *
     * Not a boolean any more. `moved` used to be set true by the FIRST onDrag callback, and
     * `detectDragGesturesAfterLongPress` fires that for a one-pixel twitch - which a finger
     * resting on glass always produces. So a long press that never went anywhere counted as
     * a drag, the drop landed on the folder the file was already in, and the answer to
     * press-and-hold was "Make a copy here?".
     *
     * A distance with a threshold tells the two apart the way a person means them: holding
     * still is the menu, going somewhere is a drag.
     */
    var travelled by remember(node.path) { mutableFloatStateOf(0f) }
    var pressAction by remember(node.path) { mutableStateOf(LongPressAction.MENU) }
    val rowKey = "row-${node.path}"

    DisposableEffect(rowKey) { onDispose { registry.remove(rowKey) } }

    return Modifier
        .onGloballyPositioned { c ->
            origin = c.positionInRoot()
            if (into != null) {
                registry.put(
                    key = rowKey,
                    rect = Rect(origin, Size(c.size.width.toFloat(), c.size.height.toFloat())),
                    target = DropTarget.Folder(into),
                    depth = 1,
                )
            } else {
                // A recycled row whose node no longer takes drops must not keep the last one's.
                registry.remove(rowKey)
            }
        }
        .pointerInput(node.path) {
            detectDragGesturesAfterLongPress(
                onDragStart = { local ->
                    travelled = 0f
                    pointer = origin + local
                    vm.focusSide(side)
                    val current = pane.state.value
                    // What a long press means depends on what is already selected. Decided by
                    // `longPressAction` rather than inline, because a press that quietly does
                    // the other thing reads as the app ignoring you. Kept for the release,
                    // which must judge by what the press FOUND rather than what it changed.
                    pressAction = longPressAction(current.selected.size, node.path in current.selected)
                    val lift = livePeek.value
                    if (lift != null && pressAction == LongPressAction.MENU) {
                        // A grid tile with nothing selected: the peek, and no drag yet.
                        peeking = true
                        lift(node)
                    } else {
                        when (pressAction) {
                            LongPressAction.EXTEND -> pane.extendSelectionTo(node)
                            LongPressAction.MENU -> pane.selectOnly(node)
                            LongPressAction.MENU_FOR_SELECTION -> Unit
                        }
                        val items = pane.selectedNodes().ifEmpty { listOf(node) }
                        vm.beginDrag(items, pane.id)
                        liveGhost.value(pointer)
                    }
                },
                onDrag = { change, delta ->
                    change.consume()
                    travelled += kotlin.math.hypot(delta.x, delta.y)
                    pointer += delta
                    if (peeking) {
                        // Moving after the peek opened is a drag after all: put the peek away
                        // and pick the tile up, the way the hold did before it peeked.
                        if (travelled > viewConfiguration.touchSlop * 2f) {
                            peeking = false
                            liveUnpeek.value()
                            pane.selectOnly(node)
                            vm.beginDrag(listOf(node), pane.id)
                            liveGhost.value(pointer)
                        }
                    } else {
                        liveGhost.value(pointer)
                        vm.dragOver(registry.hitTest(pointer))
                    }
                },
                onDragEnd = {
                    // Lifted without going anywhere: the peek stays up with its menu.
                    if (peeking) {
                        peeking = false
                        return@detectDragGesturesAfterLongPress
                    }
                    liveGhost.value(null)
                    // Move or copy is the plan's call, not the caller's - same volume moves,
                    // a different one copies, exactly as a desktop file manager does.
                    // Measured against a real distance rather than against any movement at
                    // all. Twice the touch slop: far enough that a resting finger never
                    // reaches it, near enough that a deliberate short drag onto the folder
                    // beside this one still counts.
                    if (travelled > viewConfiguration.touchSlop * 2f) {
                        vm.endDrag()
                    } else {
                        // Long-pressed and held STILL. That is the right-click gesture, and it
                        // is free: the drag detector already had to tell the two apart to know
                        // whether a drop happened. Press and move drags, press and hold opens
                        // the menu - which is what press-and-hold means everywhere else on a
                        // touchscreen.
                        vm.cancelDrag()
                        liveHeldStill.value(node, pointer, pressAction)
                    }
                },
                onDragCancel = {
                    if (peeking) {
                        peeking = false
                    } else {
                        liveGhost.value(null)
                        vm.cancelDrag()
                    }
                },
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
            apk = FiletIcons.Apk, cog = FiletIcons.Cog,
            audio = FiletIcons.Audio, rows = FiletIcons.Rows,
            folder = FiletIcons.FolderOpen, invert = FiletIcons.Check,
        ),
        on = SelectionCallbacks(
            copy = { vm.copySelection() },
            move = { vm.cutSelection() },
            send = { vm.shareSelectionToApps() },
            delete = { vm.confirmDelete() },
            compress = { vm.askCompress() },
            rename = { vm.renameSelection() },
            openWith = { vm.openWithSelection() },
            details = { vm.detailsForSelection() },
            bookmark = { vm.bookmarkSelection() },
            nearby = { vm.shareSelectionNearby() },
            shortcut = { vm.shortcutSelection() },
            install = { vm.installSelection() },
            metadata = { vm.metadataForSelection() },
            extractHere = { vm.extractSelection() },
            extractToOtherPane = { vm.extractSelectionToOtherPane() },
            addToPlaylist = { vm.addToPlaylistSelection() },
            makeBeatMaps = { vm.makeBeatMapsForSelection() },
            enqueue = { vm.enqueueSelection() },
            announce = { vm.announceSelectionToMedia() },
            copyPath = { vm.copyPathsOfSelection() },
            invert = { pane.invertSelection() },
            reveal = { vm.revealSelection() },
        ),
        archive = vm.selectionIsArchive(),
        otherPane = vm.isSplit(),
        picking = vm.pickingAnything,
        installable = vm.selectionIsInstallable(),
        hasMetadata = vm.selectionHasMetadata(),
        // Both of these were missing here while the selection bar had them, so "Add to
        // gallery" - which exists only when it applies - never appeared in this menu.
        announceable = vm.selectionIsOnThisDevice(),
        shape = SelectionShape.of(pane.selectedNodes()),
        elsewhere = vm.selectionIsElsewhere(),
    )

    // "Select" is gone, and it had to go for two reasons rather than one. It was asked to be
    // removed, and it was also a dead switch: `run` was an empty lambda, so the row closed the
    // menu and did nothing else. The comment that used to sit here claimed it "keeps the row
    // selected instead of clearing it on dismiss" - which the long press had already done, so
    // the row described a side effect of doing nothing (R1).
    // Copy path was an extra here, written out beside the list; it is in the list now, so the
    // selection bar has it too.
    val extras = emptyList<SelectionAction>()
    // The icon row follows the selection bar once that has been arranged - see
    // SelectionBarConfig.contextQuick for why one order, not two.
    val storedBar by vm.prefs.selectionBar.collectAsState()
    val (quick, rest) = splitForContextMenu(base, SelectionBarConfig.contextQuick(storedBar))

    ContextMenu(
        title = if (state.selected.size > 1) "${state.selected.size} items" else node.name,
        subtitle = if (state.selected.size > 1) "" else node.path.path.substringBeforeLast('/'),
        // The facts line: what this is and how big, which is what a person checks before
        // pressing Delete. On a selection it is the count, because that is the only number
        // that matters there.
        meta = if (state.selected.size > 1) {
            "${state.selected.size} selected"
        } else if (node.isDir) {
            "Folder"
        } else {
            humanSize(node.size)
        },
        thumb = if (state.selected.size > 1) null else node,
        quick = quick,
        rest = extras + rest,
        onDismiss = onDismiss,
        onBlocked = vm::toast,
        offsetX = at.x.toInt(),
        offsetY = at.y.toInt(),
    )
}

/**
 * A row that takes a drop without being a file: the notes under an opened branch, which drop
 * into that branch. See `TreeDrop`.
 */
@Composable
private fun dropZone(registry: DropRegistry, key: String, into: VPath): Modifier {
    DisposableEffect(key) { onDispose { registry.remove(key) } }
    return Modifier.onGloballyPositioned { c ->
        registry.put(
            key = key,
            rect = Rect(c.positionInRoot(), Size(c.size.width.toFloat(), c.size.height.toFloat())),
            target = DropTarget.Folder(into),
            depth = 1,
        )
    }
}

/** A branch that has been opened and is still being read. */
@Composable
private fun BranchLoading(depth: Int, steps: TreeScale.Steps, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(
                start = (steps.startDp + depth * steps.indentDp).dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(26.dp))
        Text("Reading\u2026", fontSize = 11.sp, color = Filet.colors.fg3)
    }
}

/**
 * A branch that was opened and holds nothing.
 *
 * ## Why this row exists at all
 *
 * Nothing used to be drawn here, on the reasoning that the chevron turning is acknowledgement
 * enough. But a folder known to be empty no longer HAS a chevron, so the only way to reach
 * this state is a folder whose child count was stale or whose read came back empty - which is
 * precisely the case where somebody needs to be told something, rather than left looking at a
 * branch that opened and did nothing.
 *
 * Deliberately the same shape as [BranchLoading]: same indent, same size, same grey. The two
 * are the same kind of statement about a branch, and a reader who has seen one should
 * recognise the other without reading it.
 */
@Composable
private fun BranchEmpty(depth: Int, steps: TreeScale.Steps, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(
                start = (steps.startDp + depth * steps.indentDp).dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(26.dp))
        Text("Nothing in here", fontSize = 11.sp, color = Filet.colors.fg3)
    }
}

/**
 * The deepest view step a tree will use: COMFORTABLE.
 *
 * Past it are the grids, which have no gutter for an indent and no line to draw a guide
 * along - a tree in a tile layout is just tiles. It is an ordinal rather than the enum itself
 * so adding a step between the lists cannot silently move the ceiling into a grid.
 */
private const val TREE_MAX_STEP = 2

/**
 * Where each tab's listing was scrolled to while it is not on screen.
 *
 * Process-lifetime and keyed by pane id. Pane ids are never reused within a process, so a
 * closed tab's entry is a few bytes that nothing will ask for again. Not written to disk: a
 * tab restored after the process died lands at the top of its folder, with its folds open.
 */
/**
 * The list's row keys, in order. One function, because the list draws with them and a scroll
 * position is put back by them, and two copies of the formula would drift.
 *
 * A tree repeats a path only as a "still reading" placeholder under the folder it belongs to, so
 * the depth goes in the key as well.
 */
private fun rowKeys(tree: Boolean, rows: List<VNode>, treeRows: List<TreeRow>): List<String> =
    if (tree) {
        treeRows.map { "${it.node.path}@${it.depth}${if (it.loading) "?" else ""}" }
    } else {
        rows.map { it.path.toString() }
    }

/** The first row on screen, named by its key so it can be found again in a list that moved. */
private fun markOf(state: androidx.compose.foundation.lazy.LazyListState): ScrollMemory.Mark {
    val i = state.firstVisibleItemIndex
    val key = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }?.key as? String
    return ScrollMemory.Mark(i, state.firstVisibleItemScrollOffset, key)
}

private fun markOf(state: androidx.compose.foundation.lazy.grid.LazyGridState): ScrollMemory.Mark {
    val i = state.firstVisibleItemIndex
    val key = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }?.key as? String
    return ScrollMemory.Mark(i, state.firstVisibleItemScrollOffset, key)
}

/**
 * The folder's name, large, in the empty top that reach mode leaves.
 *
 * Without it the gap reads as a layout that failed to fill the screen. With it the gap reads as
 * a header, which is what One UI's version is. It moves up with the rows as they scroll and is
 * gone once the first row has left the screen. It takes no input, so a touch in the gap goes
 * through to the list behind it.
 *
 * @param scrolled how far the first row has scrolled, or null once it has scrolled out of view.
 *   A lambda so it is read in the draw phase and the title does not recompose every frame.
 */
@Composable
private fun ReachTitle(
    title: String,
    sub: String,
    height: androidx.compose.ui.unit.Dp,
    scrolled: () -> Int?,
) {
    val colors = Filet.colors
    Column(
        Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                val by = scrolled()
                alpha = if (by == null) 0f else (1f - by / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                translationY = -(by ?: 0).toFloat()
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        // The folder's name, so never dotted. See ListName.
        ListNameText(
            title,
            MaterialTheme.colorScheme.onSurface,
            startSp = 26f,
            lines = 2,
            isDir = true,
            fontWeight = FontWeight.SemiBold,
        )
        if (sub.isNotEmpty()) {
            Text(sub, fontSize = 12.sp, color = colors.fg3)
        }
    }
}

/**
 * A file a live pick will not take, drawn faded rather than left out.
 *
 * Hidden, a picture in a format the pick refuses reads as Filet being unable to see it; faded,
 * it is plainly there and plainly not this. A tap on it still reaches `openNode`, which says why.
 * Read per row rather than collected: a pick always lists in a fresh tab, so the rows are
 * composed after the pick exists, and an external pick is set before the first frame.
 */
private fun pickDim(vm: BrowserViewModel, node: VNode): Modifier =
    if (vm.pickRowState(node) == dev.niccc2007.filet.pick.PickSpec.RowState.DIMMED) {
        Modifier.alpha(0.38f)
    } else {
        Modifier
    }

/**
 * Whether one row is selected, as a read scoped to that row.
 *
 * A derived boolean per row: the set changes on every tap, but each row's answer changes only for
 * the row that was tapped (or the rows a range or select-all touched), and `derivedStateOf` only
 * invalidates a reader when its answer changes. So one tap recomposes one row.
 *
 * A file taken by the live pick counts as selected HERE, in the one place the listing asks, so
 * the row that was tapped to answer a pick looks the same as a row tapped to select it. Those
 * were the same set until the pick's chosen list moved off the pane - see [LocalPickChosen] -
 * and the point of saying it here is that nothing downstream has to know which of the two it is
 * drawing.
 */
/** Taken in by a selected folder above it. See `TreeSelection`. */
@Composable
private fun rowIncluded(path: dev.niccc2007.filet.vfs.VPath, selection: State<Set<dev.niccc2007.filet.vfs.VPath>>): Boolean {
    val included by remember(path, selection) {
        derivedStateOf { TreeSelection.includedBy(selection.value, path) != null }
    }
    return included
}

@Composable
private fun rowSelected(path: dev.niccc2007.filet.vfs.VPath, selection: State<Set<dev.niccc2007.filet.vfs.VPath>>): Boolean {
    val selected by remember(path, selection) { derivedStateOf { path in selection.value } }
    return selected || isChosenForPick(path)
}

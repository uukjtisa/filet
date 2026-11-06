package dev.niccc2007.filet.browser

import androidx.compose.ui.draw.clipToBounds
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.niccc2007.filet.data.TabSize
import dev.niccc2007.filet.data.ViewStep
import dev.niccc2007.filet.ui.HScroll
import dev.niccc2007.filet.ui.Motion
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgAction
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Below this, the rail is a drawer rather than a permanent column. */
private const val RAIL_AT_DP = 720

/** A pane narrower than this stops being a pane (mock round 5). */
private const val MIN_PANE_DP = 132

/**
 * The browser, with the pick's chosen set published around all of it.
 *
 * The provider is here rather than in the pane because a pick is answerable from anywhere a
 * file can be seen - Home, a category, the gallery, the history screen, a dialogue - and every
 * one of those is drawn somewhere under this. See [LocalPickChosen].
 */
@Composable
fun BrowserScreen(vm: BrowserViewModel) {
    val paths = remember(vm) {
        vm.pickChosen.map { chosen -> chosen.map { it.path }.toSet() }
    }.collectAsState(emptySet())
    androidx.compose.runtime.CompositionLocalProvider(LocalPickChosen provides paths) {
        BrowserBody(vm)
    }
}

@Composable
private fun BrowserBody(vm: BrowserViewModel) {
    val app by vm.state.collectAsState()
    val tabs by vm.tabs.collectAsState()
    val colors = Filet.colors
    val registry = remember { DropRegistry() }
    var ghost by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(Unit) { vm.start() }

    val paneA = tabs.getOrNull(app.activeA)
    val paneB = tabs.getOrNull(app.activeB)
    // Once in the whole app's life. It exists because "Home is not what it was" is alarming
    // and the way back is not discoverable, so the one thing it must do is name the place.
    val homeHint by vm.homeHint.collectAsState()
    if (homeHint) {
        Dlg(onDismiss = { vm.dismissHomeHint() }) {
            DlgHeader(FiletIcons.Home, HomeTarget.REMINDER_TITLE, onClose = { vm.dismissHomeHint() })
            DlgBody {
                Text(
                    HomeTarget.REMINDER_BODY,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Filet.colors.fg2,
                )
            }
            DlgFooter {
                DlgSpacer()
                DlgBtn("Got it", kind = BtnKind.PRIMARY) { vm.dismissHomeHint() }
            }
        }
    }

    val active = if (app.focused == Side.A || app.split == SplitMode.OFF) paneA else paneB
    val activeState = active?.state?.collectAsState()?.value

    BackHandler(enabled = true) {
        when {
            app.switcherOpen -> vm.openSwitcher(false)
            // A screen INSIDE the pane that has a level to close gets the press before the
            // pane's own history does. See `BrowserViewModel.claimInnerBack` - without this,
            // backing out of an open playlist left the Playlists tab altogether.
            vm.consumeInnerBack() -> Unit
            activeState?.search?.open == true -> active.openSearch(false)
            activeState?.selecting == true -> active.clearSelection()
            // BEFORE the history, and before going up. In a tree, the innermost thing a back
            // press can undo is an open branch - issue #12 - and a tree four deep otherwise
            // takes four taps on four chevrons to put away while the one gesture a thumb
            // reaches for without looking leaves the folder entirely.
            // The focus a tap moved comes back first - the smallest undo there is. See
            // PaneController.stepFocusBack.
            active?.stepFocusBack() == true -> Unit
            active?.collapseDeepest() == true -> Unit
            active?.goBack() == true -> Unit
            // push = false: see PaneController.goUp. Pushing here makes back and up fight each
            // other and the screen never closes.
            active?.goUp(push = false) == true -> Unit
            else -> vm.requestExit()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth.value >= RAIL_AT_DP
        val pushed = app.switcherOpen
        val windowDp = maxWidth.value

        // Each surface's width as drawn: the live one while its edge is being dragged, else the
        // stored one, clamped to this window either way. See SideWidth.
        val drawerStored by vm.prefs.drawerWidth.collectAsState()
        var drawerLive by remember { mutableStateOf<Float?>(null) }
        val drawerDp = SideWidth.clamp(drawerLive ?: drawerStored.toFloat(), SideWidth.Surface.DRAWER, windowDp)
        val railStored by vm.prefs.railWidth.collectAsState()
        var railLive by remember { mutableStateOf<Float?>(null) }
        val railDp = SideWidth.clamp(railLive ?: railStored.toFloat(), SideWidth.Surface.RAIL, windowDp)

        // The switcher pushes the whole app card away: scale .78, radius 26, 500 ms on Trawl's
        // PushEasing, its left edge ending a fixed gap past the drawer. No 3D rotation - upstream
        // rejected it, and the comment in TrawlSwitcher.kt says why.
        val push by animateFloatAsState(
            targetValue = if (pushed) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(Motion.SWITCHER, easing = Motion.Push),
            label = "switcherPush",
        )

        if (push > 0f) {
            Switcher(vm, tabs, drawerDp) {
                SideEdge(
                    widthDp = drawerDp,
                    bounds = { SideWidth.clamp(it, SideWidth.Surface.DRAWER, windowDp) },
                    onLive = { drawerLive = it },
                    onCommit = { vm.prefs.setDrawerWidth(it) },
                    onReset = { drawerLive = null; vm.prefs.setDrawerWidth(SideWidth.DEFAULT_DP) },
                    line = false,
                )
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = SideWidth.cardShift(push, drawerDp, windowDp).dp.toPx()
                    scaleX = SideWidth.cardScale(push)
                    scaleY = SideWidth.cardScale(push)
                    shape = RoundedCornerShape((26 * push).dp)
                    clip = push > 0f
                }
                .background(MaterialTheme.colorScheme.background),
        ) {
            // Reach mode moves the app's own bars UNDER the listing. A list that starts low
            // while the tabs, back, up and the path bar stay along the top is still a
            // two-handed screen, so moving the rows alone would have been half an answer.
            // See `ThumbReach` in RowViews.kt for why the rows move by a gap rather than by
            // reversing the list.
            val reach by vm.prefs.reachMode.collectAsState()
            // Clear of the sides: sideways, three-key navigation is a strip along one long edge
            // and the camera cutout is on the other, and both were over the rows' ends. The
            // bottom is the strip at the end of this column, not padding here - see there.
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                if (!reach) {
                    TabStrip(vm, tabs, app, showMenu = !wide)
                    TopBar(vm, app, active, wide)
                    HorizontalDivider(color = colors.lineSoft)
                }

                // The pick's chrome is NOT a bar here any more.
                //
                // It was a strip under the toolbar, which is a LAYER OVER the browser - and
                // most callers that ask for a file are themselves a sheet or a full-screen
                // handler, so the real stack was a bar, under a sheet, over a browser, with
                // nothing reachable. It is a floating card inside the pane now, belonging to
                // the listing it is asking about the way the dock's paste pill does. See
                // `PickDock`, drawn by `PaneView`.
                //
                // Logged at both ends because every fault in this subsystem presents as "a tab
                // opened and nothing happened", whether the pick never started, started and
                // was replaced, or started and was never drawn.
                val livePick = vm.inAppPick.collectAsState().value
                androidx.compose.runtime.LaunchedEffect(livePick, vm.picking) {
                    dev.niccc2007.filet.log.FiletLog.i(
                        "pick",
                        "browser sees live=${livePick != null} external=${vm.picking}",
                    )
                }

                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (wide) {
                        Rail(vm, app, railDp)
                        SideEdge(
                            widthDp = railDp,
                            bounds = { SideWidth.clamp(it, SideWidth.Surface.RAIL, windowDp) },
                            onLive = { railLive = it },
                            onCommit = { vm.prefs.setRailWidth(it) },
                            onReset = { railLive = null; vm.prefs.setRailWidth(SideWidth.DEFAULT_DP) },
                            line = true,
                        )
                    }
                    Body(vm, app, paneA, paneB, registry) { ghost = it }
                }

                if (reach) {
                    HorizontalDivider(color = colors.lineSoft)
                    TopBar(vm, app, active, wide)
                    TabStrip(vm, tabs, app, showMenu = !wide)
                }

                // Under the moved bars in reach mode as well, and that ordering is deliberate:
                // in a selection the action bar is what a thumb needs most, so it stays
                // nearest it in both modes.
                // Not during a pick. The pick card is the bottom control then and it carries
                // the confirm; drawing the selection bar as well put two bars over each other,
                // both claiming the same gesture, and the one on top was the one for the
                // operation nobody was doing.
                val selectionUp = activeState?.selecting == true && livePick == null && !vm.picking && active != null
                if (selectionUp && activeState != null && active != null) {
                    // Off a folder - a category, New files - the rows are the ones its screen
                    // drew, and the writes are asked of the folders the files are in.
                    val shown by active.shown.collectAsState()
                    SelectionBar(
                        vm,
                        activeState.selected.size,
                        if (activeState.kind == PaneKind.FOLDER) activeState.visible.size else shown.size,
                        vm.selectionWriteBlock(),
                    )
                }

                // Above the bottom bar and below everything else, so it is present on every
                // pane - Home, a folder, Settings, Remotes - rather than only where a file list
                // is. "Everywhere in the app" was the ask and this is the one row that satisfies
                // it without being pinned over the content.
                //
                // Outside the `wide` branch below on purpose: a tablet has no bottom bar, and
                // hanging the strip off it would make the player uncontrollable on exactly the
                // screens with room for it.
                dev.niccc2007.filet.playback.NowPlayingStrip(vm)

                // The nav bar steps aside while a selection is running, so only the selection
                // bar covers the list. Clear is pinned on that bar and Back still ends the
                // selection, so nothing is unreachable while it is away.
                //
                // This was a three-way setting. It is gone: HIDDEN was the default, nobody
                // needs to express an opinion about how far a bar moves during a selection,
                // and a preference whose off state is "slightly different bar" is not a
                // preference worth maintaining.
                val navUp = !wide && activeState?.selecting != true
                if (navUp) BottomNav(vm, app, active)

                // The navigation bar's own strip, ONE for the whole stack, in the colour of the
                // bar above it.
                //
                // Bug identified here: only BottomNav cleared the navigation bar, so whenever it
                // stepped aside - for a selection, on a tablet - the bar that was last instead sat
                // under the three keys. The selection bar's Copy, Move and Delete were behind
                // Back, Home and Recents. Inset on every bar, two of them would pad twice when
                // stacked; here it is one strip after whichever is last, and none of them does it.
                val edge = when {
                    navUp || selectionUp || dev.niccc2007.filet.playback.nowPlayingShown(vm) -> colors.raised
                    reach -> colors.sunken
                    else -> MaterialTheme.colorScheme.background
                }
                Spacer(Modifier.fillMaxWidth().background(edge).windowInsetsBottomHeight(WindowInsets.navigationBars))
            }

            // Tapping the pushed-away app closes the sidebar.
            //
            // An overlay that CONSUMES input, not a `pointerInput` on the parent Box: Compose
            // hands pointer events to children first, and every row, tab and button in there
            // is clickable - so the parent gesture never fired and tapping the window did
            // nothing. Inside the same Box as the content, so it is clipped and transformed
            // with it and covers exactly the visible card.
            if (pushed) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.18f * push))
                        .pointerInput(Unit) {
                            detectTapGestures { vm.openSwitcher(false) }
                        }
                )
            }
        }

        // Overlays ride above the pushed card so a sheet is never sliced by the transform.
        ghost?.let { pos -> DragGhost(vm, pos) }

        app.toast?.let { msg ->
            Toast(msg) { vm.consumeToast() }
        }
    }
}

// ────────────────────────────────── tabs ──────────────────────────────────

@Composable
private fun TabStrip(
    vm: BrowserViewModel,
    tabs: List<PaneController>,
    app: AppState,
    showMenu: Boolean,
) {
    val colors = Filet.colors
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().background(colors.sunken),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The menu button sits ON the tab row, pinned, not on the line below it. It is the
        // top-left corner of the app and it should read as one row with the tabs.
        if (showMenu) {
            Icon(
                FiletIcons.Menu, "Menu", tint = colors.fg2,
                modifier = Modifier
                    .size(32.dp)
                    .clickable { vm.openSwitcher(true) }
                    .padding(8.dp),
            )
        }

        Box(Modifier.weight(1f)) {
            // The shared cue, same as every other sideways-scrolling row in the app. It
            // pointed out the tab strip had been left out, and asked for it anywhere something
            // scrolls horizontally. It had been exempted on the grounds that
            // the strip auto-scrolls to the active tab and a static chevron would contradict
            // that - which was a reason, and not a good one. A chevron that says "there are more
            // tabs that way" is true whether or not the strip moved on its own.
            HScroll(ground = colors.sunken, state = scroll) {
                TabStripContent(vm, tabs, app)
            }
        }
    }
}

@Composable
private fun TabStripContent(vm: BrowserViewModel, tabs: List<PaneController>, app: AppState) {
    val colors = Filet.colors
    val size by vm.prefs.tabSize.collectAsState()
    // Where each chip sits, filled in as they lay out. A drag needs the resting centres and
    // Compose is the only thing that knows them.
    val centres = remember(tabs.size) { mutableStateMapOf<Int, Float>() }
    val widths = remember(tabs.size) { mutableStateMapOf<Int, Float>() }
    var dragging by remember { mutableStateOf(-1) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val target = if (dragging < 0) -1 else dragTarget(dragging, dragX, (0 until tabs.size).map { centres[it] ?: 0f })
    // True for the frame a drop is committed. See `settle` below.
    var landing by remember { mutableStateOf(false) }
    LaunchedEffect(landing) {
        if (landing) {
            withFrameNanos { }
            landing = false
        }
    }

    run {
        tabs.forEachIndexed { i, pane ->
            // Keyed by the tab, not the slot. Bug identified here: the chips were positional, so
            // on a drop the reorder handed every slot a different tab while each slot kept the
            // step-aside offset it had animated to - and every chip then slid back from where
            // the previous occupant had been. The order had already been shown during the drag;
            // the second slide read as the tabs being reordered again.
            key(pane) {
                val s by pane.state.collectAsState()
                val onA = i == app.activeA
                val onB = app.split != SplitMode.OFF && i == app.activeB
                TabChip(
                    title = s.title,
                    icon = tabIcon(s),
                    active = onA || onB,
                    side = when {
                        app.split == SplitMode.OFF -> null
                        onA -> "A"
                        onB -> "B"
                        else -> null
                    },
                    size = size,
                    dragging = i == dragging,
                    insertHere = target == i && dragging >= 0 && dragging != i,
                    // How far this chip is from where it lives. The one being dragged follows
                    // the finger; the ones it is passing step aside to open the gap it will land
                    // in, so the order is settled on screen before anybody lifts their thumb.
                    //
                    // It all worked before this and none of it was visible: the chip stayed put,
                    // changed colour, and the tabs reordered on lift. Reported as "dragging a tab
                    // should change its ordering" by somebody who could not tell that it already
                    // did.
                    offsetX = when {
                        dragging < 0 -> 0f
                        i == dragging -> dragX - (centres[i] ?: dragX)
                        target < 0 -> 0f
                        dragging < target && i > dragging && i <= target -> -(widths[dragging] ?: 0f)
                        dragging > target && i >= target && i < dragging -> widths[dragging] ?: 0f
                        else -> 0f
                    },
                    // Not on the frame the drop lands: the reorder moves each chip exactly as far as
                    // its step-aside offset had, so the offsets snap to zero and nothing visibly moves.
                    settle = i != dragging && !landing,
                    onClick = { vm.focusTab(i) },
                    onClose = { vm.closeTab(i) },
                    onCentre = { c, w -> centres[i] = c; widths[i] = w },
                    onDragStart = { dragging = i; dragX = centres[i] ?: 0f },
                    onDrag = { dx -> dragX += dx },
                    onDragEnd = {
                        // Committed on lift rather than per frame: rebuilding the list sixty times
                        // a second re-keys every chip and makes the drag stutter.
                        if (dragging >= 0 && target >= 0 && target != dragging) {
                            landing = true
                            vm.moveTab(dragging, target)
                        }
                        dragging = -1
                    },
                )
            }
        }
        // The new-tab button scales with the tabs. Leaving it at one size is how a Huge strip
        // ends up with a target beside it that is harder to hit than the tabs were before.
        Icon(
            FiletIcons.Plus, "New tab", tint = colors.fg2,
            modifier = Modifier
                .size(size.plus.dp)
                .clickable { vm.addTab() }
                .padding((size.plus / 3.75f).dp),
        )
    }
}

@Composable
private fun TabChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    side: String?,
    size: TabSize,
    dragging: Boolean = false,
    insertHere: Boolean = false,
    offsetX: Float = 0f,
    settle: Boolean = true,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onCentre: (Float, Float) -> Unit = { _, _ -> },
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    val colors = Filet.colors
    // See the note on the drag detector below: these must be read live, not captured.
    val liveDragStart = rememberUpdatedState(onDragStart)
    val liveDrag = rememberUpdatedState(onDrag)
    val liveDragEnd = rememberUpdatedState(onDragEnd)
    // The chip being dragged tracks the finger exactly - an animation there is a chip that
    // lags behind the thumb holding it. The ones stepping aside DO animate, because a gap
    // that appears in one frame reads as the row twitching rather than as making room.
    val shift by animateFloatAsState(
        offsetX,
        animationSpec = tween(if (settle) 160 else 0),
        label = "tab-shift",
    )
    val travel = if (settle) shift else offsetX
    Row(
        Modifier
            .padding(horizontal = 2.dp, vertical = 3.dp)
            .onGloballyPositioned {
                onCentre(
                    it.positionInParent().x + it.size.width / 2f,
                    it.size.width.toFloat(),
                )
            }
            .graphicsLayer {
                translationX = travel
                // Lifted off the strip while it is held: a little bigger, and over the top of
                // its neighbours rather than sliding under them.
                if (dragging) {
                    scaleX = 1.06f
                    scaleY = 1.06f
                    shadowElevation = 14f
                    shape = RoundedCornerShape(7.dp)
                    clip = false
                }
            }
            .zIndex(if (dragging) 1f else 0f)
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    dragging -> colors.sel
                    insertHere -> colors.drop
                    active -> MaterialTheme.colorScheme.background
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            // A long press to start, so an ordinary tap still switches tabs. Last in the
            // chain so it wins the main pass over the click above.
            //
            // The callbacks are read through `rememberUpdatedState`, and that is the whole
            // reason tab dragging did nothing. `pointerInput(Unit)` launches its block ONCE and
            // never again, so it holds the callback instances from the very first composition -
            // and `onDragEnd` closes over `target`, which in `TabStripContent` is a plain `val`
            // recomputed every composition. The frozen copy therefore saw `target == -1`
            // forever, `vm.moveTab` was never reached, and the chip animated under the finger
            // and sprang back. Everything underneath - `movedItem`, `dragTarget`,
            // `indexAfterMove`, `moveTab` - already existed and was already tested, which is
            // why this read as "the feature was never built" when it was only never connected.
            //
            // Exactly the same mistake as the image viewer's pinch-to-zoom in this round.
            // `tools/check-deadswitch.mjs` now fails the build on the pattern rather than on
            // these two instances of it.
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { liveDragStart.value() },
                    onDragEnd = { liveDragEnd.value() },
                    onDragCancel = { liveDragEnd.value() },
                ) { change, drag ->
                    change.consume()
                    liveDrag.value(drag.x)
                }
            }
            .padding(horizontal = size.padH.dp, vertical = size.padV.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null, tint = if (active) colors.accent else colors.fg3,
            modifier = Modifier.size(size.icon.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            title,
            fontSize = size.fontSize.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (active) MaterialTheme.colorScheme.onSurface else colors.fg2,
            modifier = Modifier.widthIn(max = size.maxTitle.dp),
        )
        // In a split, the badge says which pane this tab is showing in. Without it, two
        // highlighted tabs is just confusing.
        if (side != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                side,
                fontSize = (size.fontSize * 0.72f).sp,
                color = colors.accent,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(3.dp))
        Icon(
            FiletIcons.Close, "Close tab", tint = colors.fg3,
            modifier = Modifier.size(size.close.dp).clickable(onClick = onClose).padding(2.dp),
        )
    }
}

private fun tabIcon(s: PaneState) = when (s.kind) {
    PaneKind.HOME -> FiletIcons.Home
    PaneKind.ABOUT -> FiletIcons.Info
    PaneKind.SETTINGS -> FiletIcons.Cog
    PaneKind.BOOKMARKS -> FiletIcons.Star
    PaneKind.PLAYLISTS -> FiletIcons.Audio
    PaneKind.RESUME -> FiletIcons.Clock
    PaneKind.CATEGORY -> FiletIcons.Image
    PaneKind.RECENT -> FiletIcons.Clock
    PaneKind.HISTORY -> FiletIcons.Clock
    PaneKind.SHORTCUTS -> FiletIcons.Home
    PaneKind.ACTIVITY -> FiletIcons.Jobs
    PaneKind.SCRIPTS -> FiletIcons.Script
    PaneKind.APPS -> FiletIcons.Apk
    PaneKind.NEARBY -> FiletIcons.Wifi
    PaneKind.REMOTES -> FiletIcons.Device
    PaneKind.SCAN -> FiletIcons.Scan
    PaneKind.FOLDER -> if (s.cwd?.scheme == "zip") FiletIcons.Zip else FiletIcons.Folder
}

// ────────────────────────────────── top bar ──────────────────────────────────

@Composable
private fun TopBar(vm: BrowserViewModel, app: AppState, active: PaneController?, wide: Boolean) {
    val colors = Filet.colors
    var viewPopup by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val s = active?.state?.collectAsState()?.value

    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The menu button now lives on the tab row, level with the tabs.
        NavCluster(vm, active, s, wide)
        PathBar(vm, active, s, Modifier.weight(1f))

        Box {
            BarButton(FiletIcons.Grid, "View") { viewPopup = true }
            if (viewPopup) ViewPopover(vm) { viewPopup = false }
        }
        BarButton(
            when (app.split) {
                SplitMode.OFF, SplitMode.SIDE -> FiletIcons.SplitH
            },
            "Split panes",
            tint = if (app.split != SplitMode.OFF) colors.accent else colors.fg2,
        ) { vm.cycleSplit() }
        Box {
            BarButton(FiletIcons.More, "More") { moreMenu = true }
            MoreMenu(vm, active, s, moreMenu) { moreMenu = false }
        }
    }
}

/**
 * Back, forward, up, refresh. The cluster every desktop file manager puts left of the path.
 *
 * ## Why they are disabled rather than hidden
 *
 * The set has to keep its width. If Forward vanished whenever there was nothing to go forward
 * to, every navigation would shuffle the other three sideways and the breadcrumb would jump
 * with them - so the button you were aiming at moves out from under your thumb. Greyed and in
 * place, which is what Explorer does and for the same reason.
 *
 * ## Why it narrows instead of dropping one
 *
 * Four buttons plus a breadcrumb plus three existing tools is a lot for a phone width, and
 * this row is already the densest in the app. The first cut shed Forward on a narrow pane;
 * measured on a 1080px phone the row had room to spare and all that did was make the set
 * incomplete on the device it matters most on. So all four stay at every width and the
 * targets shrink instead - 32dp wide, 28dp narrow, both above the 24dp floor where a
 * fingertip starts missing.
 */
@Composable
private fun NavCluster(
    vm: BrowserViewModel,
    active: PaneController?,
    s: PaneState?,
    wide: Boolean,
) {
    val colors = Filet.colors
    val folder = s?.kind == PaneKind.FOLDER
    val size = if (wide) 32.dp else 28.dp

    NavButton(FiletIcons.Back, "Back", size, enabled = s?.canGoBack == true) { active?.goBack() }
    NavButton(FiletIcons.Forward, "Forward", size, enabled = s?.canGoForward == true) {
        active?.goForward()
    }
    NavButton(
        FiletIcons.Up, "Up one folder", size,
        enabled = folder && s?.cwd?.parent != null,
    ) { active?.goUp() }
    // One turn per press: the acknowledgement the refresh toast used to give, given by the
    // thing that was pressed. The same turn the pull's indicator makes when it fires.
    val turn = remember { androidx.compose.animation.core.Animatable(0f) }
    val spinScope = androidx.compose.runtime.rememberCoroutineScope()
    NavButton(FiletIcons.Refresh, "Refresh", size, enabled = active != null, turn = { turn.value }) {
        active?.let { vm.refreshPane(it) }
        spinScope.launch {
            turn.snapTo(0f)
            turn.animateTo(360f, androidx.compose.animation.core.tween(450))
        }
    }
    Spacer(Modifier.width(2.dp))
}

@Composable
private fun NavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    /** Degrees, read in the draw phase so a spinning icon does not recompose the bar. */
    turn: (() -> Float)? = null,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Icon(
        icon, label,
        tint = if (enabled) colors.fg2 else colors.fg3.copy(alpha = 0.3f),
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(size * 0.22f)
            .then(if (turn != null) Modifier.graphicsLayer { rotationZ = turn() } else Modifier),
    )
}

@Composable
private fun BarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Filet.colors.fg2,
    onClick: () -> Unit,
) {
    Icon(
        icon, label, tint = tint,
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(7.dp),
    )
}

/**
 * The path bar: a breadcrumb until you tap it, then the raw path, selected.
 *
 * Both, because both are wanted at different moments - tapping an ancestor is the common
 * case, and typing a path is the escape hatch when it is not reachable by tapping.
 */
@Composable
private fun PathBar(vm: BrowserViewModel, active: PaneController?, s: PaneState?, modifier: Modifier) {
    val colors = Filet.colors
    var draft by remember(s?.cwd) { mutableStateOf(s?.cwd?.toString() ?: "") }

    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.sunken)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s == null) {
            Text("Filet", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            return@Row
        }
        if (s.editingPath) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(colors.accent),
                keyboardActions = run {
                    // Every action a keyboard might actually send, not just onDone.
                    //
                    // The IME action here is `Go`, and Compose routes `Go` to `onGo` - so a
                    // handler that only defines `onDone` gets the default behaviour, which is
                    // "hide the keyboard and do nothing". That is exactly what tapping the
                    // check key used to do. Gboard, Samsung and several third-party keyboards
                    // also send Done or Enter regardless of what was requested.
                    val commit: () -> Unit = {
                        val target = resolveTyped(draft, s.cwd)
                        // walking: typed into the path bar, which is this pane's own address.
                        if (target != null) active?.navigateTo(target)
                        else vm.toast("That is not a path Filet can open.")
                        active?.startEditingPath(false)
                    }
                    androidx.compose.foundation.text.KeyboardActions(
                        onGo = { commit() },
                        onDone = { commit() },
                        onSend = { commit() },
                        onSearch = { commit() },
                    )
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Go
                ),
                modifier = Modifier.weight(1f),
            )
            Icon(
                FiletIcons.Close, "Cancel", tint = colors.fg3,
                modifier = Modifier.size(20.dp).clickable { active?.startEditingPath(false) }.padding(3.dp),
            )
        } else {
            // walking: a breadcrumb is a step back up the pane's own path.
            Breadcrumb(s, vm, Modifier.weight(1f)) { active?.crumbTo(it) }
            Icon(
                FiletIcons.Copy, "Copy path", tint = colors.fg3,
                // The path the bar names, which in a tree is the focused branch.
                modifier = Modifier.size(21.dp).clickable { vm.copyPathToClipboard(s.target) }.padding(4.dp),
            )
            Icon(
                FiletIcons.Rename, "Edit path", tint = colors.fg3,
                modifier = Modifier.size(21.dp).clickable { active?.startEditingPath(true) }.padding(4.dp),
            )
        }
    }
}

/** One clickable piece of the breadcrumb. */
private class Crumb(val label: String, val target: VPath?)

/**
 * Break a location into crumbs that go where they say they go.
 *
 * The subtle case is a **container**. Inside `apk:///storage/emulated/0/Download/x.apk!/…`
 * the segments before the `!` are not part of the archive at all - they are the local path of
 * the file the archive lives in. Building an `apk:` path out of them produces
 * `apk:///storage/emulated/0/Download`, which no provider can list, so tapping "Download"
 * did nothing and left you stuck inside the APK.
 *
 * Those outer crumbs therefore navigate with the **host** scheme, and the archive's own name
 * is a crumb that returns to its root.
 *
 * A drive somebody has renamed is one crumb under its name: everything up to and including
 * its root folds into it, so a card reads `My SD / DCIM` rather than `storage / 1234-ABCD /
 * DCIM`. [rootName] answers for renamed roots only - an unrenamed drive keeps its segments.
 */
private fun crumbsOf(
    cwd: VPath,
    nameForId: (String, String) -> String? = { _, _ -> null },
    rootName: (VPath) -> String? = { null },
): List<Crumb> {
    val crumbs = segmentCrumbs(cwd, nameForId)
    // The deepest renamed root, so a drive mounted inside another drive is named by its own.
    val at = crumbs.indexOfLast { c -> c.target?.let(rootName) != null }
    if (at < 0) return crumbs
    val root = crumbs[at].target!!
    return listOf(Crumb(rootName(root)!!, root)) + crumbs.drop(at + 1)
}

private fun segmentCrumbs(cwd: VPath, nameForId: (String, String) -> String?): List<Crumb> {
    val cut = cwd.path.indexOf('!')
    if (cut < 0) {
        val segs = cwd.segments
        return segs.mapIndexed { i, seg ->
            // On a network scheme the FIRST segment is a connection id, not a folder - the path
            // shape every net provider shares is `scheme:///<id>/<remote>`. Drawn raw it put a
            // string like `nmuknmxy0` at the head of the breadcrumb, which names nothing a person
            // has ever seen. It navigates to exactly the same place either way; only the label
            // changes.
            val label = if (i == 0) nameForId(cwd.scheme, seg) ?: seg else seg
            Crumb(label, VPath.of(cwd.scheme, "/" + segs.take(i + 1).joinToString("/")))
        }
    }

    val outer = cwd.path.substring(0, cut)
    val inner = cwd.path.substring(cut + 1).trim('/')
    val outerSegs = outer.split('/').filter { it.isNotEmpty() }
    val out = ArrayList<Crumb>(outerSegs.size + 4)

    // Everything up to and including the archive's parent folder is ordinary local storage.
    outerSegs.dropLast(1).forEachIndexed { i, seg ->
        out += Crumb(seg, VPath.of(HOST_SCHEME, "/" + outerSegs.take(i + 1).joinToString("/")))
    }
    // The archive itself: tapping it returns to the root of the container.
    outerSegs.lastOrNull()?.let { out += Crumb(it, VPath.of(cwd.scheme, "$outer!/")) }

    val innerSegs = inner.split('/').filter { it.isNotEmpty() }
    innerSegs.forEachIndexed { i, seg ->
        out += Crumb(seg, VPath.of(cwd.scheme, "$outer!/" + innerSegs.take(i + 1).joinToString("/")))
    }
    return out
}

/** Containers always sit on a real file, and a real file is `local:`. */
private const val HOST_SCHEME = "local"

/**
 * Turn whatever the user typed into a location.
 *
 * They type `/storage/emulated/0/Download`, not `local:///storage/emulated/0/Download` -
 * requiring the scheme is why the path box used to reject everything a human would write.
 * A bare absolute path keeps the pane's current scheme; `~` and `sdcard` are the two
 * shorthands worth knowing.
 */
private fun resolveTyped(raw: String, cwd: VPath?): VPath? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    if (text.contains("://")) return runCatching { VPath.parse(text) }.getOrNull()
    val expanded = when {
        text == "~" || text.startsWith("~/") -> "/storage/emulated/0" + text.removePrefix("~")
        text.startsWith("sdcard/") || text == "sdcard" -> "/storage/emulated/0" + text.removePrefix("sdcard")
        else -> text
    }
    if (!expanded.startsWith("/")) return null
    // Inside a container a typed path is still a local one - nobody types an `apk:` path.
    val scheme = cwd?.scheme?.takeIf { it != "zip" && it != "apk" } ?: HOST_SCHEME
    return runCatching { VPath.of(scheme, expanded) }.getOrNull()
}

@Composable
private fun Breadcrumb(
    s: PaneState,
    vm: BrowserViewModel,
    modifier: Modifier,
    onNavigate: (VPath) -> Unit,
) {
    val colors = Filet.colors
    val cwd = s.cwd
    if (cwd == null) {
        Text(s.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
        return
    }
    // Read through a derived state, so the bar recomposes when a drive is renamed and not on
    // every other change to the app's state.
    val app = vm.state.collectAsState()
    val renamed by remember {
        androidx.compose.runtime.derivedStateOf(androidx.compose.runtime.structuralEqualityPolicy()) {
            app.value.volumes.filter { it.label != it.systemLabel }
                .associate { dev.niccc2007.filet.data.DriveNames.idOf(it.node.path) to it.label }
        }
    }
    // The focused branch, in a tree: the bar names where New folder and Paste will land, which
    // is what it is read for. It named the tree's root before, four levels above the branch
    // being worked in. Crumbs inside the tree move the focus rather than re-rooting it - see
    // `PaneController.crumbTo`.
    val shown = s.target ?: cwd
    val crumbs = remember(shown, renamed) {
        crumbsOf(
            shown,
            nameForId = { scheme, id -> vm.connectionName(scheme, id) },
            rootName = { renamed[dev.niccc2007.filet.data.DriveNames.idOf(it)] },
        )
    }
    val scroll = rememberScrollState()
    LaunchedEffect(shown) { scroll.scrollTo(scroll.maxValue) }
    // Deliberately NOT HScroll, and this is the exception the checker records. Rejected:
    // the scroll cue here specifically and asked for the plain version back. He is right -
    // the breadcrumb already auto-scrolls to the deepest crumb on every navigation, so a
    // chevron sits there pointing back at a path you just came from, in the densest strip
    // on the screen.
    Row(modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        Text(
            cwd.scheme,
            fontSize = 10.sp,
            color = colors.fg3,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onNavigate(VPath(cwd.scheme, "/")) }
                .padding(horizontal = 3.dp),
        )
        crumbs.forEachIndexed { i, crumb ->
            Text("/", color = colors.fg3, fontSize = 12.sp)
            val last = i == crumbs.lastIndex
            Text(
                text = crumb.label,
                fontSize = 12.5.sp,
                maxLines = 1,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) MaterialTheme.colorScheme.onSurface else colors.fg2,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .then(
                        if (last || crumb.target == null) Modifier
                        else Modifier.clickable { onNavigate(crumb.target) }
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

// ────────────────────────────────── body ──────────────────────────────────

@Composable
private fun Body(
    vm: BrowserViewModel,
    app: AppState,
    paneA: PaneController?,
    paneB: PaneController?,
    registry: DropRegistry,
    onGhost: (Offset?) -> Unit,
) {
    val colors = Filet.colors
    val ratio by vm.prefs.splitRatio.collectAsState()
    var live by remember { mutableStateOf(ratio) }
    LaunchedEffect(ratio) { live = ratio }

    if (paneA == null) {
        EmptyNote("Loading…", Modifier.fillMaxSize())
        return
    }

    val swipeOn by vm.prefs.paneSwipe.collectAsState()
    val swipeAt = PaneSwipe.Sensitivity.of(vm.prefs.paneSwipeAt.collectAsState().value)
    // Where the swiping pane is and which tab it would land on. See TabSwipe.kt.
    val motion = rememberSwipeMotion()

    // Single pane gets the gesture too.
    //
    // It was dual-pane-only when it switched panes, which made sense then: with one pane
    // there was nothing to switch to. Switching TABS has somewhere to go in either layout,
    // and one pane is how the app is used most of the time, so withholding it there would
    // have left the gesture in the mode that needs it least.
    if (app.split == SplitMode.OFF || paneB == null) {
        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .then(tabSwipe(vm, app, swipeOn, swipeAt, splitAt = null, motion)),
        ) {
            SwipePanes(motion, Side.A, vm, paneA) { p, current, m ->
                PaneView(p, vm, Side.A, current, registry, if (current) onGhost else ({}), m)
            }
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Always side by side now - see SplitMode. The flag stays because the divider's
        // drag maths reads it, and a layout that hard-codes an axis is a layout that cannot
        // be given one back.
        val horizontal = true
        val totalDp = if (horizontal) maxWidth.value else maxHeight.value
        val minFrac = (MIN_PANE_DP / totalDp).coerceIn(0.05f, 0.45f)
        val f = live.coerceIn(minFrac, 1f - minFrac)
        val density = LocalDensity.current

        val dividerDrag = Modifier.pointerInput(horizontal, totalDp) {
            // One gesture loop, not two. A separate tap detector alongside the drag detector
            // races it: whichever consumes the down event first wins, so the tap fires on
            // some drags and never on others depending on composition order. Reading the
            // pointer here means the same press decides once.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val startedAt = System.currentTimeMillis()
                val origin = down.position
                var furthest = 0f
                var dragged = false
                val totalPx = with(density) { totalDp.dp.toPx() }
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUp()) break
                    val travelPx = (change.position - origin).getDistance()
                    furthest = maxOf(furthest, with(density) { travelPx.toDp().value })
                    val delta = change.positionChange()
                    val px = if (horizontal) delta.x else delta.y
                    if (px != 0f) {
                        dragged = true
                        change.consume()
                        // Work in FRACTIONS of the body, not pixels: the same maths then
                        // behaves identically at every density and in every scaled preview.
                        live = (live + px / totalPx).coerceIn(minFrac, 1f - minFrac)
                    }
                }
                val heldMs = System.currentTimeMillis() - startedAt
                if (DividerTap.isTap(heldMs, furthest)) {
                    // A tap swaps the panes. The thresholds are in DividerTap with tests:
                    // getting this wrong the other way resizes the split every time somebody
                    // meant to swap.
                    vm.swapPanes()
                } else if (dragged) {
                    vm.prefs.setSplitRatio(live)
                }
            }
        }.pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { live = 0.5f; vm.prefs.setSplitRatio(0.5f) })
        }

        val paneSwipe = tabSwipe(vm, app, swipeOn, swipeAt, splitAt = f, motion)

        // Bug identified here: SplitFitted was written, tested and offered in Settings, and no
        // pane was ever drawn inside it, so "Shrink to fit" was a switch connected to nothing and
        // a split pane laid a phone-width design into half a phone. Each half is now drawn at the
        // density its own width calls for. See SplitFit.
        val fitMode = vm.prefs.splitFit.collectAsState().value
            .let { id -> SplitFit.Mode.entries.firstOrNull { it.id == id } ?: SplitFit.Mode.SHRINK }
        val panesDp = totalDp - DIVIDER_DP

        // Each pane in its own clipped box, so the one being swiped slides inside its half and
        // never over the divider or the other pane.
        Row(Modifier.fillMaxSize().then(paneSwipe)) {
            Box(Modifier.weight(f).fillMaxHeight().clipToBounds()) {
                SplitFitted((panesDp * f).dp, fitMode) {
                    SwipePanes(motion, Side.A, vm, paneA) { p, current, m ->
                        PaneView(p, vm, Side.A, current && app.focused == Side.A, registry, if (current) onGhost else ({}), m)
                    }
                }
            }
            Divider(vertical = true, modifier = dividerDrag)
            Box(Modifier.weight(1f - f).fillMaxHeight().clipToBounds()) {
                SplitFitted((panesDp * (1f - f)).dp, fitMode) {
                    SwipePanes(motion, Side.B, vm, paneB) { p, current, m ->
                        PaneView(p, vm, Side.B, current && app.focused == Side.B, registry, if (current) onGhost else ({}), m)
                    }
                }
            }
        }
    }
}

/** The divider's thickness. The panes share what is left, which is what [SplitFitted] is given. */
private const val DIVIDER_DP = 6f

/** The grip is always faintly visible: a drag handle nobody can see is not a drag handle. */
@Composable
private fun Divider(vertical: Boolean, modifier: Modifier) {
    val colors = Filet.colors
    Box(
        modifier
            .then(if (vertical) Modifier.width(DIVIDER_DP.dp).fillMaxHeight() else Modifier.height(DIVIDER_DP.dp).fillMaxWidth())
            .background(colors.lineSoft),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .then(if (vertical) Modifier.width(2.dp).height(26.dp) else Modifier.height(2.dp).width(26.dp))
                .clip(RoundedCornerShape(2.dp))
                .background(colors.fg3.copy(alpha = 0.32f))
        )
    }
}

// ────────────────────────────────── chrome ──────────────────────────────────

@Composable
private fun Rail(vm: BrowserViewModel, app: AppState, widthDp: Float) {
    val colors = Filet.colors

    // The portrait drawer's folds, not a second design of them. The rail used to be flat
    // sections of its own - on a phone with no history yet, three plain headings that looked
    // nothing like the drawer it is the landscape version of. See SideFolds.
    Column(
        Modifier
            .width(widthDp.dp)
            .fillMaxHeight()
            .background(colors.raised),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            // The Filet card is the last fold, inside the scroll - see SideFolds.
            androidx.compose.runtime.CompositionLocalProvider(LocalSideBarWidth provides widthDp) {
                SideFolds(vm, app, reduced = true) {}
            }
        }
    }
}

@Composable
private fun BottomNav(
    vm: BrowserViewModel,
    app: AppState,
    active: PaneController?,
    /** Icons only, no captions. The form the bar takes under a selection bar. */
    compact: Boolean = false,
) {
    val colors = Filet.colors
    val jobs by vm.ledger.jobs.collectAsState()
    val stored by vm.prefs.bottomBar.collectAsState()
    val items = remember(stored) { BottomBarConfig.normalise(stored) }

    val row: @Composable () -> Unit = {
        for (item in items) {
            val activity = item == BarItem.ACTIVITY
            val badge = if (activity) jobs.count { it.running } else 0
            val ring = if (activity) dev.niccc2007.filet.jobs.JobRate.overall(jobs) else null
            NavItem(barIcon(item), item.label, badge, compact, ring) { runBarItem(item, vm, active) }
        }
    }

    val base = Modifier
        .fillMaxWidth()
        .background(colors.raised)
        // No inset of its own: the strip after the last bar clears the navigation bar for the
        // whole stack. See BrowserScreen.
        .padding(vertical = if (compact) 1.dp else 4.dp)

    // Past six the labels stop being readable on a narrow phone, so the bar takes its natural
    // width and scrolls rather than squeezing every entry thinner. HScroll and not a bare
    // horizontalScroll: a row that scrolls with no sign that it does is the thing
    // tools/check-scrollcue.mjs exists to prevent.
    if (BottomBarConfig.scrolls(items.size)) {
        HScroll(modifier = base, ground = colors.raised, contentPadding = 6.dp) { row() }
    } else {
        Row(base, horizontalArrangement = Arrangement.SpaceEvenly) { row() }
    }
}

private fun barIcon(item: BarItem) = when (item) {
    BarItem.FILES -> FiletIcons.Storage
    BarItem.SEARCH -> FiletIcons.Search
    BarItem.ACTIVITY -> FiletIcons.Jobs
    BarItem.BOOKMARKS -> FiletIcons.Star
    BarItem.PLAYLISTS -> FiletIcons.Audio
    BarItem.RESUME -> FiletIcons.Clock
    BarItem.HOME -> FiletIcons.Home
    BarItem.RECENT -> FiletIcons.Clock
    BarItem.NEW_FILES -> FiletIcons.Download
    BarItem.SHARING -> FiletIcons.Wifi
    BarItem.REMOTES -> FiletIcons.Device
    BarItem.SCRIPTS -> FiletIcons.Script
    BarItem.SHORTCUTS -> FiletIcons.Pin
    BarItem.SPLIT -> FiletIcons.SplitV
    BarItem.NEW_TAB -> FiletIcons.Plus
    BarItem.CLOSE_TABS -> FiletIcons.CloseAll
    BarItem.SETTINGS -> FiletIcons.Cog
}

/**
 * What each bar entry does.
 *
 * A `when` with no else, so adding an entry to [BarItem] without giving it an action does not
 * compile. The alternative is a button in the bar that does nothing, which is rule R1.
 */
private fun runBarItem(item: BarItem, vm: BrowserViewModel, active: PaneController?) {
    when (item) {
        BarItem.FILES -> vm.goToFiles()
        // R1: offering search on a pane where it can do nothing is a dead control.
        BarItem.SEARCH -> active?.let { if (searchOffered(it.state.value.kind)) it.openSearch(true) }
        BarItem.ACTIVITY -> vm.openActivity()
        BarItem.BOOKMARKS -> active?.openSpecial(PaneKind.BOOKMARKS, "Bookmarks")
        BarItem.PLAYLISTS -> active?.openSpecial(PaneKind.PLAYLISTS, "Playlists")
        BarItem.RESUME -> active?.openSpecial(PaneKind.RESUME, "Carry on")
        BarItem.HOME -> active?.openHome()
        BarItem.RECENT -> active?.openSpecial(PaneKind.RECENT, "Opened")
        BarItem.REMOTES -> active?.openSpecial(PaneKind.REMOTES, "Remotes")
        BarItem.NEW_FILES -> active?.openSpecial(
            PaneKind.HISTORY,
            dev.niccc2007.filet.home.HomeSections.ARRIVED_TAB,
        )
        BarItem.SHARING -> active?.openSpecial(PaneKind.NEARBY, "Share")
        BarItem.SCRIPTS -> active?.openSpecial(PaneKind.SCRIPTS, "Scripts")
        BarItem.SHORTCUTS -> active?.openSpecial(PaneKind.SHORTCUTS, "Shortcuts")
        BarItem.SPLIT -> vm.cycleSplit()
        BarItem.NEW_TAB -> vm.addTab()
        BarItem.CLOSE_TABS -> vm.closeAllTabs()
        BarItem.SETTINGS -> active?.openSpecial(PaneKind.SETTINGS, "Settings")
    }
}

@Composable
private fun NavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    badge: Int = 0,
    /**
     * Drop the caption and tighten the box. The label becomes the icon's
     * `contentDescription` rather than disappearing, so the entry is still named to a screen
     * reader - the caption is what gets dropped, not the name.
     */
    compact: Boolean = false,
    /**
     * How far the running work has got, drawn as a ring round the icon: 0..1, a negative number
     * for work that cannot say, null for nothing running. See `JobRate.overall`.
     */
    ring: Float? = null,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    // Turns only while there is work that cannot say how far it has got.
    val spin = if (ring != null && ring < 0f) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "ring").animateFloat(
            0f, 360f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1_100, easing = androidx.compose.animation.core.LinearEasing)),
            label = "ring",
        ).value
    } else {
        0f
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (compact) 7.dp else 4.dp),
    ) {
        Box {
            Icon(
                icon,
                if (compact) label else null,
                tint = colors.fg2,
                modifier = Modifier
                    .size(19.dp)
                    // Behind the icon and outside its bounds, inside the item's padding, so a
                    // job starting never changes the bar's height.
                    .drawBehind {
                        val r = ring ?: return@drawBehind
                        val stroke = 2.dp.toPx()
                        val radius = size.minDimension / 2f + 2.5.dp.toPx()
                        val topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius)
                        val arc = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
                        val style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawArc(colors.lineSoft, 0f, 360f, false, topLeft, arc, style = style)
                        if (r < 0f) drawArc(colors.accent, spin - 90f, 90f, false, topLeft, arc, style = style)
                        else drawArc(colors.accent, -90f, 360f * r, false, topLeft, arc, style = style)
                    },
            )
            if (badge > 0) {
                // The number, not a dot: "two things running" and "one" are different answers
                // to whether the phone can be put down.
                Text(
                    badge.toString(),
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 7.dp, y = (-5).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.accent)
                        .padding(horizontal = 3.5.dp, vertical = 0.5.dp),
                )
            }
        }
        if (!compact) Text(label, fontSize = 9.5.sp, color = colors.fg3)
    }
}

/**
 * What you can do with the current selection.
 *
 * Two renderings of one list (`SelectionActions.kt`), chosen in Settings. The default is the
 * menu, the bar had to scroll to hold eleven actions, and an action that is
 * off the edge of a bar nobody knows scrolls does not exist. A menu is bounded by the screen
 * instead of by the width of a row, so it always fits and every action is readable at once.
 */
@Composable
private fun SelectionBar(vm: BrowserViewModel, count: Int, total: Int, readOnly: String?) {
    val colors = Filet.colors
    val onBar = SelectionBarConfig.normalise(vm.prefs.selectionBar.collectAsState().value)
    var moreOpen by remember { mutableStateOf(false) }

    val actions = selectionActions(
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
            announce = { vm.announceSelectionToMedia() },
            extractHere = { vm.extractSelection() },
            extractToOtherPane = { vm.extractSelectionToOtherPane() },
            addToPlaylist = { vm.addToPlaylistSelection() },
            makeBeatMaps = { vm.makeBeatMapsForSelection() },
            enqueue = { vm.enqueueSelection() },
            copyPath = { vm.copyPathsOfSelection() },
            invert = { vm.focusedPane()?.invertSelection() },
            reveal = { vm.revealSelection() },
        ),
        archive = vm.selectionIsArchive(),
        otherPane = vm.isSplit(),
        picking = vm.pickingAnything,
        installable = vm.selectionIsInstallable(),
        hasMetadata = vm.selectionHasMetadata(),
        shape = SelectionShape.of(vm.focusedPane()?.selectedNodes().orEmpty()),
        announceable = vm.selectionIsOnThisDevice(),
        elsewhere = vm.selectionIsElsewhere(),
    )

    Column(Modifier.fillMaxWidth().background(colors.raised)) {
        HorizontalDivider(color = colors.lineSoft)
        // Everything not on the bar, behind one button. The bar cannot hold fifteen actions and
        // a menu puts the four anybody uses two taps away, so the split is the answer and WHICH
        // four is the setting. Taken out here because the trailing group needs it too: More is
        // drawn only when there is something behind it.
        val (barActions, more) = SelectionBarConfig.split(actions, onBar)
        // There was a "Use these" here while a pick was live, and it had to go.
        //
        // Bug identified here: it confirmed the PICK while standing in the bar that counts
        // the SELECTION, and those are two different sets now that a pick can be answered
        // from a surface with no selection. So a long press during a pick put "Use these"
        // above three highlighted rows, and pressing it answered "choose a file first" -
        // about the three files it was sitting on top of.
        //
        // One confirm, on the card, which is on screen for the whole of the pick and
        // follows the pane being looked at. See `PickDock`.
        // Three groups, in the order Android's contextual bars use everywhere: the way out and
        // the count at the start, what can be done in the middle, and More at the very edge of
        // the screen, which is where a thumb goes looking for an overflow.
        //
        // Bug identified here: More sat INSIDE the bar, between "All" and the cross, so the
        // three dots were in the one place nobody looks for them, and the cross at the edge was
        // where More should have been. Clear and More used to be pinned together on the right
        // because both had to stay reachable; they still are, one at each end.
        //
        // Decision: the count is the number alone, never "12 selected". The highlighted rows and
        // the bar being there at all already say that something is selected, so the word only
        // added width - and it was most of what pushed the actions into scrolling.
        Row(
            Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarGlyph(FiletIcons.Close, "Clear selection") { vm.focusedPane()?.clearSelection() }
            Text(
                count.toString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .padding(end = 8.dp)
                    // Read out as what it means, not as a bare number.
                    .semantics { contentDescription = "$count selected" },
            )
            /**
             * The actions SCROLL. They used to be inflexible children of a `fillMaxWidth` Row,
             * and that is the whole of the reported fault.
             *
             * A Row with no room left does not stop laying out - it squeezes whatever cannot
             * flex. The buttons shrank, then `More` was handed a box narrower than the word,
             * and a `Text` with no line limit in a box that narrow breaks INSIDE the word:
             * "Mor" on one line and "e" on the next. `Clear` was pushed off the edge entirely.
             * Seen on a 320dp screen; it is a function of how many actions are on the bar, so
             * a wider phone with more of them reaches the same place.
             *
             * `HScroll` is the house component for this and carries the edge cue that
             * `check-scrollcue.mjs` requires, so the row says there is more of it. It takes
             * what the two ends leave, and the ends are never squeezed: they hold the way out.
             */
            Box(Modifier.weight(1f)) {
                dev.niccc2007.filet.ui.HScroll(
                    ground = colors.raised,
                    spacing = 6.dp,
                ) {
                    for (action in barActions) {
                        FootButton(
                            action.icon,
                            action.label,
                            if (action.danger) colors.bad else colors.fg2,
                            blocked = action.blocked,
                            onBlocked = vm::toast,
                            onClick = action.run,
                        )
                    }
                }
            }
            // Select all, pinned beside More rather than mixed into the actions.
            //
            // Having picked one file you often want the lot, and the only way to say so was the
            // toolbar overflow - a menu that is not where you are looking, in a bar that exists
            // because a selection is running. Copy and Delete act on the selection; All and the
            // cross CHANGE it.
            //
            // Gone once everything is already in, because then it does nothing. A control that
            // is present and inert teaches people to stop reading the bar.
            if (count < total) {
                Text(
                    "All",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.fg2,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { vm.focusedPane()?.selectAll() }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                )
            }
            // Drawn only when there is something behind it.
            if (more.isNotEmpty()) {
                BarGlyph(FiletIcons.More, "More actions") { moreOpen = true }
            }
        }
    }

    if (moreOpen) {
        val (_, more) = SelectionBarConfig.split(actions, onBar)
        ActionsPopup(
            actions = more,
            onBlocked = vm::toast,
            onDismiss = { moreOpen = false },
        )
    }
}

/** A glyph button at either end of the selection bar: Clear at the start, More at the edge. */
@Composable
private fun BarGlyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Icon(
        icon,
        label,
        tint = Filet.colors.fg2,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(9.dp),
    )
}

@Composable
private fun FootDivider() {
    Box(
        Modifier
            .padding(horizontal = 5.dp)
            .width(1.dp)
            .height(26.dp)
            .background(Filet.colors.lineSoft)
    )
}

/** The overflow cue: more that way. */
@Composable
private fun BoxScope.EdgeFade(ground: Color, side: Alignment) {
    // `matchParentSize` rather than `fillMaxHeight`, for the reason written out at the tab
    // strip: an overlay that fills the height can decide the parent's height instead of
    // following it, and the failure only shows once the row overflows.
    val stops = if (side == Alignment.CenterStart) {
        arrayOf(0f to ground, 0.07f to Color.Transparent, 1f to Color.Transparent)
    } else {
        arrayOf(0f to Color.Transparent, 0.93f to Color.Transparent, 1f to ground)
    }
    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(*stops)))
}

/**
 * @param blocked why this button cannot do its job right now, or null when it can.
 *
 * A blocked button is greyed **and still tappable**: it answers instead of acting. On a
 * desktop the answer would be a tooltip, but a phone has no hover, so a plain disabled
 * control leaves the user pressing a dead icon and guessing - which is exactly the failure
 * this is here to remove.
 */
@Composable
private fun FootButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Filet.colors.fg2,
    blocked: String? = null,
    onBlocked: (String) -> Unit = {},
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val enabled = blocked == null
    val shown = if (enabled) tint else colors.fg3.copy(alpha = 0.38f)
    // The caption goes back UNDER the icon, on a chip.
    //
    // Three shapes have been through here and the arithmetic is the whole argument. The
    // original was a 30dp circle over a 9.5sp caption at about 56dp, and it was too thick -
    // but only because a full-height nav bar sat underneath it, two bands over the list at
    // the moment you most want to see the list. Replacing it with a side-by-side pill got
    // the bar to 39dp and lost the captions, which were the part worth keeping.
    //
    // So: the stack returns, the circle does not. A bare 19dp glyph with the chip's rounding
    // doing the work the circle used to do, 10sp of caption, and tighter padding, which
    // measures 49dp against the mock - 7dp over the pill, 7dp under the original, and with
    // the nav bar now stepping aside the total chrome is still well down on the complaint.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) colors.high else Color.Transparent)
            .clickable { if (enabled) onClick() else onBlocked(blocked!!) }
            .widthIn(min = 52.dp)
            .padding(start = 9.dp, end = 9.dp, top = 5.dp, bottom = 4.dp),
    ) {
        Icon(icon, label, tint = shown, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 9.5.sp,
            lineHeight = 11.sp,
            color = if (enabled) colors.fg3 else shown,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun DragGhost(vm: BrowserViewModel, pos: Offset) {
    val app by vm.state.collectAsState()
    val drag = app.drag ?: return
    val colors = Filet.colors
    val density = LocalDensity.current
    val plan = drag.drop
    val refused = plan?.refusal != null
    val edge = when {
        refused -> colors.bad
        plan != null -> colors.accent
        else -> colors.lineSoft
    }
    Column(
        Modifier
            .offset { IntOffset(pos.x.roundToInt() - 40, pos.y.roundToInt() - 110) }
            .clip(RoundedCornerShape(8.dp))
            .background(colors.high.copy(alpha = 0.94f))
            .border(1.dp, edge, RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            if (drag.items.size == 1) drag.items[0].name else "${drag.items.size} items",
            fontSize = 11.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // What releasing here would do, said BEFORE the release. A ghost that only carries a
        // filename tells you what you picked up, which you already know.
        Text(
            plan?.label ?: "Drop on a folder or the other pane",
            fontSize = 9.5.sp,
            maxLines = 1,
            color = if (refused) colors.bad else if (plan != null) colors.accent else colors.fg3,
        )
    }
}

@Composable
private fun Toast(message: String, onDone: () -> Unit) {
    LaunchedEffect(message) {
        kotlinx.coroutines.delay(1900)
        onDone()
    }
    val colors = Filet.colors
    // Measured from above the navigation bar: from the screen's edge, 70dp put a message over the
    // bottom bar whenever three-key navigation made that bar taller.
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 70.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            message,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

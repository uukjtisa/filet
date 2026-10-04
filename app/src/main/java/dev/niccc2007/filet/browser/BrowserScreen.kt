package dev.niccc2007.filet.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.data.TabSize
import dev.niccc2007.filet.data.ViewStep
import dev.niccc2007.filet.jobs.ActivitySheet
import dev.niccc2007.filet.ui.Motion
import dev.niccc2007.filet.ui.HScroll
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
import kotlin.math.roundToInt

/** Below this, the rail is a drawer rather than a permanent column. */
private const val RAIL_AT_DP = 720

/** A pane narrower than this stops being a pane (mock round 5). */
private const val MIN_PANE_DP = 132

@Composable
fun BrowserScreen(vm: BrowserViewModel) {
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
            app.activityOpen -> vm.openActivity(false)
            activeState?.search?.open == true -> active.openSearch(false)
            activeState?.selecting == true -> active.clearSelection()
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

        // The switcher pushes the whole app card away: translate 50%, scale .78, radius 26,
        // 500 ms on Trawl's PushEasing. No 3D rotation - upstream rejected it, and the
        // comment in TrawlSwitcher.kt says why.
        val push by animateFloatAsState(
            targetValue = if (pushed) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(Motion.SWITCHER, easing = Motion.Push),
            label = "switcherPush",
        )

        if (push > 0f) Switcher(vm, tabs)

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = size.width * 0.5f * push
                    scaleX = 1f - 0.22f * push
                    scaleY = 1f - 0.22f * push
                    shape = RoundedCornerShape((26 * push).dp)
                    clip = push > 0f
                }
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(Modifier.fillMaxSize()) {
                TabStrip(vm, tabs, app, showMenu = !wide)
                TopBar(vm, app, active, wide)
                HorizontalDivider(color = colors.lineSoft)

                // Below the tabs and the toolbar on purpose. It sat ABOVE both at first and
                // a strip over the top of the whole app looks like
                // a system dialog wrapped around Filet rather than like Filet doing something.
                // Down here it is one of the app's own bars, and the action that completes the
                // pick lives in the selection bar with every other action.
                if (vm.picking) PickNotice(vm, activeState?.selected?.size ?: 0)

                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (wide) {
                        Rail(vm, app)
                        androidx.compose.material3.VerticalDivider(color = colors.lineSoft)
                    }
                    Body(vm, app, paneA, paneB, registry) { ghost = it }
                }

                if (activeState?.selecting == true) {
                    SelectionBar(vm, activeState.selected.size, vm.writeBlockReason(activeState.cwd))
                }
                if (!wide) BottomNav(vm, app, active)
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

        if (app.activityOpen) {
            ActivitySheet(vm.ledger, onClose = { vm.openActivity(false) })
        }

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
    var dragging by remember { mutableStateOf(-1) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val target = if (dragging < 0) -1 else dragTarget(dragging, dragX, (0 until tabs.size).map { centres[it] ?: 0f })

    run {
        tabs.forEachIndexed { i, pane ->
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
                onClick = { vm.focusTab(i) },
                onClose = { vm.closeTab(i) },
                onCentre = { centres[i] = it },
                onDragStart = { dragging = i; dragX = centres[i] ?: 0f },
                onDrag = { dx -> dragX += dx },
                onDragEnd = {
                    // Committed on lift rather than per frame: rebuilding the list sixty times
                    // a second re-keys every chip and makes the drag stutter.
                    if (dragging >= 0 && target >= 0) vm.moveTab(dragging, target)
                    dragging = -1
                },
            )
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
    onClick: () -> Unit,
    onClose: () -> Unit,
    onCentre: (Float) -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    val colors = Filet.colors
    // See the note on the drag detector below: these must be read live, not captured.
    val liveDragStart = rememberUpdatedState(onDragStart)
    val liveDrag = rememberUpdatedState(onDrag)
    val liveDragEnd = rememberUpdatedState(onDragEnd)
    Row(
        Modifier
            .padding(horizontal = 2.dp, vertical = 3.dp)
            .onGloballyPositioned {
                onCentre(it.positionInParent().x + it.size.width / 2f)
            }
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
    PaneKind.RECENT -> FiletIcons.Clock
    PaneKind.HISTORY -> FiletIcons.Clock
    PaneKind.SHORTCUTS -> FiletIcons.Home
    PaneKind.ACTIVITY -> FiletIcons.Jobs
    PaneKind.SCRIPTS -> FiletIcons.Script
    PaneKind.APPS -> FiletIcons.Apk
    PaneKind.NEARBY -> FiletIcons.Wifi
    PaneKind.REMOTES -> FiletIcons.Device
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
                SplitMode.STACK -> FiletIcons.SplitV
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
    NavButton(FiletIcons.Refresh, "Refresh", size, enabled = active != null) {
        active?.let { vm.refreshPane(it) }
    }
    Spacer(Modifier.width(2.dp))
}

@Composable
private fun NavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
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
            .padding(size * 0.22f),
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
            Breadcrumb(s, vm, Modifier.weight(1f)) { active?.navigateTo(it) }
            Icon(
                FiletIcons.Copy, "Copy path", tint = colors.fg3,
                modifier = Modifier.size(21.dp).clickable { vm.copyPathToClipboard(s.cwd) }.padding(4.dp),
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
 */
private fun crumbsOf(cwd: VPath, nameForId: (String, String) -> String? = { _, _ -> null }): List<Crumb> {
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
    val crumbs = remember(cwd) { crumbsOf(cwd) { scheme, id -> vm.connectionName(scheme, id) } }
    val scroll = rememberScrollState()
    LaunchedEffect(cwd) { scroll.scrollTo(scroll.maxValue) }
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
    if (app.split == SplitMode.OFF || paneB == null) {
        PaneView(paneA, vm, Side.A, true, registry, onGhost, Modifier.fillMaxSize())
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontal = app.split == SplitMode.SIDE
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

        if (horizontal) {
            Row(Modifier.fillMaxSize()) {
                PaneView(paneA, vm, Side.A, app.focused == Side.A, registry, onGhost, Modifier.weight(f))
                Divider(vertical = true, modifier = dividerDrag)
                PaneView(paneB, vm, Side.B, app.focused == Side.B, registry, onGhost, Modifier.weight(1f - f))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                PaneView(paneA, vm, Side.A, app.focused == Side.A, registry, onGhost, Modifier.weight(f))
                Divider(vertical = false, modifier = dividerDrag)
                PaneView(paneB, vm, Side.B, app.focused == Side.B, registry, onGhost, Modifier.weight(1f - f))
            }
        }
    }
}

/** The grip is always faintly visible: a drag handle nobody can see is not a drag handle. */
@Composable
private fun Divider(vertical: Boolean, modifier: Modifier) {
    val colors = Filet.colors
    Box(
        modifier
            .then(if (vertical) Modifier.width(6.dp).fillMaxHeight() else Modifier.height(6.dp).fillMaxWidth())
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
private fun Rail(vm: BrowserViewModel, app: AppState) {
    val colors = Filet.colors
    val bookmarks by vm.bookmarks.items.collectAsState()
    val recents by vm.recents.items.collectAsState()
    val jobs by vm.ledger.jobs.collectAsState()
    val remotesRevision by vm.remotesRevision.collectAsState()
    val connections = remember(remotesRevision) { vm.connections.all() }
    val here = vm.focusedPane()?.state?.collectAsState()?.value?.cwd

    // Folders only, and the count is what makes Quick access mean anything - see QuickAccess
    // for why a pin outranks a count and why the two groups never repeat each other.
    val visited = recents.filter { it.isDir }.map {
        QuickAccess.Entry(it.path, it.label, pinned = false, visits = it.visits, lastAt = it.at)
    }
    val pinned = bookmarks.map {
        QuickAccess.Entry(it.path, it.label, pinned = true, visits = 0, lastAt = 0)
    }
    val quick = QuickAccess.quick(pinned, visited)
    val recent = QuickAccess.recent(visited, quick)

    Column(
        Modifier
            .width(212.dp)
            .fillMaxHeight()
            .background(colors.raised)
            .verticalScroll(rememberScrollState()),
    ) {
        // Quick access leads, the way Explorer's pane does, because navigation in a file
        // manager is overwhelmingly repetitive - the same few folders, over and over - and
        // making somebody walk down to them every time re-derives what the app already knows.
        if (quick.isNotEmpty()) {
            SectionLabel("Quick access")
            quick.forEach { q ->
                RailItem(
                    if (q.pinned) FiletIcons.Star else FiletIcons.Folder,
                    q.label,
                    current = here == q.path,
                ) { vm.focusedPane()?.navigateTo(q.path) }
            }
        }

        if (recent.isNotEmpty()) {
            SectionLabel("Recent")
            recent.forEach { r ->
                RailItem(FiletIcons.Clock, r.label, current = here == r.path) {
                    vm.focusedPane()?.navigateTo(r.path)
                }
            }
        }

        SectionLabel("This device")
        app.volumes.forEach { v ->
            RailItem(FiletIcons.Storage, v.label, current = here == v.node.path) {
                vm.focusedPane()?.navigateTo(v.node.path)
            }
        }

        // Saved remotes by name, rather than one row reading "Remotes" that costs a tap to find
        // out what is behind it. The list is short by nature and this is the pane where a
        // network location is supposed to be as reachable as a local one.
        if (connections.isNotEmpty()) {
            SectionLabel("Network")
            connections.take(8).forEach { c ->
                RailItem(FiletIcons.Device, c.label.ifEmpty { c.host }) { vm.openConnection(c) }
            }
        }

        SectionLabel("Places")
        RailItem(FiletIcons.Home, "Home") { vm.focusedPane()?.openHome() }
        // The same list the switcher draws. See SpecialPanes for why this is one declaration.
        SpecialPanes.DESTINATIONS.forEach { d ->
            RailItem(d.icon, d.label) { vm.focusedPane()?.openSpecial(d.kind, d.label) }
        }

        Spacer(Modifier.weight(1f))
        RailItem(FiletIcons.Jobs, "Activity", badge = jobs.count { it.running }) { vm.openActivity(true) }
        RailItem(FiletIcons.Cog, "Settings") { vm.focusedPane()?.openSpecial(PaneKind.SETTINGS, "Settings") }
        RailItem(FiletIcons.Info, "About") { vm.focusedPane()?.openSpecial(PaneKind.ABOUT, "About") }
    }
}

@Composable
private fun RailItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    badge: Int = 0,
    indent: Boolean = false,
    /** The pane is showing this place right now, so the row says so. */
    current: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (current) colors.sel else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = if (indent) 20.dp else 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.fg2, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(9.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (badge > 0) {
            Text(
                "$badge", fontSize = 9.sp, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(colors.accent).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun BottomNav(vm: BrowserViewModel, app: AppState, active: PaneController?) {
    val colors = Filet.colors
    val jobs by vm.ledger.jobs.collectAsState()
    val stored by vm.prefs.bottomBar.collectAsState()
    val items = remember(stored) { BottomBarConfig.normalise(stored) }

    val row: @Composable () -> Unit = {
        for (item in items) {
            val badge = if (item == BarItem.ACTIVITY) jobs.count { it.running } else 0
            NavItem(barIcon(item), item.label, badge) { runBarItem(item, vm, active) }
        }
    }

    val base = Modifier
        .fillMaxWidth()
        .background(colors.raised)
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(vertical = 4.dp)

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
    BarItem.HOME -> FiletIcons.Home
    BarItem.RECENT -> FiletIcons.Clock
    BarItem.SHARING -> FiletIcons.Wifi
    BarItem.SCRIPTS -> FiletIcons.Script
    BarItem.SHORTCUTS -> FiletIcons.Pin
    BarItem.SPLIT -> FiletIcons.SplitV
    BarItem.NEW_TAB -> FiletIcons.Plus
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
        BarItem.ACTIVITY -> vm.openActivity(true)
        BarItem.BOOKMARKS -> active?.openSpecial(PaneKind.BOOKMARKS, "Bookmarks")
        BarItem.HOME -> active?.openHome()
        BarItem.RECENT -> active?.openSpecial(PaneKind.RECENT, "Recent")
        BarItem.SHARING -> active?.openSpecial(PaneKind.NEARBY, "Share")
        BarItem.SCRIPTS -> active?.openSpecial(PaneKind.SCRIPTS, "Scripts")
        BarItem.SHORTCUTS -> active?.openSpecial(PaneKind.SHORTCUTS, "Shortcuts")
        BarItem.SPLIT -> vm.cycleSplit()
        BarItem.NEW_TAB -> vm.addTab()
        BarItem.SETTINGS -> active?.openSpecial(PaneKind.SETTINGS, "Settings")
    }
}

@Composable
private fun NavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(9.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Box {
            Icon(icon, null, tint = colors.fg2, modifier = Modifier.size(19.dp))
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.accent)
                )
            }
        }
        Text(label, fontSize = 9.5.sp, color = colors.fg3)
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
private fun SelectionBar(vm: BrowserViewModel, count: Int, readOnly: String?) {
    val colors = Filet.colors
    val onBar = SelectionBarConfig.normalise(vm.prefs.selectionBar.collectAsState().value)
    var moreOpen by remember { mutableStateOf(false) }
    val single = count == 1

    val actions = selectionActions(
        count = count,
        readOnly = readOnly,
        icons = SelectionIcons(
            copy = FiletIcons.Copy, cut = FiletIcons.Cut, share = FiletIcons.Share,
            delete = FiletIcons.Delete, zip = FiletIcons.Zip, rename = FiletIcons.Rename,
            open = FiletIcons.Open, info = FiletIcons.Info, star = FiletIcons.Star,
            wifi = FiletIcons.Wifi, home = FiletIcons.Home,
            apk = FiletIcons.Apk, cog = FiletIcons.Cog,
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
            extractTo = { vm.extractSelectionToPicked() },
            extractToOtherPane = { vm.extractSelectionToOtherPane() },
        ),
        archive = vm.selectionIsArchive(),
        otherPane = vm.isSplit(),
        picking = vm.picking,
        installable = vm.selectionIsInstallable(),
        hasMetadata = vm.selectionHasMetadata(),
    )

    Column(Modifier.fillMaxWidth().background(colors.raised)) {
        HorizontalDivider(color = colors.lineSoft)
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (single) "1 selected" else "$count selected",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.accent,
            )
            Spacer(Modifier.weight(1f))
            // While picking, the thing that finishes the job sits with the other actions
            // rather than in a bar of its own, and it is the emphasised one because it is the
            // only reason this screen is open.
            if (vm.picking) {
                Text(
                    if (count > 1) "Use these" else "Use this",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.sel)
                        .clickable { vm.onPickConfirm?.invoke() }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            // Everything not on the bar, behind one button. The bar cannot hold fifteen
            // actions and a menu puts the four anybody uses two taps away, so the split is the
            // answer and WHICH four is the setting.
            val (barActions, more) = SelectionBarConfig.split(actions, onBar)
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
            if (more.isNotEmpty()) {
                Text(
                    "More",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.fg2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { moreOpen = true }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
            Text(
                "Clear",
                fontSize = 11.5.sp,
                color = colors.fg2,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .clickable { vm.focusedPane()?.clearSelection() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { if (enabled) onClick() else onBlocked(blocked!!) }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (enabled) colors.high else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = shown, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 9.sp, color = shown, maxLines = 1)
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
    Box(Modifier.fillMaxSize().padding(bottom = 70.dp), contentAlignment = Alignment.BottomCenter) {
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

/**
 * The strip that says a file is being chosen for somebody else.
 *
 * The only thing on screen explaining why Filet opened without being opened, so it is always
 * visible while picking - but it is a line of the app's own chrome rather than a lid over it.
 * It carries the count as it changes, so "nothing chosen yet" and "2 chosen" are the same
 * sentence moving rather than a bar appearing from nowhere.
 *
 * Cancel lives here because there has to be a way out that is not the back button, and the
 * confirm deliberately does NOT: it belongs with the other actions, in the selection bar, where
 * somebody looks after choosing something.
 */
@Composable
private fun PickNotice(vm: BrowserViewModel, count: Int) {
    val colors = Filet.colors
    val request = vm.pickRequest ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.high)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FiletIcons.Check,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                vm.pickCaller?.let { "Choosing ${request.what} for $it" } ?: "Choosing ${request.what}",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    count == 0 && request.allowMultiple -> "Hold a file to select — one or more"
                    count == 0 -> "Hold a file to select it"
                    count == 1 -> "1 chosen — Use this, below"
                    else -> "$count chosen — Use these, below"
                },
                fontSize = 9.5.sp,
                color = colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "Cancel",
            fontSize = 11.5.sp,
            color = colors.fg2,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { vm.onPickCancel?.invoke() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    HorizontalDivider(color = colors.lineSoft)
}

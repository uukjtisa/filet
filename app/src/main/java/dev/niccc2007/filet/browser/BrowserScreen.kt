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
    val active = if (app.focused == Side.A || app.split == SplitMode.OFF) paneA else paneB
    val activeState = active?.state?.collectAsState()?.value

    BackHandler(enabled = true) {
        when {
            app.switcherOpen -> vm.openSwitcher(false)
            app.activityOpen -> vm.openActivity(false)
            activeState?.search?.open == true -> active.openSearch(false)
            activeState?.selecting == true -> active.clearSelection()
            active?.goBack() == true -> Unit
            active?.goUp() == true -> Unit
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
            Row(
                Modifier.horizontalScroll(scroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabStripContent(vm, tabs, app)
            }
            // Edge fades, shown only on the side there is actually more to see. A row of
            // tabs that overflows with a hard edge looks like a row of tabs that ends.
            if (scroll.value > 0) {
                Box(
                    Modifier.align(Alignment.CenterStart).fillMaxHeight().width(18.dp)
                        .background(
                            Brush.horizontalGradient(listOf(colors.sunken, Color.Transparent))
                        )
                )
            }
            if (scroll.value < scroll.maxValue) {
                Box(
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(18.dp)
                        .background(
                            Brush.horizontalGradient(listOf(Color.Transparent, colors.sunken))
                        )
                )
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
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                ) { change, drag ->
                    change.consume()
                    onDrag(drag.x)
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
    PaneKind.SHORTCUTS -> FiletIcons.Home
    PaneKind.ACTIVITY -> FiletIcons.Jobs
    PaneKind.SCRIPTS -> FiletIcons.Script
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
            Breadcrumb(s, Modifier.weight(1f)) { active?.navigateTo(it) }
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
private fun crumbsOf(cwd: VPath): List<Crumb> {
    val cut = cwd.path.indexOf('!')
    if (cut < 0) {
        val segs = cwd.segments
        return segs.mapIndexed { i, seg ->
            Crumb(seg, VPath.of(cwd.scheme, "/" + segs.take(i + 1).joinToString("/")))
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
private fun Breadcrumb(s: PaneState, modifier: Modifier, onNavigate: (VPath) -> Unit) {
    val colors = Filet.colors
    val cwd = s.cwd
    if (cwd == null) {
        Text(s.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
        return
    }
    val crumbs = remember(cwd) { crumbsOf(cwd) }
    val scroll = rememberScrollState()
    LaunchedEffect(cwd) { scroll.scrollTo(scroll.maxValue) }
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
            detectDragGestures(
                onDragEnd = { vm.prefs.setSplitRatio(live) },
                onDrag = { change, delta ->
                    change.consume()
                    val px = if (horizontal) delta.x else delta.y
                    val totalPx = with(density) { totalDp.dp.toPx() }
                    // Work in FRACTIONS of the body, not pixels: the same maths then behaves
                    // identically at every density and in every scaled preview.
                    live = (live + px / totalPx).coerceIn(minFrac, 1f - minFrac)
                },
            )
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
    val jobs by vm.ledger.jobs.collectAsState()
    Column(
        Modifier
            .width(184.dp)
            .fillMaxHeight()
            .background(colors.raised)
            .verticalScroll(rememberScrollState()),
    ) {
        SectionLabel("Storage")
        app.volumes.forEach { v ->
            RailItem(FiletIcons.Storage, v.label) { vm.focusedPane()?.navigateTo(v.node.path) }
        }
        SectionLabel("Places")
        RailItem(FiletIcons.Home, "Home") { vm.focusedPane()?.openHome() }
        RailItem(FiletIcons.Clock, "Recent") { vm.focusedPane()?.openSpecial(PaneKind.RECENT, "Recent") }
        RailItem(FiletIcons.Star, "Bookmarks") { vm.focusedPane()?.openSpecial(PaneKind.BOOKMARKS, "Bookmarks") }
        RailItem(FiletIcons.Wifi, "Nearby") { vm.focusedPane()?.openSpecial(PaneKind.NEARBY, "Nearby") }
        RailItem(FiletIcons.Device, "Remotes") { vm.focusedPane()?.openSpecial(PaneKind.REMOTES, "Remotes") }
        RailItem(FiletIcons.Script, "Scripts") { vm.focusedPane()?.openSpecial(PaneKind.SCRIPTS, "Scripts") }
        if (bookmarks.isNotEmpty()) {
            bookmarks.take(8).forEach { b ->
                RailItem(FiletIcons.Folder, b.label, indent = true) { vm.focusedPane()?.navigateTo(b.path) }
            }
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
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (indent) 26.dp else 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
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
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.raised)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Used to be `if not already a folder, go Home`, which meant it did nothing at all
        // whenever you were looking at files - i.e. almost always. It now goes to storage,
        // and to Home when you are already there, so it always does something.
        NavItem(FiletIcons.Storage, "Files") { vm.goToFiles() }
        NavItem(FiletIcons.Search, "Search") { active?.openSearch(true) }
        NavItem(FiletIcons.Jobs, "Activity", jobs.count { it.running }) { vm.openActivity(true) }
        NavItem(FiletIcons.Star, "Bookmarks") { active?.openSpecial(PaneKind.BOOKMARKS, "Bookmarks") }
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

@Composable
private fun SelectionBar(vm: BrowserViewModel, count: Int, readOnly: String?) {
    val colors = Filet.colors
    // Actions that only make sense for exactly one file are DISABLED, not hidden.
    //
    // Hiding them makes the bar jump every time the second item is picked, and leaves the
    // user wondering where "Open with" went. Greying it out answers the question - you
    // selected two things - which is the whole point of R1 applied to a toolbar.
    val single = count == 1
    val scroll = rememberScrollState()
    val onlyOne = if (single) null else "Pick one file — this opens a single file"

    Column(Modifier.fillMaxWidth().background(colors.raised)) {
        HorizontalDivider(color = colors.lineSoft)
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (single) "1 selected" else "$count selected",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.accent,
            )
            Spacer(Modifier.weight(1f))
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

        // The row is scrollable and now SAYS so: a fading edge on whichever side has more
        // behind it. Nic's complaint was not that it could not scroll - it was that nothing
        // on screen suggested it could, so the last three actions may as well not exist.
        val atStart = scroll.value <= 2
        val atEnd = scroll.value >= scroll.maxValue - 2
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scroll)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // The four that work on any number of files, and the four people reach for.
                FootButton(FiletIcons.Copy, "Copy", onBlocked = vm::toast) { vm.copySelection() }
                FootButton(FiletIcons.Cut, "Move", blocked = readOnly, onBlocked = vm::toast) { vm.cutSelection() }
                FootButton(FiletIcons.Share, "Send", onBlocked = vm::toast) { vm.shareSelection() }
                FootButton(
                    FiletIcons.Delete, "Delete", colors.bad,
                    blocked = readOnly, onBlocked = vm::toast,
                ) { vm.confirmDelete() }

                FootDivider()

                FootButton(FiletIcons.Zip, "Compress", blocked = readOnly, onBlocked = vm::toast) { vm.askCompress() }
                FootButton(
                    FiletIcons.Rename, "Rename",
                    blocked = readOnly ?: if (single) null else "Pick one file — rename takes one name at a time",
                    onBlocked = vm::toast,
                ) { vm.renameSelection() }
                FootButton(FiletIcons.Open, "Open with", blocked = onlyOne, onBlocked = vm::toast) { vm.openWithSelection() }
                FootButton(
                    FiletIcons.Info, "Details",
                    blocked = if (single) null else "Pick one file — details describe one file",
                    onBlocked = vm::toast,
                ) { vm.detailsForSelection() }

                FootDivider()

                FootButton(FiletIcons.Star, "Bookmark", onBlocked = vm::toast) { vm.bookmarkSelection() }
                FootButton(FiletIcons.Wifi, "Nearby", onBlocked = vm::toast) { vm.shareSelectionNearby() }
                FootButton(FiletIcons.Home, "Shortcut", onBlocked = vm::toast) { vm.shortcutSelection() }
            }
            // Drawn over the row, so a half-visible button fades into the edge rather than
            // being cut off mid-icon - which is what made it read as a broken layout.
            if (!atStart) EdgeFade(colors.raised, Alignment.CenterStart)
            if (!atEnd) EdgeFade(colors.raised, Alignment.CenterEnd)
        }
    }
}

/** A hairline between groups, so ten buttons read as three things rather than one wall. */
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
    Box(
        Modifier
            .align(side)
            .fillMaxHeight()
            .width(22.dp)
            .background(
                Brush.horizontalGradient(
                    if (side == Alignment.CenterStart) listOf(ground, Color.Transparent)
                    else listOf(Color.Transparent, ground)
                )
            )
    )
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

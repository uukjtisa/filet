@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.niccc2007.filet.home

import dev.niccc2007.filet.ui.Labels
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.niccc2007.filet.data.FiletStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.PaneController
import dev.niccc2007.filet.browser.PaneKind
import dev.niccc2007.filet.browser.SectionLabel
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import dev.niccc2007.filet.browser.SectionSpec
import dev.niccc2007.filet.browser.SectionMenu
import dev.niccc2007.filet.browser.SwitcherRow
import dev.niccc2007.filet.browser.SwitcherGroup
import dev.niccc2007.filet.browser.ago
import dev.niccc2007.filet.browser.chosenForPick
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgAction
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCtxHead
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.tabs.EmptyTab
import dev.niccc2007.filet.ui.tabs.StorageTile
import dev.niccc2007.filet.ui.tabs.Tiles
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode

/**
 * The landing page: storage, what just arrived, and what you touched last.
 *
 * Always a list, and scaled on its own damped curve rather than the pane's view step - Home
 * must not follow the grid's jump to a 56px icon (mock round 3).
 *
 * Sections with nothing in them are not rendered. An empty "New downloads" heading is a dead
 * switch (PLAN.md R1) dressed up as a layout.
 *
 * ## The edit state
 *
 * A hold on any empty part of Home, or Edit Home on a held heading, puts this same screen into
 * its edit state ([PaneController.homeEditing]); see holdOnEmpty for why the header has no
 * control for it. Each card below is one function drawn in both states, so
 * what is edited is the card itself and not a stand-in for it: the list of names this replaced
 * took every card off the screen while it was up, and a density slider whose effect cannot be
 * seen needed a preview to explain itself. See HomeEdit.kt.
 */
@Composable
fun HomeOverview(vm: BrowserViewModel, pane: PaneController) {
    val app by vm.state.collectAsState()
    val downloads by vm.home.recent.collectAsState()
    val recents by vm.recents.items.collectAsState()
    val folds by vm.prefs.homeFolds.collectAsState()
    val scanning by vm.home.loading.collectAsState()
    val hideStorage by vm.prefs.hideStorage.collectAsState()
    val hiddenCards by vm.prefs.hiddenCards.collectAsState()
    val arrivedCount by vm.prefs.homeArrivedCount.collectAsState()
    val arrivedDensity by vm.prefs.homeArrivedDensity.collectAsState()
    val openedCount by vm.prefs.homeOpenedCount.collectAsState()
    val openedDensity by vm.prefs.homeOpenedDensity.collectAsState()
    val feedLinked by vm.prefs.homeFeedLinked.collectAsState()
    val driveLayout = HomeArrange.Layout.of(vm.prefs.homeDriveLayout.collectAsState().value)
    val perApp = HomeArrange.PerApp.of(vm.prefs.homeDrivePerApp.collectAsState().value)
    val bars by vm.prefs.homeDriveBars.collectAsState()
    val driveOrder by vm.prefs.homeDriveOrder.collectAsState()
    val driveGroups = HomeArrange.groupOrder(vm.prefs.homeDriveGroups.collectAsState().value)
    val categoriesHidden by vm.prefs.homeCategoriesHidden.collectAsState()
    val categoryLayouts by vm.prefs.categoryLayouts.collectAsState()
    val coachSeen by vm.prefs.homeEditCoachSeen.collectAsState()
    val editing by pane.homeEditing.collectAsState()
    val arrivedRows = FeedCard.metrics(arrivedDensity)
    val openedRows = FeedCard.metrics(openedDensity)
    // The New files card kept current while Home is on screen, as the New files tab is. Not when
    // the card is hidden: nothing on Home would show what the re-read found.
    if (HomeCards.ARRIVED !in hiddenCards) KeepNewFilesCurrent(vm)
    val colors = Filet.colors
    /**
     * The row a long press opened, and whether it came from the OPENED feed.
     *
     * Both feeds share one sheet, and the sheet was always offering "Remove from this list" -
     * which is an action on the recents list and means nothing for a file that turned up on
     * the phone. There is no list to remove it from; the file is simply there. So the action
     * is absent for an arrival rather than present and lying about what it does.
     */
    var menuFor by remember { mutableStateOf<Pair<VNode, Boolean>?>(null) }
    val storedOrder by vm.prefs.homeOrder.collectAsState()
    val storedHidden by vm.prefs.homeHidden.collectAsState()
    val homeLayout = dev.niccc2007.filet.data.SectionLayout.of(HomeCards.ALL.map { it.id }, storedOrder, storedHidden)
    /** The card whose heading was held, for its Edit Home / Hide menu. */
    var sectionMenu by remember { mutableStateOf<String?>(null) }
    val holdHeading = { id: String ->
        Modifier.pointerInput(id) { detectTapGestures(onLongPress = { sectionMenu = id }) }
    }
    /**
     * The held drive and which of its dialogues is up. By path rather than by the card, so the
     * dialogue reads the card as it is now - Details shows a figure that arrived while open.
     */
    var driveFor by remember { mutableStateOf<Pair<dev.niccc2007.filet.vfs.VPath, DriveDialog>?>(null) }
    /** The one-per-app card whose folders are listed, by its tile id. */
    var appOpen by remember { mutableStateOf<String?>(null) }
    val listState = dev.niccc2007.filet.browser.rememberKeptListState("home")
    val viewport = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    /**
     * Anything done in the edit state: the first line of help goes for good, and an Undo left by
     * Reset gives way to the change, as the header promises.
     */
    val touch = {
        vm.prefs.setHomeEditCoachSeen()
        pane.setHomeUndo(null)
    }

    LaunchedEffect(app.revision) { vm.home.refresh() }

    // Re-read the volumes while this tab is up, so a mounted share that comes back online
    // updates its card instead of staying Offline for the rest of the session.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val job = vm.watchVolumes()
        onDispose {
            job.cancel()
            // Leaving Home ends its edit state. Only leaving: a rotation disposes this screen
            // too, and the pane is still on Home when it comes back.
            if (pane.state.value.kind != PaneKind.HOME) pane.setHomeEditing(false)
        }
    }

    // No discovery on this tab.
    //
    // A mounted place is a volume and appears as a storage card like any other, with its size
    // read through the VFS. Listing what is merely ON the network is a different question and it
    // belongs on Remotes, where adding one is the thing somebody came to do.

    // ── the drives ───────────────────────────────────────────────────────────────────────────
    //
    // A NETWORK drive that is not answering is not shown at all.
    //
    // It was drawn as an "Offline" card, which reads as a fault to go and fix and takes a full
    // tile to say nothing actionable. With several addresses per remote there is also no longer a
    // single thing to report as down: the card comes back by itself the moment any address answers.
    //
    // The test used to be "local, or it has figures", and that is wider than it reads: `root:` and
    // `saf:` are not the local scheme either, so a superuser shell that was busy or a document
    // provider that declined for a moment took those cards off Home as well - the drive figures
    // disappearing as if Filet had lost the device, from nothing more than one slow measurement. A
    // volume on this device is shown whether or not its size is known; only something reached over
    // the network has to prove it is there.
    val live = app.volumes.filter { !it.remote || (it.free != null && it.total != null) }
    val volumeOf = live.associateBy { it.node.path.toString() }
    // Termux, if it is here and not yet connected. Shown only when installed, so this is not a
    // permanent advertisement for an app somebody does not have - and it disappears once its tree
    // is granted, because at that point it is a volume like any other.
    //
    // Dismissible, through `hiddenCards` - the same mechanism every storage card uses. Its own
    // `hideTermux` preference was removed with the settings cleanup, and the replacement reasoning
    // was that the tile disappears once its tree is granted. That was wrong: somebody who has
    // Termux installed and does not want it connected to Filet has no way to grant the tree, so the
    // tile became permanent. A card nobody can dismiss is bloat on the one screen that opens first.
    val termuxOffered = vm.termuxInstalled && app.volumes.none {
        dev.niccc2007.filet.integrations.Termux.isTermuxTree(android.net.Uri.parse(it.node.path.path)) ||
            it.systemLabel.startsWith("Termux")
    }
    // The Locker's card goes last, under the drives, and is hidden the way any card is.
    // A drive plugged in that the phone cannot read has no folder, so no volume - it still gets a
    // card saying so, rather than looking exactly like nothing being plugged in.
    val pluggedDrives by vm.drives.plugged.collectAsState()
    val unreadable = pluggedDrives.filter { it.path == null }
    val arriving by vm.drives.arrived.collectAsState()
    val jobs by vm.ledger.jobs.collectAsState()
    val natural = live.map { it.node.path.toString() } + unreadable.map { HomeArrange.unreadableCard(it.uuid) } +
        (if (termuxOffered) listOf(TERMUX_CARD) else emptyList()) + LOCKER_CARD
    val hiddenCount = natural.count { it in hiddenCards }

    val storageTiles: @Composable (Boolean) -> Unit = { edit -> Column(Modifier.fillMaxWidth()) {
        // Every drive while editing, the hidden ones faint, so a hidden drive can be put back
        // from where it was taken off.
        val arranged = HomeArrange.ordered(natural, driveOrder)
        val drawn = if (edit) arranged else arranged.filter { it !in hiddenCards }
        val tiles = HomeArrange.tiles(drawn, perApp)
        val byTile = tiles.associateBy { it.id }
        val slots = tiles.map { t ->
            DriveSlot(
                id = t.id,
                kind = if (t is HomeArrange.AppCard) HomeArrange.DriveKind.APPS
                else HomeArrange.kindOf(t.id, remote = volumeOf[t.id]?.remote == true),
                hidden = t.ids.all { it in hiddenCards },
            )
        }
        DriveRows(
            slots = slots,
            layout = driveLayout,
            groups = driveGroups,
            onGroupMove = { kind, by ->
                touch()
                val shown = slots.map { it.kind }.distinct()
                vm.prefs.setHomeDriveGroups(HomeArrange.movedGroup(driveGroups, shown, kind, by).map { it.name })
            },
            editing = edit,
            onEye = { id ->
                touch()
                val t = byTile[id]
                if (t != null) {
                    val hide = !t.ids.all { it in hiddenCards }
                    for (one in t.ids) if (hide) vm.prefs.hideCard(one) else vm.prefs.showCard(one)
                }
            },
            onDrop = { order -> vm.prefs.setHomeDriveOrder(HomeArrange.storedAs(driveOrder, tiles, order)) },
            onLift = touch,
        ) { slot, e ->
            when (val t = byTile.getValue(slot.id)) {
                is HomeArrange.AppCard -> AppFolderTile(
                    card = t,
                    volumes = volumeOf,
                    editing = e,
                    dimmed = slot.hidden,
                    bar = bars,
                    open = appOpen == t.id,
                    onOpen = { appOpen = t.id },
                    onClose = { appOpen = null },
                    onHide = { t.ids.forEach(vm.prefs::hideCard) },
                    onPick = { path -> appOpen = null; pane.jumpTo(path) },
                )
                is HomeArrange.Drive -> if (HomeArrange.isUnreadableCard(t.id)) {
                    val d = unreadable.firstOrNull { HomeArrange.unreadableCard(it.uuid) == t.id }
                    val kind = if (d?.kind == dev.niccc2007.filet.vfs.provider.Removables.Kind.USB) {
                        dev.niccc2007.filet.data.DriveRegistry.Kind.USB
                    } else {
                        dev.niccc2007.filet.data.DriveRegistry.Kind.SD
                    }
                    StorageTile(
                        icon = if (kind == dev.niccc2007.filet.data.DriveRegistry.Kind.USB) FiletIcons.Usb else FiletIcons.Sd,
                        kind = dev.niccc2007.filet.data.DriveRegistry.displayName(kind, d?.description.orEmpty()),
                        value = "Can't read",
                        valueSoft = true,
                        caption = "Its format isn't one this phone reads",
                        onHide = { vm.prefs.hideCard(t.id) },
                        editing = e,
                        dimmed = slot.hidden,
                        bar = bars,
                        onEject = vm::safelyRemove,
                        onClick = { vm.toast("This phone can't read that drive's format. A computer can copy its files off.") },
                    )
                } else if (t.id == LOCKER_CARD) {
                    dev.niccc2007.filet.locker.LockerTile(
                        vm = vm,
                        editing = e,
                        dimmed = slot.hidden,
                        bar = bars,
                        onHide = { vm.prefs.hideCard(LOCKER_CARD) },
                    )
                } else if (t.id == TERMUX_CARD) {
                    StorageTile(
                        icon = FiletIcons.Terminal,
                        kind = "Termux",
                        value = "Connect",
                        caption = "its home and usr/bin, as a volume",
                        onHide = { vm.prefs.hideCard(TERMUX_CARD) },
                        editing = e,
                        dimmed = slot.hidden,
                        bar = bars,
                        onClick = { vm.connectTermux(home = true) },
                    )
                } else {
                    val v = volumeOf.getValue(t.id)
                    VolumeTile(
                        v = v,
                        editing = e,
                        dimmed = slot.hidden,
                        bar = bars,
                        onHide = { vm.prefs.hideCard(v.node.path.toString()) },
                        onLongClick = { driveFor = v.node.path to DriveDialog.MENU },
                        onClick = { vm.openDrive(v) },
                        writing = DriveLineText.writingTo(jobs, v),
                        arriving = v.removable?.uuid?.let { it in arriving } == true,
                        onArrived = { v.removable?.uuid?.let { vm.drives.shown(setOf(it)) } },
                        onEject = if (v.removable != null) vm::safelyRemove else null,
                        onAllowWriting = { vm.allowWriting(v) },
                    )
                }
            }
        }
        // What is not plugged in, and a USB device the phone is not reading. Not while editing,
        // where only the cards are being arranged.
        if (!edit) DriveLines(vm)
    } }

    // ── the cards, each drawn the same in both states ────────────────────────────────────────

    // Files that turned up on the phone, newest first. Kept apart from Recently opened below,
    // which is files opened in Filet; each heading names its own cause, because a subtitle under a
    // heading is not read. See `HomeFeed` for what the list contains.
    //
    // The side bar's card fold, so Home and the drawer read as one design: the card shows its rows
    // - eight unless the edit state set another count - and its last row opens the full list.
    // Editing, it is drawn open whatever its fold, since a folded card says nothing about the
    // count and density being set on it.
    val arrivedCard: @Composable (Boolean) -> Unit = { edit ->
        if (downloads.isEmpty()) {
            if (edit) EditPlaceholder("Files that turn up on the phone show here.")
        } else {
            SwitcherGroup(
                title = HomeSections.ARRIVED,
                detail = "",
                open = edit || HomeFolds.ARRIVED !in folds,
                onToggle = { if (!edit) vm.prefs.toggleHomeFold(HomeFolds.ARRIVED) },
                onLongClick = if (edit) null else ({ sectionMenu = HomeCards.ARRIVED }),
                // The feed draws its last answer at once and corrects it as the read lands, so the
                // dots are what tell a finished list from one still being checked.
                trailing = if (scanning) ({ ScanningDots(); Spacer(Modifier.width(8.dp)) }) else null,
            ) {
                val card = downloads.take(FeedCard.snap(arrivedCount))
                for (d in card) {
                    FeedRow(
                        node = d.node,
                        when_ = ago(d.listedAt),
                        sub = d.origin,
                        metrics = arrivedRows,
                        onClick = { vm.openHomeEntry(d.node, card.map { it.node }) },
                        onLongClick = { menuFor = d.node to false },
                    )
                }
                FullListRow(FiletIcons.Download) { pane.openSpecial(PaneKind.HISTORY, HomeSections.ARRIVED_TAB) }
            }
        }
    }

    // Issue #4: all the photos at once, and the same for audio, video, packages and documents, so
    // the app can stand in for the gallery and the music player. Always drawn: it does not depend
    // on any other section having rows. The categories are on screen at once as tiles - a sideways
    // scroller would hide half of them behind a gesture nothing points at - in rows that divide
    // evenly; see CategoryTiles. The ones taken off in the edit state are not drawn.
    val categoriesCard: @Composable (Boolean) -> Unit = { edit ->
        SwitcherGroup(
            title = "Categories",
            detail = "",
            open = edit || HomeFolds.CATEGORIES !in folds,
            onToggle = { if (!edit) vm.prefs.toggleHomeFold(HomeFolds.CATEGORIES) },
            onLongClick = if (edit) null else ({ sectionMenu = HomeCards.CATEGORIES }),
        ) {
            CategoryTiles(MediaCategory.entries.filter { it.id !in categoriesHidden }) { c -> pane.openCategory(c) }
        }
    }

    val openedCard: @Composable (Boolean) -> Unit = { edit ->
        if (recents.isEmpty()) {
            if (edit) EditPlaceholder("Files you open in Filet show here.")
        } else {
            SwitcherGroup(
                title = HomeSections.OPENED,
                detail = "",
                open = edit || HomeFolds.OPENED !in folds,
                onToggle = { if (!edit) vm.prefs.toggleHomeFold(HomeFolds.OPENED) },
                onLongClick = if (edit) null else ({ sectionMenu = HomeCards.OPENED }),
            ) {
                val card = recents.take(FeedCard.snap(openedCount))
                for (r in card) {
                    val node = VNode(r.path, r.isDir, -1, r.at)
                    FeedRow(
                        node = node,
                        when_ = ago(r.at),
                        sub = r.path.parent?.path,
                        metrics = openedRows,
                        onClick = {
                            if (r.isDir) pane.jumpTo(r.path)
                            else vm.openHomeEntry(node, card.map { VNode(it.path, it.isDir, -1, it.at) })
                        },
                        onLongClick = { menuFor = node to true },
                    )
                }
                FullListRow(FiletIcons.Clock) { pane.openSpecial(PaneKind.RECENT, HomeSections.OPENED) }
            }
        }
    }

    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val enterEdit by androidx.compose.runtime.rememberUpdatedState {
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        pane.setHomeEditing(true)
    }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { viewport[0] = it.boundsInWindow() }
            // A hold on any empty part of Home opens the edit state. See holdOnEmpty for why it
            // cannot steal a row's hold or the scroll.
            .then(
                if (editing) Modifier
                else Modifier.pointerInput(Unit) {
                    holdOnEmpty { enterEdit() }
                },
            ),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
    ) {
        // No search card. It was a full row above the drives saying what the glyph in the pane's
        // header says on every other tab, so Home now uses that glyph and the field opens in the
        // header when it is wanted - the drives start at the top of the screen.
        if (editing) {
            item(key = "edit") {
                // Linked, either slider moves both; switching the link on takes the density of the
                // card it was switched on under, so the two meet at the one being looked at.
                val options: Map<String, @Composable () -> Unit> = mapOf(
                    HomeCards.STORAGE to {
                        StorageOptions(
                            layout = driveLayout,
                            onLayout = { touch(); vm.prefs.setHomeDriveLayout(it.key) },
                            perApp = perApp,
                            onPerApp = { touch(); vm.prefs.setHomeDrivePerApp(it.key) },
                            bars = bars,
                            onBars = { touch(); vm.prefs.setHomeDriveBars(it) },
                        )
                    },
                    HomeCards.ARRIVED to {
                        FeedOptions(
                            count = FeedCard.snap(arrivedCount),
                            onCount = { touch(); vm.prefs.setHomeArrivedCount(it) },
                            density = arrivedDensity,
                            onDensity = {
                                touch()
                                vm.prefs.setHomeArrivedDensity(it)
                                if (feedLinked) vm.prefs.setHomeOpenedDensity(it)
                            },
                            linked = feedLinked,
                            onLinked = { on ->
                                touch()
                                vm.prefs.setHomeFeedLinked(on)
                                if (on) vm.prefs.setHomeOpenedDensity(arrivedDensity)
                            },
                        )
                    },
                    HomeCards.OPENED to {
                        FeedOptions(
                            count = FeedCard.snap(openedCount),
                            onCount = { touch(); vm.prefs.setHomeOpenedCount(it) },
                            density = openedDensity,
                            onDensity = {
                                touch()
                                vm.prefs.setHomeOpenedDensity(it)
                                if (feedLinked) vm.prefs.setHomeArrivedDensity(it)
                            },
                            linked = feedLinked,
                            onLinked = { on ->
                                touch()
                                vm.prefs.setHomeFeedLinked(on)
                                if (on) vm.prefs.setHomeArrivedDensity(openedDensity)
                            },
                        )
                    },
                    HomeCards.CATEGORIES to {
                        CategoryOptions(
                            hidden = categoriesHidden,
                            onToggle = { id ->
                                touch()
                                vm.prefs.setHomeCategoriesHidden(
                                    HomeArrange.toggledCategory(MediaCategory.entries.map { it.id }, categoriesHidden, id),
                                )
                            },
                            layouts = categoryLayouts,
                            onLayout = { c, l -> touch(); vm.prefs.setCategoryLayout(c.id, l) },
                        )
                    },
                )
                HomeEditCards(
                    specs = HomeCards.ALL,
                    layout = homeLayout,
                    coach = !coachSeen,
                    listState = listState,
                    viewport = { viewport[0] },
                    onTouched = touch,
                    onLayout = vm.prefs::setHomeLayout,
                    options = options,
                ) { id ->
                    when (id) {
                        HomeCards.STORAGE -> storageTiles(true)
                        HomeCards.ARRIVED -> Inert { arrivedCard(true) }
                        HomeCards.CATEGORIES -> Inert { categoriesCard(true) }
                        HomeCards.OPENED -> Inert { openedCard(true) }
                        HomeCards.CACHE -> Inert { CacheReminder(vm, editing = true) {} }
                    }
                }
            }
        }
        // In whatever order somebody arranged them, without the ones they took off.
        for (card in if (editing) emptyList() else homeLayout.visible) {
            when (card) {
                HomeCards.STORAGE -> {
                    item(key = "storage:head") {
                        // Bringing back what was hidden, one card or the section. Without these the
                        // per-card X is a one-way door, which is not a control, it is a trap.
                        val links: @Composable () -> Unit = {
                            if (hiddenCount > 0) {
                                Text(
                                    "$hiddenCount hidden - show",
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    color = colors.accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { vm.prefs.showAllCards() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            Text(
                                if (hideStorage) "Show all" else "Hide all",
                                fontSize = 10.sp,
                                maxLines = 1,
                                color = colors.accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { vm.prefs.setHideStorage(!hideStorage) }
                                    .padding(start = 8.dp, end = 15.dp, top = 4.dp, bottom = 4.dp),
                            )
                        }
                        // Two links beside a heading need about 150dp, which a split pane does not have
                        // - so there the heading keeps the line and the links take the next one. Side by
                        // side the heading was squeezed to sixty dp and broke in half.
                        if (dev.niccc2007.filet.ui.narrowPane()) {
                            Column(Modifier.fillMaxWidth().then(holdHeading(HomeCards.STORAGE))) {
                                SectionLabel("Storage")
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) { links() }
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth().then(holdHeading(HomeCards.STORAGE)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SectionLabel("Storage", Modifier.weight(1f))
                                links()
                            }
                        }
                    }
                    // One flow row rather than one full-width card each, or a row per kind when
                    // grouped. Weighting inside the flow makes each line share the width it has, so
                    // three volumes read as two and one-that-fills rather than two and a gap.
                    if (!hideStorage) item(key = "storage:tiles") { storageTiles(false) }
                }
                HomeCards.ARRIVED -> if (downloads.isNotEmpty()) {
                    item(key = "card:" + HomeFolds.ARRIVED) { arrivedCard(false) }
                }
                HomeCards.CATEGORIES -> item(key = "card:" + HomeFolds.CATEGORIES) { categoriesCard(false) }
                HomeCards.OPENED -> if (recents.isNotEmpty()) {
                    item(key = "card:" + HomeFolds.OPENED) { openedCard(false) }
                }
                HomeCards.CACHE -> item(key = "card:cache") { CacheReminder(vm, editing = false) { sectionMenu = HomeCards.CACHE } }
            }
        }

        if (!editing && app.volumes.isEmpty() && downloads.isEmpty() && recents.isEmpty()) {
            item {
                EmptyTab(
                    FiletIcons.Storage,
                    "No storage is reachable yet",
                    "Grant all-files access, or pick a folder",
                )
            }
        }
    }

    sectionMenu?.let { id ->
        val spec = HomeCards.ALL.first { it.id == id }
        SectionMenu(
            spec,
            onRearrange = { pane.setHomeEditing(true) },
            onHide = { vm.prefs.setHomeLayout(homeLayout.hide(id, emptySet())) },
            onClose = { sectionMenu = null },
            rearrange = FiletIcons.Rename to "Edit Home",
        )
    }

    driveFor?.let { (path, dialog) ->
        val v = app.volumes.firstOrNull { it.node.path == path }
        if (v == null) {
            // The drive went away while its menu was open - a card pulled, a share dropped.
            LaunchedEffect(path) { driveFor = null }
        } else {
            val close = { driveFor = null }
            when (dialog) {
                DriveDialog.MENU -> DriveMenu(vm, v) { next -> driveFor = next?.let { path to it } }
                DriveDialog.RENAME -> RenameDriveDialog(vm, v, close)
                DriveDialog.DETAILS -> DriveDetailsDialog(v, close)
            }
        }
    }

    // What a Home row can do besides open.
    //
    // Open location is the one that was missing and the one that is wanted most:
    // Home shows you a file out of context, and the next thought is almost always "where does
    // this live".
    menuFor?.let { (node, fromOpened) ->
        HomeRowSheet(
            node = node,
            onDismiss = { menuFor = null },
            onReveal = { menuFor = null; vm.revealInFolder(node) },
            onOpen = { menuFor = null; vm.openHomeEntry(node) },
            onShare = { menuFor = null; vm.share(node) },
            onBookmark = { menuFor = null; vm.bookmarkOne(node) },
            onShortcut = { menuFor = null; vm.shortcutOne(node) },
            // Only the opened list has anything to forget. See `menuFor`.
            onForget = if (fromOpened) ({ menuFor = null; vm.forgetHomeEntry(node) }) else null,
        )
    }
}

/**
 * One volume's tile.
 *
 * A network volume is reachable or it is not, and that is a live fact rather than a stored
 * setting. A local one is simply there.
 *
 * `remote`, NOT a scheme test. This read `v.node.path.scheme != "local"` once, which is the mistake
 * the filter in [HomeOverview] was fixed for, left behind in the renderer: `root:` and `saf:` are
 * not the local scheme and are not network drives either. The visible result was `/data`,
 * `/system` and a granted media tree drawn with a Wi-Fi glyph, one of them captioned "Network
 * drive not answering" - about a partition on this phone. `VolumeInfo.remote` exists precisely to
 * be the one answer to this, and its own KDoc says so. Two readings of the same question is how
 * the filter got fixed and the icon did not.
 */
@Composable
private fun VolumeTile(
    v: dev.niccc2007.filet.browser.VolumeInfo,
    editing: Boolean,
    dimmed: Boolean,
    bar: Boolean,
    onHide: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    /** Jobs writing to this drive right now. Any at all makes the dot amber. */
    writing: List<dev.niccc2007.filet.jobs.Job> = emptyList(),
    arriving: Boolean = false,
    onArrived: () -> Unit = {},
    onEject: (() -> Unit)? = null,
    onAllowWriting: () -> Unit = {},
) {
    val used = if (v.free != null && v.total != null && v.total > 0) (v.total - v.free).coerceAtLeast(0) else null
    val net = v.remote
    val reachable = v.free != null && v.total != null
    val r = v.removable
    val busy = writing.isNotEmpty()
    val asksWriting = !busy && r?.needsGrant == true
    StorageTile(
        icon = when {
            net -> FiletIcons.Wifi
            r?.kind == dev.niccc2007.filet.data.DriveRegistry.Kind.USB -> FiletIcons.Usb
            r != null -> FiletIcons.Sd
            else -> FiletIcons.Storage
        },
        live = if (net) reachable else null,
        busy = busy,
        onEject = onEject,
        arriving = arriving,
        onArrived = onArrived,
        captionTone = when {
            busy -> dev.niccc2007.filet.ui.tabs.TileTone.WARN
            asksWriting -> dev.niccc2007.filet.ui.tabs.TileTone.ACT
            else -> dev.niccc2007.filet.ui.tabs.TileTone.PLAIN
        },
        onCaption = if (asksWriting) onAllowWriting else null,
        kind = v.label,
        // Free is the number being looked for, with the denominator underneath. "12 GB free"
        // alone says nothing about whether that is an empty card or a full phone.
        value = v.free?.let { humanSize(it) } ?: if (net) "Offline" else "—",
        caption = when {
            // While something is being written to it, the one thing worth saying about a drive
            // that can be pulled out.
            busy -> DriveLineText.writingCaption(writing)
            // Android 8 to 10: readable by path, writable only through a granted tree.
            asksWriting -> "Read only · Allow writing"
            used != null && v.total != null -> "free of ${humanSize(v.total)} · ${humanSize(used)} used"
            v.free != null -> "free"
            // A network drive that does not answer is not a drive with an unreadable size - it is
            // a drive that is not there, and saying so names something the reader can act on.
            net -> "Network drive not answering"
            // "Tap to open" was a promise this card could not keep. A volume with no readable size
            // is usually one of /storage's pseudo-directories, and tapping it did nothing at all.
            else -> "Size unknown - may not be readable"
        },
        // Only drawn when both figures are real. A bar at an invented percentage is a lie that
        // looks like data.
        fraction = if (used != null && v.total != null) used.toFloat() / v.total else null,
        onHide = onHide,
        onLongClick = onLongClick,
        editing = editing,
        dimmed = dimmed,
        bar = bar,
        onClick = onClick,
    )
}

/**
 * Several folders granted from one app, as one card: the app's name, how many folders, and their
 * names. A tap lists them, and picking one opens it. No capacity bar's figure, because a set of
 * folders lent by an app has no capacity of its own to show.
 */
@Composable
private fun AppFolderTile(
    card: HomeArrange.AppCard,
    volumes: Map<String, dev.niccc2007.filet.browser.VolumeInfo>,
    editing: Boolean,
    dimmed: Boolean,
    bar: Boolean,
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onHide: () -> Unit,
    onPick: (dev.niccc2007.filet.vfs.VPath) -> Unit,
) {
    val context = LocalContext.current
    // The app's own name, from the system: whoever answers for the folders' authority.
    val app = remember(card.authority) {
        runCatching {
            val pm = context.packageManager
            pm.resolveContentProvider(card.authority, 0)?.applicationInfo?.loadLabel(pm)?.toString()
        }.getOrNull() ?: card.authority
    }
    val members = card.ids.mapNotNull { volumes[it] }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var tall by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    Box(Modifier.onSizeChanged { tall = it.height }) {
        StorageTile(
            icon = FiletIcons.Storage,
            kind = app,
            value = "${members.size} folders",
            caption = members.joinToString(", ") { it.label },
            onHide = onHide,
            editing = editing,
            dimmed = dimmed,
            bar = bar,
            onClick = onOpen,
        )
        // Under the tile, not over it, so the card it came from stays in sight.
        dev.niccc2007.filet.ui.dialogs.PopMenu(expanded = open, onDismiss = onClose, below = with(density) { tall.toDp() }) {
            for (v in members) {
                dev.niccc2007.filet.ui.dialogs.PopRow(FiletIcons.Folder, v.label) { onPick(v.node.path) }
            }
        }
    }
}

/**
 * The actions a Home row offers.
 *
 * Not private, because the expanded tab and the search results show the same rows and must
 * offer the same actions. Two copies of this list would drift, and the drift would be silent -
 * a row that answers a long press differently depending on which screen it is on is worse than
 * one that does not answer at all.
 *
 * ## What the redesign changed here
 *
 * It was a stock `AlertDialog` holding six unadorned 13sp `Text` rows and a "Close" button.
 * Nothing about it was decided; it was the Material default with labels dropped in, which is
 * why it looked like a different app from the screen that opened it.
 *
 * Now on the same kit as every other dialogue, and the header earns its space: the name, then
 * the **path**, which on this sheet is the whole point. Every list that opens this one - New
 * files, Recent, the Home feed, a search result - shows files from somewhere other than where
 * you are standing, so "which IMG_2043.jpg is this" is the first question and it used to be
 * unanswerable without leaving.
 *
 * The verbs keep icons for the same reason the context menu's row does: five bare sentences
 * read slower than five glyphs, and the rows here are the same five verbs.
 */
/**
 * Says so when Filet's own cached files have grown worth noticing, and opens the folder.
 *
 * ## Why this exists at all
 *
 * The derived files moved out of Android's cache directory, which fixed a real fault - the
 * system empties that whenever the phone wants room, so beat maps costing two seconds of
 * analysis each were being thrown away within days - and created a new obligation. Nothing
 * clears the new folder but a person, so a cache that only ever grows would eventually be
 * sitting on a couple of gigabytes with no sign of where it had come from.
 *
 * This is what keeps "we keep it until you say otherwise" honest instead of sneaky. It stays
 * out of the way until there is something to say - see `FiletStore.shouldRemind`, which is
 * pure and tested because a reminder that fires at the wrong size is either nagging or
 * useless, and neither shows up until it is already in front of somebody.
 *
 * It leads somewhere rather than just reporting: tapping it opens the folder in a tab, where
 * each kind has its own named directory and the three that cannot be made again are not in
 * the cache folder at all.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CacheReminder(vm: BrowserViewModel, editing: Boolean, onLongClick: () -> Unit) {
    val colors = Filet.colors
    val context = LocalContext.current
    val where by vm.prefs.cacheLocation.collectAsState()
    // Measured off the main thread - it walks the folders - and only while the home screen is
    // actually showing, which is the one moment the answer is wanted.
    val bytes by produceState(0L, where) {
        value = withContext(Dispatchers.IO) {
            runCatching { FiletStore.sizeOf(FiletStore.cacheRoot(context, where)) }.getOrDefault(0L)
        }
    }
    val past by vm.prefs.cacheRemindPast.collectAsState()
    val dismissed by vm.prefs.cacheRemindDismissed.collectAsState()
    if (where != FiletStore.Location.FILET || !FiletStore.shouldRemind(bytes, past, dismissed)) {
        // Editing, the card still has a place to be moved and hidden from, and says when it shows.
        if (editing) EditPlaceholder("Shows here when Filet's own files grow past the reminder size.")
        return
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.sunken)
            .combinedClickable(onClick = { vm.openFiletFolder() }, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FiletIcons.Storage, null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Filet is keeping " + humanSize(bytes),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Decoded music and other things it worked out. Safe to delete - tap to see it.",
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                color = colors.fg3,
            )
        }
        // Quiet until it has grown another step past this size - see FiletStore.shouldRemind.
        Icon(
            FiletIcons.Close,
            "Dismiss",
            tint = colors.fg3,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { vm.prefs.setCacheRemindDismissed(bytes) }
                .padding(6.dp)
                .size(12.dp),
        )
    }
}

@Composable
fun HomeRowSheet(
    node: VNode,
    onDismiss: () -> Unit,
    onReveal: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onBookmark: () -> Unit,
    onShortcut: () -> Unit,
    /**
     * Null where there is no list to be removed from.
     *
     * A search result is not an entry in a kept list - it is a file that matched - so offering
     * to remove it from one would either do nothing or delete the file, and both of those are
     * worse than not offering it.
     */
    onForget: (() -> Unit)? = null,
    /**
     * Null everywhere except New files, which is the only list a folder can be blocked out of.
     *
     * Offering it on Recent or on a search result would be a row that quietly changes a
     * different screen - the file would stay where it was found and vanish from somewhere the
     * reader was not looking at.
     */
    onBlockFolder: (() -> Unit)? = null,
) {
    Dlg(onDismiss = onDismiss) {
        DlgCtxHead(
            name = node.name,
            path = node.path.path.substringBeforeLast('/'),
            meta = if (node.isDir) "Folder" else humanSize(node.size),
            thumb = node,
            onClose = onDismiss,
        )
        DlgBody(padded = false) {
            Spacer(Modifier.height(6.dp))
            DlgAction(FiletIcons.FolderOpen, Labels.OPEN_LOCATION, onClick = onReveal)
            DlgAction(
                if (node.isDir) FiletIcons.Folder else FiletIcons.Open,
                if (node.isDir) "Open folder" else "Open",
                onClick = onOpen,
            )
            DlgAction(FiletIcons.Share, "Share", onClick = onShare)
            DlgAction(FiletIcons.Star, "Bookmark", onClick = onBookmark)
            DlgAction(FiletIcons.Home, "Add to home screen", onClick = onShortcut)
            if (onForget != null || onBlockFolder != null) {
                androidx.compose.material3.HorizontalDivider(
                    color = Filet.colors.lineSoft,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            if (onBlockFolder != null) {
                // Named with the folder rather than "block this", because the row below it
                // removes one file and this one removes everything in a directory - the two
                // are a tap apart and the difference has to be legible before the tap.
                val folder = (if (node.isDir) node.path else node.path.parent)?.name
                DlgAction(
                    FiletIcons.EyeOff,
                    if (folder != null) "Never show “$folder” again" else "Never show this folder again",
                    onClick = onBlockFolder,
                )
            }
            if (onForget != null) {
                // Separated and red, because it is the one row here that changes something
                // rather than going somewhere.
                DlgAction(
                    FiletIcons.Close, "Remove from this list",
                    danger = true, onClick = onForget,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        // No footer. The close is in the corner and the scrim dismisses, so a row holding one
        // button that says the same thing is a row of nothing.
    }
}

/**
 * One row of a Home feed card, drawn at the card's density - see [FeedCard.metrics].
 *
 * Folded, the origin line goes and the time follows the name on its one line, "name · 2h": at
 * that size a second line of grey path is the first thing that stops being read.
 */
@Composable
internal fun FeedRow(
    node: VNode,
    when_: String,
    sub: String?,
    metrics: FeedCard.Metrics,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val colors = Filet.colors
    val m = metrics
    Row(
        Modifier
            .fillMaxWidth()
            // Same mark as the file list and the tab rows: a pick can be answered from here.
            .chosenForPick(node.path)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = m.padV.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same avatar the expanded history uses, so the card and the full screen show the
        // same file the same way rather than one drawing a picture and the other a glyph.
        Box(
            Modifier
                .size(m.thumb.dp)
                .clip(RoundedCornerShape(m.corner.dp))
                .background(colors.sunken),
            contentAlignment = Alignment.Center,
        ) {
            // Home is always drawn: its cards are a summary in the app's own look, not a place
            // with a Legacy choice of its own. See IconStyles.
            androidx.compose.runtime.CompositionLocalProvider(
                dev.niccc2007.filet.icons.LocalPlaceLook provides dev.niccc2007.filet.icons.PlaceLook.DRAWN,
            ) {
                dev.niccc2007.filet.browser.FileThumb(
                    node = node,
                    size = m.thumb.dp,
                    fallbackTint = colors.fg2,
                    glyphSize = m.glyph.dp,
                )
            }
        }
        Spacer(Modifier.width(m.gap.dp))
        if (m.folded) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                dev.niccc2007.filet.browser.ListNameText(
                    node.name,
                    MaterialTheme.colorScheme.onSurface,
                    m.nameSp,
                    modifier = Modifier.weight(1f, fill = false),
                    isDir = node.isDir,
                )
                Text(" · " + when_, fontSize = 10.sp, maxLines = 1, softWrap = false, color = colors.fg3)
            }
        } else {
            Column(Modifier.weight(1f)) {
                dev.niccc2007.filet.browser.ListNameText(node.name, MaterialTheme.colorScheme.onSurface, m.nameSp, isDir = node.isDir)
                if (!sub.isNullOrEmpty()) {
                    Text(sub, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.fg3, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(when_, fontSize = 10.sp, color = colors.fg3)
        }
    }
}

/**
 * A card's way into its full list, as its last row - the way the side bar's folds end. Quiet,
 * so it reads as a way out rather than one more item. The wording is [FULL_LIST_LABEL].
 */
@Composable
private fun FullListRow(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    SwitcherRow(icon, FULL_LIST_LABEL, index = 0, reduced = true, quiet = true, onClick = onClick)
}

/**
 * The categories inside their card, in rows that always divide evenly.
 *
 * A wrapping flow decided the column count by a minimum tile width, and inside the card's
 * padding a 104dp minimum fit only two across a 360dp phone; at tablet widths the same rule made
 * five and left the sixth alone on a row. [CategoryGrid.columns] picks the count instead, from
 * the width the card actually has, and refuses any count that would strand a single tile.
 */
@Composable
private fun CategoryTiles(shown: List<MediaCategory>, onOpen: (MediaCategory) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = CategoryGrid.EDGE_DP.dp, vertical = 4.dp),
    ) {
        val cols = CategoryGrid.columns(maxWidth.value, shown.size)
        Column(verticalArrangement = Arrangement.spacedBy(CategoryGrid.GAP_DP.dp)) {
            for (row in CategoryGrid.layoutOf(cols, shown)) {
                CategoryRow(cols, row, onOpen)
            }
        }
    }
}

/**
 * One line of category tiles, each a column wide, at the column [CategoryGrid.Slot.at] says -
 * which may fall halfway between two, for the tile centred under a pair.
 */
@Composable
private fun CategoryRow(cols: Int, row: List<CategoryGrid.Slot>, onOpen: (MediaCategory) -> Unit) {
    androidx.compose.ui.layout.Layout(
        content = { for (slot in row) CategoryChip(slot.category) { onOpen(slot.category) } },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gap = CategoryGrid.GAP_DP.dp.roundToPx()
        val tile = ((constraints.maxWidth - gap * (cols - 1)) / cols).coerceAtLeast(0)
        // A tile spanning columns takes the gaps between them as well, so its edges line up
        // with the tiles above it.
        val placeables = measurables.mapIndexed { i, m ->
            val span = row[i].span
            val width = (tile * span + gap * (span - 1f)).toInt().coerceAtLeast(0)
            m.measure(androidx.compose.ui.unit.Constraints.fixedWidth(width))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { i, p -> p.place((row[i].at * (tile + gap)).toInt(), 0) }
        }
    }
}

/** Home's cards, in their default order, as the rearranging and hiding know them. */
internal object HomeCards {
    const val STORAGE = "storage"
    const val ARRIVED = "arrived"
    const val CATEGORIES = "categories"
    const val OPENED = "opened"
    const val CACHE = "cache"

    // Decision: storage, then the categories, then the two feeds. What is on the phone and the
    // ways into it come before what changed on it, which is the order that reads as an overview
    // rather than as a notification list.
    val ALL = listOf(
        SectionSpec(STORAGE, "Storage"),
        SectionSpec(CATEGORIES, "Categories"),
        SectionSpec(ARRIVED, HomeSections.ARRIVED),
        SectionSpec(OPENED, HomeSections.OPENED),
        SectionSpec(CACHE, "Filet cache reminder"),
    )
}

/** How many categories go on a row. Pure, so the widths it promises are a test. */
internal object CategoryGrid {
    /** The card's inner edge, matching the rows above and below it. */
    const val EDGE_DP = 10f
    const val GAP_DP = 8f

    /** The narrowest a category tile reads at: an icon and one word. */
    const val MIN_TILE_DP = 88f

    /**
     * The most columns that fit [widthDp], never leaving one tile alone on the last row - three
     * on a phone, four on a tablet, six on anything wider. Never fewer than two.
     */
    fun columns(widthDp: Float, count: Int): Int {
        val fit = ((widthDp + GAP_DP) / (MIN_TILE_DP + GAP_DP)).toInt().coerceIn(2, maxOf(2, count))
        return (fit downTo 2).firstOrNull { c -> count <= c || count % c != 1 } ?: 2
    }

    /**
     * A tile, the column it starts at, and how many columns it spans.
     *
     * A span rather than half-column offsets: the third of each half used to sit one column wide
     * centred under its pair, which left a half-column of nothing either side of it - reported as
     * the bottom two not using the space they are given. Spanning the pair fills the row.
     */
    data class Slot(val category: MediaCategory, val at: Float, val span: Float = 1f)

    /** What is on the phone: pictures, video and sound. Left, always. */
    val MEDIA = listOf(MediaCategory.IMAGES, MediaCategory.VIDEOS, MediaCategory.AUDIO)

    /** What is kept on it: documents, archives and packages. Right, always. */
    val FILES = listOf(MediaCategory.DOCUMENTS, MediaCategory.ARCHIVES, MediaCategory.APPS)

    /**
     * The rows for [cols] across, as two triangles: media on the left and files on the right.
     *
     * Six fit on one line in that order. Four is two pairs with the third of each spanning its
     * own pair underneath, so each half reads as one group and every row is full. Three is a media
     * row over a files row. Two - a narrow split - stacks the two triangles, the third of each
     * across the whole width. Pure, so where every tile lands is a test.
     */
    fun layoutOf(cols: Int, shown: Collection<MediaCategory>): List<List<Slot>> {
        val list = (MEDIA + FILES).filter { it in shown }
        if (list.size == MEDIA.size + FILES.size) return layout(cols)
        // Some taken off in the edit state: plain rows in the same media-then-files order, the last
        // row's tiles widened to fill it, since the two triangles no longer have three a side.
        return list.chunked(cols.coerceAtLeast(1)).map { row ->
            val span = cols.toFloat() / row.size
            row.mapIndexed { i, c -> Slot(c, i * span, span) }
        }
    }

    fun layout(cols: Int): List<List<Slot>> = when {
        cols >= MEDIA.size + FILES.size ->
            listOf((MEDIA + FILES).mapIndexed { i, c -> Slot(c, i.toFloat()) })
        cols >= 4 -> listOf(
            listOf(Slot(MEDIA[0], 0f), Slot(MEDIA[1], 1f), Slot(FILES[0], 2f), Slot(FILES[1], 3f)),
            listOf(Slot(MEDIA[2], 0f, span = 2f), Slot(FILES[2], 2f, span = 2f)),
        )
        cols == 3 -> listOf(
            MEDIA.mapIndexed { i, c -> Slot(c, i.toFloat()) },
            FILES.mapIndexed { i, c -> Slot(c, i.toFloat()) },
        )
        else -> listOf(MEDIA, FILES).flatMap { group ->
            listOf(
                listOf(Slot(group[0], 0f), Slot(group[1], 1f)),
                listOf(Slot(group[2], 0f, span = 2f)),
            )
        }
    }
}

/**
 * Three dots, travelling, while the media index is being read.
 *
 * A spinner was the obvious choice and is the wrong one: a spinner over a list that already
 * has rows in it says "this is not ready", and it is - those rows are real, they are simply
 * the previous answer. Dots beside the heading say "still looking" without contradicting
 * what is underneath them.
 */
@Composable
private fun ScanningDots() {
    val clock = rememberInfiniteTransition(label = "scan")
    val t = clock.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dots",
    )
    val colour = Filet.colors.accent
    Canvas(Modifier.size(width = 22.dp, height = 8.dp)) {
        // Read in the draw lambda: a value read in composition would rebuild the whole
        // heading every frame. See LivingGlow for the version of this that cost real heat.
        val at = t.value
        for (i in 0 until 3) {
            val near = 1f - kotlin.math.min(kotlin.math.abs(at - i), 1f)
            drawCircle(
                color = colour.copy(alpha = 0.25f + 0.6f * near),
                radius = size.height * (0.22f + 0.1f * near),
                center = androidx.compose.ui.geometry.Offset(
                    size.width * (0.18f + 0.32f * i),
                    size.height / 2f,
                ),
            )
        }
    }
}

/**
 * One category, as a chip.
 *
 * A chip rather than a list row because this is a shortcut, not content: six full-width rows
 * would be most of a phone's first screen spent on navigation. As tiles all six fit in two
 * short rows - see CategoryTiles.
 */
@Composable
private fun CategoryChip(
    category: dev.niccc2007.filet.home.MediaCategory,
    onOpen: () -> Unit,
) {
    val colors = Filet.colors
    Column(
        Modifier
            // Fills its tile. Without this the chips are their own width inside equal boxes,
            // which is a row of different-sized things pretending to be a grid.
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.raised)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The category's tab icon on its tile, as its home-screen shortcut and the Shortcuts tab
        // draw it, so a category looks the same wherever it is a way in. The line glyph stays
        // only for a category with no drawing.
        val art = dev.niccc2007.filet.shortcuts.categoryArt(category)
        if (art != null) {
            dev.niccc2007.filet.shortcuts.ArtTile(art, side = 34.dp)
        } else {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(colors.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(category.icon, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            category.label,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The Termux tile's id in `hiddenCards`.
 *
 * Not a path, because the tile is an offer rather than a volume - but it shares the set, so
 * dismissing it works the same way, survives a restart, and comes back with "show hidden
 * cards" like everything else there.
 */
private const val TERMUX_CARD = HomeArrange.TERMUX_OFFER
private val LOCKER_CARD = dev.niccc2007.filet.locker.LockerPaths.ROOT.toString()

/** The last row of the New files and Recently opened cards: opens the whole list. */
private const val FULL_LIST_LABEL = "See all"

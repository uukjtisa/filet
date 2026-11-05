package dev.niccc2007.filet.settings

import dev.niccc2007.filet.data.PrefKeys
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.ui.tabs.HintInfo
import dev.niccc2007.filet.ui.tabs.HoldHint
import dev.niccc2007.filet.browser.ago
import dev.niccc2007.filet.data.SettingsHome
import androidx.compose.material3.OutlinedTextField
import dev.niccc2007.filet.home.FeedExclusions
import androidx.compose.material3.LocalTextStyle
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.niccc2007.filet.data.FiletStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.SectionLabel
import dev.niccc2007.filet.data.AccentChoice
import dev.niccc2007.filet.data.TabSize
import dev.niccc2007.filet.data.ThemeChoice
import dev.niccc2007.filet.data.ViewStep
import dev.niccc2007.filet.handlers.OpenerPicker
import dev.niccc2007.filet.handlers.defaultOpenersSection
import dev.niccc2007.filet.handlers.eqName
import dev.niccc2007.filet.index.CrawlEnd
import dev.niccc2007.filet.ui.theme.Filet

/**
 * Settings.
 *
 * Every row here changes something observable immediately. PLAN.md R1 forbids a toggle whose
 * handler is a TODO, so a setting that is not wired is simply absent from this file.
 */
/**
 * A section heading that actually separates.
 *
 * Settings were packed, with no visual distinction between the interface options, the general
 * ones and the rest. The old heading was 9.5sp grey micro-caps with 7dp of padding -
 * technically a label, visually a row like any other, so eleven controls read as one
 * undifferentiated list.
 *
 * Three changes, and the first is the one that does the work: **space above**. A group is
 * defined by the gap before it far more than by its title. Then a title at reading size rather
 * than caption size, and a sentence saying what the group is for, which doubles as the answer
 * to "which section is this setting in".
 *
 * 20dp above rather than the 26 it was. The rows under it were slimmed (see [CompactSwitch]),
 * and a gap sized for 66dp rows read as a missing row once they were 44.
 */
@Composable
private fun SettingsSection(title: String, subtitle: String, first: Boolean = false) {
    val colors = Filet.colors
    Column(Modifier.fillMaxWidth().padding(top = if (first) 8.dp else 20.dp)) {
        Text(
            title,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.fg2,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp),
        )
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = colors.fg3,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 9.dp),
            )
        } else {
            Spacer(Modifier.height(9.dp))
        }
        HorizontalDivider(color = colors.lineSoft)
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Settings, in seven rooms named for what somebody is trying to change.
 *
 * ## Why the rooms are named for the reader's goal and not for the code
 *
 * The first version grouped the rows by where they already sat, and the result had a room called
 * Files holding gestures, one-handed layout, both bar editors and the tree view - things a person
 * looking for "how do I change the swipe" would never open Files to find. Android's own settings
 * guidance asks for titles that are brief and meaningful rather than a vague catch-all, and for a
 * screen of more than 10-15 items to be split. Firefox's 2017 preferences redesign reported a 30%
 * gain in task success from fewer, task-named categories, and a later one removed a "General"
 * page for the reason Files failed here: unrelated controls under a label that predicts none of
 * them. So each room has to pass one test - given only the label, would somebody guess what is
 * inside it?
 *
 *  - **Appearance** - how it looks: theme, palette, sizes, how much of a name shows.
 *  - **Browsing** - how folders behave: where it opens, tabs coming back, the tree, folders
 *    first, which app opens which file.
 *  - **Controls** - what a finger does: swipes, one-handed layout, and which buttons sit on the
 *    two bars.
 *  - **Playback** - the music and video players: their look, the beat, the disc, and how
 *    playback behaves.
 *  - **Reading** - how documents read: text size, the column, tables, and web pictures. The same
 *    values the Aa panel on a document moves, for every document at once.
 *  - **Search** - the index and its state.
 *  - **Storage and access** - what Filet may see: Android's grants, root and
 *    Termux, which are both ways of reaching storage that is otherwise closed.
 *  - **Maintenance** - looking after the app itself: updates, logs, and moving the setup to
 *    another phone.
 *
 * Every control is in exactly one room. A setting that seems to belong in two is a setting whose
 * label is wrong, and duplicating it would give "where did I change that" two answers.
 *
 * ## Why this is a tab strip and not a longer list
 *
 * There are over forty controls and they were one scroll. Settings is ONE place somebody goes,
 * so the rooms are tabs inside it rather than seven destinations in the navigation, and the tab
 * is remembered for the session so coming back lands where you were.
 */
enum class SettingsGroup(val label: String) {
    APPEARANCE("Appearance"),
    BROWSING("Browsing"),
    CONTROLS("Controls"),
    PLAYBACK("Playback"),
    READING("Reading"),
    SEARCH("Search"),
    STORAGE("Storage"),
    MAINTENANCE("System"),

    /**
     * Debug builds only - see [DeveloperCard]. Filtered out of the tab strip in a release,
     * rather than shown and empty, because a tab that opens onto nothing reads as a fault.
     */
    DEVELOPER("Developer"),
    ;

    companion object {
        /** The groups this build actually has. */
        val shown: List<SettingsGroup>
            get() = entries.filter { it != DEVELOPER || dev.niccc2007.filet.BuildConfig.DEBUG }
    }
}


@Composable
fun SettingsPage(vm: BrowserViewModel) {
    val prefs = vm.prefs
    val theme by prefs.theme.collectAsState()
    val accent by prefs.accent.collectAsState()
    val step by prefs.viewStep.collectAsState()
    val restoreTabs by prefs.restoreTabs.collectAsState()
    val storedBar by prefs.bottomBar.collectAsState()
    val sort by prefs.sort.collectAsState()
    val indexOn by prefs.indexEnabled.collectAsState()
    val indexAuto by prefs.indexAuto.collectAsState()
    val listenMinutes by prefs.listenMinutes.collectAsState()
    val stopConfirm by prefs.stopConfirm.collectAsState()
    val verboseLogs by prefs.verboseLogs.collectAsState()
    val updatesOn by vm.updateNotificationsOn.collectAsState()
    val selectionBar by prefs.selectionBar.collectAsState()
    val uninterrupted by prefs.uninterrupted.collectAsState()
    val videoNextPlays by prefs.videoNextPlays.collectAsState()
    val videoSkip by prefs.videoSkipSeconds.collectAsState()
    val videoPip by prefs.videoPipOnHome.collectAsState()
    val pipOnLeave = dev.niccc2007.filet.handlers.pipOnLeaveOffered(LocalContext.current)
    val playerFace by prefs.playerFace.collectAsState()
    // Hoisted out of the look-picker row below: the beat settings are scoped to the
    // look that is selected, and a LazyColumn's items cannot see a value declared
    // inside one of their siblings.
    val face = dev.niccc2007.filet.playback.PlayerFace.of(playerFace)
    val pitchMode by prefs.pitchMode.collectAsState()
    val musicSound by prefs.soundOf(dev.niccc2007.filet.playback.SoundTarget.MUSIC).collectAsState()
    val videoSound by prefs.soundOf(dev.niccc2007.filet.playback.SoundTarget.VIDEO).collectAsState()
    // What this phone has, for the effects it can be offered: no reverb, no Reverb row.
    val soundOffers = dev.niccc2007.filet.playback.Sound.of(LocalContext.current).offers
    val resumePlayback by prefs.resumePlayback.collectAsState()
    val rootVolumes by prefs.rootVolumes.collectAsState()
    val nameLines by prefs.nameLines.collectAsState()
    val discGlow by prefs.discGlow.collectAsState()
    val beatPulse by prefs.beatPulse.collectAsState()
    val pulseDepth by prefs.pulseDepth.collectAsState()
    val waveGap by prefs.waveGap.collectAsState()
    val glowSpill by prefs.glowSpill.collectAsState()
    val treeView by prefs.treeView.collectAsState()
    val reachMode by prefs.reachMode.collectAsState()
    val splitFit by prefs.splitFit.collectAsState()
    val deviceTier by prefs.deviceTier.collectAsState()
    val scratchAudio by prefs.scratchAudio.collectAsState()
    val discTurn by prefs.discTurnMs.collectAsState()
    val glowStrength by prefs.glowStrength.collectAsState()
    val paneSwipe by prefs.paneSwipe.collectAsState()
    val paneSwipeAt by prefs.paneSwipeAt.collectAsState()
    val tabSize by prefs.tabSize.collectAsState()
    val entry by prefs.entry.collectAsState()
    val reading by prefs.reading.collectAsState()
    // Read once per visit. The line goes on the next visit after the first hint, never during
    // this one: it sits above the list, and taking it away while somebody is scrolling moved
    // every row by its height under their finger.
    val hintLine = remember { !prefs.settingsHintsSeen.value }
    val hintOpened = remember(prefs) { { prefs.setSettingsHintsSeen(true) } }
    var pickingOpenerFor by remember { mutableStateOf<String?>(null) }
    val colors = Filet.colors

    // Kept with the tab, through a switch to another tab and through a restart, and each page
    // keeps its own scroll.
    //
    // Bug identified here: the page was held in a plain `remember`, so it lasted only as long
    // as the composition - and switching to another tab disposes it. Every return to Settings
    // landed on Appearance, whichever page had been open seconds before. The page was once left
    // out of the saved state on purpose, on the theory that a page restored a week later would
    // open somewhere unexplained; in use, a tab coming back exactly as it was left is the
    // expected behaviour, and Settings is a tab like any other.
    var group by dev.niccc2007.filet.browser.rememberKeptEnum("settings.group", SettingsGroup.APPEARANCE)

    // The pane's search box. While it has letters in it the page is the settings that match -
    // see SettingsResults - and a tap goes to that setting's tab, brings its row into view and
    // marks it.
    val query = dev.niccc2007.filet.ui.LocalPaneQuery.current
    val listState = dev.niccc2007.filet.browser.rememberKeptListState("settings:" + group.name)
    val marks = remember { HashMap<String, Int>() }
    var target by remember { mutableStateOf<Pair<String, Long>?>(null) }
    androidx.compose.runtime.LaunchedEffect(target) {
        val (key, _) = target ?: return@LaunchedEffect
        // The tab's list is rebuilt for the switch on the next frame, and its anchors with it.
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        marks[key]?.let { listState.animateScrollToItem(it) }
        kotlinx.coroutines.delay(dev.niccc2007.filet.ui.PULSE_MS + 200L)
        target = null
    }

    androidx.compose.runtime.CompositionLocalProvider(
        dev.niccc2007.filet.ui.tabs.LocalHintOpened provides hintOpened,
        dev.niccc2007.filet.ui.tabs.LocalSettingPulse provides target,
    ) {
    Column(Modifier.fillMaxSize()) {
        // The strip scrolls, because seven labels do not fit across a narrow phone and shrinking
        // them to fit is how a tab strip becomes unreadable. `HScroll` so the edge says there
        // is more - which is the whole rule the scroll-cue checker enforces.
        dev.niccc2007.filet.ui.HScroll(
            ground = Filet.colors.raised,
            contentPadding = 12.dp,
            spacing = 6.dp,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        ) {
            for (g in SettingsGroup.shown) {
                val on = g == group
                Text(
                    g.label,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) colors.accent else colors.sunken)
                        .clickable { group = g }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                )
            }
        }
        HorizontalDivider(color = colors.lineSoft)
        // Until the first hint has been opened and closed. The descriptions stopped being
        // printed, and nothing else on the page says a hold does anything.
        if (hintLine) {
            Text(
                "Tap ⓘ or hold a setting for details.",
                fontSize = 11.sp,
                color = colors.fg3,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.sunken)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        if (query.isNotBlank()) {
            SettingsResults(query) { e ->
                group = e.tab
                target = e.key to System.nanoTime()
                vm.focusedPane()?.openSearch(false)
            }
        } else
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
    // Counted, so a search result can be scrolled to: see AnchoredList and each anchor() below.
    marks.clear()
    with(AnchoredList(this, marks)) {
        if (group == SettingsGroup.APPEARANCE) {
        item { SettingsSection("Appearance", "", first = true) }
        anchor(Prefs.K_THEME)
        item {
            SegmentRow("Theme", ThemeChoice.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, theme.ordinal, key = Prefs.K_THEME) {
                prefs.setTheme(ThemeChoice.entries[it])
            }
        }
        anchor(Prefs.K_ACCENT)
        item {
            SegmentRow("Palette", listOf("Slate", "Ember", "Moss", "Ink"), accent.ordinal, key = Prefs.K_ACCENT) {
                prefs.setAccent(AccentChoice.entries[it])
            }
        }
        item {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Row {
                    Text("View", fontSize = 12.sp, color = colors.fg2, modifier = Modifier.weight(1f))
                    Text(ViewStep.of(step).label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                }
                Slider(
                    value = step.toFloat(),
                    onValueChange = { prefs.setViewStep(it.toInt()) },
                    valueRange = 1f..6f,
                    steps = 4,
                )
            }
        }

        anchor(Prefs.K_TAB_SIZE)
        item {
            SegmentRow("Tab size", TabSize.entries.map { it.label }, tabSize.ordinal, hint = "Size of the tabs along the top.", key = Prefs.K_TAB_SIZE) {
                prefs.setTabSize(TabSize.entries[it])
            }
        }
        anchor(Prefs.K_NAME_LINES)
        item {
            // Issue #11, and the request was specific: show the whole name by letting it wrap,
            // NOT by scrolling it past. A marquee does show all of it eventually, and
            // "eventually" is the objection - a list is read by scanning, and a name that has
            // to be waited for cannot be scanned.
            Column {
                SegmentRow(
                    "Name lines",
                    listOf("1", "2", "3", "4"),
                    (nameLines - 1).coerceIn(0, 3),
                    hint = if (nameLines <= 1) "Long names end in an ellipsis." else "Long names wrap onto $nameLines lines.",
                    key = Prefs.K_NAME_LINES,
                ) { prefs.setNameLines(it + 1) }
            }
        }

        // ── what a row says under its name ──
        //
        // Bug identified here: the row display was a stored preference that every listing read
        // and nothing could change. Its control had gone in an earlier rework, so the date
        // format, the folder counts and the date column were fixed at their defaults with the
        // code for all of it still in place. Found by the test that every preference read by the
        // app has something that writes it.
        //
        // The preview runs through `RowDetails.render`, the same function the list uses, so
        // what it shows is what the rows will show rather than a second guess at it.
        item {
            SettingsSection(
                "Row details",
                "",
            )
        }
        anchor(Prefs.K_E_PARTS)
        item { RowDetailCard(entry) { prefs.setEntry(it) } }
        // ── the column beside the name ──
        //
        // One choice per side rather than the two switches this replaced ("Count what is in
        // folders", "Date column"). The pair could put two columns on the right of a row - the
        // duplication that started this rework - and still could not say "the folder total
        // instead of the count". See `SideColumn`.
        //
        // Each side is offered only what can answer for it, from `SideColumn.optionsFor`, so
        // there is no chip here that would leave the column empty on every row it applies to.
        anchor(Prefs.K_E_SIDE_FILE)
        item {
            val options = remember { dev.niccc2007.filet.browser.SideColumn.optionsFor(isDir = false) }
            SegmentRow(
                "Beside a file",
                options.map(dev.niccc2007.filet.browser.SideColumn::labelOf),
                options.indexOf(entry.sideFile),
                hint = "Dates show only in wide panes.",
                key = Prefs.K_E_SIDE_FILE,
            ) { prefs.setEntry(entry.copy(sideFile = options[it])) }
        }
        anchor(Prefs.K_E_SIDE_FOLDER)
        item {
            val options = remember { dev.niccc2007.filet.browser.SideColumn.optionsFor(isDir = true) }
            SegmentRow(
                "Beside a folder",
                options.map(dev.niccc2007.filet.browser.SideColumn::labelOf),
                options.indexOf(entry.sideFolder),
                hint = "Dates show only in wide panes.",
                key = Prefs.K_E_SIDE_FOLDER,
            ) { prefs.setEntry(entry.copy(sideFolder = options[it])) }
        }
        anchor(Prefs.K_E_REL)
        item {
            ToggleRow(
                "Relative dates",
                "Shows Today, Yesterday and weekday names for the last week.",
                entry.relative,
                key = Prefs.K_E_REL,
            ) { prefs.setEntry(entry.copy(relative = it)) }
        }
        anchor(Prefs.K_E_STYLE)
        item {
            SegmentRow(
                "Date",
                dev.niccc2007.filet.browser.DateStyle.entries.map { it.label },
                entry.style.ordinal,
                key = Prefs.K_E_STYLE,
            ) { prefs.setEntry(entry.copy(style = dev.niccc2007.filet.browser.DateStyle.entries[it])) }
        }
        anchor(Prefs.K_E_ORDER)
        item {
            SegmentRow(
                "Order",
                dev.niccc2007.filet.browser.DateOrder.entries.map { it.label },
                entry.order.ordinal,
                key = Prefs.K_E_ORDER,
            ) { prefs.setEntry(entry.copy(order = dev.niccc2007.filet.browser.DateOrder.entries[it])) }
        }
        // Only where it does anything: a named month is joined with spaces whatever this says.
        if (entry.style == dev.niccc2007.filet.browser.DateStyle.NUMERIC) {
            anchor(Prefs.K_E_SEP)
            item {
                val seps = listOf("-", "/", ".")
                SegmentRow("Separator", seps, seps.indexOf(entry.separator).coerceAtLeast(0), key = Prefs.K_E_SEP) {
                    prefs.setEntry(entry.copy(separator = seps[it]))
                }
            }
        }
        }
        if (group == SettingsGroup.BROWSING) {
        item { SettingsSection("Browsing", "", first = true) }
        // First under Browsing, because the folder Filet opens on is where browsing starts.
        // It sat under Appearance on the reasoning that Home is what the app looks like when it
        // opens; that is true of the overview and false of a folder set as Home, which is the
        // case this row exists for.
        item {
            val home by prefs.homeFolder.collectAsState()
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Home folder", fontSize = 12.sp, color = colors.fg2)
                        Text(
                            home ?: "Home overview",
                            fontSize = 10.5.sp,
                            color = colors.fg3,
                            maxLines = 2,
                        )
                    }
                    // Only offered when there is something to undo. A reset button on a
                    // default is a control that does nothing.
                    if (home != null) SmallButton("Reset") { prefs.setHomeFolder(null) }
                }
            }
        }
        anchor(Prefs.K_RESTORE_TABS)
        item {
            ToggleRow(
                "Restore tabs",
                "Reopens the last session's tabs on start.",
                restoreTabs,
                key = Prefs.K_RESTORE_TABS,
            ) {
                prefs.setRestoreTabs(it)
            }
        }
        item { SettingsSection("Viewing options", "") }
        anchor(Prefs.K_TREE_VIEW)
        item {
            ToggleRow(
                "Tree view",
                "Shows folders as an expandable tree.",
                treeView,
                key = Prefs.K_TREE_VIEW,
            ) { prefs.setTreeView(it) }
        }
        anchor(Prefs.K_FOLDERS_FIRST)
        item {
            ToggleRow("Folders first", "Lists folders above files in every sort order.", sort.foldersFirst, key = Prefs.K_FOLDERS_FIRST) {
                prefs.setSort(sort.copy(foldersFirst = it))
            }
        }
        item { SettingsSection("New files", "") }
        anchor(Prefs.K_FEED_PREFIXES)
        item { FeedPrefixCard(vm) }
        // Hidden by pointing, not by typing. A row in New files can be held to hide its folder,
        // and the two buttons here open the picker for a folder or for files - so the box above
        // is for patterns that have not appeared yet, and an exact place is never typed, where
        // it could be mistyped into a different real one. What is hidden is listed, with a way
        // back for each.
        item {
            val blocked by prefs.blockedFolders.collectAsState()
            val shown = remember(blocked) { blocked.sortedBy { it.lowercase() } }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(
                    "Hidden folders and files",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("Choose a folder") { vm.pickNewFilesExclusion(folder = true) }
                    SmallButton("Choose files") { vm.pickNewFilesExclusion(folder = false) }
                }
                Spacer(Modifier.height(4.dp))
                if (shown.isEmpty()) {
                    Text(
                        "Nothing hidden here yet. Holding a row in New files hides its folder too.",
                        fontSize = 10.5.sp,
                        color = colors.fg3,
                    )
                } else {
                    for (path in shown) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    path.substringAfterLast('/').ifEmpty { path },
                                    fontSize = 12.sp,
                                    color = colors.fg2,
                                )
                                // The whole path, because two folders called "cache" is the
                                // normal case and the name alone cannot tell them apart.
                                Text(path, fontSize = 10.sp, color = colors.fg3, maxLines = 2)
                            }
                            SmallButton("Show") { vm.unblockHomeFolder(path) }
                        }
                    }
                }
            }
        }
        // Directly under Browsing, not at the bottom. Buried at the end of the page,
        // "where is that?" was the result.
        defaultOpenersSection(vm) { pickingOpenerFor = it }
        }
        if (group == SettingsGroup.CONTROLS) {
        item { SettingsSection("Gestures", "", first = true) }
        // The way back from the stop sheet's own "Don't ask again". Without it, the tick could be
        // undone only on the next stop that happened to interrupt a copy - which for most people
        // is never, so one tap would have been permanent with nowhere to see it.
        anchor(Prefs.K_STOP_CONFIRM)
        item {
            ToggleRow(
                "Confirm Shutdown Filet",
                if (stopConfirm) {
                    "Shows running work before Filet stops."
                } else {
                    "Stops at once unless work would be cut short."
                },
                stopConfirm,
                key = Prefs.K_STOP_CONFIRM,
            ) { prefs.setStopConfirm(it) }
        }
        anchor(Prefs.K_REACH_MODE)
        item {
            ToggleRow(
                "One-handed mode",
                if (reachMode) {
                    "Moves the tabs, path and toolbar to the bottom."
                } else {
                    "Tabs, path and toolbar stay at the top."
                },
                reachMode,
                key = Prefs.K_REACH_MODE,
            ) { prefs.setReachMode(it) }
        }
        anchor(Prefs.K_SPLIT_FIT)
        item {
            Column {
                SegmentRow(
                    "Split view",
                    dev.niccc2007.filet.browser.SplitFit.Mode.entries.map { it.label },
                    dev.niccc2007.filet.browser.SplitFit.Mode.entries
                        .indexOfFirst { it.id == splitFit }.coerceAtLeast(0),
                    hint = "Shrinks each pane to fit half the screen.",
                    key = Prefs.K_SPLIT_FIT,
                ) {
                    prefs.setSplitFit(dev.niccc2007.filet.browser.SplitFit.Mode.entries[it].id)
                }
            }
        }
        anchor(Prefs.K_PANE_SWIPE)
        item {
            ToggleRow(
                "Swipe between tabs",
                if (paneSwipe) {
                    "A sideways swipe changes the tab in that pane."
                } else {
                    "Tabs change only from the tab strip."
                },
                paneSwipe,
                key = Prefs.K_PANE_SWIPE,
            ) { prefs.setPaneSwipe(it) }
        }
        if (paneSwipe) {
            anchor(Prefs.K_PANE_SWIPE_AT)
            item {
                val at = dev.niccc2007.filet.browser.PaneSwipe.Sensitivity.of(paneSwipeAt)
                Column {
                    SegmentRow(
                        "Swipe sensitivity",
                        dev.niccc2007.filet.browser.PaneSwipe.Sensitivity.entries.map { it.label },
                        dev.niccc2007.filet.browser.PaneSwipe.Sensitivity.entries.indexOf(at),
                        hint = "Firmer needs a longer swipe.",
                        key = Prefs.K_PANE_SWIPE_AT,
                    ) {
                        prefs.setPaneSwipeAt(
                            dev.niccc2007.filet.browser.PaneSwipe.Sensitivity.entries[it].id,
                        )
                    }
                }
            }
        }
        item {
            val bar = dev.niccc2007.filet.browser.BottomBarConfig.normalise(storedBar)
            SettingsSection(
                "Bottom bar",
                if (dev.niccc2007.filet.browser.BottomBarConfig.scrolls(bar.size))
                    "${bar.size} on the bar · scrolls sideways"
                else
                    "${bar.size} on the bar",
            )
        }
        // Two lists, in the bar's OWN order, rather than one list of switches in the order
        // the enum happens to declare.
        //
        // The order used to be whatever order things were ticked in, which is an order
        // nobody chose: adding one put it at the end whatever it was for, and moving it
        // meant unticking everything after it and ticking them back. A row of toggles also
        // could not show the order at all - it said "position 3" in a sentence, under a list
        // sorted some other way, which is the longest possible way to describe a sequence.
        anchor(Prefs.K_BOTTOM_BAR)
        item {
            val bar = dev.niccc2007.filet.browser.BottomBarConfig.normalise(storedBar)
            fun write(items: List<dev.niccc2007.filet.browser.BarItem>) =
                prefs.setBottomBar(dev.niccc2007.filet.browser.BottomBarConfig.encode(items))
            Column {
                for ((i, item) in bar.withIndex()) {
                    BarRow(
                        label = item.label,
                        detail = "Position ${i + 1}",
                        onUp = if (i > 0) ({
                            write(dev.niccc2007.filet.browser.BottomBarConfig.moved(bar, i, i - 1))
                        }) else null,
                        onDown = if (i < bar.size - 1) ({
                            write(dev.niccc2007.filet.browser.BottomBarConfig.moved(bar, i, i + 1))
                        }) else null,
                        // The last one cannot be taken off. An empty bar is not a
                        // configuration, it is a screen with no way back to this one.
                        onRemove = if (bar.size > 1) ({
                            write(dev.niccc2007.filet.browser.BottomBarConfig.toggled(bar, item))
                        }) else null,
                    )
                }
                val off = dev.niccc2007.filet.browser.BarItem.entries.filterNot { it in bar }
                if (off.isNotEmpty()) {
                    Text(
                        "Not on the bar",
                        fontSize = 9.sp,
                        letterSpacing = 1.3.sp,
                        color = Filet.colors.fg3,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                    )
                    for (item in off) {
                        BarRow(
                            label = item.label,
                            detail = null,
                            hint = "Adds it to the end of the bar.",
                            key = Prefs.K_BOTTOM_BAR,
                            onAdd = {
                                write(dev.niccc2007.filet.browser.BottomBarConfig.toggled(bar, item))
                            },
                        )
                    }
                }
            }
        }
        item {
            val bar = dev.niccc2007.filet.browser.SelectionBarConfig.normalise(selectionBar)
            SettingsSection(
                "Selection bar",
                "${bar.size} of ${dev.niccc2007.filet.browser.SelectionBarConfig.MAX_ON_BAR} on the bar",
            )
        }
        // The bottom bar's editor rather than a second kind: the bar in its own order with up,
        // down and remove, then everything behind More with an add. The list of switches this
        // replaces could not show an order at all, and the order was whatever things had been
        // switched on in - putting Delete first meant switching off everything ahead of it.
        anchor(Prefs.K_SELECTION_BAR)
        item {
            val sel = dev.niccc2007.filet.browser.SelectionBarConfig
            val bar = sel.normalise(selectionBar)
            fun write(ids: List<String>) = prefs.setSelectionBar(sel.encode(ids))
            Column {
                for ((i, id) in bar.withIndex()) {
                    BarRow(
                        label = sel.labelFor(id) ?: id,
                        detail = "Position ${i + 1}",
                        what = "the selection bar",
                        onUp = if (i > 0) ({ write(sel.moved(bar, i, i - 1)) }) else null,
                        onDown = if (i < bar.size - 1) ({ write(sel.moved(bar, i, i + 1)) }) else null,
                        // The last one stays. A bar holding only More and a count reads as a
                        // failure to load rather than as a choice.
                        onRemove = if (bar.size > 1) ({ write(sel.toggled(bar, id)) }) else null,
                    )
                }
                val off = sel.CHOOSABLE.filterNot { it.first in bar }
                if (off.isNotEmpty()) {
                    Text(
                        "In More",
                        fontSize = 9.sp,
                        letterSpacing = 1.3.sp,
                        color = Filet.colors.fg3,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                    )
                    // Full means no add at all, and the row says why: an add that silently
                    // pushed the first one off would be a switch that turns another one off.
                    val full = sel.full(bar)
                    for ((id, label) in off) {
                        BarRow(
                            label = label,
                            detail = if (full) "Bar full · take one off to add this" else null,
                            hint = "Adds it to the end of the bar. Everything not on the bar is behind More.",
                            what = "the selection bar",
                            key = Prefs.K_SELECTION_BAR,
                            onAdd = if (full) null else ({ write(sel.toggled(bar, id)) }),
                        )
                    }
                }
            }
        }
        }
        if (group == SettingsGroup.PLAYBACK) {
        item {
            SettingsSection(
                "Viewers",
                "",
            )
        }
        anchor(Prefs.K_DEVICE_TIER)
        item {
            Column {
                SegmentRow(
                    "Player detail",
                    listOf("Automatic", "Full", "Light"),
                    dev.niccc2007.filet.data.PLAYER_TIERS.indexOf(deviceTier)
                        .coerceAtLeast(0),
                    hint = "Light updates the beat and glow less often.",
                    key = Prefs.K_DEVICE_TIER,
                ) { prefs.setDeviceTier(dev.niccc2007.filet.data.PLAYER_TIERS[it]) }
            }
        }
        anchor(Prefs.K_PLAYER_FACE)
        item {
            Column {
                SegmentRow(
                    "Music player",
                    dev.niccc2007.filet.playback.PlayerFace.entries.map { it.label },
                    dev.niccc2007.filet.playback.PlayerFace.entries.indexOf(face),
                    hint = face.about,
                    key = Prefs.K_PLAYER_FACE,
                ) { prefs.setPlayerFace(dev.niccc2007.filet.playback.PlayerFace.entries[it].id) }
            }
        }
        // The background glow has no switch here any more, and that is the correction rather
        // than an omission. It used to be "Living background", and turning it off removed the
        // colour from the full player, the minimised strip and the notification card at once -
        // which is not an effect being disabled, it is the player becoming a grey rectangle.
        // How much of it there is was always the real question, and "Glow strength" below
        // answers it down to nearly nothing for anybody who wants that.
        //
        // The switch that remains is over the DISC's glow, which is what the old one was
        // usually reached for, and it is offered only on a look that has a disc to ring.
        if (face.hasDiscGlow) {
            anchor(Prefs.K_DISC_GLOW)
            item {
                ToggleRow(
                    "Disc glow",
                    if (discGlow) {
                        "Casts the cover's colour around the disc."
                    } else {
                        "The disc keeps only a thin rim of light."
                    },
                    discGlow,
                    key = Prefs.K_DISC_GLOW,
                ) { prefs.setDiscGlow(it) }
            }
        }
        anchor(Prefs.K_BEAT_PULSE)
        item {
            // Under the glow, because it IS the glow answering the music, and indented to
            // say so.
            ToggleRow(
                "Beat pulse",
                if (!face.pulses) {
                    null
                } else if (beatPulse) {
                    "The glow swells on each drum."
                } else {
                    "The glow drifts slowly."
                },
                beatPulse && face.pulses,
                status = if (!face.pulses) "Not available on Classic" else null,
                beta = true,
                sub = true,
                key = Prefs.K_BEAT_PULSE,
            ) { prefs.setBeatPulse(it) }
        }
        // Two of the five beat sliders remain: how much the beat moves things, and where
        // Aurora's wave sits.
        //
        // Sensitivity, Wave at rest and Wave on a beat were each a real control over a real
        // term, and with these two they made five sliders for one effect - a mixing desk
        // rather than a setting. The three that went are constants at the values they
        // defaulted to, so a player nobody tuned looks exactly as it did. Beat intensity
        // stays, renamed for what it answers, because it is the one that changes how much
        // the beat shows on every look that pulses. Distance stays because where a ring sits
        // against the artwork is taste rather than tuning.
        //
        // Only the controls that do something on the look that is selected: a control that
        // is visible and inert reads as the feature being broken. See `PlayerFace.pulses`
        // and `PlayerFace.hasWave`.
        if (beatPulse && face.pulses) {
            anchor(Prefs.K_PULSE_DEPTH)
            item {
                SliderRow(
                    "Beat strength",
                    "How far each beat moves the glow and wave.",
                    pulseDepth,
                    beta = true,
                    sub = true,
                    key = Prefs.K_PULSE_DEPTH,
                ) { prefs.setPulseDepth(it) }
            }
            if (face.hasWave) {
                anchor(Prefs.K_WAVE_GAP)
                item {
                    SliderRow(
                        "Wave distance",
                        "Gap between the artwork and the wave.",
                        waveGap,
                        beta = true,
                        sub = true,
                        key = Prefs.K_WAVE_GAP,
                    ) { prefs.setWaveGap(it) }
                }
            }
        }
        anchor(Prefs.K_GLOW_STRENGTH)
        item {
            SliderRow(
                "Glow strength",
                "How bright the glow is.",
                glowStrength,
                beta = true,
                key = Prefs.K_GLOW_STRENGTH,
            ) { prefs.setGlowStrength(it) }
        }
        anchor(Prefs.K_GLOW_SPILL)
        item {
            ToggleRow(
                "Glow over the list",
                if (glowSpill) {
                    "The mini player's glow spreads over the list."
                } else {
                    "The glow stays inside the mini player."
                },
                glowSpill,
                key = Prefs.K_GLOW_SPILL,
            ) { prefs.setGlowSpill(it) }
        }
        item { SettingsSection("Halo disc", "") }
        anchor(Prefs.K_SCRATCH)
        item {
            ToggleRow(
                "Scratch audio",
                if (scratchAudio) {
                    "Turning the disc plays the audio, backwards too."
                } else {
                    "Turning the disc seeks in silence."
                },
                scratchAudio,
                beta = true,
                key = Prefs.K_SCRATCH,
            ) { prefs.setScratchAudio(it) }
        }
        anchor(Prefs.K_DISC_TURN)
        item {
            // Not under the switch above. The disc moves through the track whether or not
            // anything is audible, so how far a turn goes is its own question.
            SliderRow(
                "Disc travel",
                "How much of the track one full turn covers.",
                dev.niccc2007.filet.playback.Scratch.turnPosition(discTurn),
                beta = true,
                status = dev.niccc2007.filet.playback.Scratch.travelLabel(discTurn),
                key = Prefs.K_DISC_TURN,
            ) { prefs.setDiscTurnMs(dev.niccc2007.filet.playback.Scratch.turnMsFor(it)) }
        }
        item { SettingsSection("Playing", "") }
        anchor(Prefs.K_RESUME)
        item {
            ToggleRow(
                "Resume playback",
                if (resumePlayback) {
                    "Tracks and videos open where they stopped."
                } else {
                    "Tracks and videos open at the start; Carry on stays empty."
                },
                resumePlayback,
                key = Prefs.K_RESUME,
            ) { prefs.setResumePlayback(it) }
        }
        anchor(Prefs.K_UNINTERRUPTED)
        item {
            ToggleRow(
                "Uninterruptible playback",
                if (uninterrupted) {
                    "Plays over other apps; only calls pause it."
                } else {
                    "Pauses for calls and other audio."
                },
                uninterrupted,
                key = Prefs.K_UNINTERRUPTED,
            ) { prefs.setUninterrupted(it) }
        }
        anchor(Prefs.K_PITCH_MODE)
        item {
            val mode = dev.niccc2007.filet.playback.PitchMode.of(pitchMode)
            Column {
                SegmentRow(
                    "Pitch when slowed",
                    dev.niccc2007.filet.playback.PitchMode.entries.map { it.label },
                    dev.niccc2007.filet.playback.PitchMode.entries.indexOf(mode),
                    hint = mode.about,
                    key = Prefs.K_PITCH_MODE,
                ) {
                    prefs.setPitchMode(dev.niccc2007.filet.playback.PitchMode.entries[it].id)
                }
            }
        }
        // The video player's own. Every video opens under its silent preview; this is what one
        // reached by a gallery's arrows or swipe does. The same switch as the viewer's menu.
        item { SettingsSection("Video", "") }
        anchor(Prefs.K_VIDEO_NEXT_PLAYS)
        item {
            SegmentRow(
                "Next video",
                listOf("Tap to play", "Start playing"),
                if (videoNextPlays) 1 else 0,
                hint = if (videoNextPlays) {
                    "A video you swipe or step to in a gallery starts playing."
                } else {
                    "A video you swipe or step to in a gallery waits for a tap, like one you open."
                },
                key = Prefs.K_VIDEO_NEXT_PLAYS,
            ) { prefs.setVideoNextPlays(it == 1) }
        }
        anchor(Prefs.K_VIDEO_SKIP)
        item {
            SegmentRow(
                "Double-tap skip",
                listOf("2s", "5s", "10s"),
                Prefs.VIDEO_SKIP_CHOICES.indexOf(videoSkip),
                hint = "How far a double tap on either side of a video moves it, and the buttons beside Play.",
                key = Prefs.K_VIDEO_SKIP,
            ) { prefs.setVideoSkipSeconds(Prefs.VIDEO_SKIP_CHOICES[it]) }
        }
        // Only where leaving can go into the window on its own: Android 12 on, and a phone with it.
        if (pipOnLeave) {
            anchor(Prefs.K_VIDEO_PIP_HOME)
            item {
                ToggleRow(
                    "Picture-in-picture on leaving",
                    "A playing video goes on in a small window when you leave Filet, instead of stopping.",
                    videoPip,
                    key = Prefs.K_VIDEO_PIP_HOME,
                ) { prefs.setVideoPipOnHome(it) }
            }
        }
        // Music's own: the same as its sheet, which is where they are usually set. Here so they
        // can be found by searching, and reset without a file open. A file remembered from its sheet keeps its own.
        item { SettingsSection("Sound · Music", "") }
        anchor(Prefs.K_SOUND_MUSIC_VOLUME)
        item {
            val top = dev.niccc2007.filet.playback.SoundMaths.MAX_VOLUME.toFloat()
            Column {
                SliderRow(
                    "App volume",
                    "Of the phone's own volume, for every track. Above 100% a limiter keeps " +
                        "the loud parts under full scale.",
                    musicSound.volume / top,
                    beta = true,
                    status = volumeStatus(musicSound.volume),
                    key = Prefs.K_SOUND_MUSIC_VOLUME,
                ) { f ->
                    prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) {
                        it.copy(volume = dev.niccc2007.filet.playback.SoundMaths.snapVolume(f * top))
                    }
                }
                // Only offered when there is something to undo, like the Home folder's.
                if (musicSound.volume != 100) {
                    Row(Modifier.padding(start = 14.dp, bottom = 6.dp)) {
                        SmallButton("Reset to 100%") {
                            prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { it.copy(volume = 100) }
                        }
                    }
                }
            }
        }
        // The effects, each its own switch and strength as in the sheet; none under the equalizer's.
        if (dev.niccc2007.filet.playback.SoundPart.SPACE in soundOffers) {
            anchor(Prefs.K_SOUND_MUSIC_SPACE)
            item {
                val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.REVERB
                val level = fx.of(musicSound)
                val sizes = dev.niccc2007.filet.playback.SoundMaths.Space.SIZES
                Column {
                    ToggleRow(
                        "Reverb",
                        "A room around the sound, for every track: from a small room to a large hall.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_MUSIC_SPACE,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { fx.switched(it, on) }
                    }
                    SegmentRow(
                        "Size",
                        sizes.map { it.label },
                        sizes.indexOf(dev.niccc2007.filet.playback.SoundMaths.Space.sized(level.amount)),
                        sub = true,
                        beta = true,
                        hint = "Set while the reverb is off, it is the size heard when it is switched on.",
                        key = Prefs.K_SOUND_MUSIC_SPACE,
                    ) { i ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { fx.withAmount(it, sizes[i].amount) }
                    }
                }
            }
        }
        // Every effect but the reverb shares the filter key, so the first title is the search's.
        anchor(Prefs.K_SOUND_MUSIC_FILTER)
        item {
            Column {
                run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.MUFFLED
                    val level = fx.of(musicSound)
                    ToggleRow(
                        "Muffled",
                        "Through a wall: the top goes and the bass stays.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_MUSIC_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { fx.switched(it, on) }
                    }
                    SliderRow(
                        "Muffled strength",
                        "How thick the wall is. Set while off, it is heard when Muffled is on.",
                        level.amount / 100f,
                        beta = true,
                        sub = true,
                        status = fx.amountLabel(level.amount),
                        key = Prefs.K_SOUND_MUSIC_FILTER,
                    ) { f ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { fx.withAmount(it, fx.snap(f * 100f)) }
                    }
                }
                run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.RADIO
                    val level = fx.of(musicSound)
                    ToggleRow(
                        "Radio",
                        "A small speaker or a phone line: the middle, with both ends cut.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_MUSIC_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { fx.switched(it, on) }
                    }
                    SliderRow(
                        "Radio strength",
                        "How small the speaker is. Set while off, it is heard when Radio is on.",
                        level.amount / 100f,
                        beta = true,
                        sub = true,
                        status = fx.amountLabel(level.amount),
                        key = Prefs.K_SOUND_MUSIC_FILTER,
                    ) { f ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { fx.withAmount(it, fx.snap(f * 100f)) }
                    }
                }
                // The effects that came after: a switch each, their strengths on the sheet.
                run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.UNDERWATER
                    val level = fx.of(musicSound)
                    ToggleRow(
                        "Underwater",
                        "Under the surface: the low end swells and the rest goes dull. Its strength is on the sheet.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_MUSIC_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { fx.switched(it, on) }
                    }
                }
                if (dev.niccc2007.filet.playback.SoundPart.WIDE in soundOffers) run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.WIDE
                    val level = fx.of(musicSound)
                    ToggleRow(
                        "Wide",
                        "Spreads the sound past your ears, on headphones. Its strength is on the sheet.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_MUSIC_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { fx.switched(it, on) }
                    }
                }
                run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.EVEN_OUT
                    val level = fx.of(musicSound)
                    ToggleRow(
                        "Even out",
                        "Brings the loud parts down toward the quiet ones. Its strength is on the sheet.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_MUSIC_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { fx.switched(it, on) }
                    }
                }
                run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.NIGHTCORE
                    val level = fx.of(musicSound)
                    ToggleRow(
                        "Nightcore",
                        "Faster and higher together, on top of the speed. Switches Vaporwave off.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_MUSIC_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { fx.switched(it, on) }
                    }
                }
                run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.VAPORWAVE
                    val level = fx.of(musicSound)
                    ToggleRow(
                        "Vaporwave",
                        "Slower and lower together, on top of the speed. Switches Nightcore off.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_MUSIC_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { fx.switched(it, on) }
                    }
                }
                run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.ORBIT
                    val level = fx.of(musicSound)
                    ToggleRow(
                        "8D",
                        "The sound sweeps from ear to ear every 8 seconds, on headphones. Its swing is on the sheet.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_MUSIC_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { fx.switched(it, on) }
                    }
                }
            }
        }
        anchor(Prefs.K_SOUND_MUSIC_EQ_ON)
        item {
            ToggleRow(
                "Equalizer",
                "The five bands and the bass boost, for every track. It never raises the level.",
                musicSound.eqOn,
                beta = true,
                status = if (musicSound.eqOn) eqName(musicSound) else null,
                key = Prefs.K_SOUND_MUSIC_EQ_ON,
            ) { on -> prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { it.copy(eqOn = on) } }
        }
        anchor(Prefs.K_SOUND_MUSIC_BANDS)
        item {
            val presets = dev.niccc2007.filet.playback.SoundMaths.EqPreset.entries
            val preset = dev.niccc2007.filet.playback.SoundMaths.EqPreset.of(musicSound)
            SegmentRow(
                "EQ preset",
                presets.map { it.label },
                if (preset == null) -1 else presets.indexOf(preset),
                sub = true,
                beta = true,
                hint = "The equalizer's curves, the same seventeen as the sheet. Picking one turns it on.",
                status = if (preset == null) "Custom, from the music player's sheet" else null,
                key = Prefs.K_SOUND_MUSIC_BANDS,
            ) { i -> prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) { presets[i].on(it) } }
        }
        anchor(Prefs.K_SOUND_MUSIC_BASS)
        item {
            SliderRow(
                "Bass boost",
                "Lifts what is under 250 Hz, by up to 9 dB. Part of the equalizer, so it is off when that is.",
                musicSound.bass / 100f,
                beta = true,
                sub = true,
                status = "${musicSound.bass}%",
                key = Prefs.K_SOUND_MUSIC_BASS,
            ) { f ->
                prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.MUSIC) {
                    it.copy(bass = kotlin.math.round(f * 20f).toInt() * 5, eqOn = true)
                }
            }
        }
        // Video's own: the same as its sheet, which is where they are usually set. Here so they
        // can be found by searching, and reset without a file open. Apart from the music since the two were split.
        item { SettingsSection("Sound · Video", "") }
        anchor(Prefs.K_SOUND_VIDEO_VOLUME)
        item {
            val top = dev.niccc2007.filet.playback.SoundMaths.MAX_VOLUME.toFloat()
            Column {
                SliderRow(
                    "App volume",
                    "Of the phone's own volume, for every video. Above 100% a limiter keeps " +
                        "the loud parts under full scale.",
                    videoSound.volume / top,
                    beta = true,
                    status = volumeStatus(videoSound.volume),
                    key = Prefs.K_SOUND_VIDEO_VOLUME,
                ) { f ->
                    prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) {
                        it.copy(volume = dev.niccc2007.filet.playback.SoundMaths.snapVolume(f * top))
                    }
                }
                // Only offered when there is something to undo, like the Home folder's.
                if (videoSound.volume != 100) {
                    Row(Modifier.padding(start = 14.dp, bottom = 6.dp)) {
                        SmallButton("Reset to 100%") {
                            prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { it.copy(volume = 100) }
                        }
                    }
                }
            }
        }
        // The effects, each its own switch and strength as in the sheet; none under the equalizer's.
        if (dev.niccc2007.filet.playback.SoundPart.SPACE in soundOffers) {
            anchor(Prefs.K_SOUND_VIDEO_SPACE)
            item {
                val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.REVERB
                val level = fx.of(videoSound)
                val sizes = dev.niccc2007.filet.playback.SoundMaths.Space.SIZES
                Column {
                    ToggleRow(
                        "Reverb",
                        "A room around the sound, for every video: from a small room to a large hall.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_VIDEO_SPACE,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { fx.switched(it, on) }
                    }
                    SegmentRow(
                        "Size",
                        sizes.map { it.label },
                        sizes.indexOf(dev.niccc2007.filet.playback.SoundMaths.Space.sized(level.amount)),
                        sub = true,
                        beta = true,
                        hint = "Set while the reverb is off, it is the size heard when it is switched on.",
                        key = Prefs.K_SOUND_VIDEO_SPACE,
                    ) { i ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { fx.withAmount(it, sizes[i].amount) }
                    }
                }
            }
        }
        // Every effect but the reverb shares the filter key, so the first title is the search's.
        anchor(Prefs.K_SOUND_VIDEO_FILTER)
        item {
            Column {
                run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.MUFFLED
                    val level = fx.of(videoSound)
                    ToggleRow(
                        "Muffled",
                        "Through a wall: the top goes and the bass stays.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_VIDEO_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { fx.switched(it, on) }
                    }
                    SliderRow(
                        "Muffled strength",
                        "How thick the wall is. Set while off, it is heard when Muffled is on.",
                        level.amount / 100f,
                        beta = true,
                        sub = true,
                        status = fx.amountLabel(level.amount),
                        key = Prefs.K_SOUND_VIDEO_FILTER,
                    ) { f ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { fx.withAmount(it, fx.snap(f * 100f)) }
                    }
                }
                run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.RADIO
                    val level = fx.of(videoSound)
                    ToggleRow(
                        "Radio",
                        "A small speaker or a phone line: the middle, with both ends cut.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_VIDEO_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { fx.switched(it, on) }
                    }
                    SliderRow(
                        "Radio strength",
                        "How small the speaker is. Set while off, it is heard when Radio is on.",
                        level.amount / 100f,
                        beta = true,
                        sub = true,
                        status = fx.amountLabel(level.amount),
                        key = Prefs.K_SOUND_VIDEO_FILTER,
                    ) { f ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { fx.withAmount(it, fx.snap(f * 100f)) }
                    }
                }
                // The effects that came after: a switch each, their strengths on the sheet.
                run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.UNDERWATER
                    val level = fx.of(videoSound)
                    ToggleRow(
                        "Underwater",
                        "Under the surface: the low end swells and the rest goes dull. Its strength is on the sheet.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_VIDEO_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { fx.switched(it, on) }
                    }
                }
                if (dev.niccc2007.filet.playback.SoundPart.WIDE in soundOffers) run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.WIDE
                    val level = fx.of(videoSound)
                    ToggleRow(
                        "Wide",
                        "Spreads the sound past your ears, on headphones. Its strength is on the sheet.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_VIDEO_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { fx.switched(it, on) }
                    }
                }
                run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.EVEN_OUT
                    val level = fx.of(videoSound)
                    ToggleRow(
                        "Even out",
                        "Brings the loud parts down toward the quiet ones. Its strength is on the sheet.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_VIDEO_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { fx.switched(it, on) }
                    }
                }
                run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.NIGHTCORE
                    val level = fx.of(videoSound)
                    ToggleRow(
                        "Nightcore",
                        "Faster and higher together, on top of the speed. Switches Vaporwave off.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_VIDEO_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { fx.switched(it, on) }
                    }
                }
                run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.VAPORWAVE
                    val level = fx.of(videoSound)
                    ToggleRow(
                        "Vaporwave",
                        "Slower and lower together, on top of the speed. Switches Nightcore off.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_VIDEO_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { fx.switched(it, on) }
                    }
                }
                run {
                    val fx = dev.niccc2007.filet.playback.SoundMaths.Overlay.ORBIT
                    val level = fx.of(videoSound)
                    ToggleRow(
                        "8D",
                        "The sound sweeps from ear to ear every 8 seconds, on headphones. Its swing is on the sheet.",
                        level.on,
                        beta = true,
                        status = if (level.on) fx.amountLabel(level.amount) else null,
                        key = Prefs.K_SOUND_VIDEO_FILTER,
                    ) { on ->
                        prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { fx.switched(it, on) }
                    }
                }
            }
        }
        anchor(Prefs.K_SOUND_VIDEO_EQ_ON)
        item {
            ToggleRow(
                "Equalizer",
                "The five bands and the bass boost, for every video. It never raises the level.",
                videoSound.eqOn,
                beta = true,
                status = if (videoSound.eqOn) eqName(videoSound) else null,
                key = Prefs.K_SOUND_VIDEO_EQ_ON,
            ) { on -> prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { it.copy(eqOn = on) } }
        }
        anchor(Prefs.K_SOUND_VIDEO_BANDS)
        item {
            val presets = dev.niccc2007.filet.playback.SoundMaths.EqPreset.entries
            val preset = dev.niccc2007.filet.playback.SoundMaths.EqPreset.of(videoSound)
            SegmentRow(
                "EQ preset",
                presets.map { it.label },
                if (preset == null) -1 else presets.indexOf(preset),
                sub = true,
                beta = true,
                hint = "The equalizer's curves, the same seventeen as the sheet. Picking one turns it on.",
                status = if (preset == null) "Custom, from the video player's sheet" else null,
                key = Prefs.K_SOUND_VIDEO_BANDS,
            ) { i -> prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { presets[i].on(it) } }
        }
        anchor(Prefs.K_SOUND_VIDEO_BASS)
        item {
            SliderRow(
                "Bass boost",
                "Lifts what is under 250 Hz, by up to 9 dB. Part of the equalizer, so it is off when that is.",
                videoSound.bass / 100f,
                beta = true,
                sub = true,
                status = "${videoSound.bass}%",
                key = Prefs.K_SOUND_VIDEO_BASS,
            ) { f ->
                prefs.editSound(dev.niccc2007.filet.playback.SoundTarget.VIDEO) {
                    it.copy(bass = kotlin.math.round(f * 20f).toInt() * 5, eqOn = true)
                }
            }
        }
        }
        if (group == SettingsGroup.READING) {
        item { SettingsSection("Documents", "") }
        anchor(Prefs.K_READ_SIZE)
        item {
            SegmentRow(
                "Text size",
                READ_SIZES.map { "$it%" },
                READ_SIZES.indexOf(reading.size),
                hint = "How large Markdown, Word and slide text is drawn. Pinching a page moves this too.",
                status = if (reading.size !in READ_SIZES) "Now ${reading.size}%, from a pinch" else null,
                key = Prefs.K_READ_SIZE,
            ) { i -> prefs.setReadSize(READ_SIZES[i]) }
        }
        anchor(Prefs.K_READ_COLUMN)
        item {
            ToggleRow(
                "Readable column",
                "Holds text to about seventy characters a line on a wide screen. Tables and code still take the width.",
                reading.column,
                key = Prefs.K_READ_COLUMN,
            ) { prefs.setReadColumn(it) }
        }
        anchor(Prefs.K_READ_TABLES)
        item {
            // Compact is offered in the document's own Aa panel, where the table that needs it is
            // on screen; here it is said rather than hidden when it is the one in force.
            val modes = listOf(dev.niccc2007.filet.docs.TableMode.FIT, dev.niccc2007.filet.docs.TableMode.COMFORTABLE)
            SegmentRow(
                "Tables",
                modes.map { it.label },
                modes.indexOf(reading.tables),
                hint = "Fit wraps tables to the screen. Comfortable keeps cells on one line and scrolls.",
                status = if (reading.tables !in modes) "Compact, chosen on a document" else null,
                key = Prefs.K_READ_TABLES,
            ) { i -> prefs.setReadTables(modes[i]) }
        }
        anchor(Prefs.K_READ_WEB_PICTURES)
        item {
            ToggleRow(
                "Load web pictures automatically",
                "Pictures a document links from the web, like badges and screenshots, load as it opens. Off, each shows its name and loads when tapped.",
                reading.webPictures,
                key = Prefs.K_READ_WEB_PICTURES,
            ) { prefs.setReadWebPictures(it) }
        }
        }
        if (group == SettingsGroup.SEARCH) {
        item { SettingsSection("Search index", "") }
        anchor(Prefs.K_INDEX)
        item {
            ToggleRow(
                "Index files",
                "Makes whole-device search instant.",
                indexOn,
                key = Prefs.K_INDEX,
            ) {
                prefs.setIndexEnabled(it)
                vm.onIndexToggled(it)
            }
        }
        // Only with an index to keep. A switch for how the index updates, drawn while there is
        // no index, is a control with nothing behind it.
        if (indexOn) {
            anchor(Prefs.K_INDEX_AUTO)
            item {
                ToggleRow(
                    "Update automatically",
                    if (indexAuto) {
                        "Updates on open and while charging."
                    } else {
                        "Updates only when started manually."
                    },
                    indexAuto,
                    key = Prefs.K_INDEX_AUTO,
                ) {
                    prefs.setIndexAuto(it)
                    vm.onIndexAutoToggled(it)
                }
            }
            // Under "on its own", because it is one of the ways it is on its own - and only while
            // that is on, since Manual means nothing runs unasked, a listener included.
            if (indexAuto) {
                anchor(Prefs.K_LISTEN_MINUTES)
                item {
                    SegmentRow(
                        "Watch after leaving",
                        dev.niccc2007.filet.index.ListenWindow.CHOICES.map(dev.niccc2007.filet.index.ListenWindow::label),
                        dev.niccc2007.filet.index.ListenWindow.CHOICES.indexOf(listenMinutes).coerceAtLeast(0),
                        sub = true,
                        hint = dev.niccc2007.filet.index.ListenWindow.describe(listenMinutes),
                        key = Prefs.K_LISTEN_MINUTES,
                    ) { i -> prefs.setListenMinutes(dev.niccc2007.filet.index.ListenWindow.CHOICES[i]) }
                }
            }
        }
        item { IndexStatusCard(vm) }

        }
        if (group == SettingsGroup.STORAGE) {
        item {
            SettingsSection(
                "Filet storage",
                "",
                first = true,
            )
        }
        anchor(PrefKeys.CACHE_LOCATION, Prefs.K_CACHE_REMIND)
        item { CacheCard(vm) }
        // Every setting as one text file, for somebody who would rather type than tap. The view
        // model writes it if it is missing and opens it in Filet's own editor.
        item {
            HoldHint(
                "Every setting as text. Saving applies it while Filet is open; a value it cannot " +
                    "use is skipped and named with its line.",
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Settings file", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            HintInfo()
                        }
                        Text(
                            "filet.json in the Filet folder. Save it to apply.",
                            fontSize = 10.5.sp,
                            color = colors.fg3,
                        )
                    }
                    SmallButton("Edit") { vm.editSettingsFile() }
                }
            }
        }
        // How long Activity keeps what finished. The history survives a restart; this is where
        // it stops. See JobHistory.prune.
        item { SettingsSection("Activity", "") }
        anchor(Prefs.K_JOBS_KEEP_DAYS)
        item {
            val keep by prefs.jobsKeepDays.collectAsState()
            val choices = dev.niccc2007.filet.jobs.JobHistory.KEEP_CHOICES
            SegmentRow(
                "Keep finished jobs",
                choices.map(dev.niccc2007.filet.jobs.JobHistory::keepLabel),
                choices.indexOf(keep).takeIf { it >= 0 } ?: choices.indexOf(dev.niccc2007.filet.jobs.JobHistory.DEFAULT_KEEP_DAYS),
                hint = "How long Activity lists a job after it ends. Running jobs are never removed.",
                key = Prefs.K_JOBS_KEEP_DAYS,
            ) { i -> prefs.setJobsKeepDays(choices[i]) }
        }
        item { SettingsSection("Storage access", "") }
        item { StorageAccessCard(vm) }
        // Only on a phone that has actually granted root. Offering it anywhere else would be
        // a switch over three volumes that cannot exist - and those volumes appearing without
        // root is the bug this setting arrived with. See `RootProvider.roots`.
        if (dev.niccc2007.filet.vfs.provider.RootProvider.isGranted()) {
            anchor(Prefs.K_ROOT_VOLUMES)
            item {
                ToggleRow(
                    "Root storage on Home",
                    if (rootVolumes) {
                        "Shows root, /data and /system on Home."
                    } else {
                        "Keeps root, /data and /system off Home."
                    },
                    rootVolumes,
                    key = Prefs.K_ROOT_VOLUMES,
                ) { prefs.setRootVolumes(it) }
            }
        }
        // Only when Termux is actually on the phone. An integration section advertising an
        // app somebody does not have is the definition of a dead switch.
        if (vm.termuxInstalled) {
            item {
                SettingsSection(
                    "Termux",
                    "",
                )
            }
            item {
                HoldHint("Android asks once for access to Termux's files.") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SmallButton("Connect home") { vm.connectTermux(home = true) }
                    SmallButton("Connect files") { vm.connectTermux(home = false) }
                    SmallButton("Open Termux") { vm.launchTermux() }
                }
                }
            }
        }
        }
        if (group == SettingsGroup.DEVELOPER) {
            item {
                SettingsSection(
                    "Developer",
                    "",
                    first = true,
                )
            }
            item { DeveloperCard(vm) }
        }
        if (group == SettingsGroup.MAINTENANCE) {
        // First on System rather than inside Storage. Most of the list is not about storage at
        // all - notifications, the camera, installing packages - and under Storage it read as
        // a storage setting that had wandered. What the system lets Filet do is this tab.
        item { SettingsSection("Permissions", "", first = true) }
        item {
            // Re-read whenever the app comes back to the front, which is the only moment a
            // permission can have changed - see PermissionWatch for why that beats a timer.
            dev.niccc2007.filet.perms.PermissionsCard { intents -> vm.openFirstThatResolves(intents) }
        }
        // Only on the flavour that has an updater at all. R1, no dead switches: on F-Droid
        // this would be a control over something that is compiled out.
        if (dev.niccc2007.filet.BuildConfig.UPDATER_ENABLED) {
            item { SettingsSection("Updates", "") }
            anchor(Prefs.K_UPD_OFF)
            item {
                ToggleRow(
                    "Update notifications",
                    "Checks GitHub twice a day for a new version.",
                    updatesOn,
                    key = Prefs.K_UPD_OFF,
                ) { vm.setUpdateNotifications(it) }
            }
            item {
                // ── when it last asked, said out loud ──
                //
                // Without this, "there is no update" and "nothing has been checked" look
                // identical, which is how a silent updater gets reported as a broken one and
                // cannot be argued with. Two facts, both already stored: the version running,
                // and when GitHub last answered.
                val checkedAt by prefs.updateLastCheckedAt.collectAsState()
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(
                        "Running " + dev.niccc2007.filet.update.Updater.installed.toString() +
                            if (checkedAt <= 0L) {
                                "  ·  not checked yet"
                            } else {
                                "  ·  checked " + dev.niccc2007.filet.playback.agoOf(
                                    System.currentTimeMillis() - checkedAt,
                                )
                            },
                        fontSize = 10.5.sp,
                        color = Filet.colors.fg3,
                    )
                    Spacer(Modifier.height(6.dp))
                    SmallButton("Check now") { vm.checkForUpdates() }
                }
            }
        }
        item {
            SettingsSection(
                "Logs",
                "",
            )
        }
        anchor(dev.niccc2007.filet.log.FiletLog.PREF_VERBOSE)
        item {
            ToggleRow(
                "Detailed logging",
                "Logs every step, not only warnings and failures.",
                verboseLogs,
                key = dev.niccc2007.filet.log.FiletLog.PREF_VERBOSE,
            ) { prefs.setVerboseLogs(it) }
        }
        item { LogFileRow(vm) }
        item {
            SettingsSection(
                "Settings transfer",
                "",
            )
        }
        item {
            HoldHint("A plain text file, with nothing that identifies this phone.") {
                Row(Modifier.padding(horizontal = 15.dp, vertical = 4.dp)) {
                    SmallButton("Export") { vm.openSettingsExport() }
                    Spacer(Modifier.width(8.dp))
                    SmallButton("Import") { vm.openSettingsImport() }
                }
            }
        }
        }
        }
    }
    }
    }

    pickingOpenerFor?.let { ext ->
        OpenerPicker(vm, ext) { pickingOpenerFor = null }
    }
}

/**
 * This run's log file, and the way to it.
 *
 * A setting that writes a file somebody cannot find is a setting that does nothing for them. The
 * row names the file this run is writing and opens the folder in Filet itself - which is the whole
 * reason the logs are in shared storage rather than app-private storage.
 */
@Composable
private fun LogFileRow(vm: BrowserViewModel) {
    val colors = Filet.colors
    val file = dev.niccc2007.filet.log.FiletLog.file
    Column(
        Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 2.dp, bottom = 6.dp),
    ) {
        Text(
            if (file == null) "No log file yet" else "Writing " + file.name,
            fontSize = 10.5.sp,
            color = colors.fg3,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(7.dp))
        SmallButton("Open logs") { vm.openLogsFolder() }
    }
}

/**
 * Says that a setting governs something still being worked out.
 *
 * On the setting rather than only in the release notes: notes are read once, by whoever
 * happens to read them, and this is the screen somebody is on when the thing misbehaves.
 * It is the difference between "the app is broken" and "that part is new".
 */
@Composable
fun BetaTag() {
    val colors = Filet.colors
    Text(
        "BETA",
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = colors.accent,
        modifier = Modifier
            .padding(start = 7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.accent.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** A labelled slider with the sentence that says what moving it does. */
/**
 * How far in a dependent row sits.
 *
 * ## Why these rows needed an indent at all
 *
 * The player's settings are two levels deep - the glow, then the pulse under it, then the
 * pulse's own four controls - and every one of them was drawn at the same 14dp margin. Read
 * top to bottom that is eight equal siblings, so nothing says that turning the glow off takes
 * seven of them with it, and the list reads as a pile of unrelated switches rather than as one
 * feature with its settings. The panel inside the player already indents its sub-rows; this
 * brings the two into line.
 *
 * One step, not two. A second level of indent on the pulse's children would cost 28dp of a
 * phone's width to say something the enclosing switch already says.
 */
private val SUB_INDENT = 30.dp

/** The text sizes Settings offers. The document's Aa panel steps through every ten between. */
private val READ_SIZES = listOf(80, 100, 120, 140, 160)

@Composable
fun SliderRow(
    title: String,
    /** What it does, shown on hold rather than printed. See [HoldHint]. */
    hint: String?,
    value: Float,
    beta: Boolean = false,
    /** True for a row that only applies while the row above it is on. See [SUB_INDENT]. */
    sub: Boolean = false,
    /** A live value or a warning, which stays printed under the title. */
    status: String? = null,
    /** The preference this row writes, shown last in its hint. */
    key: String? = null,
    onChange: (Float) -> Unit,
) = HoldHint(hint, key) {
    val colors = Filet.colors
    Column(
        Modifier.padding(
            start = if (sub) SUB_INDENT else 14.dp,
            end = 14.dp,
            top = 4.dp,
            bottom = 4.dp,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            HintInfo()
            if (beta) BetaTag()
        }
        if (status != null) Text(status, fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp)
        CompactSlider(value, onChange)
    }
}

/**
 * Material's slider and switch at the size of the text beside them.
 *
 * Bug identified here: the rows were slimmed to 13sp titles with the hint moved behind a hold,
 * but the controls kept Material's full size. A Switch reserves a 48dp touch square however small
 * it draws, and the 1.4 Slider draws a 44dp thumb, so a one-line toggle stood 66dp tall and a
 * slider row 74 - each row read as if a second line of text were still under its title, with
 * nothing there.
 *
 * The touch target does not shrink with the drawing. A toggle row is clickable end to end, so
 * its target is the whole 44dp row rather than the switch; a slider keeps a 32dp band to drag in.
 */
@Composable
private fun CompactSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Box(Modifier.size(42.dp, 26.dp), contentAlignment = Alignment.Center) {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                modifier = Modifier.requiredSize(52.dp, 32.dp).scale(0.8f),
            )
        }
    }
}

/** See [CompactSwitch]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactSlider(value: Float, onChange: (Float) -> Unit) {
    val colors = Filet.colors
    val sliderColors = SliderDefaults.colors(
        thumbColor = colors.accent,
        activeTrackColor = colors.accent,
        inactiveTrackColor = colors.sunken,
    )
    val interaction = remember { MutableInteractionSource() }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            colors = sliderColors,
            interactionSource = interaction,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interaction,
                    colors = sliderColors,
                    thumbSize = DpSize(4.dp, 24.dp),
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(10.dp),
                    colors = sliderColors,
                )
            },
        )
    }
}

@Composable
fun ToggleRow(
    title: String,
    /** What it does, shown on hold rather than printed. See [HoldHint]. */
    hint: String?,
    checked: Boolean,
    beta: Boolean = false,
    /** True for a row that only applies while the row above it is on. See [SUB_INDENT]. */
    sub: Boolean = false,
    /** A live value or a warning, which stays printed under the title. */
    status: String? = null,
    /** The preference this row writes, shown last in its hint. */
    key: String? = null,
    onChange: (Boolean) -> Unit,
) = HoldHint(hint, key) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(
                start = if (sub) SUB_INDENT else 14.dp,
                end = 14.dp,
                top = 9.dp,
                bottom = 9.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                HintInfo()
                if (beta) BetaTag()
            }
            if (status != null) Text(status, fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        CompactSwitch(checked, onChange)
    }
}

/**
 * A row of mutually exclusive choices.
 *
 * ## It wraps, and that is the whole fix
 *
 * A plain [Row] lays its children out side by side whether or not there is room, so in a
 * split pane the fourth palette came out as "I / n / k" standing outside the control's own
 * border, and the selected "Large" chip grew into a two-line block that pushed the row to
 * twice its height. Neither was a styling problem; the control was being asked for a width
 * it did not have and had no way to say so.
 *
 * [FlowRow] moves what does not fit onto a second line. At full width nothing moves - it
 * measures identically to the Row it replaced, border still hugging the chips - so this
 * costs nothing on a phone held in one hand and is the whole of the repair in a split.
 *
 * The chips themselves never wrap internally (`softWrap = false`): a chip is one word for
 * one choice, and a choice broken across two lines is unreadable at this size.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SegmentRow(
    label: String,
    options: List<String>,
    selected: Int,
    /** True for a row that only applies while the row above it is on. See [SUB_INDENT]. */
    sub: Boolean = false,
    beta: Boolean = false,
    /** What it, or the chosen option, does - shown on hold. See [HoldHint]. */
    hint: String? = null,
    /** A live value or a warning, which stays printed under the chips. */
    status: String? = null,
    /** The preference this row writes, shown last in its hint. */
    key: String? = null,
    onPick: (Int) -> Unit,
) = HoldHint(hint, key) {
    val colors = Filet.colors
    Column(Modifier.padding(start = if (sub) 14.dp + SUB_INDENT else 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = colors.fg2)
            if (beta) BetaTag()
            HintInfo()
        }
        Spacer(Modifier.height(5.dp))
        FlowRow(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.lineSoft, RoundedCornerShape(8.dp)),
        ) {
            options.forEachIndexed { i, opt ->
                val on = i == selected
                Text(
                    opt,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                    modifier = Modifier
                        .background(if (on) colors.accent else Color.Transparent)
                        .clickable { onPick(i) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (status != null) {
            Spacer(Modifier.height(5.dp))
            Text(status, fontSize = 11.5.sp, color = colors.fg3, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun IndexStatusCard(vm: BrowserViewModel) {
    val colors = Filet.colors
    val status by vm.indexStatus.collectAsState()
    val run by vm.indexRun.collectAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .background(colors.raised)
            .padding(12.dp),
    ) {
        // SEARCH.md §7.11: the controls are only honest if the readout beside them is real.
        Text(status.headline, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(3.dp))
        Text(status.detail, fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp)

        // A run the user started, and why it is or is not still going. The complaint this
        // answers was "why did it stop?" - so a finished run keeps its reason on screen
        // instead of vanishing and leaving the readout looking identical to a run that never
        // happened.
        if (run.line.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (run.active) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = colors.accent,
                    )
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    run.line,
                    fontSize = 11.sp,
                    color = when {
                        run.active -> colors.accent
                        run.endedBecause == CrawlEnd.DONE -> colors.fg2
                        run.endedBecause == CrawlEnd.FAILED -> colors.bad
                        else -> colors.warn
                    },
                )
            }
            run.endedBecause?.takeIf { !run.active }?.let { end ->
                Spacer(Modifier.height(3.dp))
                Text(end.explanation, fontSize = 10.sp, color = colors.fg3, lineHeight = 13.5.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // R1, no dead switches: while a crawl is running, the button that starts one is a
            // button that does nothing, so it becomes the one that stops it.
            // One button, three states, decided by IndexRun.action - Chosen over two
            // separate buttons: "if the user wants to rebuild from scratch then press the
            // clear button that already exists". So there is no rebuild control to press by
            // accident, and clearing is the deliberate act it should be.
            if (run.active) SmallButton(run.action) { vm.stopIndexing() }
            else SmallButton(run.action) { vm.reindexNow() }
            SmallButton("Clear index") { vm.clearIndex() }
        }
        Spacer(Modifier.height(6.dp))
        // Was one 442-character paragraph. Nothing in it was wrong and none of it was needed
        // before pressing the button, which is the definition of something that belongs shut.
        dev.niccc2007.filet.ui.tabs.NoteStack(
            "About indexing",
            listOf(
                "Updates add new files and drop removed ones.",
                "Runs when Filet opens, and continues while locked.",
                "Stops from the notification, or by itself below 15% battery.",
                "Clear index deletes it; the next run rebuilds it.",
            ),
        )
    }
}

/**
 * Where Filet's derived files live, how big they have become, and the way into the folder.
 *
 * ## Why this is a setting at all
 *
 * The default moved out of Android's cache directory, and that is a real trade rather than a
 * straight win. What was gained: beat maps survive, because the system empties `cacheDir`
 * whenever the phone wants room and does not say so - which quietly threw away work that cost
 * two seconds of spectrum analysis per track. What was lost: nothing empties it for you any
 * more. Somebody who would rather have the old behaviour should be able to have it, so the
 * choice is here and the consequence of each option is written next to it rather than implied.
 *
 * ## Why the sizes are per folder
 *
 * A single "Clear cache" button tells somebody nothing about what they are about to lose. Ten
 * megabytes of decoded samples and seventy kilobytes of beat maps are both "cache", and only
 * one of them is worth keeping - so the folders are listed with what each holds and what it
 * costs, and the row that cannot be made again is not offered for deletion at all.
 */
/**
 * The name rules New files leaves out, and the folders anything matching them contains.
 *
 * ## Why this one IS a text field when the folder list deliberately is not
 *
 * The folder list next to it is read-and-undo on purpose: a folder gets hidden from the row it
 * was noisy on, where the thing being hidden is in front of you, and a path typed into a
 * settings box is a second way to do it that can be wrong and cannot be checked.
 *
 * A prefix is the opposite. It is short, it is not a path so there is nothing to mistype into
 * a different real place, and - crucially - it is a rule about things that have not appeared
 * yet. `.trashed-1699`, `.trashed-1700`: blocking those one folder at a time is a losing game
 * because the next one has a different number on the end. That is the whole reason to have
 * prefixes at all, and it cannot be done by pointing at a row.
 *
 * The suggestions are chips rather than a default value. A feed that hid things from the first
 * launch would be impossible to tell apart from a feed that was not working.
 */
@Composable
private fun FeedPrefixCard(vm: BrowserViewModel) {
    val colors = Filet.colors
    val saved by vm.prefs.feedPrefixes.collectAsState()
    // The text is the field's own state while it is being typed; the SET is the authority.
    // Rebuilt whenever the stored value changes from elsewhere - a chip, another screen - so
    // the box can never show a rule that is not in force.
    // Exactly what is in force, never a placeholder standing in for it. The defaults live in
    // the preference - see `FeedExclusions.DEFAULT_PREFIXES` for what went wrong when they
    // lived here.
    var text by remember(saved) { mutableStateOf(FeedExclusions.format(saved)) }

    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        HoldHint("This is for the [New files] list. You may put prefixes for files/folders (ex: ., tmp_, etc...), or the complete file name, or folder name in here.", key = Prefs.K_FEED_PREFIXES) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Content exclusion list",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                HintInfo()
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                // Applied as it is typed rather than behind a Save. There is nothing to
                // commit - the feed re-reads the rule on its next page - and a Save button
                // is one more thing to forget to press.
                vm.prefs.setFeedPrefixes(FeedExclusions.parse(it))
            },
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
            placeholder = { Text("Nothing hidden", fontSize = 12.sp, color = colors.fg3) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in FeedExclusions.SUGGESTED) {
                val on = p in saved
                SmallButton(if (on) "✓ $p" else p) {
                    val next = if (on) saved - p else saved + p
                    vm.prefs.setFeedPrefixes(next)
                }
            }
        }
    }
}

@Composable
private fun CacheCard(vm: BrowserViewModel) {
    val colors = Filet.colors
    val context = LocalContext.current
    val where by vm.prefs.cacheLocation.collectAsState()
    // Re-read after a change or a clear, off the main thread: it walks the folders.
    //
    // Bug identified here: this card is one item of the tab's LazyColumn, and an item that
    // scrolls out of view leaves composition. Coming back, produceState started again from an
    // empty list, so every folder row vanished and reappeared a moment later - the whole list
    // appeared to reload on each scroll up. It now starts from the last readout and the walk
    // only replaces it, so a scroll back shows the same numbers it left.
    var tick by remember { mutableIntStateOf(0) }
    val sizes by produceState(StorageReadout.sizes, where, tick) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                FiletStore.Area.entries.map { it to FiletStore.sizeOf(context, it) }
            }.getOrDefault(emptyList())
        }
        StorageReadout.sizes = value
    }

    SegmentRow(
        "App data location",
        FiletStore.Location.entries.map { it.label },
        FiletStore.Location.entries.indexOf(where),
        hint = "Settings, playlists and bookmarks always stay in the Filet folder.",
        key = PrefKeys.CACHE_LOCATION,
    ) { i -> vm.prefs.setCacheLocation(FiletStore.Location.entries[i]) }
    val remindPast by vm.prefs.cacheRemindPast.collectAsState()
    SegmentRow(
        "Remind me past",
        FiletStore.REMIND_STEPS.map(FiletStore::remindLabel),
        FiletStore.REMIND_STEPS.indexOf(remindPast).coerceAtLeast(0),
        hint = "Home mentions the cache once it grows past this.",
        key = Prefs.K_CACHE_REMIND,
    ) { i -> vm.prefs.setCacheRemindPast(FiletStore.REMIND_STEPS[i]) }
    // What the setting does not move is the hint on App data location, above the folder list.
    //
    // The setting moves the derived half - the things Filet can work out again. It does not
    // move what cannot be: settings, playlists and bookmarks live in the Filet folder whatever
    // this is set to, because a control worded about working files that could quietly put
    // somebody's playlists back somewhere a wipe deletes them is a trap. That is not
    // hypothetical - it is what was lost the day this was written.

    for ((area, bytes) in sizes) {
        if (bytes <= 0L) continue
        HoldHint(area.note) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(area.folder, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                HintInfo()
            }
            Text(
                humanSize(bytes),
                fontSize = 12.sp,
                fontFamily = null,
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                color = colors.fg2,
            )
            // Only what can be made again. A button that deletes somebody's scripts does not
            // belong beside one that deletes a decoded copy of a song.
            if (area.disposable) {
                Spacer(Modifier.width(10.dp))
                SmallButton("Clear") {
                    FiletStore.clear(context, area)
                    vm.prefs.setCacheRemindDismissed(0L)
                    tick++
                }
            }
        }
        }
    }
    // Restoring, for the case the silent path cannot cover.
    //
    // A cleared app whose All-files access survived is put back before the first preference is
    // read, with nothing to decide - see `FiletApp.restoreSettingsIfLost`. A FRESH INSTALL has
    // no permission at startup and cannot see shared storage at all, so it reaches here
    // instead: the snapshot is found once permission exists, and applying it needs a restart
    // because the settings were already read into memory on the way to this screen.
    val savedAt by produceState(StorageReadout.savedAt, tick) {
        value = withContext(Dispatchers.IO) {
            runCatching { SettingsHome.newestAt(context) }.getOrNull()
        }
        StorageReadout.savedAt = value
    }
    savedAt?.let { stamp ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Settings backup", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Saved " + ago(stamp),
                    fontSize = 10.5.sp,
                    color = colors.fg3,
                )
            }
            SmallButton("Restore") {
                if (SettingsHome.restore(context) > 0) vm.restartForRestoredSettings()
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SmallButton("Open folder") { vm.openFiletFolder() }
        SmallButton("Clear cache") {
            for (a in FiletStore.Area.entries) if (a.disposable) FiletStore.clear(context, a)
            // The size a dismissal measured is gone with it.
            vm.prefs.setCacheRemindDismissed(0L)
            tick++
        }
    }
}

/**
 * The cache card's last measurement, kept outside the card so it outlives the list item.
 * Only a starting point: the card measures again every time it is composed.
 */
private object StorageReadout {
    @Volatile var sizes: List<Pair<FiletStore.Area, Long>> = emptyList()
    @Volatile var savedAt: Long? = null
}

@Composable
private fun StorageAccessCard(vm: BrowserViewModel) {
    val colors = Filet.colors
    // Reads the actual state instead of assuming the worst.
    //
    // This card offered **Grant all files** whether or not all-files access was already
    // granted, which was the report: a button that offers something you already have makes
    // you doubt that the grant took. It knows now, and when it has the access it says so and
    // offers only the thing that is still useful - another folder on top.
    val granted = dev.niccc2007.filet.perms.rememberGrantedPermissions()
    val hasAll = dev.niccc2007.filet.perms.Perms.ALL_FILES in granted
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .background(colors.raised)
            .padding(12.dp),
    ) {
        Text(
            if (hasAll) {
                "All files access granted."
            } else {
                "Only granted folders are visible."
            },
            fontSize = 11.sp, color = colors.fg2, lineHeight = 15.sp,
        )
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hasAll) SmallButton("All files access") { vm.requestAllFiles() }
            SmallButton("Add a folder") { vm.requestFolderGrant() }
        }
    }
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit) {
    val colors = Filet.colors
    Text(
        text,
        fontSize = 11.5.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(7.dp))
            .background(colors.high)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

/**
 * One entry in the bottom-bar editor.
 *
 * Arrows rather than a drag. A drag inside a `LazyColumn` that is itself scrolling needs a
 * nested-scroll dance to tell "move this row" from "scroll the page", and it is the gesture
 * that most often fights a scroll on a phone; two buttons are unambiguous, reachable with a
 * thumb, and work with a screen reader - which a drag does not without a second,
 * accessibility-only path that would then be the thing nobody tests.
 */
@Composable
internal fun BarRow(
    label: String,
    /** A live value - the position on the bar - printed under the label. Null for none. */
    detail: String?,
    /** What it is, shown on hold. See [HoldHint]. */
    hint: String? = null,
    /**
     * What the thing is being added to or taken off, for the content descriptions.
     *
     * Parameterised because this row is no longer only the bottom bar's: the row-detail card
     * reuses it, and a screen reader announcing "Take Date off the bar" on the Rows screen
     * would be describing a control that does not exist.
     */
    what: String = "the bar",
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
    /** The preference this row writes, shown last in its hint. */
    key: String? = null,
) = HoldHint(hint, key) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onAdd != null) Modifier.clickable(onClick = onAdd) else Modifier)
            .padding(start = 16.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                HintInfo()
            }
            if (detail != null) Text(detail, fontSize = 10.sp, color = colors.fg3)
        }
        if (onAdd != null) {
            Icon(
                FiletIcons.Plus, "Add $label to $what",
                tint = colors.accent,
                modifier = Modifier.size(32.dp).padding(8.dp),
            )
        }
        onUp?.let {
            Icon(
                FiletIcons.Up, "Move $label up",
                tint = colors.fg2,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = it).padding(8.dp),
            )
        }
        onDown?.let {
            Icon(
                FiletIcons.Down, "Move $label down",
                tint = colors.fg2,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = it).padding(8.dp),
            )
        }
        onRemove?.let {
            Icon(
                FiletIcons.Close, "Take $label off $what",
                tint = colors.fg3,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = it).padding(9.dp),
            )
        }
    }
}

/** The app volume as the row says it, with the boost's read-back rule where there is a boost. */
private fun volumeStatus(volume: Int): String =
    if (volume > 100) "$volume%, back to 100% the next time Filet starts" else "$volume%"

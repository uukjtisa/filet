package dev.niccc2007.filet.settings

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
 * The design calls for Settings to be properly segregated - it was packed, with no visual
 * distinction between the interface options, the general ones and the rest. He is right. The
 * old heading was 9.5sp grey micro-caps
 * with 7dp of padding - technically a label, visually a row like any other, so eleven controls
 * read as one undifferentiated list.
 *
 * Three changes, and the first is the one that does the work: **space above**. A group is
 * defined by the gap before it far more than by its title. Then a title at reading size rather
 * than caption size, and a sentence saying what the group is for, which doubles as the answer
 * to "which section is this setting in".
 */
@Composable
private fun SettingsSection(title: String, subtitle: String, first: Boolean = false) {
    val colors = Filet.colors
    Column(Modifier.fillMaxWidth().padding(top = if (first) 8.dp else 26.dp)) {
        Text(
            title,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.fg2,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp),
        )
        Text(
            subtitle,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = colors.fg3,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 9.dp),
        )
        HorizontalDivider(color = colors.lineSoft)
        Spacer(Modifier.height(6.dp))
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
    SEARCH("Search"),
    STORAGE("Storage and access"),
    MAINTENANCE("Maintenance"),
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
    val verboseLogs by prefs.verboseLogs.collectAsState()
    val updatesOn by vm.updateNotificationsOn.collectAsState()
    val selectionBar by prefs.selectionBar.collectAsState()
    val uninterrupted by prefs.uninterrupted.collectAsState()
    val playerFace by prefs.playerFace.collectAsState()
    // Hoisted out of the look-picker row below: the beat settings are scoped to the
    // look that is selected, and a LazyColumn's items cannot see a value declared
    // inside one of their siblings.
    val face = dev.niccc2007.filet.playback.PlayerFace.of(playerFace)
    val pitchMode by prefs.pitchMode.collectAsState()
    val resumePlayback by prefs.resumePlayback.collectAsState()
    val rootVolumes by prefs.rootVolumes.collectAsState()
    val nameLines by prefs.nameLines.collectAsState()
    val livingGlow by prefs.livingGlow.collectAsState()
    val beatPulse by prefs.beatPulse.collectAsState()
    val pulseDepth by prefs.pulseDepth.collectAsState()
    val waveGap by prefs.waveGap.collectAsState()
    val glowSpill by prefs.glowSpill.collectAsState()
    val treeView by prefs.treeView.collectAsState()
    val reachMode by prefs.reachMode.collectAsState()
    val deviceTier by prefs.deviceTier.collectAsState()
    val scratchAudio by prefs.scratchAudio.collectAsState()
    val discTurn by prefs.discTurnMs.collectAsState()
    val glowStrength by prefs.glowStrength.collectAsState()
    val paneSwipe by prefs.paneSwipe.collectAsState()
    val paneSwipeAt by prefs.paneSwipeAt.collectAsState()
    val tabSize by prefs.tabSize.collectAsState()
    val entry by prefs.entry.collectAsState()
    var pickingOpenerFor by remember { mutableStateOf<String?>(null) }
    val colors = Filet.colors

    // Remembered for the session, not stored. Which page of settings somebody was last on is a
    // fact about the last thirty seconds, and restoring it a week later would be a screen that
    // opens somewhere unexplained.
    var group by remember { mutableStateOf(SettingsGroup.APPEARANCE) }

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
            for (g in SettingsGroup.entries) {
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        if (group == SettingsGroup.APPEARANCE) {
        item { SettingsSection("Appearance", "Theme, accent, and how much fits on a screen.", first = true) }
        item {
            SegmentRow("Theme", ThemeChoice.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, theme.ordinal) {
                prefs.setTheme(ThemeChoice.entries[it])
            }
        }
        item {
            SegmentRow("Palette", listOf("Slate", "Ember", "Moss", "Ink"), accent.ordinal) {
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

        item {
            SegmentRow("Tab size", TabSize.entries.map { it.label }, tabSize.ordinal) {
                prefs.setTabSize(TabSize.entries[it])
            }
        }
        item {
            Text(
                "Scales the whole chip, not just the label.",
                fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 4.dp),
            )
        }
        item {
            // Issue #11, and the request was specific: show the whole name by letting it wrap,
            // NOT by scrolling it past. A marquee does show all of it eventually, and
            // "eventually" is the objection - a list is read by scanning, and a name that has
            // to be waited for cannot be scanned.
            Column {
                SegmentRow(
                    "Lines for a name",
                    listOf("1", "2", "3", "4"),
                    (nameLines - 1).coerceIn(0, 3),
                ) { prefs.setNameLines(it + 1) }
                Text(
                    if (nameLines <= 1) {
                        "Long names are cut short with an ellipsis."
                    } else {
                        "Wraps onto $nameLines lines rather than being cut. Fewer rows fit."
                    },
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = Filet.colors.fg3,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                )
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
        // The preview runs through `EntryFormat`, the same function the list uses, so what it
        // shows is what the rows will show rather than a second guess at it.
        item { SettingsSection("Rows", "What each file and folder shows under its name.") }
        item {
            val sample = remember { System.currentTimeMillis() - 12L * 86_400_000L }
            Text(
                "report.pdf   " + dev.niccc2007.filet.browser.EntryFormat.subtitle(
                    isDir = false, size = 284_000L, at = sample, d = entry,
                ),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.fg2,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        item {
            ToggleRow("Details under the name", "Size and date on a second line", entry.subtitle) {
                prefs.setEntry(entry.copy(subtitle = it))
            }
        }
        item {
            ToggleRow(
                "Count what is in folders",
                "A folder shows how many things it holds. Each one is read once to count it.",
                entry.measure,
            ) { prefs.setEntry(entry.copy(measure = it)) }
        }
        item {
            ToggleRow(
                "Date column",
                "A separate date on the right, on screens wide enough for it. It repeats the " +
                    "line under the name, which is why it starts off.",
                entry.dateColumn,
            ) { prefs.setEntry(entry.copy(dateColumn = it)) }
        }
        item {
            ToggleRow(
                "Recent dates in words",
                "Today, yesterday, and up to six days ago, instead of the date.",
                entry.relative,
            ) { prefs.setEntry(entry.copy(relative = it)) }
        }
        item {
            SegmentRow(
                "Date",
                dev.niccc2007.filet.browser.DateStyle.entries.map { it.label },
                entry.style.ordinal,
            ) { prefs.setEntry(entry.copy(style = dev.niccc2007.filet.browser.DateStyle.entries[it])) }
        }
        item {
            SegmentRow(
                "Order",
                dev.niccc2007.filet.browser.DateOrder.entries.map { it.label },
                entry.order.ordinal,
            ) { prefs.setEntry(entry.copy(order = dev.niccc2007.filet.browser.DateOrder.entries[it])) }
        }
        // Only where it does anything: a named month is joined with spaces whatever this says.
        if (entry.style == dev.niccc2007.filet.browser.DateStyle.NUMERIC) {
            item {
                val seps = listOf("-", "/", ".")
                SegmentRow("Separator", seps, seps.indexOf(entry.separator).coerceAtLeast(0)) {
                    prefs.setEntry(entry.copy(separator = seps[it]))
                }
            }
        }
        }
        if (group == SettingsGroup.BROWSING) {
        item { SettingsSection("Where you start", "The folder Filet opens on, and the tabs that come back with it.", first = true) }
        // First under Browsing, because the folder Filet opens on is where browsing starts.
        // It sat under Appearance on the reasoning that Home is what the app looks like when it
        // opens; that is true of the overview and false of a folder set as Home, which is the
        // case this row exists for.
        item {
            val home by prefs.homeFolder.collectAsState()
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Home screen", fontSize = 12.sp, color = colors.fg2)
                        Text(
                            home ?: "The overview — storage, new files and recents",
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
        item {
            ToggleRow(
                "Reopen my tabs",
                "Start with the tabs you had open last time. Off starts with Home alone — your saved tabs are kept, so turning this back on brings them back.",
                restoreTabs,
            ) {
                prefs.setRestoreTabs(it)
            }
        }
        item { SettingsSection("Folders", "How a folder lists what is in it.") }
        item {
            ToggleRow(
                "Tree view",
                if (treeView) {
                    "A folder opens underneath itself. Its name still goes into it."
                } else {
                    "A folder replaces the list, the usual way."
                },
                treeView,
                beta = true,
            ) { prefs.setTreeView(it) }
        }
        item {
            ToggleRow("Folders first", "Keep directories above files whichever way you sort", sort.foldersFirst) {
                prefs.setSort(sort.copy(foldersFirst = it))
            }
        }
        // Directly under Browsing, not at the bottom. Buried at the end of the page,
        // "where is that?" was the result.
        defaultOpenersSection(vm) { pickingOpenerFor = it }
        }
        if (group == SettingsGroup.CONTROLS) {
        item { SettingsSection("Gestures and reach", "What a swipe does, and how far up the screen a thumb has to go.", first = true) }
        item {
            ToggleRow(
                "One-handed",
                if (reachMode) {
                    "Folders start halfway down the screen, and the tabs, the path and the " +
                        "toolbar sit at the bottom."
                } else {
                    "Folders start at the top, with the toolbar above them."
                },
                reachMode,
            ) { prefs.setReachMode(it) }
        }
        item {
            ToggleRow(
                "Swipe to change tab",
                if (paneSwipe) {
                    "A sideways swipe moves to the next tab. In dual pane it only moves the " +
                        "pane you swiped in."
                } else {
                    "Tap a tab at the top to move to it."
                },
                paneSwipe,
                beta = true,
            ) { prefs.setPaneSwipe(it) }
        }
        if (paneSwipe) {
            item {
                val at = dev.niccc2007.filet.browser.PaneSwipe.Sensitivity.of(paneSwipeAt)
                Column {
                    SegmentRow(
                        "How hard",
                        dev.niccc2007.filet.browser.PaneSwipe.Sensitivity.entries.map { it.label },
                        dev.niccc2007.filet.browser.PaneSwipe.Sensitivity.entries.indexOf(at),
                    ) {
                        prefs.setPaneSwipeAt(
                            dev.niccc2007.filet.browser.PaneSwipe.Sensitivity.entries[it].id,
                        )
                    }
                    Text(
                        "Firmer if you keep catching it while scrolling.",
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = Filet.colors.fg3,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                    )
                }
            }
        }
        item {
            val bar = dev.niccc2007.filet.browser.BottomBarConfig.normalise(storedBar)
            SettingsSection(
                "Bottom bar",
                if (dev.niccc2007.filet.browser.BottomBarConfig.scrolls(bar.size))
                    "${bar.size} on the bar. Past ${dev.niccc2007.filet.browser.BottomBarConfig.SCROLLS_PAST} it scrolls sideways. Only shown on a phone-width screen."
                else
                    "${bar.size} on the bar. Add as many as you like — past ${dev.niccc2007.filet.browser.BottomBarConfig.SCROLLS_PAST} it scrolls sideways. Only shown on a phone-width screen.",
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
                        "NOT ON THE BAR",
                        fontSize = 9.sp,
                        letterSpacing = 1.3.sp,
                        color = Filet.colors.fg3,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                    )
                    for (item in off) {
                        BarRow(
                            label = item.label,
                            detail = "Tap to add it to the end",
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
                "When you select files",
                "${bar.size} of ${dev.niccc2007.filet.browser.SelectionBarConfig.MAX_ON_BAR} " +
                    "on the bar. Everything else is behind More.",
            )
        }
        items(dev.niccc2007.filet.browser.SelectionBarConfig.CHOOSABLE.size) { i ->
            val (id, label) = dev.niccc2007.filet.browser.SelectionBarConfig.CHOOSABLE[i]
            val bar = dev.niccc2007.filet.browser.SelectionBarConfig.normalise(selectionBar)
            val on = id in bar
            val full = dev.niccc2007.filet.browser.SelectionBarConfig.full(bar)
            ToggleRow(
                label,
                when {
                    on -> "On the bar, position ${bar.indexOf(id) + 1}"
                    full -> "Behind More - the bar is full"
                    else -> "Behind More"
                },
                on,
            ) {
                prefs.setSelectionBar(
                    dev.niccc2007.filet.browser.SelectionBarConfig.encode(
                        dev.niccc2007.filet.browser.SelectionBarConfig.toggled(bar, id),
                    ),
                )
            }
        }
        }
        if (group == SettingsGroup.PLAYBACK) {
        item {
            SettingsSection(
                "Filet's own viewers",
                "How the built-in music player, video player and viewers look and behave.",
            )
        }
        item {
            Column {
                SegmentRow(
                    "Player detail",
                    listOf("Automatic", "Full", "Light"),
                    dev.niccc2007.filet.data.PLAYER_TIERS.indexOf(deviceTier)
                        .coerceAtLeast(0),
                ) { prefs.setDeviceTier(dev.niccc2007.filet.data.PLAYER_TIERS[it]) }
                Text(
                    "Light updates the beat and glow less often. Nothing is removed.",
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = Filet.colors.fg3,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                )
            }
        }
        item {
            Column {
                SegmentRow(
                    "Music player",
                    dev.niccc2007.filet.playback.PlayerFace.entries.map { it.label },
                    dev.niccc2007.filet.playback.PlayerFace.entries.indexOf(face),
                ) { prefs.setPlayerFace(dev.niccc2007.filet.playback.PlayerFace.entries[it].id) }
                Text(
                    face.about,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = Filet.colors.fg3,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                )
            }
        }
        item {
            ToggleRow(
                "Living background",
                if (livingGlow) {
                    "The glow behind whatever is playing drifts in the track's own colours - " +
                        "on every look, on the strip at the bottom, and on the notification " +
                        "card, which changes colour between posts since a notification " +
                        "cannot animate. It holds still when playback is paused."
                } else {
                    "Off. No glow behind the player, on the strip, or on the notification " +
                        "card, and Aurora's background holds still."
                },
                livingGlow,
            ) { prefs.setLivingGlow(it) }
        }
        if (livingGlow) {
            item {
                // Under the glow, because it IS the glow answering the music, and indented to
                // say so. With the glow off there is nothing for a beat to move.
                ToggleRow(
                    "Pulse on the beat",
                    if (!face.pulses) {
                        "Nothing to move on the Classic look, which is the flat one by " +
                            "design. Switch to Ambient, Editorial, Immersive or Aurora."
                    } else if (beatPulse) {
                        "The glow swells on every drum and dips between them. The track is " +
                            "read once when the player opens - a second of work, then " +
                            "nothing while it plays."
                    } else {
                        "The glow drifts on its own time."
                    },
                    beatPulse && face.pulses,
                    beta = true,
                    sub = true,
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
                item {
                    SliderRow(
                        "How much it reacts",
                        "How far a beat moves the glow from where it rests - and on Aurora, how " +
                            "far it pushes the wave. Low is a gentle breath; high is a hard hit " +
                            "on every drum.",
                        pulseDepth,
                        beta = true,
                        sub = true,
                    ) { prefs.setPulseDepth(it) }
                }
                if (face.hasWave) {
                    item {
                        SliderRow(
                            "Wave distance",
                            "How far off the artwork the wave sits.",
                            waveGap,
                            beta = true,
                            sub = true,
                        ) { prefs.setWaveGap(it) }
                    }
                }
            }
            item {
                SliderRow(
                    "Glow strength",
                    "How bright the glow is at all. A deep cover throws more colour than a " +
                        "pale one, so this is a per-eye setting rather than a per-track one. " +
                        "The minimised player cycles three stops on this same scale.",
                    glowStrength,
                    beta = true,
                ) { prefs.setGlowStrength(it) }
            }
            item {
                ToggleRow(
                    "Glow spills past the strip",
                    if (glowSpill) {
                        "The minimised player's light reaches up into the list above it."
                    } else {
                        "The light stops at the edge of the minimised player."
                    },
                    glowSpill,
                ) { prefs.setGlowSpill(it) }
            }
        }
        item { SettingsSection("Aurora's disc", "What turning the record does.") }
        item {
            ToggleRow(
                "Sound follows the disc",
                if (scratchAudio) {
                    "Turning Aurora's disc plays the track at the speed of your hand, " +
                        "backwards too, instead of moving through it in silence."
                } else {
                    "Turning the disc moves through the track in silence."
                },
                scratchAudio,
                beta = true,
            ) { prefs.setScratchAudio(it) }
        }
        item {
            // Not under the switch above. The disc moves through the track whether or not
            // anything is audible, so how far a turn goes is its own question.
            SliderRow(
                "Disc travel",
                "How much of the track one full turn of Aurora's disc covers - " +
                    dev.niccc2007.filet.playback.Scratch.travelLabel(discTurn) +
                    ". Short is a record you can hear; long is a way across a whole song.",
                dev.niccc2007.filet.playback.Scratch.turnPosition(discTurn),
                beta = true,
            ) { prefs.setDiscTurnMs(dev.niccc2007.filet.playback.Scratch.turnMsFor(it)) }
        }
        item { SettingsSection("Playing", "How a track or a film starts, and what may interrupt it.") }
        item {
            ToggleRow(
                "Carry on where I left off",
                if (resumePlayback) {
                    "A track or a film opens where it stopped, and the Carry on tab lists " +
                        "what is part way through. Positions are written as it plays, so " +
                        "they survive the app being closed from the task switcher."
                } else {
                    "Everything opens at the start. Nothing is remembered, and the Carry on " +
                        "tab stays empty."
                },
                resumePlayback,
            ) { prefs.setResumePlayback(it) }
        }
        item {
            ToggleRow(
                "Uninterrupted playback",
                if (uninterrupted) {
                    "Filet takes no audio focus and gives none up: it plays over other apps " +
                        "and they cannot pause it. A phone call still wins - that one is not " +
                        "Filet's to refuse."
                } else {
                    "Normal behaviour: Filet pauses for a call and ducks for a prompt, and " +
                        "starting a track stops whatever else was playing."
                },
                uninterrupted,
            ) { prefs.setUninterrupted(it) }
        }
        item {
            val mode = dev.niccc2007.filet.playback.PitchMode.of(pitchMode)
            Column {
                SegmentRow(
                    "Pitch when slowed",
                    dev.niccc2007.filet.playback.PitchMode.entries.map { it.label },
                    dev.niccc2007.filet.playback.PitchMode.entries.indexOf(mode),
                ) {
                    prefs.setPitchMode(dev.niccc2007.filet.playback.PitchMode.entries[it].id)
                }
                Text(
                    mode.about,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = Filet.colors.fg3,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                )
            }
        }
        }
        if (group == SettingsGroup.SEARCH) {
        item { SettingsSection("Search index", "The database that makes whole-device search answer instantly.") }
        item {
            ToggleRow(
                "Keep an index",
                "Makes whole-device search instant. Search still works without it, just slower.",
                indexOn,
            ) {
                prefs.setIndexEnabled(it)
                vm.onIndexToggled(it)
            }
        }
        item { IndexStatusCard(vm) }

        }
        if (group == SettingsGroup.STORAGE) {
        item { SettingsSection("Storage access", "What Android lets Filet see.", first = true) }
        item { StorageAccessCard(vm) }
        item {
            SettingsSection(
                "Permissions",
                "What Filet holds, and what it costs to go without the rest.",
            )
        }
        item {
            // Re-read whenever the app comes back to the front, which is the only moment a
            // permission can have changed - see PermissionWatch for why that beats a timer.
            dev.niccc2007.filet.perms.PermissionsCard { intents -> vm.openFirstThatResolves(intents) }
        }
        // Only on a phone that has actually granted root. Offering it anywhere else would be
        // a switch over three volumes that cannot exist - and those volumes appearing without
        // root is the bug this setting arrived with. See `RootProvider.roots`.
        if (dev.niccc2007.filet.vfs.provider.RootProvider.isGranted()) {
            item {
                ToggleRow(
                    "Root volumes on Home",
                    if (rootVolumes) {
                        "The phone's root, /data and /system are listed as drives. They are " +
                            "only ever offered while root is granted."
                    } else {
                        "Hidden. Root still works everywhere else - this is only about the " +
                            "three drive cards."
                    },
                    rootVolumes,
                ) { prefs.setRootVolumes(it) }
            }
        }
        // Only when Termux is actually on the phone. An integration section advertising an
        // app somebody does not have is the definition of a dead switch.
        if (vm.termuxInstalled) {
            item {
                SettingsSection(
                    "Termux",
                    "Its files live in another app's private storage, so Android has to grant " +
                        "access once. After that it is an ordinary volume.",
                )
            }
            item {
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
        if (group == SettingsGroup.MAINTENANCE) {
        // Only on the flavour that has an updater at all. R1, no dead switches: on F-Droid
        // this would be a control over something that is compiled out.
        if (dev.niccc2007.filet.BuildConfig.UPDATER_ENABLED) {
            item { SettingsSection("Updates", "Filet is sideloaded, so it looks for its own new versions.", first = true) }
            item {
                ToggleRow(
                    "Tell me about new versions",
                    "Checks GitHub twice a day and posts a notification when there is one. " +
                        "Off, nothing is checked and nothing is posted - About, Check for " +
                        "updates still works whenever you ask it to.",
                    updatesOn,
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
                "A file per run, so a fault that happens once can still be looked at afterwards.",
            )
        }
        item {
            ToggleRow(
                "Detailed logging",
                "Records what Filet is doing, step by step, into " +
                    dev.niccc2007.filet.log.FiletLog.FOLDER +
                    ". Off still records warnings and failures. " +
                    "The newest " + dev.niccc2007.filet.log.FiletLog.KEEP +
                    " runs are kept and older ones are deleted.",
                verboseLogs,
            ) { prefs.setVerboseLogs(it) }
        }
        item { LogFileRow(vm) }
        item {
            SettingsSection(
                "Moving to another phone",
                "Write your setup to a file, and read one back in.",
            )
        }
        item {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 4.dp)) {
                Text(
                    "Plain text. Nothing that identifies this phone travels.",
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = Filet.colors.fg3,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    SmallButton("Export settings") { vm.openSettingsExport() }
                    Spacer(Modifier.width(8.dp))
                    SmallButton("Import settings") { vm.openSettingsImport() }
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
            if (file == null) "No log file for this run" else "This run is writing " + file.name,
            fontSize = 10.5.sp,
            color = colors.fg3,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(7.dp))
        SmallButton("Open the logs folder") { vm.openLogsFolder() }
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

@Composable
fun SliderRow(
    title: String,
    subtitle: String,
    value: Float,
    beta: Boolean = false,
    /** True for a row that only applies while the row above it is on. See [SUB_INDENT]. */
    sub: Boolean = false,
    onChange: (Float) -> Unit,
) {
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
            if (beta) BetaTag()
        }
        Text(subtitle, fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp)
        Slider(
            value = value,
            onValueChange = onChange,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.sunken,
            ),
        )
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    beta: Boolean = false,
    /** True for a row that only applies while the row above it is on. See [SUB_INDENT]. */
    sub: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
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
                if (beta) BetaTag()
            }
            Text(subtitle, fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
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
fun SegmentRow(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val colors = Filet.colors
    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(label, fontSize = 12.sp, color = colors.fg2)
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
            "How indexing behaves",
            listOf(
                "Updating adds what is new and drops what has gone. It never starts from nothing.",
                "One runs every time Filet opens, and keeps going through the lock screen.",
                "Stop is in the notification shade. It also stops itself under 15% battery.",
                "Clear index throws it away, so the next run rebuilds from scratch.",
            ),
        )
    }
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
                "Filet can see the whole shared volume. Granted folders still work alongside it."
            } else {
                "Filet can only see folders you grant. All files access covers the lot."
            },
            fontSize = 11.sp, color = colors.fg2, lineHeight = 15.sp,
        )
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hasAll) SmallButton("Grant all files") { vm.requestAllFiles() }
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
private fun BarRow(
    label: String,
    detail: String,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onAdd != null) Modifier.clickable(onClick = onAdd) else Modifier)
            .padding(start = 16.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, fontSize = 10.sp, color = colors.fg3)
        }
        if (onAdd != null) {
            Icon(
                FiletIcons.Plus, "Add $label to the bar",
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
                FiletIcons.Close, "Take $label off the bar",
                tint = colors.fg3,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = it).padding(9.dp),
            )
        }
    }
}

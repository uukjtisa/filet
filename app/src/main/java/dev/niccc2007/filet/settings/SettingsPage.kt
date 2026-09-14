package dev.niccc2007.filet.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import dev.niccc2007.filet.data.SortKey
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
 * Nic: *"make the settigns be properly segregated.. its so packked.. thers no dsitinciton
 * betewen ui settings general and etc."* He is right. The old heading was 9.5sp grey micro-caps
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

@Composable
fun SettingsPage(vm: BrowserViewModel) {
    val prefs = vm.prefs
    val theme by prefs.theme.collectAsState()
    val accent by prefs.accent.collectAsState()
    val step by prefs.viewStep.collectAsState()
    val hidden by prefs.showHidden.collectAsState()
    val sort by prefs.sort.collectAsState()
    val indexOn by prefs.indexEnabled.collectAsState()
    val updatesOn by vm.updateNotificationsOn.collectAsState()
    val selectionStyle by prefs.selectionStyle.collectAsState()
    val hideTermux by prefs.hideTermux.collectAsState()
    val tabSize by prefs.tabSize.collectAsState()
    var pickingOpenerFor by remember { mutableStateOf<String?>(null) }
    val tracked by vm.tracked.paths.collectAsState()
    val colors = Filet.colors

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
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
                "Scales the whole chip — label, icon, close button and the padding around " +
                    "them — because a bigger font inside the same box is no easier to hit.",
                fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 4.dp),
            )
        }

        item { SettingsSection("Files and folders", "Sorting, hidden files, and what happens when you pick things.") }
        item {
            ToggleRow("Show hidden files", "Dotfiles and anything the volume marks hidden", hidden) {
                prefs.setShowHidden(it)
            }
        }
        item {
            ToggleRow("Folders first", "Keep directories above files whichever way you sort", sort.foldersFirst) {
                prefs.setSort(sort.copy(foldersFirst = it))
            }
        }
        item {
            SegmentRow("Sort by", listOf("Name", "Size", "Date", "Type"), sortIndex(sort.key)) {
                prefs.setSort(sort.copy(key = sortKey(it)))
            }
        }
        item {
            ToggleRow("Descending", "Reverse the sort order", sort.descending) {
                prefs.setSort(sort.copy(descending = it))
            }
        }

        item {
            SegmentRow(
                "When you select files",
                listOf("Menu", "Bar"),
                if (selectionStyle == dev.niccc2007.filet.browser.SelectionStyle.MENU) 0 else 1,
            ) {
                vm.prefs.setSelectionStyle(
                    if (it == 0) dev.niccc2007.filet.browser.SelectionStyle.MENU
                    else dev.niccc2007.filet.browser.SelectionStyle.BAR
                )
            }
        }
        item {
            Text(
                "Menu puts the eleven actions in a popup that fits the screen. Bar is the old " +
                    "row along the bottom - one tap instead of two, but it has to scroll.",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = colors.fg3,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }

        // Directly under Browsing, not at the bottom. It was below the Tracked-folders list,
        // which can run to a dozen rows, and "where is that?" was the result.
        defaultOpenersSection(vm) { pickingOpenerFor = it }

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

        // Only on the flavour that has an updater at all. R1, no dead switches: on F-Droid
        // this would be a control over something that is compiled out.
        if (dev.niccc2007.filet.BuildConfig.UPDATER_ENABLED) {
            item { SettingsSection("Updates", "Filet is sideloaded, so it looks for its own new versions.") }
            item {
                ToggleRow(
                    "Tell me about new versions",
                    "Checks GitHub twice a day and posts a notification when there is one. " +
                        "Off, nothing is checked and nothing is posted - About, Check for " +
                        "updates still works whenever you ask it to.",
                    updatesOn,
                ) { vm.setUpdateNotifications(it) }
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
            item {
                ToggleRow(
                    "Show it on Home",
                    "The card disappears from Home once its folder is granted anyway. These " +
                        "buttons stay here either way.",
                    !hideTermux,
                ) { prefs.setHideTermux(!it) }
            }
        }

        item { SettingsSection("Tracked folders", "Watched closely, so Home shows what just arrived.") }
        item {
            Text(
                "Watched closely, so Home shows what just arrived. Keep this list short: each " +
                    "folder costs an inotify watch, and the OS caps them.",
                fontSize = 11.sp,
                color = colors.fg3,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }
        items(tracked.size) { i ->
            val p = tracked[i]
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(FiletIcons.Folder, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    p.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.fg2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Icon(
                    FiletIcons.Close, "Stop tracking", tint = colors.fg3,
                    modifier = Modifier.size(20.dp).clickable { vm.tracked.remove(p) }.padding(3.dp),
                )
            }
        }
        item {
            Text(
                "Add the folder you are standing in with the ⋮ menu.",
                fontSize = 10.5.sp, color = colors.fg3,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        item { SettingsSection("Storage access", "What Android lets Filet see.") }
        item { StorageAccessCard(vm) }
    }

    pickingOpenerFor?.let { ext ->
        OpenerPicker(vm, ext) { pickingOpenerFor = null }
    }
}

private fun sortIndex(k: SortKey) = when (k) {
    SortKey.NAME -> 0; SortKey.SIZE -> 1; SortKey.MODIFIED -> 2; SortKey.TYPE -> 3
}

private fun sortKey(i: Int) = when (i) {
    1 -> SortKey.SIZE; 2 -> SortKey.MODIFIED; 3 -> SortKey.TYPE; else -> SortKey.NAME
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SegmentRow(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val colors = Filet.colors
    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(label, fontSize = 12.sp, color = colors.fg2)
        Spacer(Modifier.height(5.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.lineSoft, RoundedCornerShape(8.dp)),
        ) {
            options.forEachIndexed { i, opt ->
                val on = i == selected
                Text(
                    opt,
                    fontSize = 11.5.sp,
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
            if (run.active) SmallButton("Stop indexing") { vm.stopIndexing() }
            else SmallButton("Index now") { vm.reindexNow() }
            SmallButton("Clear index") { vm.clearIndex() }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Index now walks everything and keeps going until it is done — through the lock " +
                "screen, with a Stop in the notification shade. The only thing that halts it by " +
                "itself is a battery under 15% that is not charging.",
            fontSize = 10.sp, color = colors.fg3, lineHeight = 13.5.sp,
        )
    }
}

@Composable
private fun StorageAccessCard(vm: BrowserViewModel) {
    val colors = Filet.colors
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
            "All-files access lets Filet see the whole shared volume. Without it, only folders " +
                "you pick by hand are visible — which still works, just narrower.",
            fontSize = 11.sp, color = colors.fg2, lineHeight = 15.sp,
        )
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Grant all files") { vm.requestAllFiles() }
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

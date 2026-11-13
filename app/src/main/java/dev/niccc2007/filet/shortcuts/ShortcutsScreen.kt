package dev.niccc2007.filet.shortcuts

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.TabGroup
import dev.niccc2007.filet.browser.TabTarget
import dev.niccc2007.filet.browser.tabGlyph
import dev.niccc2007.filet.script.Script
import dev.niccc2007.filet.ui.tabs.TabHeader
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import kotlinx.coroutines.launch

/**
 * What Filet has put on your home screen, and everything it could.
 *
 * A pinned shortcut is otherwise write-only: you make one and it disappears into the
 * launcher. Keeping the list here is what makes them renameable, cleanable and countable.
 *
 * Below the list are the catalogues: every tab, the actions and the scripts. An action has no
 * row in a folder to long-press, so its catalogue is the only way one gets made. Each is a
 * folding section with its count; the two longer-tail ones start folded.
 */
@Composable
fun ShortcutsScreen(vm: BrowserViewModel) {
    val allRecords by vm.shortcuts.all.collectAsState()
    val allScripts by vm.scripts.scripts.collectAsState()
    val folded by vm.shortcuts.folded.collectAsState()
    // Narrowed by the pane's search box: what is pinned, and what could be.
    val query = dev.niccc2007.filet.ui.LocalPaneQuery.current
    fun match(vararg f: String?) = dev.niccc2007.filet.ui.TextMatch.matches(query, *f)
    val searching = query.isNotBlank()
    // Opening the tab is a moment to look: an icon may have been taken off the home screen.
    LaunchedEffect(Unit) { vm.shortcuts.reconcileSoon() }
    var renaming by remember { mutableStateOf<ShortcutRecord?>(null) }

    val placedIds = remember(allRecords) { allRecords.mapTo(HashSet()) { it.id } }
    val lines = remember(allRecords, allScripts, query, folded) {
        shortcutLines(
            records = allRecords.filter { match(it.label, it.kindLabel()) }.sortedByDescending { it.createdAt },
            tabs = TabTarget.entries.filter { match(it.label, it.home, it.group.label) },
            actions = AppAction.LISTED.filter { match(it.label, it.description) },
            scripts = allScripts.filter { match(it.name) },
            placedIds = placedIds,
            folded = if (searching) emptySet() else folded,
            searching = searching,
            nothingPlaced = allRecords.isEmpty(),
        )
    }

    val list = dev.niccc2007.filet.browser.rememberKeptListState("shortcuts")
    val scope = rememberCoroutineScope()
    // A link into a folded section opens it and brings its heading to the top.
    fun goTo(section: String) {
        vm.shortcuts.unfold(section)
        scope.launch {
            val at = lines.indexOfFirst { it is Line.Head && it.id == section }
            if (at >= 0) list.animateScrollToItem(at + HEADER_ITEMS)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = list) {
        item { TabHeader("Home screen shortcuts") }
        items(lines, key = { it.key }) { line ->
            when (line) {
                is Line.NoMatch -> dev.niccc2007.filet.ui.NoMatch(query)
                is Line.Empty -> ShortcutsEmpty(
                    onTabs = { goTo(ShortcutStore.FOLD_TABS) },
                    onActions = { goTo(ShortcutStore.FOLD_ACTIONS) },
                )
                is Line.Head -> FoldHead(line.label, line.count, line.folded, foldable = !searching) {
                    vm.shortcuts.toggleFold(line.id)
                }
                is Line.Group -> GroupLabel(line.group.label)
                is Line.Placed -> ShortcutRow(
                    record = line.record,
                    // Only a launcher that never reports leaves a row unconfirmed. See PinReconcile.
                    live = !line.record.unconfirmed,
                    onOpen = { vm.openShortcutRecord(line.record) },
                    onRename = { renaming = line.record },
                    onForget = { vm.shortcuts.forget(line.record.id) },
                )
                is Line.Tab -> CatalogueRow(
                    icon = tabGlyph(line.target),
                    label = line.target.label,
                    placed = line.placed,
                ) { vm.askToPin(line.target) }
                is Line.Action -> CatalogueRow(
                    icon = actionGlyph(line.action),
                    label = line.action.label,
                    desc = line.action.description,
                    placed = line.placed,
                ) { vm.askToPin(line.action) }
                is Line.ScriptPick -> {
                    // Pinning does not approve. A script that has never been approved would tap
                    // through to a consent dialog from the home screen, so the row says so first.
                    val approved = vm.scripts.isApproved(line.script)
                    CatalogueRow(
                        icon = FiletIcons.Script,
                        label = line.script.name,
                        desc = if (approved) "Runs on tap" else "Approve it once before it can run from the home screen",
                        placed = line.placed,
                        warn = !approved,
                    ) { vm.askToPin(line.script) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    renaming?.let { record ->
        var label by remember(record.id) { mutableStateOf(record.label) }
        // dismissOnScrim is off: there is typing in here, and a stray tap outside the card
        // throwing away a half-entered name is the kind of loss nobody reports.
        Dlg(onDismiss = { renaming = null }, dismissOnScrim = false) {
            DlgHeader(FiletIcons.Rename, "Rename shortcut", onClose = { renaming = null })
            DlgBody {
                DlgField("Name", label, { label = it.take(40) })
            }
            DlgFooter {
                DlgBtn("Cancel") { renaming = null }
                DlgBtn("Rename", kind = BtnKind.PRIMARY, enabled = label.isNotBlank()) {
                    vm.shortcuts.rename(record.id, label.trim()); renaming = null
                }
            }
        }
    }
}

/** Items the list draws before the first [Line]: the tab's heading. */
private const val HEADER_ITEMS = 1

/** One row of the Shortcuts tab, in order. Built as plain data so a link can find its section. */
internal sealed interface Line {
    val key: String

    data object NoMatch : Line { override val key = "nomatch" }
    data object Empty : Line { override val key = "empty" }
    data class Head(val id: String, val label: String, val count: Int, val folded: Boolean) : Line {
        override val key get() = "head-$id"
    }
    data class Group(val group: TabGroup) : Line { override val key get() = "group-${group.name}" }
    data class Placed(val record: ShortcutRecord) : Line { override val key get() = "placed-${record.id}" }
    data class Tab(val target: TabTarget, val placed: Boolean) : Line { override val key get() = "tab-${target.id}" }
    data class Action(val action: AppAction, val placed: Boolean) : Line { override val key get() = "action-${action.name}" }
    data class ScriptPick(val script: Script, val placed: Boolean) : Line { override val key get() = "script-${script.id}" }
}

/**
 * The Shortcuts tab as a list of rows.
 *
 * - Nothing on the home screen and no search: the empty card stands where the list would be.
 * - A section with nothing in it (no scripts yet, or nothing matching a search) has no heading.
 * - A folded section keeps its heading and count and drops its rows.
 * - A catalogue row knows whether that thing is already on the home screen, by the id its
 *   shortcut would have.
 */
internal fun shortcutLines(
    records: List<ShortcutRecord>,
    tabs: List<TabTarget>,
    actions: List<AppAction>,
    scripts: List<Script>,
    placedIds: Set<String>,
    folded: Set<String>,
    searching: Boolean,
    nothingPlaced: Boolean,
): List<Line> = buildList {
    if (searching && records.isEmpty() && tabs.isEmpty() && actions.isEmpty() && scripts.isEmpty()) {
        add(Line.NoMatch)
        return@buildList
    }
    if (nothingPlaced && !searching) {
        add(Line.Empty)
    } else if (records.isNotEmpty()) {
        val shut = ShortcutStore.FOLD_PLACED in folded
        add(Line.Head(ShortcutStore.FOLD_PLACED, "On your home screen", records.size, shut))
        if (!shut) records.forEach { add(Line.Placed(it)) }
    }
    if (tabs.isNotEmpty()) {
        val shut = ShortcutStore.FOLD_TABS in folded
        add(Line.Head(ShortcutStore.FOLD_TABS, "Tabs", tabs.size, shut))
        if (!shut) {
            for (group in TabGroup.entries) {
                val inGroup = tabs.filter { it.group == group }
                if (inGroup.isEmpty()) continue
                add(Line.Group(group))
                inGroup.forEach { add(Line.Tab(it, TAB_PREFIX + it.id in placedIds)) }
            }
        }
    }
    if (actions.isNotEmpty()) {
        val shut = ShortcutStore.FOLD_ACTIONS in folded
        add(Line.Head(ShortcutStore.FOLD_ACTIONS, "Actions", actions.size, shut))
        if (!shut) actions.forEach { add(Line.Action(it, "action:${it.name}" in placedIds)) }
    }
    if (scripts.isNotEmpty()) {
        val shut = ShortcutStore.FOLD_SCRIPTS in folded
        add(Line.Head(ShortcutStore.FOLD_SCRIPTS, "Scripts", scripts.size, shut))
        if (!shut) scripts.forEach { add(Line.ScriptPick(it, "script:${it.id}" in placedIds)) }
    }
}

package dev.niccc2007.filet.shortcuts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.EmptyNote
import dev.niccc2007.filet.browser.SectionLabel
import dev.niccc2007.filet.ui.theme.Filet

/**
 * What Filet has put on your home screen.
 *
 * A pinned shortcut is otherwise write-only: you make one and it disappears into the
 * launcher, which will not reliably tell you afterwards whether it is still there. Keeping
 * the list here is what makes them renameable, cleanable and - most usefully - *countable*.
 *
 * This is also where the non-file shortcuts are made, because there is nowhere else they
 * could be: an action has no row in a folder to long-press.
 */
@Composable
fun ShortcutsScreen(vm: BrowserViewModel) {
    val colors = Filet.colors
    val records by vm.shortcuts.all.collectAsState()
    val scripts by vm.scripts.scripts.collectAsState()
    val live = remember(records) { vm.shortcuts.livePinnedIds() }
    var renaming by remember { mutableStateOf<ShortcutRecord?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionLabel("Home screen shortcuts") }
        item {
            Text(
                "A shortcut to a file stores its Filet ID, not its path — move the file and " +
                    "the shortcut follows. Delete it and the shortcut disables itself with a " +
                    "message rather than doing nothing when you tap it.",
                fontSize = 11.sp, lineHeight = 15.sp, color = colors.fg3,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }

        if (records.isEmpty()) {
            item {
                EmptyNote(
                    "Nothing pinned yet. Select a file and choose Shortcut.",
                    Modifier.fillMaxWidth().height(110.dp),
                )
            }
        }

        items(records.sortedByDescending { it.createdAt }, key = { it.id }) { record ->
            ShortcutRow(
                record = record,
                // Advisory: several OEM launchers report nothing here while the icons are
                // plainly on the home screen, so this greys a row rather than removing it.
                live = live.isEmpty() || record.id in live,
                onOpen = { vm.openShortcutRecord(record) },
                onRename = { renaming = record },
                onForget = { vm.shortcuts.forget(record.id) },
            )
        }

        item { SectionLabel("Add an action") }
        item {
            Text(
                "Things worth one tap that are not files.",
                fontSize = 11.sp, color = colors.fg3,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }
        items(AppAction.entries.toList(), key = { "action-" + it.name }) { action ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
                    .background(colors.raised)
                    .clickable { vm.shortcutForAction(action) }
                    .padding(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(action.label, fontSize = 13.sp)
                    Text(action.description, fontSize = 10.sp, color = colors.fg3)
                }
                Text("Add", fontSize = 11.sp, color = colors.accent)
            }
        }

        if (scripts.isNotEmpty()) {
            item { SectionLabel("Add a script") }
            items(scripts, key = { "script-" + it.id }) { script ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
                        .background(colors.raised)
                        .clickable { vm.shortcutForScript(script) }
                        .padding(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(script.name, fontSize = 13.sp)
                        Text(
                            if (vm.scripts.isApproved(script)) "Runs on tap"
                            else "Approve it once before it can run from the home screen",
                            fontSize = 10.sp,
                            color = if (vm.scripts.isApproved(script)) colors.fg3 else colors.warn,
                        )
                    }
                    Text("Add", fontSize = 11.sp, color = colors.accent)
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    renaming?.let { record ->
        var label by remember(record.id) { mutableStateOf(record.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename shortcut", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.shortcuts.rename(record.id, label.trim()); renaming = null },
                    enabled = label.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

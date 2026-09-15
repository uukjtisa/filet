package dev.niccc2007.filet.script

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import dev.niccc2007.filet.settings.SmallButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.EmptyNote
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.SectionLabel
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The script manager.
 *
 * Every row shows what the script is allowed to touch before it is run, not after. That is
 * the whole user-facing half of the security model - the other half is [GuardedVfs].
 */
@Composable
fun ScriptsScreen(vm: BrowserViewModel) {
    val colors = Filet.colors
    val scripts by vm.scripts.scripts.collectAsState()
    val running by vm.scriptRunning.collectAsState()
    val result by vm.scriptResult.collectAsState()
    var pendingApproval by remember { mutableStateOf<Script?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionLabel("Scripts") }
        item {
            Text(
                "Lua, bound to Filet's storage layer — so a script that works on internal " +
                    "storage also works inside an archive or on a network share. Each one " +
                    "declares what it may touch, and you approve that before it runs.",
                fontSize = 11.sp, lineHeight = 15.sp, color = colors.fg3,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallButton("New script") { vm.newScript() }
                Spacer(Modifier.width(7.dp))
                // The API is not on the internet, so a model cannot know it. This hands over
                // the whole brief in one copy, and the user appends what they want.
                SmallButton("Copy AI prompt") {
                    vm.copyText(ScriptPrompt.TEXT, "Prompt copied — paste it and add what you want")
                }
                Spacer(Modifier.width(7.dp))
                SmallButton("Examples") { vm.restoreScriptExamples() }
            }
        }

        if (scripts.isEmpty()) {
            item { EmptyNote("No scripts yet. Start one with New script.", Modifier.fillMaxWidth().height(120.dp)) }
        }

        items(scripts, key = { it.id }) { script ->
            ScriptRow(
                script = script,
                approved = script.approved,
                busy = running == script.id,
                onRun = {
                    if (vm.scripts.isApproved(script)) vm.runScript(script)
                    else pendingApproval = script
                },
                onEdit = { vm.editScript(script) },
                onDelete = { vm.deleteScript(script) },
            )
        }

        result?.let { r ->
            item { SectionLabel(if (r.ok) "Last run" else "Last run — failed") }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.sunken)
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    if (r.error != null) {
                        Text(r.error, fontSize = 11.5.sp, color = colors.bad, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        r.output.ifEmpty { "(no output)" },
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.fg2,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("${r.elapsedMs} ms", fontSize = 10.sp, color = colors.fg3)
                }
            }
        }
    }

    pendingApproval?.let { script ->
        ApprovalDialog(
            script = script,
            onDismiss = { pendingApproval = null },
            onAllowOnce = { pendingApproval = null; vm.runScript(script) },
            onAlwaysAllow = {
                pendingApproval = null
                vm.scripts.approve(script)
                vm.runScript(script)
            },
        )
    }
}

@Composable
private fun ScriptRow(
    script: Script,
    approved: Boolean,
    busy: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(FiletIcons.Script, null, tint = colors.accent, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(10.dp))
            Text(script.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (approved) {
                Text("approved", fontSize = 9.sp, color = colors.good)
                Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        script.permissions.describe().forEach { line ->
            Text("· $line", fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (busy) "Running…" else "Run", primary = true, enabled = !busy, onClick = onRun)
            Chip("Edit", primary = false, enabled = true, onClick = onEdit)
            Chip("Delete", primary = false, enabled = true, onClick = onDelete)
        }
    }
}

@Composable
private fun Chip(text: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = Filet.colors
    Text(
        text,
        fontSize = 11.5.sp,
        color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (primary) colors.accent else colors.high)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Consent, in plain words, before anything runs.
 *
 * "Always allow" is bound to this exact source: editing the script, or anything replacing it,
 * invalidates the approval. An external intent can *offer* a script; only this dialog runs one.
 */
@Composable
private fun ApprovalDialog(
    script: Script,
    onDismiss: () -> Unit,
    onAllowOnce: () -> Unit,
    onAlwaysAllow: () -> Unit,
) {
    val colors = Filet.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run “${script.name}”?", fontSize = 15.sp) },
        text = {
            Column {
                Text("This script will be allowed to:", fontSize = 12.sp, color = colors.fg2)
                Spacer(Modifier.height(6.dp))
                script.permissions.describe().forEach {
                    Text("· $it", fontSize = 12.sp, lineHeight = 17.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Nothing outside that list is reachable, even if the script asks. " +
                        "Approval covers this exact version — editing it will ask again.",
                    fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAllowOnce) { Text("Run once") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = onAlwaysAllow) { Text("Always allow") }
            }
        },
    )
}

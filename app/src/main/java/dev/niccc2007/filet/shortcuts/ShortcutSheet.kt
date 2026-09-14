package dev.niccc2007.filet.shortcuts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.handlers.HandlerId
import dev.niccc2007.filet.ui.HScroll
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode

/**
 * What happens between "I want this on my home screen" and the launcher asking to place it.
 *
 * Three decisions worth offering, and no more: what it is called, what opens it, and whether
 * Filet remembers it. Everything else about a shortcut is the launcher's business.
 *
 * The opener matters more than it looks. A shortcut to a video that opens the text editor is
 * useless, and the per-type default is a *routing* preference that can change later - so a
 * shortcut pins its own answer and stops depending on one.
 */
@Composable
fun ShortcutSheet(
    node: VNode,
    onDismiss: () -> Unit,
    onCreate: (label: String, handler: HandlerId?) -> Unit,
) {
    val colors = Filet.colors
    var label by remember(node.path) { mutableStateOf(node.name) }
    var handler by remember(node.path) { mutableStateOf<HandlerId?>(null) }

    val options: List<Pair<HandlerId?, String>> = remember(node.path) {
        buildList {
            add(null to "Default")
            if (node.isDir) return@buildList
            when (FileKind.of(node)) {
                FileKind.IMAGE -> add(HandlerId.IMAGE to "Image viewer")
                FileKind.VIDEO, FileKind.AUDIO -> add(HandlerId.MEDIA to "Media player")
                FileKind.APK -> add(HandlerId.APK to "APK inspector")
                FileKind.ARCHIVE -> add(HandlerId.ARCHIVE to "Open as folder")
                else -> Unit
            }
            add(HandlerId.TEXT to "Text editor")
            add(HandlerId.HEX to "Hex viewer")
            add(HandlerId.EXTERNAL to "Another app")
        }.distinctBy { it.first }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to home screen", fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    singleLine = true,
                    label = { Text("Name") },
                )
                Spacer(Modifier.height(12.dp))
                Text("Opens with", fontSize = 11.sp, color = colors.fg3)
                Spacer(Modifier.height(6.dp))
                HScroll(ground = colors.raised) {
                    for ((id, name) in options) {
                        val on = id == handler
                        Text(
                            name,
                            fontSize = 11.sp,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                            modifier = Modifier
                                .padding(end = 5.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (on) colors.accent else colors.high)
                                .clickable { handler = id }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "The shortcut stores this file's Filet ID, not its path — move the file " +
                        "and the shortcut follows it. Delete it and the shortcut says so " +
                        "instead of doing nothing.",
                    fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(label.trim().ifEmpty { node.name }, handler) },
                enabled = label.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One row in the in-app shortcut list. */
@Composable
fun ShortcutRow(
    record: ShortcutRecord,
    live: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .background(colors.raised)
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
            Text(record.label, fontSize = 13.sp)
            Text(
                buildString {
                    append(record.kindLabel())
                    if (!live) append(" · not on the home screen")
                    record.handler?.let { append(" · opens with ").append(it.lowercase()) }
                },
                fontSize = 10.sp, color = if (live) colors.fg3 else colors.warn,
            )
        }
        TextButton(onClick = onRename) { Text("Rename", fontSize = 11.sp) }
        TextButton(onClick = onForget) { Text("Forget", fontSize = 11.sp) }
    }
}

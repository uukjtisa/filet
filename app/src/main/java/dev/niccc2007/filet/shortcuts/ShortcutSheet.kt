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
import dev.niccc2007.filet.ui.tabs.SmallBtn
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgAction
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.dialogs.DlgWarn
import dev.niccc2007.filet.browser.FiletIcons
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
                FileKind.ARCHIVE -> add(HandlerId.ARCHIVE to "Archive viewer")
                else -> Unit
            }
            add(HandlerId.TEXT to "Text editor")
            add(HandlerId.HEX to "Hex viewer")
            add(HandlerId.EXTERNAL to "Another app")
        }.distinctBy { it.first }
    }

    // dismissOnScrim is off: there is typing in here, and a stray tap outside the card
    // throwing away a half-entered name is the kind of loss nobody reports.
    Dlg(onDismiss = onDismiss, dismissOnScrim = false) {
        DlgHeader(FiletIcons.Pin, "Add to home screen", onClose = onDismiss)
        DlgBody {
            DlgField("Name", label, { label = it.take(40) })
            DlgSection("Opens with")
            Spacer(Modifier.height(2.dp))
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
            Spacer(Modifier.height(10.dp))
            DlgCaption("Follows the file if you move it.")
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn("Add", kind = BtnKind.PRIMARY, enabled = label.isNotBlank()) {
                onCreate(label.trim().ifEmpty { node.name }, handler)
            }
        }
    }
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
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
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
        SmallBtn("Rename", onRename)
        SmallBtn("Forget", onForget)
    }
}

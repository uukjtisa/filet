package dev.niccc2007.filet.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.dialogs.DlgTone
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath

/**
 * Browse to a folder and take it.
 *
 * The other half of "Extract here": here is one destination, and this is every other one. It
 * lists folders only, because a file is never an answer to "where should this go", and it takes
 * the folder that is OPEN rather than one selected in the list - so an empty folder, which is
 * usually exactly where an extraction should land, is reachable like any other.
 *
 * Deliberately not a second file browser: no sorting, no selection, no operations. The way out
 * is up, down, or one of the two buttons. The confirm label is the caller's, because "Extract
 * into this" and "Save here" are the same gesture and different sentences.
 */
@Composable
fun FolderPicker(
    title: String,
    at: VPath,
    entries: List<VNode>,
    loading: Boolean,
    atRoot: Boolean,
    confirmLabel: String,
    onOpen: (VPath) -> Unit,
    onUp: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Filet.colors
    Dlg(onDismiss = onDismiss) {
        // The path goes in the header's subtitle rather than as the body's first line. It is
        // what this dialogue is ABOUT - which folder you are standing in - and a header is
        // where a dialogue says that.
        DlgHeader(
            FiletIcons.Folder,
            title,
            sub = at.path,
            onClose = onDismiss,
        )
        // scroll = false: the list below scrolls itself, and nesting two vertical scrollers
        // gives the inner one zero height.
        DlgBody(scroll = false, padded = false) {
            LazyColumn(Modifier.heightIn(min = 120.dp, max = 300.dp)) {
                if (!atRoot) {
                    item(key = "..") {
                        PickRow(FiletIcons.Up, "..", colors.fg3, onUp)
                    }
                }
                items(entries, key = { it.path.path }) { node ->
                    PickRow(FiletIcons.Folder, node.name, MaterialTheme.colorScheme.onSurface) { onOpen(node.path) }
                }
                if (entries.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            // "No folders" and not "empty": an empty folder is a perfectly
                            // good destination, and the row has to not read as a failure.
                            if (loading) "Reading…" else "No folders in here",
                            fontSize = 10.5.sp,
                            color = colors.fg3,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn(confirmLabel, kind = BtnKind.PRIMARY, onClick = onConfirm)
        }
    }
}

@Composable
private fun PickRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 12.sp, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

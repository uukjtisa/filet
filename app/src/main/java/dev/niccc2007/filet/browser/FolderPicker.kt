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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 15.sp, maxLines = 2) },
        text = {
            Column {
                Text(
                    at.path,
                    fontSize = 10.5.sp,
                    color = colors.fg3,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(min = 120.dp, max = 260.dp)) {
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
                                // Not an error. An empty folder is a perfectly good destination,
                                // and saying so is the difference between "nothing here" and
                                // "this failed to load".
                                if (loading) "Reading…" else "No folders in here - which is fine, you can still extract into it",
                                fontSize = 10.5.sp,
                                color = colors.fg3,
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

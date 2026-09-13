package dev.niccc2007.filet.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which modal the browser is currently asking for. One at a time, by construction. */
sealed interface Dialog {
    data object NewFolder : Dialog
    data object NewFile : Dialog
    data class Rename(val node: VNode) : Dialog
    data object Compress : Dialog
    data class ConfirmDelete(val count: Int, val sample: String) : Dialog
    data class Properties(val node: VNode) : Dialog
}

@Composable
fun FiletDialogs(vm: BrowserViewModel) {
    // The shortcut sheet is its own flow rather than a `Dialog` case: it carries a node and
    // two settings, and folding it into the name-only dialog family would mean a dialog type
    // with three unused fields for everyone else.
    vm.shortcutFor.collectAsState().value?.let { node ->
        dev.niccc2007.filet.shortcuts.ShortcutSheet(
            node = node,
            onDismiss = { vm.dismissShortcutSheet() },
            onCreate = { label, handler -> vm.createShortcut(node, label, handler) },
        )
    }

    val dialog by vm.dialog.collectAsState()
    when (val d = dialog) {
        null -> Unit
        is Dialog.NewFolder -> NameDialog("New folder", "", "Create", vm::dismissDialog) { vm.createFolder(it) }
        is Dialog.NewFile -> NameDialog("New file", "", "Create", vm::dismissDialog) { vm.createFile(it) }
        is Dialog.Rename -> NameDialog("Rename", d.node.name, "Rename", vm::dismissDialog, selectStem = true) {
            vm.rename(d.node.path, it)
        }
        is Dialog.Compress -> NameDialog("Compress to zip", vm.suggestedArchiveName(), "Compress", vm::dismissDialog) {
            vm.compressSelection(it)
        }
        is Dialog.ConfirmDelete -> ConfirmDialog(
            title = if (d.count == 1) "Delete ${d.sample}?" else "Delete ${d.count} items?",
            body = "This cannot be undone. Filet has no trash yet, so deleted means gone.",
            confirm = "Delete",
            destructive = true,
            onDismiss = vm::dismissDialog,
        ) { vm.deleteSelection() }
        is Dialog.Properties -> PropertiesDialog(vm, d.node, vm::dismissDialog)
    }
}

/**
 * @param selectStem pre-selects the name without its extension, the way every desktop rename
 *   does. Selecting the whole thing means the extension gets typed over by accident.
 */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    selectStem: Boolean = false,
    onDone: (String) -> Unit,
) {
    val stemEnd = if (selectStem) initial.lastIndexOf('.').let { if (it > 0) it else initial.length } else initial.length
    var value by remember {
        mutableStateOf(TextFieldValue(initial, TextRange(0, stemEnd)))
    }
    val invalid = value.text.contains('/') || value.text == "." || value.text == ".."
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    isError = invalid,
                    label = { Text("Name") },
                )
                if (invalid) {
                    Text(
                        "A name cannot contain a slash.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDone(value.text.trim()); onDismiss() },
                enabled = value.text.isNotBlank() && !invalid,
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = { Text(body, fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(
                    confirm,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PropertiesDialog(vm: BrowserViewModel, node: VNode, onDismiss: () -> Unit) {
    val colors = Filet.colors
    var origin by remember(node.path) { mutableStateOf<dev.niccc2007.filet.index.Provenance?>(null) }
    LaunchedEffect(node.path) { origin = runCatching { vm.provenanceOf(node.path) }.getOrNull() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(node.name, fontSize = 15.sp, maxLines = 2) },
        text = {
            Column {
                Prop("Type", if (node.isDir) "Folder" else FileKind.of(node).name.lowercase())
                Prop("Location", node.path.parent?.path ?: node.path.path)
                Prop("Volume", node.path.scheme)
                if (!node.isDir) Prop("Size", "${humanSize(node.size)}  (${node.size} bytes)")
                Prop("Modified", if (node.mtime > 0) STAMP.format(Date(node.mtime)) else "unknown")
                Prop("Readable", if (node.readable) "yes" else "no")
                Prop("Writable", if (node.writable) "yes" else "no")
                if (node.hidden) Prop("Hidden", "yes")

                // The Source chip. Android has no mark-of-the-web, so this is the only
                // memory a file has of where it came from (PLAN.md §5.1).
                origin?.let { p ->
                    Spacer(Modifier.height(10.dp))
                    Text("SOURCE", fontSize = 9.sp, color = colors.fg3, letterSpacing = 1.sp)
                    Spacer(Modifier.height(4.dp))
                    p.origin?.let { Prop("From", it) }
                    p.pageTitle?.let { Prop("Title", it) }
                    p.uploader?.let { Prop("By", it) }
                    p.format?.let { Prop("Format", it) }
                    Prop("Recorded", if (p.at > 0) STAMP.format(Date(p.at)) else "")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.origin?.let { url ->
                            SmallAction("Open original") { vm.openUrl(url); onDismiss() }
                            SmallAction("Find others") { vm.searchByOrigin(hostOf(url)); onDismiss() }
                            SmallAction("Get again") {
                                vm.requestRedownload(url, p.format, node.name); onDismiss()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Prop(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(22.dp)) {
        Text(label, fontSize = 11.5.sp, color = Filet.colors.fg3, modifier = Modifier.width(76.dp))
        Text(value, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 3.dp),
    )
}

/** `https://youtube.com/watch?v=x` -> `youtube.com`, which is what "find others" means. */
private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host ?: url }.getOrDefault(url).removePrefix("www.")

private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

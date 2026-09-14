package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.provider.ArchiveCapabilities
import dev.niccc2007.filet.vfs.provider.ArchiveOptions
import dev.niccc2007.filet.vfs.provider.Archives
import dev.niccc2007.filet.vfs.provider.CompressEstimate
import dev.niccc2007.filet.vfs.provider.SizeEstimate
import dev.niccc2007.filet.vfs.provider.WrapChoice
import dev.niccc2007.filet.vfs.provider.EncryptionMethod
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

    // The prompt for a file edited inside an archive. Same reason again: it carries the edited
    // text and a callback, which no value-type dialog case can hold.
    val archiveSave by vm.archiveSave.collectAsState()
    archiveSave?.let { p ->
        ArchiveSaveSheet(
            fileName = p.node.name,
            archiveName = p.archiveName,
            cost = p.cost,
            refusal = p.refusal,
            busy = p.busy,
            onUpdate = vm::confirmArchiveSave,
            onElsewhere = vm::saveArchiveMemberElsewhere,
            onDismiss = vm::cancelArchiveSave,
        )
    }

    // Same reason as the sheet below: this one carries a callback, which a value type compared
    // for equality cannot.
    val pick by vm.folderPick.collectAsState()
    pick?.let { p ->
        FolderPicker(
            title = p.title,
            at = p.at,
            entries = p.entries,
            loading = p.loading,
            atRoot = p.atRoot,
            confirmLabel = p.confirmLabel,
            onOpen = vm::browsePick,
            onUp = vm::pickUp,
            onConfirm = vm::confirmPick,
            onDismiss = vm::cancelPick,
        )
    }

    // Not one of the `Dialog` cases: an extraction is a pending OPERATION with a plan attached
    // that re-plans as the options change, which a one-shot dialog state cannot hold.
    val pendingExtract by vm.extractPlan.collectAsState()
    pendingExtract?.let { p ->
        ExtractSheet(
            plan = p.plan,
            destinationName = p.into.path,
            busy = p.busy,
            onWrap = {
                vm.adjustExtract {
                    it.copy(
                        wrap = if (p.plan.wrapFolder != null) WrapChoice.FORCE_OFF else WrapChoice.FORCE_ON,
                    )
                }
            },
            // One level back at a time, which is the undo for "all the way down".
            onStripLess = {
                vm.adjustExtract { it.copy(stripLevels = (p.plan.strippedFolders.size - 1).coerceAtLeast(0)) }
            },
            onCollision = { c -> vm.adjustExtract { it.copy(onCollision = c) } },
            onConfirm = vm::confirmExtract,
            onDismiss = vm::cancelExtract,
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
        is Dialog.Compress -> CompressDialog(vm)
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

/**
 * Name it, pick a container, compress.
 *
 * The formats are rows rather than a dropdown because a dropdown hides the choice behind a
 * tap and the choice is the point: zip is what everything opens, tar keeps unix permissions,
 * 7z is smaller and much slower. Each row says which it is rather than leaving the user to
 * already know.
 *
 * The suffix comes from the format and never from the typed name, so picking Tar + gzip with
 * "Photos.zip" still in the box makes a `Photos.tar.gz` and not a gzipped tar wearing a zip's
 * name.
 */
@Composable
private fun CompressDialog(vm: BrowserViewModel) {
    val colors = Filet.colors
    val formats = vm.archiveFormats
    var format by remember { mutableStateOf(formats.first()) }
    // Everything the window offers comes from here rather than from a `when` over format ids.
    val capability = remember(format) { ArchiveCapabilities.of(format) }
    var strength by remember(format) { mutableStateOf<Int?>(null) }
    var password by remember { mutableStateOf("") }
    var encryption by remember(format) { mutableStateOf<EncryptionMethod?>(null) }
    var splitBytes by remember(format) { mutableStateOf<Long?>(null) }
    val totalBytes = remember { vm.selectionBytes() }
    val splitProblem = remember(capability, splitBytes, totalBytes) {
        splitBytes?.let { ArchiveCapabilities.splitProblem(capability, it, totalBytes) }
    }
    // Measured once. Re-walking the selection on every keystroke in the name field would make
    // a dialog that stutters, and the selection cannot change while the dialog is up.
    val sizes = remember { vm.selectionSizes() }
    val estimate = remember(format, strength, sizes) { CompressEstimate.of(sizes, format, strength) }
    val suggested = remember { Archives.baseName(vm.suggestedArchiveName()) }
    var value by remember { mutableStateOf(TextFieldValue(suggested, TextRange(0, suggested.length))) }
    val invalid = value.text.contains('/') || value.text.trim() == "." || value.text.trim() == ".."

    AlertDialog(
        onDismissRequest = vm::dismissDialog,
        title = { Text("Compress", fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    isError = invalid,
                    label = { Text("Name") },
                    suffix = {
                        Text(
                            format.suffix,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.accent,
                        )
                    },
                )
                if (invalid) {
                    Text(
                        "A name cannot contain a slash.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(10.dp))
                for (f in formats) {
                    val on = f.id == format.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) colors.sel else Color.Transparent)
                            .clickable { format = f }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                f.label,
                                fontSize = 13.sp,
                                color = if (on) colors.accent else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(FORMAT_NOTES[f.id].orEmpty(), fontSize = 9.5.sp, color = colors.fg3)
                        }
                        Text(
                            f.suffix,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.fg3,
                        )
                        if (on) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                FiletIcons.Check, null,
                                tint = colors.accent, modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                ArchiveOptionsSection(
                    capability = capability,
                    strength = strength,
                    onStrength = { strength = it },
                    password = password,
                    onPassword = { password = it },
                    encryption = encryption,
                    onEncryption = { encryption = it },
                    splitBytes = splitBytes,
                    onSplit = { splitBytes = it },
                    splitProblem = splitProblem,
                )

                EstimateLine(estimate)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.dismissDialog()
                    vm.compressSelection(
                        value.text.trim(),
                        format,
                        ArchiveOptions(
                            strength = strength,
                            password = password.takeIf { it.isNotEmpty() }?.toCharArray(),
                            encryption = encryption ?: capability.password.default.takeIf { password.isNotEmpty() },
                            splitBytes = splitBytes,
                        ),
                    )
                },
                // A part size that cannot work is refused HERE, before a byte is written -
                // the alternative is finding out at part 100 of a 4 GB archive.
                enabled = value.text.isNotBlank() && !invalid && splitProblem == null,
            ) { Text("Compress") }
        },
        dismissButton = { TextButton(onClick = vm::dismissDialog) { Text("Cancel") } },
    )
}

/** One line each, saying what the format is actually for. */
private val FORMAT_NOTES = mapOf(
    "zip" to "Opens on everything. The safe answer.",
    "tar" to "No compression. Fast, and keeps unix permissions.",
    "tar.gz" to "Smaller than zip on text. Common on Linux.",
    "tar.bz2" to "Smaller again, and slower.",
    "tar.xz" to "Smallest of the tars, and the slowest.",
    "7z" to "Usually the smallest. Slow on a phone, and built in the cache first.",
)

/**
 * What the archive will probably weigh.
 *
 * Phrased as a range and labelled a guess on purpose. A single confident number is the version
 * of this feature that is wrong in public: compression depends on the bytes, not the format, and
 * the one case where the answer is certain - already-compressed media, which does not shrink -
 * is the case where somebody is about to spend ten minutes finding that out the slow way.
 *
 * The numbers come from [CompressEstimate], which is tested. Nothing is computed here.
 */
@Composable
private fun EstimateLine(estimate: SizeEstimate) {
    if (estimate.empty) return
    val colors = Filet.colors
    Spacer(Modifier.height(10.dp))
    Text(
        if (estimate.partial) {
            // A folder in the selection: its contents were never listed, so the honest form is
            // a floor rather than a range.
            "Estimated output: at least ~${byteSize(estimate.low)} — folders were not measured"
        } else {
            "Estimated output: ~${byteSize(estimate.low)} to ~${byteSize(estimate.high)}"
        },
        fontSize = 11.sp,
        color = colors.fg2,
    )
    Text(
        if (estimate.mostlyIncompressible) {
            "An estimate. Most of this is already compressed, so a higher setting mostly costs time."
        } else {
            "An estimate — how much it shrinks depends on the bytes, not the format."
        },
        fontSize = 9.5.sp,
        color = colors.fg3,
    )
}

private fun byteSize(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.0f KB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024)
    else -> "%.2f GB".format(n / 1024.0 / 1024 / 1024)
}

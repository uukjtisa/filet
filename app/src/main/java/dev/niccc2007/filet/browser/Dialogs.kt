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
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontWeight
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgKv
import dev.niccc2007.filet.ui.dialogs.DlgPick
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.dialogs.DlgTick
import dev.niccc2007.filet.ui.dialogs.DlgTone
import dev.niccc2007.filet.ui.dialogs.DlgWarn
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.provider.ArchiveCapabilities
import dev.niccc2007.filet.vfs.provider.ArchiveNaming
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
            onFile = if (p.takeFile == null) null else vm::pickThisFile,
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

    // A drop waiting to be told what it is. Not a `Dialog` case: it is a pending OPERATION
    // holding the paths it will act on, and it lives on the browser state beside the drag it
    // came from rather than in the one-at-a-time modal slot.
    val browser by vm.state.collectAsState()
    browser.dropAsk?.let { DropDialog(it, vm::answerDrop, vm::cancelDrop) }
    browser.denied?.let { DeniedDialog(it, vm::dismissDenial) }
    browser.duplicateAsk?.let { DuplicateDialog(it, vm::confirmDuplicate, vm::cancelDuplicate) }

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
 * Dropping items back onto the folder they came from.
 *
 * One question with one answer, so it is a confirm and not the move-or-copy prompt: there is
 * nothing to choose between here. Moving something into the folder it is already in is a no-op,
 * which is why this case used to be refused outright.
 */
@Composable
fun DuplicateDialog(
    pending: dev.niccc2007.filet.browser.PendingDuplicate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dlg(onDismiss = onDismiss) {
        DlgHeader(FiletIcons.Copy, "Make a copy", onClose = onDismiss)
        DlgBody {
            Text(
                if (pending.count == 1) {
                    "Make a copy of \"${pending.label}\" in this folder?"
                } else {
                    "Make a copy of ${pending.count} items in this folder?"
                },
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Filet.colors.fg2,
            )
            Spacer(Modifier.height(8.dp))
            // Says the name it will use rather than leaving it to be discovered afterwards -
            // the one thing somebody wants to know before agreeing is what they will end up
            // looking at.
            DlgCaption(
                if (pending.count == 1) {
                    "Named " + dev.niccc2007.filet.browser.DuplicateName.PREFIX + pending.label
                } else {
                    "Each named " + dev.niccc2007.filet.browser.DuplicateName.PREFIX +
                        "the original, numbered if that is taken"
                },
            )
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn("Make a copy", kind = BtnKind.PRIMARY) { onConfirm() }
        }
    }
}

/**
 * An action that was refused.
 *
 * Not a toast. A refusal has two things to say - what was refused, and what would have to change
 * before it would not be - and the second one is the part that is worth showing at all. A toast
 * has room for neither, which is why "permission denied" used to be the whole message.
 *
 * One banner, carrying the headline. The detail is body text below it rather than a second
 * banner, because only one of the two is an obstacle.
 */
@Composable
fun DeniedDialog(denial: dev.niccc2007.filet.vfs.Denial, onDismiss: () -> Unit) {
    Dlg(onDismiss = onDismiss) {
        DlgHeader(FiletIcons.Info, "Not allowed", tone = DlgTone.BAD, onClose = onDismiss)
        DlgBody {
            DlgWarn(denial.headline, bad = true)
            Spacer(Modifier.height(10.dp))
            Text(denial.detail, fontSize = 13.sp, lineHeight = 19.sp, color = Filet.colors.fg2)
            Spacer(Modifier.height(10.dp))
            DlgKv(denial.action.verb.replaceFirstChar { it.uppercase() }, denial.path.name, mono = true)
            // The host's own words, when it gave any. Shown verbatim and never interpreted - a
            // server's explanation is usually better than a guess at one.
            denial.hostSaid?.let {
                Spacer(Modifier.height(6.dp))
                DlgCaption(it)
            }
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("OK", kind = BtnKind.PRIMARY, onClick = onDismiss)
        }
    }
}

/**
 * What a drop between two panes should do.
 *
 * Both answers are rows of equal weight rather than a footer's confirm and cancel: a move and a
 * copy are two different operations, not an action and its refusal. The suggested one is only
 * marked, because the same-volume rule it comes from is a good guess and a bad decision - see
 * [DragBehaviour].
 */
@Composable
fun DropDialog(
    pending: PendingDrop,
    onAnswer: (DropAction, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var always by remember { mutableStateOf(false) }
    Dlg(onDismiss = onDismiss) {
        DlgHeader(
            FiletIcons.Cut,
            if (pending.count == 1) "Drop 1 item" else "Drop ${pending.count} items",
            onClose = onDismiss,
        )
        DlgBody {
            DlgCaption("Into ${pending.destLabel}")
            Spacer(Modifier.height(8.dp))
            DlgPick(
                icon = FiletIcons.Cut,
                label = "Move",
                detail = "Takes it out of where it came from",
                tag = if (pending.suggested == DropAction.MOVE) "suggested" else null,
                ours = true,
                onClick = { onAnswer(DropAction.MOVE, always) },
            )
            DlgPick(
                icon = FiletIcons.Copy,
                label = "Copy",
                detail = "Leaves the original where it is",
                tag = if (pending.suggested == DropAction.COPY) "suggested" else null,
                ours = true,
                onClick = { onAnswer(DropAction.COPY, always) },
            )
            Spacer(Modifier.height(10.dp))
            DlgTick(
                on = always,
                label = "Always do this",
                sub = "Stops the question. Changeable in Settings.",
                onToggle = { always = !always },
            )
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Cancel", onClick = onDismiss)
        }
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
    // dismissOnScrim is off: there is typing in here, and a stray tap outside the card throwing
    // away a half-entered name is the kind of loss nobody reports and everybody resents.
    Dlg(onDismiss = onDismiss, dismissOnScrim = false) {
        DlgHeader(FiletIcons.Rename, title, onClose = onDismiss)
        DlgBody {
            // A raw BasicTextField rather than DlgField, for one reason: this dialogue needs the
            // TextFieldValue so it can pre-select the stem, and DlgField takes a String. The
            // frame around it is drawn to DlgField's spec so the two are indistinguishable.
            Text(
                "NAME",
                fontSize = 10.sp,
                letterSpacing = 1.0.sp,
                fontWeight = FontWeight.SemiBold,
                color = Filet.colors.fg3,
            )
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Filet.colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(Filet.colors.sunken)
                    .border(
                        1.dp,
                        if (invalid) Filet.colors.bad else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(9.dp),
                    )
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            )
            if (invalid) {
                Spacer(Modifier.height(5.dp))
                Text(
                    "A name cannot contain a slash.",
                    fontSize = 10.5.sp,
                    color = Filet.colors.bad,
                )
            }
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn(
                confirm,
                kind = BtnKind.PRIMARY,
                enabled = value.text.isNotBlank() && !invalid,
            ) { onDone(value.text.trim()); onDismiss() }
        }
    }
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
    Dlg(onDismiss = onDismiss) {
        // A destructive confirmation gets the red icon tile and the red button, and it gets no
        // close cross - the two answers are both in the footer, and a third way out that means
        // "no" without saying so is how a delete prompt becomes ambiguous.
        DlgHeader(
            if (destructive) FiletIcons.Delete else FiletIcons.Info,
            title,
            tone = if (destructive) DlgTone.BAD else DlgTone.NORMAL,
        )
        DlgBody {
            Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = Filet.colors.fg2)
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn(
                confirm,
                kind = if (destructive) BtnKind.DANGER else BtnKind.PRIMARY,
            ) { onConfirm(); onDismiss() }
        }
    }
}

@Composable
private fun PropertiesDialog(vm: BrowserViewModel, node: VNode, onDismiss: () -> Unit) {
    val colors = Filet.colors
    var origin by remember(node.path) { mutableStateOf<dev.niccc2007.filet.index.Provenance?>(null) }
    LaunchedEffect(node.path) { origin = runCatching { vm.provenanceOf(node.path) }.getOrNull() }

    Dlg(onDismiss = onDismiss) {
        DlgHeader(
            if (node.isDir) FiletIcons.Folder else FiletIcons.Info,
            node.name,
            sub = node.path.parent?.path ?: node.path.path,
            onClose = onDismiss,
        )
        DlgBody {
            DlgKv("Type", if (node.isDir) "Folder" else FileKind.of(node).name.lowercase())
            DlgKv("Volume", node.path.scheme, mono = true)
            if (!node.isDir) DlgKv("Size", "${humanSize(node.size)}  (${node.size} bytes)")
            DlgKv("Modified", if (node.mtime > 0) STAMP.format(Date(node.mtime)) else "unknown")
            DlgKv("Readable", if (node.readable) "yes" else "no")
            DlgKv("Writable", if (node.writable) "yes" else "no")
            if (node.hidden) DlgKv("Hidden", "yes")

            // The Source block, kept whole and kept last.
            //
            // Android has no mark-of-the-web, so this is the only memory a file has of where it
            // came from (PLAN.md 5.1), and it carries three actions that exist nowhere else in
            // the app: open the page it came from, find other files from the same host, fetch it
            // again. An earlier pass at this dialogue invented a hash row and dropped this
            // entirely, which is why it is called out here - it is the one part of Details that
            // is not in every other file manager.
            origin?.let { p ->
                DlgSection("Source")
                p.origin?.let { DlgKv("From", it, mono = true) }
                p.pageTitle?.let { DlgKv("Title", it) }
                p.uploader?.let { DlgKv("By", it) }
                p.format?.let { DlgKv("Format", it) }
                DlgKv("Recorded", if (p.at > 0) STAMP.format(Date(p.at)) else "")
                Spacer(Modifier.height(8.dp))
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
        DlgFooter {
            DlgSpacer()
            DlgBtn("Close", fill = false, onClick = onDismiss)
        }
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
 * The extension is a field of its own rather than a label, because plenty of formats in the
 * world are an ordinary archive under a name of their own - `.mcaddon`, `.cbz`, `.epub`. It
 * follows the format while it still holds a default, so switching to Tar + gzip with a
 * pre-filled `.zip` in it moves to `.tar.gz`; once it holds something typed, it is left alone.
 * See `ArchiveNaming`.
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
    var suffix by remember { mutableStateOf(formats.first().suffix) }
    val mismatch = remember(suffix, format) { ArchiveNaming.mismatchNote(suffix, format) }

    Dlg(onDismiss = vm::dismissDialog, dismissOnScrim = false) {
        DlgHeader(
            FiletIcons.Zip,
            "Compress",
            sub = "${sizes.size} item${if (sizes.size == 1) "" else "s"}  ·  " +
                humanSize(totalBytes),
            onClose = vm::dismissDialog,
        )
        DlgBody {
                // The name and the extension side by side: two fields, because the extension
                // is a choice now rather than a label reporting the format's own.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        isError = invalid,
                        label = { Text("Name") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = suffix,
                        onValueChange = { suffix = ArchiveNaming.cleanSuffix(it) },
                        singleLine = true,
                        label = { Text("Ext") },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.accent,
                        ),
                        modifier = Modifier.width(120.dp),
                    )
                }
                if (invalid) {
                    Text(
                        "A name cannot contain a slash.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (mismatch != null) {
                    // Said, not prevented. Writing a 7z under another name is the feature;
                    // doing it by accident is the mistake, and the two look identical here.
                    Spacer(Modifier.height(4.dp))
                    Text(mismatch, fontSize = 10.sp, lineHeight = 14.sp, color = colors.fg3)
                }
                Spacer(Modifier.height(10.dp))
                for (f in formats) {
                    val on = f.id == format.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) colors.sel else Color.Transparent)
                            .clickable {
                                suffix = ArchiveNaming.suffixWhenFormatChanges(suffix, f, formats)
                                format = f
                            }
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
        DlgFooter {
            DlgBtn("Cancel", onClick = vm::dismissDialog)
            DlgBtn(
                "Compress",
                kind = BtnKind.PRIMARY,
                // A part size that cannot work is refused HERE, before a byte is written - the
                // alternative is finding out at part 100 of a 4 GB archive.
                enabled = value.text.isNotBlank() && !invalid && splitProblem == null,
            ) {
                vm.dismissDialog()
                vm.compressSelection(
                    value.text.trim(),
                    format,
                    suffix,
                    ArchiveOptions(
                        strength = strength,
                        password = password.takeIf { it.isNotEmpty() }?.toCharArray(),
                        encryption = encryption ?: capability.password.default.takeIf { password.isNotEmpty() },
                        splitBytes = splitBytes,
                    ),
                )
            }
        }
    }
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
            "At least ~${byteSize(estimate.low)} (folders not measured)"
        } else {
            "~${byteSize(estimate.low)} to ~${byteSize(estimate.high)}"
        },
        fontSize = 11.sp,
        color = colors.fg2,
    )
    Text(
        if (estimate.mostlyIncompressible) {
            "An estimate. Most of this is already compressed."
        } else {
            "An estimate."
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

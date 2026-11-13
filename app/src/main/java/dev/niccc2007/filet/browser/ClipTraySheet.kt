package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ops.ClipBatch
import dev.niccc2007.filet.ops.ClipTray
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgForm
import dev.niccc2007.filet.ui.dialogs.PopMenu
import dev.niccc2007.filet.ui.dialogs.PopRow
import dev.niccc2007.filet.ui.dialogs.PopSeparator
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.types.FileTypes
import dev.niccc2007.filet.vfs.types.TypeKind

/**
 * The clipboard, opened.
 *
 * ## New above history
 *
 * Two groups, because they are two different questions. NEW is what has not been pasted yet:
 * what the pill on the dock will paste. HISTORY is what has been, newest first, each row still
 * one tap from being used again. Bug identified here: one flat list of batches, every one with
 * the same Paste button, gave no way to tell a copy waiting to be used from one already used
 * three times.
 *
 * ## A row says what it holds, where it is, and does its job in one tap
 *
 * The first file's name and "+N more" rather than "7 items", because a name is what tells two
 * batches apart. The second line says where: "from" a folder while new, "now in" one after a
 * move (the batch follows its files), "pasted 2x, last to" one after a copy. The button on the
 * row is the verb - Move or Paste - and for a move already in this folder it says Here and does
 * nothing, because moving files onto themselves is not a paste. Everything else a row can do is
 * on its menu, so the row carries one button rather than four.
 *
 * Tapping a row opens its files, each with a tick (not this time) and a cross (off the clipboard
 * for good) - two intentions, deliberately two controls.
 *
 * ## Select
 *
 * Select turns every row into a checkbox and the footer into "Do N here", for bringing several
 * rows - new or history, copies and moves together - in one press. With two or more new rows and
 * nothing selected, the footer is "Bring all N new here", which is the pill's press made from
 * inside the sheet.
 *
 * ## The destination is the subject
 *
 * A sheet that pastes "here" with no statement of where here is, is a guess in split view. One
 * line, rendered the way the path strip reads everywhere else.
 */
@Composable
fun ClipTraySheet(
    pane: PaneController,
    vm: BrowserViewModel,
    dest: VPath,
    tray: ClipTray,
    onDismiss: () -> Unit,
) {
    val colors = Filet.colors
    val blocked = vm.writeBlockReason(dest)
    val gone by vm.clipGone.collectAsState()
    var selecting by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var open by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) { vm.checkClipGone() }

    val fresh = tray.fresh
    val history = tray.history

    /** One row's job, here; the sheet closes once something has actually started. */
    fun go(b: ClipBatch, copyInstead: Boolean = false) {
        when {
            blocked != null -> vm.toast(blocked)
            b.nothingPicked -> vm.toast("Nothing is ticked in this one")
            else -> {
                if (copyInstead) vm.copyBatchInstead(pane, b.id) else vm.pasteBatch(pane, b.id)
                onDismiss()
            }
        }
    }

    Dlg(onDismiss = onDismiss, form = DlgForm.AUTO) {
        // The grab handle, not a DlgHeader: this is a sheet you pull up, and the header's
        // icon tile would put a glyph where the count belongs.
        Box(
            Modifier
                .padding(top = 9.dp, bottom = 4.dp)
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.high),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Clipboard", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(
                    "${fresh.size} new".takeIf { fresh.isNotEmpty() },
                    "${history.size} in history".takeIf { history.isNotEmpty() },
                ).joinToString("  ·  ").ifEmpty { "empty" },
                fontSize = 11.5.sp,
                color = colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!tray.isEmpty) {
                TextButton(if (selecting) "Done" else "Select", on = selecting) {
                    selecting = !selecting
                    chosen = emptySet()
                }
                TextButton("Clear") { vm.clearClipboard(); onDismiss() }
            }
        }

        Destination(dest, blocked)

        DlgBody(padded = false) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
                if (tray.isEmpty) {
                    Text(
                        "Copy or Move something and it lands here. Pasted things stay as history until you clear them.",
                        fontSize = 11.5.sp,
                        color = colors.fg3,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 14.dp),
                    )
                }
                if (fresh.isNotEmpty()) GroupLabel("NEW", "not pasted yet")
                for (b in fresh) {
                    ClipRow(
                        batch = b,
                        dest = dest,
                        gone = false,
                        selecting = selecting,
                        selected = b.id in chosen,
                        filesOpen = b.id in open,
                        vm = vm,
                        onTap = {
                            if (selecting) chosen = chosen.toggled(b.id) else open = open.toggled(b.id)
                        },
                        onGo = { go(b) },
                        onCopyInstead = { go(b, copyInstead = true) },
                        onToggleFiles = { open = open.toggled(b.id) },
                        onOpenPlace = { vm.openClipPlace(pane, b.id); onDismiss() },
                    )
                }
                if (history.isNotEmpty()) GroupLabel("HISTORY", "pasted before · tap to use again")
                for (b in history) {
                    val isGone = b.id in gone
                    ClipRow(
                        batch = b,
                        dest = dest,
                        gone = isGone,
                        selecting = selecting,
                        selected = b.id in chosen,
                        filesOpen = b.id in open,
                        vm = vm,
                        onTap = {
                            if (selecting) { if (!isGone) chosen = chosen.toggled(b.id) } else open = open.toggled(b.id)
                        },
                        onGo = { if (isGone) vm.dropClipBatch(b.id) else go(b) },
                        onCopyInstead = { go(b, copyInstead = true) },
                        onToggleFiles = { open = open.toggled(b.id) },
                        onOpenPlace = { vm.openClipPlace(pane, b.id); onDismiss() },
                    )
                }
            }
        }

        when {
            selecting -> {
                val picked = tray.batches.filter { it.id in chosen }
                val n = picked.sumOf { it.pickedCount }
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BigButton("Cancel", null, ghost = true) { selecting = false; chosen = emptySet() }
                    BigButton(
                        title = when {
                            picked.isEmpty() -> "Tick what to bring here"
                            blocked != null -> "Read-only here"
                            else -> "Do $n here"
                        },
                        detail = tray.summary(picked).ifEmpty { null },
                        enabled = picked.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (blocked != null) vm.toast(blocked) else { vm.pasteChosen(pane, chosen); onDismiss() }
                    }
                }
            }
            tray.worthPastingAll -> {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                    BigButton(
                        title = if (blocked == null) "Bring all ${tray.freshPicked} new here" else "Read-only here",
                        detail = if (blocked == null) tray.allSummary() else null,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (blocked == null) { vm.pasteInto(pane); onDismiss() } else vm.toast(blocked)
                    }
                }
            }
            else -> Spacer(Modifier.height(14.dp))
        }
    }
}

private fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id

/** `.tbtn` - a header action that is words, not a button shape. */
@Composable
private fun TextButton(label: String, on: Boolean = false, onClick: () -> Unit) {
    val colors = Filet.colors
    Text(
        label,
        fontSize = 12.sp,
        color = if (on) colors.accent else colors.fg2,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** `.grp` - a group's name and what it means, on one baseline. */
@Composable
private fun GroupLabel(name: String, meaning: String) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = colors.fg3)
        Text(meaning, fontSize = 10.5.sp, color = colors.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `.big` - the footer's button: a title and, under it, what it is about to do. */
@Composable
private fun BigButton(
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
    ghost: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val ink = if (ghost) colors.fg2 else MaterialTheme.colorScheme.onPrimary
    Column(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(13.dp))
            .background(if (ghost) colors.high else colors.accent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (ghost) 16.dp else 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
        if (detail != null) {
            Text(detail, fontSize = 11.sp, color = ink.copy(alpha = 0.72f), maxLines = 1)
        }
    }
}

/**
 * Where the sheet will paste.
 *
 * One line, monospaced, truncated at the START - the end of a path is the part that says
 * which folder, and a path clipped at the right reads `/storage/emulated/0/Andro…` on every
 * destination there is.
 */
@Composable
private fun Destination(dest: VPath, blocked: String?) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.sunken)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (blocked == null) "PASTING INTO" else "READ-ONLY",
            fontSize = 8.5.sp,
            letterSpacing = 1.1.sp,
            color = if (blocked == null) colors.fg3 else colors.warn,
        )
        Spacer(Modifier.width(8.dp))
        Icon(FiletIcons.Folder, null, tint = colors.fg3, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            // `TextOverflow.StartEllipsis` is API 35 on the platform side and this app floors
            // far below it, so the head is dropped here instead. 42 is what fits a 423dp
            // phone at 10.5sp monospace with the label beside it.
            headTrimmed(dest.path, 42),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.fg2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** `/storage/emulated/0/Download/invoices` -> `…/0/Download/invoices`. Pure, so it is tested. */
fun headTrimmed(path: String, max: Int): String =
    if (path.length <= max) path else "…" + path.takeLast(max - 1)

/** A folder as a row names it: its own name, or the path for a root, which has none. */
private fun folderName(p: VPath): String = p.name.ifEmpty { p.path }

/**
 * What a row's second line says. Pure, so the three cases cannot drift into one.
 *
 * New: where it came from. A move in history: where its files are now. A copy in history: how
 * often it has been used and where last.
 */
fun clipWhere(b: ClipBatch): String {
    val from = b.from?.let(::folderName) ?: "${b.items.size} places"
    return when {
        b.fresh -> "from $from"
        b.isMove -> "now in $from"
        else -> "pasted ${b.pastedTo.size}× · last to " + (b.pastedTo.lastOrNull()?.let(::folderName) ?: from)
    }
}

/** The row's tile glyph: the kind of its first file, by extension, as the list draws it. */
private fun iconOf(path: VPath): ImageVector {
    val dot = path.name.lastIndexOf('.')
    val ext = if (dot <= 0 || dot == path.name.length - 1) "" else path.name.substring(dot + 1).lowercase()
    return when (FileTypes.kindOf(ext)) {
        TypeKind.IMAGE -> FiletIcons.Image
        TypeKind.VIDEO -> FiletIcons.Video
        TypeKind.AUDIO -> FiletIcons.Audio
        TypeKind.ARCHIVE -> FiletIcons.Zip
        TypeKind.APK -> FiletIcons.Apk
        TypeKind.DOC -> FiletIcons.Doc
        TypeKind.CODE -> FiletIcons.Code
        else -> FiletIcons.File
    }
}

/**
 * `.crow` - one batch.
 *
 * A history row whose files are all gone stays, greyed, and its button becomes Remove: the row is
 * still a record of what was done, and the one thing it can usefully do is go.
 */
@Composable
private fun ClipRow(
    batch: ClipBatch,
    dest: VPath,
    gone: Boolean,
    selecting: Boolean,
    selected: Boolean,
    filesOpen: Boolean,
    vm: BrowserViewModel,
    onTap: () -> Unit,
    onGo: () -> Unit,
    onCopyInstead: () -> Unit,
    onToggleFiles: () -> Unit,
    onOpenPlace: () -> Unit,
) {
    val colors = Filet.colors
    val move = batch.isMove
    val accentDim = MaterialTheme.colorScheme.primaryContainer
    val here = batch.isIn(dest)
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(13.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(shape)
            .background(if (selected) colors.sel else colors.raised)
            .border(1.dp, if (selected) colors.accent else Color.Transparent, shape)
            .clickable(onClick = onTap)
            .padding(start = 10.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (selecting) Tick(on = selected, size = 19.dp, check = 12.dp)
        Row(
            Modifier.weight(1f).alpha(if (gone) 0.45f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (move) accentDim else colors.high),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        iconOf(batch.items.first()), null,
                        tint = if (move) colors.accent else colors.fg2,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (batch.size > 1) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp, y = 4.dp)
                            .defaultMinSize(minWidth = 17.dp)
                            .height(17.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp))
                            .padding(horizontal = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${batch.size}", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = colors.fg2)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        append(batch.items.first().name)
                        if (batch.size > 1) {
                            withStyle(SpanStyle(color = colors.fg3, fontSize = 12.sp)) { append("  +${batch.size - 1} more") }
                        }
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (move) "MOVE" else "COPY",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = if (move) colors.accent else colors.fg3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (move) accentDim else colors.high)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Text(
                        if (gone) "the files are no longer there" else clipWhere(batch),
                        fontSize = 10.5.sp,
                        color = colors.fg3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (!selecting) {
            val label = when {
                gone -> "Remove"
                here -> "Here"
                move -> "Move"
                else -> "Paste"
            }
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (move && !gone) colors.accent else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .alpha(if (here && !gone) 0.4f else 1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (move && !gone) accentDim else colors.high)
                    .clickable(enabled = gone || !here, onClick = onGo)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Box {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).clickable { menu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(FiletIcons.More, "More for this row", tint = colors.fg3, modifier = Modifier.size(16.dp))
                }
                PopMenu(expanded = menu, onDismiss = { menu = false }, below = 30.dp) {
                    if (move && !gone) {
                        PopRow(FiletIcons.Copy, "Copy here instead", detail = "Leaves the files where they are.") {
                            menu = false; onCopyInstead()
                        }
                    }
                    PopRow(
                        FiletIcons.Eye,
                        if (filesOpen) "Hide the files" else "Show the files",
                        detail = "Tick which ones go.",
                    ) { menu = false; onToggleFiles() }
                    if (!gone) {
                        val at = batch.from ?: batch.items.first().parent
                        PopRow(FiletIcons.Open, "Open where it is", detail = at?.path) { menu = false; onOpenPlace() }
                    }
                    PopSeparator()
                    PopRow(FiletIcons.Close, "Remove from clipboard") { menu = false; vm.dropClipBatch(batch.id) }
                }
            }
        }
    }

    if (filesOpen && !selecting) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 59.dp, bottom = 8.dp)
                // Capped and scrolled: a batch of four hundred photographs must not produce a
                // sheet taller than the phone with its footer off it.
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            for (path in batch.items) {
                FileRow(
                    path = path,
                    picked = path in batch.picked,
                    onToggle = { vm.pickClipFile(batch.id, path, path !in batch.picked) },
                    onDrop = { vm.dropClipFile(batch.id, path) },
                )
            }
        }
    }
}

/** `.cbox` - a square tick. */
@Composable
private fun Tick(on: Boolean, size: androidx.compose.ui.unit.Dp, check: androidx.compose.ui.unit.Dp) {
    val colors = Filet.colors
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(5.dp))
            .background(if (on) colors.accent else Color.Transparent)
            .border(1.5.dp, if (on) colors.accent else colors.fg3, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(FiletIcons.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(check))
    }
}

/** `.frow` - one file in an opened row: a tick, the name, and a cross that takes it off for good. */
@Composable
private fun FileRow(path: VPath, picked: Boolean, onToggle: () -> Unit, onDrop: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tick(on = picked, size = 17.dp, check = 11.dp)
        Spacer(Modifier.width(9.dp))
        // A name, so never dotted: it gives up size, then fades before its extension. See ListName.
        ListNameText(
            path.name,
            if (picked) colors.fg2 else colors.fg3,
            startSp = 11.5f,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onDrop),
            contentAlignment = Alignment.Center,
        ) {
            Icon(FiletIcons.Close, "Take this file off the clipboard", tint = colors.fg3, modifier = Modifier.size(11.dp))
        }
    }
}

/** "1 batch" / "3 batches", because "1 batches" is the tell that nobody read the string. */
fun plural(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"

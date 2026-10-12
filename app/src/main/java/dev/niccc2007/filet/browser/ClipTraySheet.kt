package dev.niccc2007.filet.browser

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ops.ClipBatch
import dev.niccc2007.filet.ops.ClipTray
import dev.niccc2007.filet.ops.PendingOp
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgForm
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VPath

/**
 * The clipboard, opened.
 *
 * ## What the first version got wrong
 *
 * It said "Move — 3 items" and nothing else. There was no way to see WHICH three and no way
 * to drop one you did not mean to pick up, which makes the count the only thing there is: a
 * summary of something that cannot be opened. Reported as exactly that, twice - *"that
 * clipboard, I must be able to see the individual files"* and *"of course I want to be able
 * to pick which file I want to paste first, not a bulk paste"*.
 *
 * So a batch is a disclosure, the same shape the APK inspector uses for its contents, and
 * every file inside it has a tick. Ticking is non-destructive: the batch keeps everything and
 * the button pastes what is ticked. The × beside a file is the other intention and the two are
 * deliberately different - unticking is "not this time", × is "I did not mean to pick this up".
 *
 * ## Why one loud button
 *
 * Round one put a Paste on every card at the same volume as the one at the bottom, which makes
 * none of them the answer. The per-batch button is quiet and bordered; the full-width accent
 * button at the foot is the one that acts on everything.
 *
 * ## The destination is the subject
 *
 * A sheet that pastes "here" with no statement of where here is, is a guess in split view. It
 * is one line, rendered the way the path strip reads everywhere else in the app - it had grown
 * into three (a label, the folder in bold, the path under it), which says the same thing twice
 * and takes a list row's height to do it. The path IS the name.
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
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("Clipboard", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(9.dp))
            Text(
                "${plural(tray.size, "batch", "batches")}  ·  " +
                    plural(tray.totalItems, "item", "items"),
                fontSize = 11.5.sp,
                color = colors.fg3,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Clear all",
                fontSize = 11.5.sp,
                color = colors.fg3,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.clearClipboard(); onDismiss() }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }

        Destination(dest, blocked)

        DlgBody(padded = false) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (tray.isEmpty) {
                    Text(
                        "Nothing copied yet. Copy or Move as often as you like " +
                            "— it all stacks up here.",
                        fontSize = 11.5.sp,
                        color = colors.fg3,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                    )
                }
                for (batch in tray.batches) {
                    BatchCard(
                        batch = batch,
                        blocked = blocked != null,
                        onPaste = { vm.pasteBatch(pane, batch.id); onDismiss() },
                        onDrop = { vm.dropClipBatch(batch.id) },
                        onPick = { path, on -> vm.pickClipFile(batch.id, path, on) },
                        onPickAll = { on -> vm.pickClipAll(batch.id, on) },
                        onDropFile = { path -> vm.dropClipFile(batch.id, path) },
                    )
                }
            }
        }

        // The one loud button. Offered only when there is more than one batch contributing -
        // with a single batch it would be the same press as that batch's own, twice.
        if (tray.worthPastingAll) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (blocked == null) colors.accent else colors.high)
                        .clickable {
                            if (blocked == null) { vm.pasteInto(pane); onDismiss() } else vm.toast(blocked)
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (blocked == null) "Paste all ${tray.totalPicked} here" else "Read-only here",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (blocked == null) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                    )
                    if (blocked == null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tray.allSummary(),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f),
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.height(12.dp))
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
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp)
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

@Composable
private fun BatchCard(
    batch: ClipBatch,
    blocked: Boolean,
    onPaste: () -> Unit,
    onDrop: () -> Unit,
    onPick: (VPath, Boolean) -> Unit,
    onPickAll: (Boolean) -> Unit,
    onDropFile: (VPath) -> Unit,
) {
    val colors = Filet.colors
    var open by remember(batch.id) { mutableStateOf(false) }
    val spin by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "chevron")
    val move = batch.op == PendingOp.MOVE

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(colors.raised)
            .border(1.dp, if (open) colors.lineSoft else Color.Transparent, RoundedCornerShape(13.dp))
            .animateContentSize(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (move) colors.accent.copy(alpha = 0.18f) else colors.high),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (move) FiletIcons.Move else FiletIcons.Copy, null,
                    tint = if (move) colors.accent else colors.fg2,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // What it will DO, not what it holds: the two differ the moment a tick
                        // comes off, and the first number is the one the button acts on.
                        if (batch.allPicked) plural(batch.size, "item", "items")
                        else "${batch.pickedCount} of ${batch.size}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // A copy survives being pasted, so it can go in several places - and
                    // saying where it has already been is what makes "it is still loaded"
                    // obvious rather than something to discover.
                    if (batch.pastedTo.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "pasted ${batch.pastedTo.size}×",
                            fontSize = 9.5.sp,
                            color = colors.fg3,
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .border(1.dp, colors.lineSoft, RoundedCornerShape(5.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (move) "MOVE" else "COPY",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = if (move) colors.accent else colors.fg3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (move) colors.accent.copy(alpha = 0.18f) else colors.high)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        batch.from?.name?.ifEmpty { batch.from.path }
                            ?: "${batch.items.size} places",
                        fontSize = 10.5.sp,
                        color = colors.fg3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                if (move) "Move" else "Paste",
                fontSize = 11.sp,
                color = if (blocked || batch.nothingPicked) colors.fg3 else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, colors.lineSoft, RoundedCornerShape(9.dp))
                    .clickable(enabled = !blocked && !batch.nothingPicked, onClick = onPaste)
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            )
            Box(
                Modifier.size(26.dp).clip(CircleShape).clickable { open = !open },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    FiletIcons.Down,
                    if (open) "Hide the files" else "Show the files",
                    tint = colors.fg3,
                    modifier = Modifier.size(15.dp).graphicsLayer { rotationZ = spin },
                )
            }
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onDrop),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    FiletIcons.Close, "Take this batch off the clipboard",
                    tint = colors.fg3, modifier = Modifier.size(13.dp),
                )
            }
        }

        if (open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                    // Capped and scrolled: a batch of four hundred photographs must not
                    // produce a sheet taller than the phone with the Paste button off it.
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            batch.allPicked -> "all picked"
                            batch.nothingPicked -> "none picked"
                            else -> "${batch.pickedCount} picked"
                        },
                        fontSize = 10.5.sp, color = colors.fg3,
                    )
                    Spacer(Modifier.width(8.dp))
                    Link("none") { onPickAll(false) }
                    Text("  ·  ", fontSize = 10.5.sp, color = colors.fg3)
                    Link("all") { onPickAll(true) }
                }
                for (path in batch.items) {
                    FileRow(
                        path = path,
                        picked = path in batch.picked,
                        onToggle = { onPick(path, path !in batch.picked) },
                        onDrop = { onDropFile(path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 10.5.sp,
        color = Filet.colors.fg2,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun FileRow(path: VPath, picked: Boolean, onToggle: () -> Unit, onDrop: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(17.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (picked) colors.accent else Color.Transparent)
                .border(
                    1.5.dp,
                    if (picked) colors.accent else colors.fg3,
                    RoundedCornerShape(5.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (picked) {
                Icon(
                    FiletIcons.Check, null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(
            path.name,
            fontSize = 11.5.sp,
            color = if (picked) colors.fg2 else colors.fg3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onDrop),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                FiletIcons.Close, "Take this file off the clipboard",
                tint = colors.fg3, modifier = Modifier.size(11.dp),
            )
        }
    }
}

/** "1 batch" / "3 batches", because "1 batches" is the tell that nobody read the string. */
fun plural(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"

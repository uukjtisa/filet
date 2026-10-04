package dev.niccc2007.filet.handlers

import androidx.compose.foundation.clickable
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Audio and video, routed to the screen each one deserves.
 *
 * They used to share one composable and one set of chrome, which is why the music player was a
 * progress line under a filename and the video player was the stock 2010 controller. They have
 * almost nothing in common beyond needing a seekable source, so they are two screens now.
 */
@Composable
fun MediaScreen(vm: BrowserViewModel, node: VNode) {
    if (FileKind.of(node) == FileKind.VIDEO) VideoScreen(vm, node) else AudioScreen(vm, node)
}

/**
 * A hex viewer that never loads the whole file.
 *
 * Rows are decoded from a page cache on demand, so a 4 GB disk image opens instantly and
 * scrolls without a 4 GB allocation. This is also the honest fallback for anything the text
 * editor refuses.
 */
@Composable
fun HexViewerScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    val bytesPerRow = 16
    val rows = ((node.size + bytesPerRow - 1) / bytesPerRow).coerceAtLeast(0)
    val cache = remember(node.path) { HexPageCache(vm, node) }

    // Edits are HELD, not applied. A hex editor writes bytes into a file with no idea what they
    // mean - a wrong byte in a header is a file that no longer opens, and it looks exactly like a
    // right one - so nothing touches the file until somebody deliberately saves. See HexEdits.
    var edits by remember(node.path) { mutableStateOf(HexEdits()) }
    var editing by remember(node.path) { mutableStateOf<Pair<Long, Byte>?>(null) }
    val tooBig = node.size > HexEdits.MAX_PATCH_BYTES

    Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = node.name,
            subtitle = buildString {
                append(humanSize(node.size))
                append("  ·  ")
                append(rows)
                append(" rows")
                if (!edits.isEmpty) append("  ·  ${edits.count} edited")
            },
            onClose = { vm.closeHandler() },
        ) {
            ViewerAction(FiletIcons.Back, "Discard edits", enabled = !edits.isEmpty) {
                edits = HexEdits()
            }
            ViewerAction(FiletIcons.Check, "Save", enabled = !edits.isEmpty && !tooBig) {
                vm.saveBytes(node, edits) {
                    edits = HexEdits()
                    cache.forget()
                }
            }
            ViewerAction(FiletIcons.Code, "Open as text") { vm.openWith(node, HandlerId.TEXT, remember = false) }
            ViewerAction(FiletIcons.Info, "Properties") { vm.showProperties(node) }
        }
        LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            items(rows.toInt()) { row ->
                val offset = row.toLong() * bytesPerRow
                var bytes by remember(node.path, row) { mutableStateOf<ByteArray?>(null) }
                LaunchedEffect(node.path, row) {
                    bytes = withContext(Dispatchers.IO) { cache.bytes(offset, bytesPerRow) }
                }
                val raw = bytes
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)) {
                    Text(
                        "%08X".format(offset),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.fg3,
                    )
                    Spacer(Modifier.width(10.dp))
                    Row(Modifier.weight(1f)) {
                        for (i in 0 until bytesPerRow) {
                            val at = offset + i
                            val original = raw?.getOrNull(i)
                            val shown = original?.let { edits.valueAt(at, it) }
                            Text(
                                shown?.let { "%02X".format(it.toInt() and 0xFF) } ?: "  ",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                // An edited byte is marked, because the whole safety of this
                                // screen is that a pending change is visible before it is written.
                                color = if (edits.isEdited(at)) colors.warn
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clickable(enabled = original != null && !tooBig) {
                                        original?.let { editing = at to it }
                                    }
                                    .padding(horizontal = 1.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                        }
                    }
                    Text(
                        buildString {
                            for (i in 0 until bytesPerRow) {
                                val original = raw?.getOrNull(i) ?: break
                                val b = edits.valueAt(offset + i, original).toInt() and 0xFF
                                append(if (b in 32..126) b.toChar() else '.')
                            }
                        },
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.fg2,
                    )
                }
            }
        }
    }

    if (tooBig) {
        // Said once, up front, rather than when a save fails. A patch is read-modify-write
        // because there is no partial write to a share or through a document provider.
        LaunchedEffect(node.path) {
            vm.toast("Too large to edit: saving would rewrite the whole file.")
        }
    }

    editing?.let { (at, original) ->
        ByteEditDialog(
            offset = at,
            original = original,
            current = edits.valueAt(at, original),
            onDismiss = { editing = null },
            onRevert = { edits = edits.revert(at); editing = null },
            onSet = { value -> edits = edits.put(at, value, original); editing = null },
        )
    }
}

/**
 * Set one byte.
 *
 * A dialogue rather than an inline cursor, and that is deliberate: this is the one editor in the
 * app that cannot tell you when you have broken the file, so each change is a decision made on
 * purpose rather than a keystroke that happened while a caret was somewhere unexpected. It shows
 * what is there now beside what it would become, and offers the way back.
 */
@Composable
private fun ByteEditDialog(
    offset: Long,
    original: Byte,
    current: Byte,
    onDismiss: () -> Unit,
    onRevert: () -> Unit,
    onSet: (Byte) -> Unit,
) {
    val colors = Filet.colors
    var typed by remember(offset) { mutableStateOf("%02X".format(current.toInt() and 0xFF)) }
    val parsed = HexEdits.parseByte(typed)
    Dlg(onDismiss = onDismiss, dismissOnScrim = false) {
        DlgHeader(
            FiletIcons.Code,
            "Byte at %08X".format(offset),
            sub = "was %02X".format(original.toInt() and 0xFF),
            onClose = onDismiss,
        )
        DlgBody {
            DlgField("New value", typed, { typed = it.take(2) }, hint = "00 to FF")
            DlgCaption(
                when {
                    parsed == null -> "Two hex digits. Anything else is refused rather than guessed."
                    parsed == original -> "Same as the file holds, so this clears the edit."
                    else -> {
                        val b = parsed.toInt() and 0xFF
                        "Writes %02X".format(b) +
                            (if (b in 32..126) "  ·  '" + b.toChar() + "'" else "") +
                            "  ·  not saved until you save the file"
                    }
                },
            )
        }
        DlgFooter {
            DlgBtn("Revert", BtnKind.PLAIN, fill = false, onClick = onRevert)
            DlgSpacer()
            DlgBtn("Cancel", BtnKind.PLAIN, fill = false, onClick = onDismiss)
            DlgBtn(
                "Set",
                BtnKind.PRIMARY,
                enabled = parsed != null,
                fill = false,
            ) { parsed?.let(onSet) }
        }
    }
}

/**
 * 64 KB pages, one stream re-opened per page.
 *
 * The VFS deliberately offers no random access on every backend (an archive entry has none),
 * so seeking is `skip`, and caching whole pages is what keeps that from being quadratic.
 */
private class HexPageCache(private val vm: BrowserViewModel, private val node: VNode) {
    private val pages = LinkedHashMap<Long, ByteArray>(8, 0.75f, true)
    private val pageSize = 64 * 1024

    /**
     * The raw bytes of one row.
     *
     * Raw rather than the formatted strings the viewer used to take, because an editor has to
     * know what the file actually holds: a pending edit is only meaningful against the original,
     * and a cell cannot offer to revert to a value nobody kept.
     */
    @Synchronized
    fun bytes(offset: Long, count: Int): ByteArray {
        val pageIndex = offset / pageSize
        val page = pages.getOrPut(pageIndex) { readPage(pageIndex) }
        if (pages.size > 6) pages.remove(pages.keys.first())
        val start = (offset % pageSize).toInt()
        val end = minOf(start + count, page.size)
        if (start >= end) return ByteArray(0)
        return page.copyOfRange(start, end)
    }

    /** Drop every page, so the next read sees what was just written. */
    @Synchronized
    fun forget() = pages.clear()

    @Synchronized
    fun row(offset: Long, count: Int): Triple<String, String, String> {
        val pageIndex = offset / pageSize
        val page = pages.getOrPut(pageIndex) { readPage(pageIndex) }
        if (pages.size > 6) pages.remove(pages.keys.first())
        val start = (offset % pageSize).toInt()
        val hex = StringBuilder()
        val ascii = StringBuilder()
        for (i in 0 until count) {
            val idx = start + i
            if (idx >= page.size) break
            val b = page[idx].toInt() and 0xFF
            hex.append("%02X ".format(b))
            ascii.append(if (b in 32..126) b.toChar() else '.')
        }
        return Triple("", hex.toString().trimEnd(), ascii.toString())
    }

    private fun readPage(index: Long): ByteArray = runCatching {
        vm.openRead(node).use { input ->
            var skipped = 0L
            val target = index * pageSize
            while (skipped < target) {
                val n = input.skip(target - skipped)
                if (n <= 0) break
                skipped += n
            }
            val buf = ByteArray(pageSize)
            var read = 0
            while (read < pageSize) {
                val n = input.read(buf, read, pageSize - read)
                if (n < 0) break
                read += n
            }
            if (read == pageSize) buf else buf.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))
}

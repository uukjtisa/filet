package dev.niccc2007.filet.handlers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import dev.niccc2007.filet.ui.dialogs.PanelRow
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    // A video opens into the video screen UNLESS it is the one currently being held as sound
    // only - see `Playback.asAudio`. Collected rather than read, so pressing Watch on the
    // music face swaps this composition to the video screen without reopening anything.
    val asAudio by vm.playback.asAudio.collectAsStateWithLifecycle()
    val heldAsAudio = asAudio && vm.playback.isCurrent(node.path.toString())
    val video = FileKind.of(node) == FileKind.VIDEO && !heldAsAudio

    // The two faces dissolve into each other rather than being swapped.
    //
    // This is the whole of "switching from watch to listen cuts". Each side had already been
    // made to overlap in SOUND - the leaving player keeps going until the arriving one is
    // ready, and the position crosses at that instant - but the picture still changed in one
    // frame, and a hard cut between two full screens reads as the app closing and reopening
    // even when the audio is continuous.
    //
    // `Crossfade` keeps both compositions alive for the length of the fade, which is exactly
    // what the hand-off needs: the video is still decoding while the music face comes up over
    // it, and the music is still playing while the video screen behind it prepares.
    androidx.compose.animation.Crossfade(
        targetState = video,
        animationSpec = androidx.compose.animation.core.tween(SWAP_MS),
        label = "watch-listen",
    ) { showVideo ->
        if (showVideo) VideoScreen(vm, node) else AudioScreen(vm, node)
    }
}

/**
 * How long watch and listen take to trade places.
 *
 * Long enough to read as a dissolve rather than a cut, and comfortably longer than a local
 * file's `prepare()` - so the sound has usually already crossed over by the time the picture
 * has. Short enough that it never feels like waiting for something.
 */
private const val SWAP_MS = 380

/**
 * The hex editor: a file's bytes, sixteen to a row, without ever loading the whole file.
 *
 * Rows are read from a page cache on demand, so a 4 GB disk image opens at once and scrolls
 * without a 4 GB allocation. Each row is one line of text with a fixed height, built once and
 * kept until an edit lands in it. The caret and the selection are painted behind the text, not
 * built into it, so moving them redraws the rows without rebuilding any. Rows keep their full
 * width and the view scrolls sideways. This is also the honest fallback for anything the text
 * editor refuses.
 */
@Composable
fun HexViewerScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    // The palette's dim accent, which the theme hands to Material as its primary container.
    val caretFill = MaterialTheme.colorScheme.primaryContainer
    val layout = remember(node.size) { HexLayout.forSize(node.size) }
    val rows = ((node.size + HexLayout.BYTES - 1) / HexLayout.BYTES).coerceAtLeast(0)
    val cache = remember(node.path) { HexPageCache(vm, node) }
    // Raised after a save, so every row reads the file again.
    var version by remember(node.path) { mutableIntStateOf(0) }

    // Edits are HELD, not applied. A hex editor writes bytes into a file with no idea what they
    // mean - a wrong byte in a header is a file that no longer opens, and it looks exactly like a
    // right one - so nothing touches the file until somebody deliberately saves. See HexEdits.
    var edits by remember(node.path) { mutableStateOf(HexEdits()) }
    var editing by remember(node.path) { mutableStateOf<Pair<Long, Byte>?>(null) }
    val tooBig = node.size > HexEdits.MAX_PATCH_BYTES

    // The caret, and the other end of a selection: -1 while there is none.
    var caret by remember(node.path) { mutableLongStateOf(0L) }
    var anchor by remember(node.path) { mutableLongStateOf(-1L) }

    // The text editor's history. Its steps hold shown values; [known] is what the file holds at
    // every offset ever edited, so a step is put back without reading the file again.
    val history = remember(node.path) { EditHistory<ByteEdit>() }
    val known = remember(node.path) { HashMap<Long, Byte>() }
    var canUndo by remember(node.path) { mutableStateOf(false) }
    var canRedo by remember(node.path) { mutableStateOf(false) }

    fun sync() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    fun record(step: ByteEdit) {
        history.push(step, android.os.SystemClock.uptimeMillis())
        edits = step.applyTo(edits) { known.getValue(it) }
        sync()
    }

    fun set(at: Long, value: Byte, file: Byte) {
        val before = edits.valueAt(at, known.getOrPut(at) { file })
        if (value != before) record(ByteEdit(listOf(ByteEdit.Change(at, before, value))))
    }

    fun step(back: Boolean) {
        val s = (if (back) history.undo() else history.redo()) ?: return
        edits = (if (back) s.inverse() else s).applyTo(edits) { known.getValue(it) }
        sync()
    }

    /** Every pending byte back to the file's, as one step that Undo brings back. */
    fun discard() {
        if (edits.isEmpty) return
        record(ByteEdit(edits.changes.entries.sortedBy { it.key }.map { (at, v) -> ByteEdit.Change(at, v, known.getValue(at)) }))
    }

    /** Over the file. Whatever changed while it was written stays pending. */
    fun saveOver(after: () -> Unit = {}) {
        val saving = edits
        vm.saveBytes(node, saving) {
            edits = ByteEdit.afterSave(saving, edits, known)
            history.markClean()
            cache.forget()
            version++
            after()
        }
    }

    /** Into a new file chosen with Filet's picker, which then opens here. */
    fun saveAsNew() {
        val saving = edits
        vm.chooseSaveAs(node, node.extension) { target ->
            vm.saveBytesAs(node, saving, target) { made -> vm.switchViewer(made, HandlerId.HEX) }
        }
    }

    // Replace is offered only where the file can be written; Save as new always is.
    val blocked = remember(node.path) { node.path.parent?.let { vm.writeBlockReason(it) } }

    val guard = rememberLeaveGuardTo(
        dirty = !edits.isEmpty,
        name = node.name,
        leave = vm::closeHandler,
        save = { after -> saveOver(after) },
    )
    val leave = remember(guard) { { guard(null) } }
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val rowWidth = remember(layout, density) {
        with(density) { measurer.measure(layout.header(), style).size.width.toDp() } + 16.dp
    }
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
            onClose = leave,
        ) {
            ViewerAction(FiletIcons.Undo, "Undo", enabled = canUndo) { step(back = true) }
            ViewerAction(FiletIcons.Redo, "Redo", enabled = canRedo) { step(back = false) }
            SaveChoice(
                label = "Save",
                enabled = !tooBig,
                quiet = edits.isEmpty,
                replaceDetail = if (!edits.isEmpty && blocked == null) "Over " + node.name else null,
                newDetail = "Choose a folder and a name; this file stays as it is",
                onReplace = { saveOver() },
                onNew = { saveAsNew() },
            )
            // Page and Code are the other screen, which reads the file as saved: an unsaved
            // byte asks first, as leaving does. Details is in the file rows below.
            ViewerMore(vm, node, unsaved = !edits.isEmpty) { close ->
                ModeRows(ViewModes.of(node.extension), ViewMode.HEX, close) { m ->
                    guard { vm.switchViewer(node, ViewModes.handlerFor(m, node.extension)) }
                }
                if (!edits.isEmpty) {
                    PanelRow("Discard edits", colors.accent, null, FiletIcons.Undo) {
                        close()
                        discard()
                    }
                }
            }
        }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background),
        ) {
            val width = maxOf(rowWidth, maxWidth)
            // The house sideways scroll, so the edge says there is more of the row.
            dev.niccc2007.filet.ui.HScroll(
                ground = MaterialTheme.colorScheme.background,
                verticalAlignment = Alignment.Top,
            ) {
            Column(Modifier.fillMaxHeight()) {
                Text(
                    layout.header(),
                    style = style,
                    color = colors.fg3,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.width(width).padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(width).height(1.dp).background(colors.lineSoft))
                LazyColumn(Modifier.width(width).weight(1f)) {
                    items(rows.toInt()) { row ->
                        val offset = row.toLong() * HexLayout.BYTES
                        var raw by remember(version, row) { mutableStateOf(cache.peek(offset, HexLayout.BYTES)) }
                        if (raw == null) {
                            LaunchedEffect(version, row) {
                                raw = withContext(Dispatchers.IO) { cache.bytes(offset, HexLayout.BYTES) }
                            }
                        }
                        // This row's pending values, worked out again only when an edit lands in it.
                        val mine by remember(row) {
                            derivedStateOf {
                                val c = edits.changes
                                if (c.isEmpty()) null
                                else List(HexLayout.BYTES) { c[offset + it] }.takeIf { l -> l.any { it != null } }
                            }
                        }
                        val bytes = raw
                        val line = remember(bytes, mine, colors) {
                            val shown = bytes?.let { b -> ByteArray(b.size) { mine?.get(it) ?: b[it] } } ?: ByteArray(0)
                            buildAnnotatedString {
                                append(layout.text(offset, shown))
                                addStyle(SpanStyle(color = colors.fg3), 0, layout.digits)
                                addStyle(SpanStyle(color = colors.fg2), layout.ascii, layout.chars)
                                // A pending byte is marked in both columns: the whole safety of
                                // this screen is that a change is visible before it is written.
                                mine?.forEachIndexed { i, v ->
                                    if (v != null) {
                                        val warn = SpanStyle(color = colors.warn)
                                        addStyle(warn, layout.hexAt(i), layout.hexAt(i) + 2)
                                        addStyle(warn, layout.ascii + i, layout.ascii + i + 1)
                                    }
                                }
                            }
                        }
                        Text(
                            line,
                            style = style,
                            color = MaterialTheme.colorScheme.onSurface,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .height(HEX_ROW)
                                .drawBehind {
                                    // Read here, so a caret move redraws rows and rebuilds none.
                                    val cw = size.width / layout.chars
                                    val c = caret
                                    val a = anchor
                                    val last = offset + HexLayout.BYTES - 1
                                    fun lit(col: Int, chars: Int, fill: androidx.compose.ui.graphics.Color) =
                                        drawRect(fill, Offset(col * cw, 0f), Size(chars * cw, size.height))
                                    if (a >= 0) {
                                        val lo = minOf(a, c)
                                        val hi = maxOf(a, c)
                                        if (hi >= offset && lo <= last) {
                                            val l = (maxOf(lo, offset) - offset).toInt()
                                            val r = (minOf(hi, last) - offset).toInt()
                                            lit(layout.hexAt(l), layout.hexAt(r) + 2 - layout.hexAt(l), colors.sel)
                                            lit(layout.ascii + l, r - l + 1, colors.sel)
                                        }
                                    }
                                    if (c in offset..last) {
                                        val i = (c - offset).toInt()
                                        val mark = 2.dp.toPx()
                                        for ((at, chars) in arrayOf(layout.hexAt(i) to 2, layout.ascii + i to 1)) {
                                            lit(at, chars, caretFill)
                                            drawRect(colors.accent, Offset(at * cw, size.height - mark), Size(chars * cw, mark))
                                        }
                                    }
                                }
                                .pointerInput(row, bytes) {
                                    // The byte under [p], which may be in a row above or below
                                    // this one while a selection is dragged.
                                    fun byteAt(p: Offset): Long? {
                                        val col = layout.columnAt((p.x * layout.chars / size.width).toInt()) ?: return null
                                        val r = row + kotlin.math.floor(p.y / size.height).toInt()
                                        return (r.toLong() * HexLayout.BYTES + col).takeIf { it in 0 until node.size }
                                    }
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        var scrolled = false
                                        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                            waitForUpOrCancellation().also { if (it == null) scrolled = true }
                                        }
                                        if (scrolled) return@awaitEachGesture
                                        val hit = byteAt(down.position) ?: return@awaitEachGesture
                                        if (up != null) {
                                            // A tap moves the caret; a tap on the caret changes
                                            // that byte, one deliberate decision at a time.
                                            val file = bytes?.getOrNull((hit - offset).toInt())
                                            if (hit == caret && anchor < 0) {
                                                if (!tooBig && file != null) editing = hit to file
                                            } else {
                                                caret = hit
                                                anchor = -1
                                            }
                                            return@awaitEachGesture
                                        }
                                        // Held: select from here, and drag to grow it.
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        anchor = hit
                                        caret = hit
                                        drag(down.id) { change ->
                                            byteAt(change.position)?.let { caret = it }
                                            change.consume()
                                        }
                                    }
                                }
                                .wrapContentHeight(Alignment.CenterVertically),
                        )
                    }
                }
            }
            }
        }
        // Read inside, so a caret move recomposes the status line and nothing above it.
        HexStatus(cache, { caret }, { anchor }, edits, version, tooBig, layout)
    }

    if (tooBig) {
        // Said once, up front, rather than when a save fails. A patch is read-modify-write
        // because there is no partial write to a share or through a document provider.
        LaunchedEffect(node.path) {
            vm.toast("Too large to edit: saving would rewrite the whole file.")
        }
    }

    editing?.let { (at, file) ->
        val original = known[at] ?: file
        ByteEditDialog(
            offset = at,
            original = original,
            current = edits.valueAt(at, original),
            onDismiss = { editing = null },
            onRevert = { set(at, original, file); editing = null },
            onSet = { value -> set(at, value, file); editing = null },
        )
    }
}

/** One row of the hex editor, fixed so every row is the same height and a drag can count rows. */
private val HEX_ROW = 16.dp

/** Under the rows: where the caret is and what it holds, or how much is selected. */
@Composable
private fun HexStatus(
    cache: HexPageCache,
    caretAt: () -> Long,
    anchorAt: () -> Long,
    edits: HexEdits,
    version: Int,
    tooBig: Boolean,
    layout: HexLayout,
) {
    val colors = Filet.colors
    val caret = caretAt()
    val anchor = anchorAt()
    val value = remember(caret, edits, version) {
        cache.peek(caret, 1)?.firstOrNull()?.let { edits.valueAt(caret, it).toInt() and 0xFF }
    }
    val strong = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    Column {
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.fg2)
            Text(
                buildAnnotatedString {
                    append("Offset ")
                    withStyle(strong) { append(layout.text(caret, ByteArray(0)).take(layout.digits)) }
                },
                style = mono,
            )
            Text(
                buildAnnotatedString {
                    append("Byte ")
                    withStyle(strong) { append(value?.let { "%02X".format(it) } ?: "--") }
                },
                style = mono,
            )
            val n = if (anchor < 0) 0L else kotlin.math.abs(caret - anchor) + 1
            Text(
                when {
                    n == 1L -> "1 byte selected"
                    n > 1L -> "$n bytes selected"
                    tooBig -> "too large to edit"
                    else -> "tap again to edit"
                },
                style = mono.copy(color = colors.fg3),
                maxLines = 1,
            )
        }
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

    /** The bytes of one row if its page is already held, without reading anything. */
    @Synchronized
    fun peek(offset: Long, count: Int): ByteArray? {
        val page = pages[offset / pageSize] ?: return null
        val start = (offset % pageSize).toInt()
        val end = minOf(start + count, page.size)
        return if (start >= end) ByteArray(0) else page.copyOfRange(start, end)
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

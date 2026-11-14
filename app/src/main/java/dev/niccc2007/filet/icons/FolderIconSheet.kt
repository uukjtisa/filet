package dev.niccc2007.filet.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.FolderIcon
import dev.niccc2007.filet.browser.PeekKind
import dev.niccc2007.filet.browser.rememberFolderPeek
import dev.niccc2007.filet.ui.HScroll
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgTick
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Change icon, from the selection's menu and bar: which folders asked, and what to offer back
 * once it is done.
 *
 * Held here rather than in the browser's view model because nothing else reads it: the sheet,
 * its Undo and the store it writes to are all in this package, and the browser's part is one
 * line that opens it.
 */
object FolderIconAsk {
    private val _asked = MutableStateFlow<List<VNode>?>(null)
    val asked: StateFlow<List<VNode>?> = _asked.asStateFlow()

    internal class Done(val message: String, val undo: () -> Unit)

    private val _done = MutableStateFlow<Done?>(null)
    internal val done: StateFlow<Done?> = _done.asStateFlow()

    /** Open the sheet for [folders]. Anything in the list that is not a folder is left out. */
    fun open(folders: List<VNode>) {
        val only = folders.filter { it.isDir }
        if (only.isNotEmpty()) _asked.value = only
    }

    internal fun close() {
        _asked.value = null
    }

    internal fun finished(done: Done) {
        _asked.value = null
        _done.value = done
    }

    internal fun clearDone(d: Done) {
        if (_done.value === d) _done.value = null
    }
}

/**
 * The Change icon sheet and its Undo, drawn wherever the app draws its dialogues.
 *
 * @param hidden true while a pick is running, as for the other sheets: the state is kept and the
 *   sheet comes back when the pick ends.
 */
@Composable
fun FolderIconHost(hidden: Boolean) {
    val asked by FolderIconAsk.asked.collectAsState()
    asked?.takeUnless { hidden }?.let { FolderIconSheet(it) }
    val done by FolderIconAsk.done.collectAsState()
    done?.let { UndoToast(it) }
}

/**
 * The sheet: both states of the folder as it will look, the emblems by group, the colours, a
 * picture from inside in front, and back to the default.
 *
 * What is chosen here is the look of every selected folder at once. The preview shows the first
 * of them, with what is really in it where it has anything.
 */
@Composable
private fun FolderIconSheet(folders: List<VNode>) {
    val context = LocalContext.current
    val styles = remember(context) { FolderStyles.of(context) }
    val first = folders.first()
    // Starts from what the first folder wears now, so opening the sheet and pressing Apply
    // changes nothing.
    val start = remember(folders) { lookFor(styles.byPath.value, first.path) }
    var emblem by remember(folders) { mutableStateOf(start.emblem) }
    var colour by remember(folders) { mutableStateOf(start.colour) }
    var cover by remember(folders) { mutableStateOf(start.cover != null) }
    var showEmblem by remember(folders) { mutableStateOf(start.showEmblem) }
    var paint by remember(folders) { mutableStateOf(start.paint) }
    var resetting by remember(folders) { mutableStateOf(false) }
    // Change icon follows the lists: its preview and its tick read Files and folders › Folders.
    val lists = LocalIconStyles.current.files.folders
    var group by remember(folders) { mutableStateOf(ALL) }
    val scope = rememberCoroutineScope()
    val graph = remember(context) { FiletApp.graphOf(context) }

    val peek = rememberFolderPeek(graph.folderPeek, first.path, first.mtime, true).value
    val firstPicture = peek.items.firstOrNull { it.kind == PeekKind.MEDIA }?.node?.name
    val look = FolderLook(emblem, colour, if (cover) firstPicture else null, showEmblem, paint)

    fun changed(block: () -> Unit) {
        block()
        resetting = false
    }

    Dlg(onDismiss = FolderIconAsk::close) {
        DlgHeader(
            FiletIcons.Folder,
            "Change icon",
            sub = namesOf(folders),
            onClose = FolderIconAsk::close,
        )
        DlgBody {
            Preview(first, look, hasThings = peek.items.isNotEmpty(), lists = lists)
            DlgSection("Emblem")
            ShowEmblemTick(showEmblem, outline = lists == FolderDrawing.OUTLINE) { changed { showEmblem = !showEmblem } }
            Spacer(Modifier.height(8.dp))
            GroupChips(group) { group = it }
            Spacer(Modifier.height(6.dp))
            EmblemGrid(emblem, group) { id -> changed { emblem = id } }
            DlgSection("Colour")
            Colours(colour) { c -> changed { colour = c } }
            Spacer(Modifier.height(8.dp))
            // What the colour paints, for this folder. With no choice of its own it follows Folders.
            PaintChips(paint ?: if (lists == FolderDrawing.DRAWN_WHOLE) FolderPaint.WHOLE else FolderPaint.EMBLEM) { p ->
                changed { paint = p }
            }
            Spacer(Modifier.height(12.dp))
            DlgTick(
                on = cover,
                label = "Use a photo from inside",
                sub = "One picture in front, instead of the three Filet would pick",
                onToggle = { changed { cover = !cover } },
            )
            Text(
                "Reset to default",
                fontSize = 12.5.sp,
                color = Filet.colors.accent,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) {
                        val back = DefaultFolders.lookOf(first.path) ?: FolderLook.PLAIN
                        emblem = back.emblem
                        colour = back.colour
                        cover = false
                        showEmblem = true
                        paint = null
                        resetting = true
                    }
                    .padding(vertical = 4.dp),
            )
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = FolderIconAsk::close)
            DlgBtn(
                if (folders.size == 1) "Apply" else "Apply to ${folders.size} folders",
                kind = BtnKind.PRIMARY,
            ) {
                scope.launch {
                    val done = apply(styles, graph.folderPeek, folders, FolderLook(emblem, colour, null, showEmblem, paint), cover, resetting)
                    FolderIconAsk.finished(done)
                }
            }
        }
    }
}

/** Set, or reset, every folder, and say what was done with a way back. */
private suspend fun apply(
    styles: FolderStyles,
    peek: dev.niccc2007.filet.browser.FolderPeek,
    folders: List<VNode>,
    /** Everything chosen but the cover, which is each folder's own first picture. */
    chosen: FolderLook,
    cover: Boolean,
    resetting: Boolean,
): FolderIconAsk.Done {
    val before: Map<VPath, FolderLook?> = folders.associate { it.path to styles.lookOf(it.path) }
    if (resetting) {
        styles.reset(folders.map { it.path })
    } else if (!cover) {
        styles.set(folders.map { it.path }, chosen)
    } else {
        // Each folder's own first picture: the one Filet would have put first anyway.
        for (f in folders) {
            val name = runCatching { peek.of(f.path, f.mtime) }.getOrNull()
                ?.items?.firstOrNull { it.kind == PeekKind.MEDIA }?.node?.name
            styles.set(listOf(f.path), chosen.copy(cover = name))
        }
    }
    val n = folders.size
    val message = when {
        resetting && n == 1 -> "Icon back to the default"
        resetting -> "$n icons back to the default"
        n == 1 -> "Icon changed"
        else -> "Icon changed on $n folders"
    }
    return FolderIconAsk.Done(message) {
        for ((path, was) in before) {
            if (was == null) styles.reset(listOf(path)) else styles.set(listOf(path), was)
        }
    }
}

/** "filet-app, Receipts, Old phone", or the first two and how many more. */
private fun namesOf(folders: List<VNode>): String = when {
    folders.size == 1 -> folders[0].name
    folders.size <= 3 -> "${folders.size} folders: " + folders.joinToString(", ") { it.name }
    else -> "${folders.size} folders: ${folders[0].name}, ${folders[1].name} and ${folders.size - 2} more"
}

/** Both states, side by side: what an empty one looks like, and one with things in it. */
@Composable
private fun Preview(first: VNode, look: FolderLook, hasThings: Boolean, lists: FolderDrawing) {
    val colors = Filet.colors
    val draw = folderDraw(lists, look)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.sunken)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // In the lists' look, so what is picked is what the lists show. See folderDraw.
        when {
            draw.outline -> for (label in listOf("Empty", "With things in it")) {
                PreviewCell(label, Modifier.weight(1f)) {
                    Box(Modifier.size(PREVIEW), contentAlignment = Alignment.Center) {
                        Icon(FiletIcons.Folder, null, tint = colors.accent, modifier = Modifier.size(PREVIEW * 0.6f))
                    }
                }
            }
            draw.legacy -> {
                PreviewCell("Empty", Modifier.weight(1f)) {
                    dev.niccc2007.filet.browser.ClassicFolderIcon(first, PREVIEW, colors.accent, previews = false, emblem = draw.emblem, colour = look.colour, whole = draw.whole)
                }
                PreviewCell("With things in it", Modifier.weight(1f)) {
                    dev.niccc2007.filet.browser.ClassicFolderIcon(first, PREVIEW, colors.accent, previews = hasThings, emblem = draw.emblem, colour = look.colour, whole = draw.whole)
                }
            }
            else -> {
                PreviewCell("Empty", Modifier.weight(1f)) {
                    Canvas(Modifier.size(PREVIEW)) { drawFolder(Inside.Empty, look, whole = draw.whole) }
                }
                PreviewCell("With things in it", Modifier.weight(1f)) {
                    if (hasThings) {
                        FolderIcon(first, PREVIEW, look = look)
                    } else {
                        Canvas(Modifier.size(PREVIEW)) { drawFolder(SAMPLE, look, whole = draw.whole) }
                    }
                }
            }
        }
    }
}

/**
 * Show emblem, under the Emblem heading. Per folder, on by default. Greyed on Legacy outline,
 * which shows no emblems, still showing what it keeps. The lines are the Legacy icons mock's.
 */
@Composable
internal fun ShowEmblemTick(on: Boolean, outline: Boolean = false, onToggle: () -> Unit) {
    DlgTick(
        on = on,
        label = "Show emblem",
        sub = when {
            outline -> "Legacy outline shows no emblems. Yours is kept."
            on -> "Untick to hide it on this folder. It stays saved."
            else -> "Hidden on this folder. Your emblem is kept."
        },
        enabled = !outline,
        onToggle = onToggle,
    )
}

/** Emblem · Whole folder: what the colour paints on this folder. */
@Composable
internal fun PaintChips(paint: FolderPaint, onPick: (FolderPaint) -> Unit) {
    val colors = Filet.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((p, label) in listOf(FolderPaint.EMBLEM to "Emblem", FolderPaint.WHOLE to "Whole folder")) {
            val on = p == paint
            Text(
                label,
                fontSize = 11.5.sp,
                color = if (on) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) colors.accent else colors.sunken)
                    .clickable(role = Role.Button) { onPick(p) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PreviewCell(label: String, modifier: Modifier, art: @Composable () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        art()
        Text(label, fontSize = 11.sp, color = Filet.colors.fg3)
    }
}

@Composable
internal fun GroupChips(group: String, onPick: (String) -> Unit) {
    val colors = Filet.colors
    HScroll(spacing = 4.dp) {
        for (g in listOf(ALL) + EMBLEM_GROUPS) {
            val on = g == group
            Text(
                g,
                fontSize = 11.5.sp,
                color = if (on) ON_ACCENT else colors.fg2,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) colors.accent else colors.high)
                    .semantics { selected = on }
                    .clickable(role = Role.Tab) { onPick(g) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

/** No emblem first, then the group's emblems, eight to a row, scrolling past three rows. */
@Composable
internal fun EmblemGrid(chosen: String?, group: String, onPick: (String?) -> Unit) {
    val shown: List<Emblem?> = listOf(null) + EMBLEMS.filter { group == ALL || it.group == group }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 158.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in shown.chunked(PER_ROW)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (e in row) {
                    EmblemCell(e, on = e?.id == chosen, Modifier.weight(1f)) { onPick(e?.id) }
                }
                repeat(PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun EmblemCell(e: Emblem?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = Filet.colors
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(if (on) colors.sel else colors.sunken)
            .border(1.5.dp, if (on) colors.accent else Color.Transparent, shape)
            .semantics {
                contentDescription = e?.label ?: "No emblem"
                selected = on
            }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(6.dp),
    ) {
        val fg2 = colors.fg2
        val fg3 = colors.fg3
        Canvas(Modifier.fillMaxSize()) {
            val k = size.minDimension / 24f
            withTransform({ scale(k, k, Offset.Zero) }) {
                if (e == null) {
                    drawLine(fg3, Offset(6f, 6f), Offset(18f, 18f), strokeWidth = 1.6f, cap = StrokeCap.Round)
                } else {
                    glyph(e.parts, ink(fg2), fg2, fg3)
                }
            }
        }
    }
}

/**
 * The colours, as swatches.
 *
 * @param tone the build's turn of Filet's blues, for a swatch of what a home-screen icon will be;
 *   none for a folder in the app, which keeps the app's own blue.
 */
@Composable
internal fun Colours(chosen: String, tone: BrandTone? = null, onPick: (String) -> Unit) {
    val colors = Filet.colors
    HScroll(spacing = 3.dp) {
        for (id in COLOUR_IDS) {
            val a = accent(id)
            val light = tone?.of(a.light) ?: a.light
            val deep = tone?.of(a.deep) ?: a.deep
            val on = id == chosen
            Box(
                Modifier
                    .size(30.dp)
                    .border(2.dp, if (on) colors.accent else Color.Transparent, CircleShape)
                    .semantics {
                        contentDescription = COLOUR_NAMES[id] ?: id
                        selected = on
                    }
                    .clip(CircleShape)
                    .clickable(role = Role.RadioButton) { onPick(id) }
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(light, deep))),
            )
        }
    }
}

/** What was done, with Undo, for long enough to reach it. */
@Composable
private fun UndoToast(done: FolderIconAsk.Done) {
    LaunchedEffect(done) {
        delay(UNDO_MS)
        FolderIconAsk.clearDone(done)
    }
    // Where the app's own messages sit: above the navigation bar and clear of the bottom bar.
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 70.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(done.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.inverseOnSurface)
            Spacer(Modifier.width(6.dp))
            Text(
                "Undo",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.inversePrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) {
                        done.undo()
                        FolderIconAsk.clearDone(done)
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

internal const val ALL = "All"
private const val PER_ROW = 8
private val PREVIEW: Dp = 104.dp

/** Long enough to read the line and reach the button; the app's plain messages stay 1.9s. */
private const val UNDO_MS = 5000L

private val SAMPLE = Inside.Things(listOf(Thing.File("source"), Thing.File("text"), Thing.Folder(true)))

/** Ink on the accent colour, as the dialogue kit's ticks use it. */
private val ON_ACCENT = Color(0xFF10100F)

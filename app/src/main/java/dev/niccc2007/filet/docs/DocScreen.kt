package dev.niccc2007.filet.docs

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.handlers.ViewerAction
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.dialogs.DlgTick
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One screen for every document format, because they are all read the same way.
 *
 * ## What this is for
 *
 * A reader turns its format into [Doc] and this draws it. So Markdown, DOCX and PPTX share
 * their scrolling, their typography, their tables, their empty state and their theming, and a
 * fix to any of those is one fix. The alternative - a screen per format - is four
 * implementations of the same reading experience that drift apart one change at a time.
 *
 * ## It reflows, deliberately
 *
 * No page geometry, no columns, no original fonts. A phone is 400 dp wide and an A4 page is
 * not, so a document reproduced faithfully is a document read at 40% zoom with horizontal
 * scrolling - which is what every "document viewer" that tries this feels like. Reflowed text
 * in the app's own type is readable, and being honestly a reader beats being a bad renderer.
 *
 * What does NOT reflow is code and wide tables, which scroll sideways in their own scrollers: a
 * wrapped line of code is worse than a scrolled one.
 *
 * ## What it draws from the file
 *
 * Everything the model carries: heading sizes with GitHub's rules under the first two levels,
 * inline emphasis, colour where it reads on the theme, alignment, quotes and alerts as rules
 * down the edge, lists with bullets by depth and right-aligned numbers, task boxes, tables set
 * to fit, highlighted code, pictures inside the text, folds, front matter as a card, and a deck
 * as one card per slide with its notes folded at the foot. A `Doc` from a reader that sets none
 * of the newer fields draws exactly as it did before they existed.
 *
 * ## How it is read
 *
 * By the reading settings, which are the same for every document - see [ReadingSettings]: a
 * text size that IS the pinch, a readable column, a table mode and whether web pictures load.
 */
@Composable
fun DocReader(
    doc: Doc,
    modifier: Modifier = Modifier,
    key: Any? = null,
    place: DocPlace? = null,
    page: DocPageState? = null,
) {
    val colors = Filet.colors
    if (doc.empty && doc.note == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing to read in this one", fontSize = 12.sp, color = colors.fg3)
        }
        return
    }
    val state = page ?: remember(key) { DocPageState() }
    val context = LocalContext.current
    val prefs = remember { FiletApp.graphOf(context).prefs }
    val reading by prefs.reading.collectAsState()
    val frames = remember(doc) { DocLayout.slideFrames(doc.blocks) }
    val background = MaterialTheme.colorScheme.background
    // The raised ground is too close to the page in the light palettes to read as a card edge.
    val card = colors.high
    val dark = background.luminance() < 0.5f
    val list = state.list
    val scope = rememberCoroutineScope()
    val base = LocalDensity.current

    // The zoom is the reading size, so it is the same number for every document and outlives
    // this screen. Held as its own state, read only inside the items, so a pinch recomposes the
    // blocks on screen and nothing else. See [PinchFrame] for why it moves once a frame.
    val zoom = remember { mutableFloatStateOf(reading.zoom) }
    val pinch = remember { PinchFrame() }
    val ticks = remember { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(reading.size) {
        if (!pinch.active && Reading.sizeOf(zoom.floatValue) != reading.size) zoom.floatValue = reading.zoom
    }
    LaunchedEffect(list) {
        for (tick in ticks) {
            withFrameNanos { }
            pinch.take(zoom.floatValue)?.let { step ->
                zoom.floatValue = step.zoom
                keepUnder(list, step.focalY, step.ratio)
            }
            if (pinch.settled()) prefs.setReadSize(Reading.sizeOf(zoom.floatValue))
        }
    }

    // Folds open as their `<details open>` says, and stay as tapped while the document is up.
    var openFolds by remember(doc) {
        mutableStateOf(doc.blocks.filterIsInstance<Block.Fold>().filter { it.open }.map { it.id }.toSet())
    }
    val visible = remember(doc, openFolds) {
        doc.blocks.indices.filter { i -> doc.nestOf(i).folds.all { it in openFolds } }
    }
    val lead = if (doc.note != null) 1 else 0
    val visibleNow by rememberUpdatedState(visible)
    state.attach(doc, visible, lead)

    var sheet by remember { mutableStateOf<String?>(null) }
    // To a block, and [within] of the way into it. The folds it is inside open first, and the
    // scroll waits a frame for the list to have the rows they hold.
    val goTo: (Int, Float) -> Unit = { index, within ->
        val inside = doc.nestOf(index).folds
        if (!openFolds.containsAll(inside)) openFolds = openFolds + inside
        scope.launch {
            withFrameNanos { }
            val at = visibleNow.indexOf(index)
            if (at >= 0) {
                list.scrollToItem(lead + at)
                if (within > 0f) {
                    val size = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lead + at }?.size ?: 0
                    if (size > 0) list.scrollToItem(lead + at, (size * within).toInt())
                }
            }
        }
    }
    val jump: (Int) -> Unit = { goTo(it, 0f) }
    state.jump = jump

    // The reading spot: offered once for this page, saved when it is left. See [ReadingSpots].
    val offer = remember(state) { (!state.spotOffered).also { state.spotOffered = true } }
    val saved = rememberSavedSpot(place?.file?.toString(), SpotKind.DOC, offer)
    SaveSpotOnLeave(place?.file) {
        val file = place?.file ?: return@SaveSpotOnLeave
        val info = list.layoutInfo
        val shown = info.visibleItemsInfo
        if (shown.isEmpty()) return@SaveSpotOnLeave
        val first = shown.first()
        val top = first.index == 0 && list.firstVisibleItemScrollOffset < info.viewportSize.height / 2
        val end = shown.last().index == info.totalItemsCount - 1
        ReadingSpots.of(context).left(file.toString(), SpotKind.DOC, top, end) {
            val block = state.topBlock
            val heading = state.outline.lastOrNull { it.index <= block }
            ReadingSpot(
                path = file.toString(),
                kind = SpotKind.DOC,
                item = block,
                within = if (first.index < lead || first.size <= 0) 0f
                else (list.firstVisibleItemScrollOffset.toFloat() / first.size).coerceIn(0f, 1f),
                label = heading?.text?.takeIf { it.isNotBlank() }
                    ?: "${block * 100 / doc.blocks.size.coerceAtLeast(1)}% in",
                anchor = heading?.slug.orEmpty(),
                stamp = place.stamp,
            )
        }
    }
    val say: (String) -> Unit = { msg -> place?.say?.invoke(msg) }
    val onLink: (String) -> Unit = { href ->
        when (val ref = DocRefs.link(href, place?.file)) {
            is Ref.Anchor -> DocOutline.find(doc.anchors, ref.fragment)?.let(jump)
                ?: say("Nothing in this document is called #${ref.fragment}.")
            is Ref.Web -> sheet = ref.url
            is Ref.Mail -> sheet = "mailto:${ref.address}"
            is Ref.Local -> {
                val fragment = href.substringAfter('#', "")
                if (ref.path == place?.file) {
                    if (fragment.isEmpty()) jump(0) else DocOutline.find(doc.anchors, fragment)?.let(jump)
                } else if (place != null) {
                    scope.launch {
                        val vfs = FiletApp.graphOf(context).vfs
                        val target = withContext(Dispatchers.IO) { localTarget(vfs, ref.path, ref.fromRoot, place.file) }
                        place.open(target)
                    }
                }
            }
            Ref.None -> say("This link does not lead anywhere Filet can follow.")
        }
    }
    val env = remember(place, reading, dark, base, doc) { DocEnv(place, reading, dark, onLink, base) }

    Box(modifier.fillMaxSize().docZoom(zoom, list, pinch, ticks)) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = list) {
            doc.note?.let { note ->
                item(key = "note") {
                    // At the TOP, and not as a toast. A document that is partly unread is a fact
                    // about what is on screen for as long as it is on screen, and a message that
                    // disappears after two seconds is not where that belongs.
                    Text(
                        note,
                        fontSize = 11.sp,
                        color = colors.warn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.warn.copy(alpha = 0.10f))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
            items(visible.size, key = { visible[it] }) { v ->
                val i = visible[v]
                val block = doc.blocks[i]
                val frame = frames[i]
                val ground = if (frame == DocLayout.Frame.NONE) background else card
                val nest = effectiveNest(doc, i)
                val before = visible.getOrNull(v - 1)?.let { effectiveNest(doc, it) } ?: Nest.NONE
                val shared = sharedFrames(nest.frames, before.frames)
                // The zoom is a density, so every dp and sp inside a block scales together:
                // type, picture sizes, table column widths, row heights and padding.
                val scaled = Density(base.density * zoom.floatValue, base.fontScale)
                CompositionLocalProvider(LocalDensity provides scaled, LocalDocEnv provides env) {
                    val wide = block is Block.Table || block is Block.Code || frame != DocLayout.Frame.NONE
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Box(
                            Modifier
                                .then(if (reading.column && !wide) Modifier.widthIn(max = READABLE) else Modifier)
                                .fillMaxWidth()
                                .slideFrame(frame, card),
                        ) {
                            Framed(nest.frames, shared, gapOf(block, doc.blocks.getOrNull(visible.getOrNull(v - 1) ?: -1)), dark) {
                                BlockView(
                                    block = block,
                                    nest = nest,
                                    ground = ground,
                                    env = env,
                                    open = (block as? Block.Fold)?.id in openFolds,
                                    onFold = { id -> openFolds = if (id in openFolds) openFolds - id else openFolds + id },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(40.dp)) }
        }
        SpotCard(saved, Modifier.align(Alignment.TopEnd)) { s ->
            val goal = ReadingSpots.goal(s, place?.stamp ?: 0L, doc.blocks.size, doc.anchors)
            goTo(goal.item, goal.within)
        }
    }

    if (state.contentsOpen) ContentsSheet(state, onDismiss = { state.contentsOpen = false })
    if (state.textOpen) ReadingSheet(reading, prefs, onDismiss = { state.textOpen = false }) { size ->
        zoom.floatValue = size / 100f
    }
    sheet?.let { url -> LinkSheet(url, onDismiss = { sheet = null }, say = say) }
}

/** A text column held to about seventy characters, which is where reading speed peaks. */
private val READABLE = 540.dp

/**
 * The page's state that the bar above it reaches: its list, its outline, and the sheets the bar
 * opens. Hoisted out of [DocReader] so the Contents and Aa buttons on a viewer bar act on the
 * page below them.
 */
@Stable
class DocPageState internal constructor() {
    internal val list = LazyListState()
    internal var contentsOpen by mutableStateOf(false)
    internal var textOpen by mutableStateOf(false)
    internal var jump: (Int) -> Unit = {}
    /** The saved reading spot has been offered for this page; a reader composed again over it does not. */
    internal var spotOffered = false
    internal var outline by mutableStateOf<List<Heading>>(emptyList())
    private var visible: List<Int> = emptyList()
    private var lead = 0

    internal fun attach(doc: Doc, visible: List<Int>, lead: Int) {
        val o = doc.outline.ifEmpty {
            // A reader that keeps no outline still marks its headings; the sheet lists those.
            doc.blocks.withIndex().mapNotNull { (i, b) ->
                (b as? Block.Para)?.takeIf { it.level in 1..6 }?.let { Heading(it.level, it.text.replace("￼", "").trim(), i, "") }
            }
        }
        if (o != outline) outline = o
        this.visible = visible
        this.lead = lead
    }

    /** The block at the top of the screen, for the Contents sheet's "you are here". */
    internal val topBlock: Int
        get() = visible.getOrNull((list.firstVisibleItemIndex - lead).coerceAtLeast(0)) ?: 0
}

@Composable
fun rememberDocPageState(key: Any?): DocPageState = remember(key) { DocPageState() }

/**
 * The page's own controls, for the bar of whatever screen is showing a document: the Contents
 * sheet and the reading settings.
 */
@Composable
fun RowScope.DocPageActions(page: DocPageState) {
    ViewerAction(DocIcons.Contents, "Contents") { page.contentsOpen = true }
    ViewerAction(DocIcons.TextSize, "Reading") { page.textOpen = true }
}

/** A plain quote from a reader with no frames - a Word quote - drawn as one rule like the rest. */
private fun effectiveNest(doc: Doc, i: Int): Nest {
    val n = doc.nestOf(i)
    val b = doc.blocks[i]
    return if (n.frames.isEmpty() && b is Block.Para && b.quote) n.copy(frames = listOf(Frame.QUOTE)) else n
}

/** How many of [frames] the block above is also inside, from the outside in. */
private fun sharedFrames(frames: List<Frame>, above: List<Frame>): Int {
    var n = 0
    while (n < frames.size && n < above.size && frames[n] == above[n]) n++
    return n
}

/** The space above a block, by what it is and what it follows. */
private fun gapOf(block: Block, above: Block?): Dp = when (block) {
    is Block.Para -> when {
        block.level == 1 -> if (above == null) 4.dp else 24.dp
        block.level == 2 -> if (above == null) 4.dp else 20.dp
        block.level == 3 -> 16.dp
        block.level > 3 -> 13.dp
        above is Block.AlertHead -> 4.dp
        else -> 9.dp
    }
    is Block.Item -> if (block.tight && above is Block.Item) 3.dp else 8.dp
    is Block.Code -> 10.dp
    is Block.Table -> 12.dp
    Block.Rule -> 14.dp
    is Block.AlertHead -> 10.dp
    is Block.Fold -> 9.dp
    is Block.Meta -> 6.dp
    is Block.Slide -> 12.dp
    is Block.Image -> 10.dp
    is Block.SlideNotes -> 12.dp
    is Block.Note -> 8.dp
}

private fun frameWidth(f: Frame): Dp = if (f == Frame.INDENT) DocLists.INDENT else 14.dp

/**
 * A block inside its frames: the gap above it, then the block, with each quote's or alert's rule
 * drawn down the start edge.
 *
 * A rule this block shares with the one above runs through the gap, so a quote reads as one
 * container; a rule that starts here starts below the gap, so it does not reach up toward the
 * paragraph before the quote.
 */
@Composable
private fun Framed(frames: List<Frame>, shared: Int, gap: Dp, dark: Boolean, content: @Composable () -> Unit) {
    val quote = Filet.colors.fg3.copy(alpha = 0.45f)
    val inset = frames.fold(0.dp) { acc, f -> acc + frameWidth(f) }
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (frames.isEmpty()) Modifier else Modifier.drawBehind {
                    var x = 0f
                    val gapPx = gap.toPx()
                    val stroke = 3.dp.toPx()
                    for ((k, f) in frames.withIndex()) {
                        val w = frameWidth(f).toPx()
                        if (f != Frame.INDENT) {
                            val color = if (f == Frame.QUOTE) quote else DocAlerts.accent(f, dark)
                            val top = if (k < shared) 0f else gapPx
                            drawLine(color, Offset(x + stroke / 2, top), Offset(x + stroke / 2, size.height), stroke)
                        }
                        x += w
                    }
                },
            ),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = inset)) {
            Spacer(Modifier.height(gap))
            content()
        }
    }
}

/**
 * Pinch and double-tap zoom for the reader.
 *
 * ## Reflow, not pan - for documents and for slides
 *
 * The zoom is applied as a larger density, so the column keeps the screen's width and the text
 * re-wraps inside it: zoomed text is read by scrolling down, never side to side. Right for a
 * document, which this reader already reflows. Also right for a slide HERE, because a slide is
 * drawn as reflowed text in a card rather than as a positioned page, so panning would only add
 * a sideways scroll over the same wrapped lines. What does pan is what already did: a table or a
 * code block, whose rows cannot wrap, scrolls sideways in its own scroller and grows with the rest.
 *
 * ## Why the pinch is read on the Initial pass
 *
 * The list is a child and would otherwise take a two-finger drag as a scroll. With two or more
 * pointers down the gesture is consumed before the list sees it; with one, nothing is touched,
 * so ordinary scrolling and taps on links inside the reader are unaffected.
 *
 * ## One update a frame
 *
 * A touch event only gathers its ratio into [pinch]; the reader applies it once a frame - see
 * [PinchFrame] for the freeze that per-event scrolling caused. A double tap is a pinch of one
 * step and goes the same way, so one place moves the zoom and the scroll together and saves the
 * size once it has landed.
 */
private fun Modifier.docZoom(
    zoom: MutableFloatState,
    list: LazyListState,
    pinch: PinchFrame,
    ticks: Channel<Unit>,
): Modifier =
    this
        .pointerInput(zoom, list) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.none { it.pressed }) break
                        if (event.changes.count { it.pressed } < 2) continue
                        pinch.begin()
                        pinch.add(event.calculateZoom(), event.calculateCentroid(useCurrent = true).y)
                        ticks.trySend(Unit)
                        event.changes.forEach { it.consume() }
                    }
                } finally {
                    if (pinch.active) {
                        pinch.end()
                        ticks.trySend(Unit)
                    }
                }
            }
        }
        .pointerInput(zoom, list) {
            detectTapGestures(onDoubleTap = { at ->
                val before = zoom.floatValue
                pinch.begin()
                pinch.add(DocZoom.doubleTap(before) / before, at.y)
                pinch.end()
                ticks.trySend(Unit)
            })
        }

/** Scroll so the content at [focalY] is still at [focalY] after a zoom of [ratio]. */
private suspend fun keepUnder(list: LazyListState, focalY: Float, ratio: Float) {
    if (focalY.isNaN() || ratio == 1f) return
    val item = list.layoutInfo.visibleItemsInfo
        .firstOrNull { focalY >= it.offset && focalY < it.offset + it.size } ?: return
    val top = DocZoom.anchoredTop(focalY, item.offset.toFloat(), ratio)
    list.scrollToItem(item.index, 0)
    // A top below the viewport's edge means scrolling back into the items above.
    list.scrollBy(-top)
}

/**
 * One slide as one card, drawn a block at a time.
 *
 * The blocks of a slide are separate items in a lazy list - they have to be, or a long deck
 * would compose every slide at once - so the card is assembled from a top, a middle and a foot
 * that meet edge to edge. See [DocLayout.slideFrames] for which is which.
 */
private fun Modifier.slideFrame(frame: DocLayout.Frame, card: Color): Modifier {
    val r = 14.dp
    return when (frame) {
        DocLayout.Frame.NONE -> this
        DocLayout.Frame.TOP -> this
            .padding(top = 18.dp)
            .clip(RoundedCornerShape(topStart = r, topEnd = r))
            .background(card)
            .padding(horizontal = 14.dp)
        DocLayout.Frame.MIDDLE -> this.background(card).padding(horizontal = 14.dp)
        DocLayout.Frame.BOTTOM -> this
            .clip(RoundedCornerShape(bottomStart = r, bottomEnd = r))
            .background(card)
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
        DocLayout.Frame.ONLY -> this
            .padding(top = 18.dp)
            .clip(RoundedCornerShape(r))
            .background(card)
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
    }
}

@Composable
private fun BlockView(block: Block, nest: Nest, ground: Color, env: DocEnv, open: Boolean, onFold: (Int) -> Unit) {
    val colors = Filet.colors
    val ink = MaterialTheme.colorScheme.onSurface
    when (block) {
        is Block.Para -> {
            val size = headingSize(block.level)
            Column(Modifier.fillMaxWidth().padding(start = indentDp(block.indent).dp)) {
                RichText(
                    text = block.text,
                    spans = block.spans,
                    pictures = block.pictures,
                    size = size,
                    line = size * 1.5f,
                    weight = if (block.level > 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (nest.quoted) colors.fg2 else ink,
                    align = block.align,
                    ground = ground,
                    modifier = Modifier.fillMaxWidth(),
                )
                // GitHub's rule under the first two heading levels, which is what makes a long
                // README's sections findable while scrolling.
                if (block.level in 1..2 && nest.frames.isEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
                }
            }
        }

        is Block.Item -> {
            // A Word item carries its depth and no frames; a Markdown one's frames already draw it.
            val extra = (block.depth - nest.indents).coerceAtLeast(0)
            Row(Modifier.fillMaxWidth().padding(start = (extra * 16).dp)) {
                val width = DocLists.markerWidth(block)
                val task = block.task
                if (task != null) {
                    Box(Modifier.width(width).height(19.dp), contentAlignment = Alignment.CenterStart) { TaskBox(task) }
                } else {
                    Text(
                        DocLists.marker(block),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = colors.fg3,
                        textAlign = if (block.ordered) androidx.compose.ui.text.style.TextAlign.End else null,
                        modifier = Modifier
                            .width(width)
                            .padding(end = if (block.ordered) 6.dp else 0.dp),
                    )
                }
                RichText(
                    text = block.text,
                    spans = block.spans,
                    pictures = block.pictures,
                    size = 13.sp,
                    line = 19.sp,
                    weight = FontWeight.Normal,
                    color = if (nest.quoted) colors.fg2 else ink,
                    align = Align.START,
                    ground = ground,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        is Block.Code -> CodeView(block, env.dark)

        is Block.Table -> TableView(block, env)

        Block.Rule -> Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(colors.lineSoft))

        is Block.AlertHead -> {
            val accent = DocAlerts.accent(block.frame, env.dark)
            Row(verticalAlignment = Alignment.CenterVertically) {
                DocAlerts.icon(block.frame)?.let { Icon(it, null, tint = accent, modifier = Modifier.size(16.dp)) }
                Spacer(Modifier.width(7.dp))
                Text(DocAlerts.title(block.frame), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = accent)
            }
        }

        is Block.Fold -> Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { onFold(block.id) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "▾" else "▸", fontSize = 13.sp, color = colors.accent, modifier = Modifier.width(18.dp))
            RichText(
                text = block.text,
                spans = block.spans,
                pictures = emptyList(),
                size = 13.sp,
                line = 19.sp,
                weight = FontWeight.Medium,
                color = ink,
                align = Align.START,
                ground = ground,
                modifier = Modifier.weight(1f),
            )
        }

        is Block.Meta -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.sunken)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            for ((k, value) in block.entries) {
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        k.uppercase(),
                        fontSize = 9.5.sp,
                        letterSpacing = 0.8.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.fg3,
                        modifier = Modifier.width(92.dp).padding(top = 2.dp),
                    )
                    Text(value, fontSize = 12.sp, lineHeight = 17.sp, color = colors.fg2, modifier = Modifier.weight(1f))
                }
            }
        }

        is Block.Slide -> Text(
            if (block.hidden) "SLIDE ${block.number}  ·  HIDDEN" else "SLIDE ${block.number}",
            fontSize = 9.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Bold,
            color = if (block.hidden) colors.fg3 else colors.accent,
        )

        is Block.Image -> PictureView(block)

        is Block.SlideNotes -> NotesView(block)

        is Block.Note -> Text(block.text, fontSize = 10.5.sp, color = colors.fg3, fontStyle = FontStyle.Italic)
    }
}

/**
 * A picture from inside the document, decoded off the main thread at the width it is drawn.
 *
 * Decoded at most [PICTURE_MAX_PX] wide: a phone photo pasted into a report is 4000 px across
 * and is drawn at a few hundred, and decoding it whole is 60 MB of bitmap for one paragraph.
 */
@Composable
private fun PictureView(block: Block.Image) {
    val colors = Filet.colors
    var bitmap by remember(block) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(block) { mutableStateOf(DocLayout.undrawable(block.format)) }
    LaunchedEffect(block) {
        if (failed) return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) { decodePicture(block.data) }
        if (decoded == null) failed = true else bitmap = decoded
    }
    val widthDp = DocLayout.pictureWidthDp(block.emuWidth)
    val sized = if (widthDp > 0f) Modifier.widthIn(max = widthDp.dp) else Modifier
    val shown = bitmap
    when {
        failed -> Text(
            pictureRefusal(block),
            fontSize = 10.5.sp,
            color = colors.fg3,
            fontStyle = FontStyle.Italic,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.sunken)
                .padding(horizontal = 10.dp, vertical = 12.dp),
        )

        shown != null -> Image(
            shown,
            contentDescription = block.alt.ifBlank { null },
            contentScale = ContentScale.Fit,
            modifier = sized
                .fillMaxWidth()
                .aspectRatio(shown.width.toFloat() / shown.height.coerceAtLeast(1))
                .clip(RoundedCornerShape(6.dp)),
        )

        // Held at its placed shape while it decodes, so the text below does not jump.
        else -> Box(
            sized
                .fillMaxWidth()
                .aspectRatio(block.aspect.takeIf { it > 0f } ?: (4f / 3f))
                .clip(RoundedCornerShape(6.dp))
                .background(colors.sunken),
        )
    }
}

private fun pictureRefusal(block: Block.Image): String {
    val what = block.alt.takeIf { it.isNotBlank() && it.length <= 80 }?.let { "\"$it\"" } ?: "A picture"
    val format = block.format.uppercase().ifEmpty { "its" }
    return "$what is not shown - $format pictures cannot be drawn here."
}

private fun decodePicture(data: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0) return@runCatching null
    val opts = BitmapFactory.Options().apply {
        inSampleSize = DocLayout.sampleSize(bounds.outWidth, PICTURE_MAX_PX)
    }
    BitmapFactory.decodeByteArray(data, 0, data.size, opts)?.asImageBitmap()
}.getOrNull()

private const val PICTURE_MAX_PX = 1440

/**
 * Speaker notes, folded at the foot of their slide.
 *
 * Folded because notes are often longer than the slide and are not what a reader opened the deck
 * to see; one tap away because they are frequently the only place the slide's argument is
 * written down. Labelled and drawn on a different ground, so they can never be read as the
 * slide's own text.
 */
@Composable
private fun NotesView(block: Block.SlideNotes) {
    val colors = Filet.colors
    var open by remember(block) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.sunken)
            .clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            if (open) "SPEAKER NOTES  ▾" else "SPEAKER NOTES  ▸",
            fontSize = 9.sp,
            letterSpacing = 1.1.sp,
            fontWeight = FontWeight.Bold,
            color = colors.fg3,
        )
        if (open) {
            Spacer(Modifier.height(6.dp))
            Text(block.text, fontSize = 12.sp, lineHeight = 18.sp, color = colors.fg2)
        }
    }
}

private fun headingSize(level: Int) = when (level) {
    1 -> 21.sp
    2 -> 17.5.sp
    3 -> 15.sp
    4 -> 13.5.sp
    5 -> 13.sp
    6 -> 12.5.sp
    else -> 13.sp
}

/** Points of indent into dp, damped and capped: a two-inch Word indent is half a phone. */
private fun indentDp(points: Int): Float = (points * 0.5f).coerceIn(0f, 40f)

// ── the sheets the bar opens ──

/** The headings, by level, with the one on screen marked. A tap goes there. */
@Composable
private fun ContentsSheet(page: DocPageState, onDismiss: () -> Unit) {
    val colors = Filet.colors
    val outline = page.outline
    val top = page.topBlock
    val here = outline.lastOrNull { it.index <= top }
    val least = outline.minOfOrNull { it.level } ?: 1
    Dlg(onDismiss = onDismiss) {
        DlgHeader(DocIcons.Contents, "Contents", sub = if (outline.isEmpty()) null else "${outline.size} headings", onClose = onDismiss)
        DlgBody {
            if (outline.isEmpty()) {
                Text("This document has no headings to list.", fontSize = 12.5.sp, color = colors.fg3)
            }
            for (h in outline) {
                val current = h == here
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (current) colors.sel else Color.Transparent)
                        .clickable {
                            onDismiss()
                            page.jump(h.index)
                        }
                        .padding(start = (8 + (h.level - least) * 14).dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        h.text.ifBlank { "(untitled)" },
                        fontSize = if (h.level == least) 13.sp else 12.5.sp,
                        fontWeight = if (h.level == least) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (current) colors.accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (current) Text("here", fontSize = 10.sp, color = colors.accent)
                }
            }
        }
    }
}

/** Aa: the reading settings, the same ones Settings, Reading holds. */
@Composable
private fun ReadingSheet(
    reading: ReadingSettings,
    prefs: dev.niccc2007.filet.data.Prefs,
    onDismiss: () -> Unit,
    onSize: (Int) -> Unit,
) {
    val colors = Filet.colors
    fun size(to: Int) {
        prefs.setReadSize(to)
        onSize(Reading.clampSize(to))
    }
    Dlg(onDismiss = onDismiss) {
        DlgHeader(DocIcons.TextSize, "Reading", sub = "For every document", onClose = onDismiss)
        DlgBody {
            DlgSection("Text size")
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val smaller = Reading.smaller(reading.size)
                val larger = Reading.larger(reading.size)
                StepButton("A−", enabled = smaller != null) { smaller?.let(::size) }
                Text(
                    "${reading.size}%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StepButton("A+", enabled = larger != null) { larger?.let(::size) }
            }
            Text("Pinching the page moves this too.", fontSize = 10.5.sp, color = colors.fg3)
            Spacer(Modifier.height(8.dp))
            DlgTick(reading.column, "Readable column", sub = "Hold text to about seventy characters a line") {
                prefs.setReadColumn(!reading.column)
            }
            DlgSection("Tables")
            for (mode in TableMode.entries) {
                DlgTick(reading.tables == mode, mode.label, sub = tableHint(mode)) { prefs.setReadTables(mode) }
            }
            DlgSection("Pictures")
            DlgTick(reading.webPictures, "Load web pictures automatically", sub = "Off: each shows its name and loads when tapped") {
                prefs.setReadWebPictures(!reading.webPictures)
            }
        }
    }
}

internal fun tableHint(mode: TableMode): String = when (mode) {
    TableMode.FIT -> "Wrap to fit the width; scroll only when it cannot"
    TableMode.COMFORTABLE -> "One line a cell, scrolled sideways"
    TableMode.COMPACT -> "Fitted, in smaller type"
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Filet.colors
    Text(
        label,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) colors.accent else colors.fg3.copy(alpha = 0.45f),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.sunken)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

/**
 * A web or mail link, shown before anything opens.
 *
 * Asked rather than followed, because a tap on a word in a document is not the same as deciding
 * to leave the app for an address nobody has read yet. The address is shown in full.
 */
@Composable
private fun LinkSheet(url: String, onDismiss: () -> Unit, say: (String) -> Unit) {
    val context = LocalContext.current
    val mail = url.startsWith("mailto:")
    Dlg(onDismiss = onDismiss) {
        DlgHeader(FiletIcons.Link, if (mail) "Mail address" else "Web link", onClose = onDismiss)
        DlgBody {
            Text(
                if (mail) url.removePrefix("mailto:") else url,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Copy") {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("link", if (mail) url.removePrefix("mailto:") else url))
                say("Link copied")
                onDismiss()
            }
            DlgBtn(if (mail) "Open in mail app" else "Open in browser", kind = BtnKind.PRIMARY) {
                onDismiss()
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: ActivityNotFoundException) {
                    say(if (mail) "No app here sends mail." else "No app here opens web links.")
                }
            }
        }
    }
}

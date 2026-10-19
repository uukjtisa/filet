package dev.niccc2007.filet.docs

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * The one thing that does NOT reflow is code, which is wrapped in a horizontal scroller
 * instead: a wrapped line of code is worse than a scrolled one.
 *
 * ## What it does draw from the file
 *
 * Everything the model carries, which is everything the readers find: heading sizes, inline
 * emphasis, colour where it reads on the theme, alignment, indentation, list markers as the file
 * defines them, tables as a ruled grid with merged cells, pictures at the size they are placed
 * at, and a deck as one card per slide with its notes folded at the foot. A `Doc` from a reader
 * that sets none of the newer fields draws exactly as it did before they existed.
 */
@Composable
fun DocReader(doc: Doc, modifier: Modifier = Modifier, key: Any? = null) {
    val colors = Filet.colors
    if (doc.empty && doc.note == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing to read in this one", fontSize = 12.sp, color = colors.fg3)
        }
        return
    }
    val frames = remember(doc) { DocLayout.slideFrames(doc.blocks) }
    val page = MaterialTheme.colorScheme.background
    // The raised ground is too close to the page in the light palettes to read as a card edge.
    val card = colors.high
    // Held here rather than per block, so scrolling and moving between slides never reset it;
    // keyed on [key] so a different file starts at 1. See [DocZoom].
    val zoom = remember(key) { mutableFloatStateOf(1f) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val base = LocalDensity.current
    val scaled = Density(base.density * zoom.floatValue, base.fontScale)
    Box(modifier.fillMaxSize().docZoom(zoom, list, scope)) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = list) {
            doc.note?.let { note ->
                item {
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
            items(doc.blocks.size) { i ->
                val frame = frames[i]
                val ground = if (frame == DocLayout.Frame.NONE) page else card
                // The zoom is a density, so every dp and sp inside a block scales together:
                // type, picture sizes, table column widths, row heights and padding.
                CompositionLocalProvider(LocalDensity provides scaled) {
                    Box(Modifier.fillMaxWidth().slideFrame(frame, card)) {
                        BlockView(doc.blocks[i], ground)
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
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
 * so ordinary scrolling and taps on notes inside the reader are unaffected.
 */
private fun Modifier.docZoom(zoom: MutableFloatState, list: LazyListState, scope: CoroutineScope): Modifier =
    this
        .pointerInput(zoom, list) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.none { it.pressed }) break
                    if (event.changes.count { it.pressed } < 2) continue
                    val before = zoom.floatValue
                    val after = DocZoom.pinch(before, event.calculateZoom())
                    if (after != before) {
                        val focal = event.calculateCentroid(useCurrent = true)
                        zoom.floatValue = after
                        scope.launch { keepUnder(list, focal.y, after / before) }
                    }
                    event.changes.forEach { it.consume() }
                }
            }
        }
        .pointerInput(zoom, list) {
            detectTapGestures(onDoubleTap = { at ->
                val before = zoom.floatValue
                val after = DocZoom.doubleTap(before)
                zoom.floatValue = after
                scope.launch { keepUnder(list, at.y, after / before) }
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
private fun BlockView(block: Block, ground: Color) {
    val colors = Filet.colors
    when (block) {
        is Block.Para -> {
            val size = headingSize(block.level)
            Spacer(Modifier.height(if (block.level > 0) 16.dp else 8.dp))
            Row(Modifier.padding(start = indentDp(block.indent).dp)) {
                if (block.quote) {
                    // A rule down the side, which is what a quote looks like everywhere. An
                    // indent alone reads as a list item.
                    Box(
                        Modifier
                            .width(2.dp)
                            .height((size.value * 1.5f).dp)
                            .background(colors.accent.copy(alpha = 0.5f)),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    annotate(block.text, block.spans, ground),
                    fontSize = size,
                    lineHeight = size * 1.5f,
                    fontWeight = if (block.level > 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (block.quote) colors.fg2 else MaterialTheme.colorScheme.onSurface,
                    textAlign = textAlignOf(block.align),
                    // The rest of the row, so an alignment has a width to align within.
                    modifier = Modifier.weight(1f),
                )
            }
        }

        is Block.Item -> {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.padding(start = (block.depth * 16).dp)) {
                val marker = block.marker.ifEmpty {
                    if (block.ordered && block.number > 0) "${block.number}." else "•"
                }
                Text(
                    marker,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = colors.fg3,
                    // A floor rather than a fixed width, so `iv.` and `2.3.` are not clipped and
                    // a bullet does not sit a numbered list's width away from its text.
                    modifier = Modifier
                        .widthIn(min = if (block.ordered) 22.dp else 14.dp)
                        .padding(end = 6.dp),
                )
                Text(
                    annotate(block.text, block.spans, ground),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        is Block.Code -> {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.sunken),
            ) {
                // Scrolled, not wrapped. A wrapped line of code is harder to read than a
                // scrolled one, and this is the one place reflowing is the wrong answer.
                //
                // Through `HScroll` rather than a bare `horizontalScroll`, so the edge says
                // there is more - which on a block of code with no visible right-hand edge is
                // the difference between a long line and a truncated one.
                dev.niccc2007.filet.ui.HScroll(
                    ground = colors.sunken,
                    contentPadding = 10.dp,
                ) {
                    Text(
                        block.text,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        is Block.Table -> {
            Spacer(Modifier.height(12.dp))
            TableView(block)
        }

        Block.Rule -> {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
        }

        is Block.Slide -> {
            Spacer(Modifier.height(12.dp))
            Text(
                if (block.hidden) "SLIDE ${block.number}  ·  HIDDEN" else "SLIDE ${block.number}",
                fontSize = 9.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold,
                color = if (block.hidden) colors.fg3 else colors.accent,
            )
        }

        is Block.Image -> {
            Spacer(Modifier.height(10.dp))
            PictureView(block)
        }

        is Block.SlideNotes -> {
            Spacer(Modifier.height(12.dp))
            NotesView(block)
        }

        is Block.Note -> {
            Spacer(Modifier.height(8.dp))
            Text(block.text, fontSize = 10.5.sp, color = colors.fg3, fontStyle = FontStyle.Italic)
        }
    }
}

/**
 * A table as a ruled grid.
 *
 * Every cell carries its own hairline, so the grid reads as a grid at any row height; the row is
 * measured at its tallest cell (`IntrinsicSize.Min`) so the lines meet rather than stopping where
 * each cell's text does. A merged cell is as wide as the columns it covers.
 */
@Composable
private fun TableView(block: Block.Table) {
    val colors = Filet.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.sunken),
    ) {
        // One scroller for the WHOLE table, so the columns stay lined up. A scroller per row is
        // what a Column of scrollable Rows gives and it comes apart the moment it is touched.
        dev.niccc2007.filet.ui.HScroll(ground = colors.sunken, contentPadding = 0.dp) {
            Column(Modifier.border(0.5.dp, colors.lineSoft)) {
                for ((r, row) in block.rows.withIndex()) {
                    val head = block.header && r == 0
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        for ((c, cellText) in row.withIndex()) {
                            val span = block.colSpans.getOrNull(r)?.getOrNull(c)?.coerceAtLeast(1) ?: 1
                            Text(
                                cellText,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                fontWeight = if (head) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (head) MaterialTheme.colorScheme.onSurface else colors.fg2,
                                modifier = Modifier
                                    .width(TABLE_CELL * span)
                                    .fillMaxHeight()
                                    .background(if (head) colors.accent.copy(alpha = 0.08f) else Color.Transparent)
                                    .border(0.5.dp, colors.lineSoft)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
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
    var failed by remember(block) { mutableStateOf(block.format in DocLayout.UNDRAWABLE) }
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

/** How wide a table cell is. Fixed, because a measured column pass is a layout engine. */
private val TABLE_CELL = 124.dp

private fun headingSize(level: Int) = when (level) {
    1 -> 20.sp
    2 -> 17.sp
    3 -> 15.sp
    4, 5, 6 -> 13.5.sp
    else -> 13.sp
}

/** Points of indent into dp, damped and capped: a two-inch Word indent is half a phone. */
private fun indentDp(points: Int): Float = (points * 0.5f).coerceIn(0f, 40f)

private fun textAlignOf(align: Align): TextAlign = when (align) {
    Align.START -> TextAlign.Start
    Align.CENTER -> TextAlign.Center
    Align.END -> TextAlign.End
    Align.JUSTIFY -> TextAlign.Justify
}

/**
 * Text plus marks into an `AnnotatedString`.
 *
 * Every span is range-checked against the text, and an out-of-range one is dropped. Not
 * defensiveness for its own sake: these offsets come out of several readers walking different
 * trees, and `AnnotatedString` THROWS on a range past the end rather than clamping - so one
 * arithmetic slip in a reader would be a crash on opening a document rather than a word in the
 * wrong weight.
 */
fun annotate(text: String, spans: List<Span>): AnnotatedString = annotate(text, spans, null)

/**
 * The same, knowing what it is drawn on, so a document colour that would vanish on the theme is
 * dropped. See [DocLayout.readable].
 */
fun annotate(text: String, spans: List<Span>, ground: Color?): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(text)
    val groundArgb = ground?.toArgb()?.toLong()?.and(0xFFFFFFFFL)
    return buildAnnotatedString {
        append(text)
        for (span in spans) {
            if (span.from < 0 || span.to > text.length || span.to <= span.from) continue
            val style = styleOf(span, spans, groundArgb) ?: continue
            addStyle(style, span.from, span.to)
        }
    }
}

private fun styleOf(span: Span, all: List<Span>, ground: Long?): SpanStyle? = when (span.style) {
    Mark.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    Mark.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    Mark.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22808080))
    // Underline and strike are both a text decoration, and two decoration spans over one range
    // REPLACE each other rather than adding up. Where both cover a run, each draws both.
    Mark.STRIKE, Mark.UNDERLINE -> SpanStyle(textDecoration = decorationFor(span, all))
    // Underlined and in the accent, but NOT clickable. A tap that opens a browser from
    // inside a document reader is an outbound network request somebody did not ask for, and
    // the link's target is visible as text anyway.
    Mark.LINK -> SpanStyle(
        textDecoration = TextDecoration.Underline,
        color = Color(0xFF6AA9FF),
    )
    Mark.COLOR -> if (span.argb == 0L || (ground != null && !DocLayout.readable(span.argb, ground))) {
        null
    } else {
        SpanStyle(color = Color(span.argb.toInt()))
    }
    // A highlighter's ink is chosen for the highlighter, not for the page: black on yellow
    // reads, the theme's pale text on yellow does not.
    Mark.HIGHLIGHT -> if (span.argb == 0L) {
        null
    } else {
        SpanStyle(
            background = Color(span.argb.toInt()).copy(alpha = 0.85f),
            color = Color(DocLayout.inkOn(span.argb).toInt()),
        )
    }
    Mark.SIZE -> if (span.scale <= 0f) null else SpanStyle(fontSize = span.scale.em)
    Mark.SUPERSCRIPT -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)
    Mark.SUBSCRIPT -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em)
}

private fun decorationFor(span: Span, all: List<Span>): TextDecoration {
    val other = if (span.style == Mark.UNDERLINE) Mark.STRIKE else Mark.UNDERLINE
    val own = if (span.style == Mark.UNDERLINE) TextDecoration.Underline else TextDecoration.LineThrough
    val overlaps = all.any { it.style == other && it.from < span.to && it.to > span.from }
    return if (overlaps) {
        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    } else {
        own
    }
}

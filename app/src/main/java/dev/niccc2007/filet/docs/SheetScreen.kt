package dev.niccc2007.filet.docs

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.handlers.ViewerBar
import dev.niccc2007.filet.ui.HScroll
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A spreadsheet, as a grid.
 *
 * ## Why this is not `DocScreen`
 *
 * A document scrolls one way and reflows to the width; a sheet scrolls both ways and must not
 * reflow at all, because a column IS its position. And a sheet scrolled to row 400 with its
 * header row gone is a field of unlabelled numbers - so the header row and the first column stay
 * on screen while everything else moves, the way every desktop spreadsheet does it.
 *
 * ## The four quadrants
 *
 * Top-left is pinned both ways (the corner and the frozen cells). Top-right moves sideways only
 * (the column letters and the frozen rows). Bottom-left moves down only (row numbers and the
 * frozen columns). Bottom-right moves both ways. The vertical axis is one `LazyColumn` whose
 * rows each contain their own pinned part, so a row's pinned and moving cells cannot drift out
 * of line. The horizontal axis is ONE `ScrollState` that every moving strip reads.
 *
 * The column letters are an [HScroll], which owns that state and draws the cue that there is more
 * to the right - that strip measures the grid's full width, which is what gives the state its
 * range. Every other strip is a [ScrollStrip] that only READS the offset, in the layout phase, so
 * a sideways drag moves cells without recomposing any of them.
 *
 * ## Only what is on screen is built
 *
 * Column widths are fixed per column, so which columns are visible is arithmetic on the scroll
 * offset - and only those are composed. A 200-column sheet costs what a 6-column one does.
 * Vertically the `LazyColumn` does the same for rows.
 *
 * ## The pinned rows and columns
 *
 * The file's own frozen panes when it has them; otherwise the first row and the first column,
 * which is where a header almost always is. At most [MAX_PIN_ROWS] and [MAX_PIN_COLS], because a
 * desktop that freezes six rows has a screen to spare and a phone does not.
 */
@Composable
fun SheetScreen(vm: BrowserViewModel, node: VNode) {
    var opened by remember(node.path) { mutableStateOf<SheetOpen?>(null) }

    LaunchedEffect(node.path) {
        opened = withContext(Dispatchers.IO) {
            SheetReader.read({ vm.openRead(node) }, node.extension)
        }
    }

    // Bug identified here: the sheet drew no bar. `DocumentScreen` hands a spreadsheet over
    // before it draws its own, so this was the one document with no name, no Close and its tabs
    // under the clock - the bar is also what keeps a viewer clear of the status bar.
    Column(Modifier.fillMaxSize()) {
        ViewerBar(title = node.name, subtitle = node.path.parent?.path.orEmpty(), onClose = { vm.closeHandler() })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val o = opened) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }

                is SheetOpen.Failed -> SheetProblemView(
                    o,
                    onElsewhere = { vm.openExternally(node, force = true) },
                    onClose = vm::closeHandler,
                )

                is SheetOpen.Ready -> WorkbookView(o.book, node, onElsewhere = { vm.openExternally(node, force = true) })
            }
        }
    }
}

@Composable
private fun WorkbookView(book: Workbook, node: VNode, onElsewhere: () -> Unit) {
    val colors = Filet.colors
    val context = LocalContext.current
    // The first tab a person would see. A hidden first sheet is common in exported workbooks
    // (lookup tables, macros) and is never the one the file was opened to read.
    val opening = remember(book) { book.sheets.indexOfFirst { !it.hidden && !it.chart }.coerceAtLeast(0) }
    var index by remember(book) { mutableIntStateOf(opening) }

    // The reading spot: a sheet, a row and a column. The workbook saves it, because only the
    // workbook knows the tab; the grid on screen says where it is, and goes where it is sent.
    val place = remember(book) { SheetPlace() }
    val saved = rememberSavedSpot(node.path.toString(), SpotKind.ROW)
    SaveSpotOnLeave(node.path) {
        val at = place.read?.invoke() ?: return@SaveSpotOnLeave
        val path = node.path.toString()
        val name = book.sheets.getOrNull(at.part)?.name ?: return@SaveSpotOnLeave
        ReadingSpots.of(context).left(path, SpotKind.ROW, top = at.part == opening && at.top, end = false) {
            ReadingSpot(
                path, SpotKind.ROW,
                item = at.row,
                part = at.part,
                column = at.column,
                label = name + " · " + CellRef.name(at.column) + (at.row + 1),
                stamp = node.mtime,
            )
        }
    }
    var sheet by remember(book) { mutableStateOf<Sheet?>(null) }
    // The workbook's, not the sheet's: switching tabs keeps it, and a new file is a new `book`
    // and starts at 1x. Kept per sheet, every tab change would snap the text size back.
    var zoom by remember(book) { mutableFloatStateOf(1f) }

    LaunchedEffect(book, index) {
        sheet = null
        val name = book.sheets[index].name
        sheet = runCatching { withContext(Dispatchers.IO) { book.sheet(index) } }.getOrElse { e ->
            Sheet(name, emptyList(), 0, note = "This sheet could not be read: " + (e.message ?: e::class.java.simpleName))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (book.sheets.size > 1) {
                SheetTabs(book.sheets, index) { index = it }
            }
            val current = sheet
            when {
                current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }

                current.empty -> Column(
                    Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        current.note ?: "This sheet is empty.",
                        fontSize = 12.sp,
                        color = colors.fg3,
                        textAlign = TextAlign.Center,
                    )
                    if (book.sheets[index].chart) {
                        Spacer(Modifier.height(14.dp))
                        Pill("Open in another app", onElsewhere)
                    }
                }

                // Keyed by sheet, so the scroll position and the selected cell belong to the sheet
                // they were made on and do not carry over to the next tab.
                else -> key(index) { SheetGrid(current, zoom, place, index) { zoom = it } }
            }
        }
        SpotCard(saved, Modifier.align(Alignment.TopEnd)) { s ->
            val part = s.part.coerceIn(0, book.sheets.size - 1)
            place.go = SheetGo(part, s.item, s.column)
            index = part
        }
    }
}

@Composable
private fun SheetTabs(sheets: List<SheetInfo>, selected: Int, onPick: (Int) -> Unit) {
    val colors = Filet.colors
    HScroll(
        ground = MaterialTheme.colorScheme.background,
        contentPadding = 10.dp,
        spacing = 6.dp,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        sheets.forEachIndexed { i, s ->
            val on = i == selected
            val label = when {
                s.chart -> s.name + " · chart"
                s.hidden -> s.name + " · hidden"
                else -> s.name
            }
            Text(
                label,
                fontSize = 11.sp,
                maxLines = 1,
                color = if (on) colors.accent else if (s.hidden) colors.fg3 else colors.fg2,
                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) colors.accent.copy(alpha = 0.14f) else colors.sunken)
                    .clickable { onPick(i) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

// ── the grid ──────────────────────────────────────────────────────────────────────

private const val MAX_PIN_ROWS = 3
private const val MAX_PIN_COLS = 2

/** A pinned column wider than this would leave nothing to scroll on a phone. */
private const val MAX_PIN_WIDTH = 120f

/** Up to this many empty rows are drawn as rows; more collapse into one line that says so. */
private const val GAP_INLINE = 12

/** One line of the scrolling body. */
private sealed interface Line {
    data class Data(val index: Int, val row: SheetRow?) : Line
    data class Gap(val from: Int, val count: Int) : Line
}

/**
 * Everything about a sheet's grid that does not change while it is on screen.
 *
 * Widths are in dp, decided from the text in each column - not the file's own column widths,
 * which are in units of a desktop font's character and are routinely set to fit a printed page.
 */
private class GridPlan(sheet: Sheet) {
    val columns = sheet.columns
    val pinRows = (if (sheet.frozenRows > 0) sheet.frozenRows else if (sheet.lastRow > 1) 1 else 0)
        .coerceAtMost(MAX_PIN_ROWS)
    val pinCols = (if (sheet.frozenCols > 0) sheet.frozenCols else if (columns > 1) 1 else 0)
        .coerceAtMost(MAX_PIN_COLS).coerceAtMost(columns)

    val widths: FloatArray = FloatArray(columns).also { w ->
        val chars = IntArray(columns)
        // A sample, not every row: two thousand rows say what a column holds, and walking
        // two hundred thousand cells on the way to the first frame says nothing more.
        for (r in sheet.rows.take(2000)) for (c in r.cells) {
            val n = c.text.substringBefore('\n').length.coerceAtMost(30)
            if (n > chars[c.col]) chars[c.col] = n
        }
        for (i in 0 until columns) {
            // An entirely empty column is drawn narrow rather than skipped: the gap is part of
            // the sheet, and its letter in the header says which column it is.
            w[i] = if (chars[i] == 0) 34f else (chars[i] * 7.4f + 14f).coerceIn(44f, 200f)
            if (i < pinCols) w[i] = w[i].coerceAtMost(MAX_PIN_WIDTH)
        }
    }

    val gutter: Float = ((sheet.lastRow.coerceAtLeast(1).toString().length) * 7f + 14f).coerceAtLeast(30f)

    private val byIndex: Map<Int, SheetRow> = sheet.rows.filter { it.index < pinRows }.associateBy { it.index }

    val pinned: List<Line.Data> = (0 until pinRows).map { Line.Data(it, byIndex[it]) }

    val body: List<Line> = ArrayList<Line>().also { out ->
        var prev = pinRows - 1
        for (r in sheet.rows) {
            if (r.index < pinRows) continue
            val gap = r.index - prev - 1
            if (gap in 1..GAP_INLINE) {
                for (i in prev + 1 until r.index) out.add(Line.Data(i, null))
            } else if (gap > GAP_INLINE) {
                out.add(Line.Gap(prev + 1, gap))
            }
            out.add(Line.Data(r.index, r))
            prev = r.index
        }
    }
}

/** Unscaled sizes. Every one of them is multiplied by the zoom before it reaches a layout. */
private const val ROW_DP = 30f
private const val HEAD_DP = 22f

/**
 * Where a grid is, for the reading spot. [top] is the first row and column on screen.
 *
 * [part] is the grid's own sheet, not the tab the workbook has moved to: between a tab tap and
 * the new sheet arriving, the grid on screen is still the old one, and a spot saved in that
 * moment belongs to the sheet it was read from.
 */
private class SheetAt(val part: Int, val row: Int, val column: Int, val top: Boolean)

/** A saved spot to scroll to once sheet [part] is on screen. */
private class SheetGo(val part: Int, val row: Int, val column: Int)

/** Between the workbook and the grid on screen: where the grid is, and where it is sent. */
private class SheetPlace {
    var read: (() -> SheetAt)? = null
    var go by mutableStateOf<SheetGo?>(null)
}

/**
 * A sideways offset waiting for the layout at a new scale. Only one past the old width waits:
 * the scroll state cannot hold an offset its content does not reach yet.
 */
private class PendingScroll {
    var x = -1f
}

/**
 * The grid's size on screen, and the zoom a landing has asked for and the layout has not caught
 * up with. Held, not state: the pinch reads them between frames and nothing draws from them.
 */
private class GridBox {
    var width = 0
    var height = 0

    /** Set by a landing mid-pinch; the touch events before the new layout are held until then. */
    var landingAt = -1f

    /** Their ratios, multiplied, so the fingers lose none of what they did while held. */
    var held = 1f
}

/**
 * The grid.
 *
 * ## Zoom
 *
 * Pinch, or double tap, scales EVERYTHING together - text, row height, column width, gutter and
 * header - because a spreadsheet zoomed by enlarging text inside fixed columns clips every cell
 * and reads worse than no zoom at all. Bounds and the double-tap cycle are [SheetZoom].
 *
 * While the fingers are down the grid is a picture of itself being scaled, and it is laid out at
 * the new size once, when they lift. Laying it out every frame is the lag [SheetPinch] records.
 *
 * All four quadrants take their pixel positions from one [SheetGeometry] at one scale. The
 * frozen strips and the body each sizing themselves from dp would round differently, and the
 * letters would drift off their columns a pixel at a time.
 *
 * The gesture is read in the INITIAL pass and consumed only once a second finger is down, so a
 * one-finger drag still reaches the scrollers beneath and a pinch never also scrolls. A tap is
 * never consumed either: the cells still receive it, and a double tap is recognised by timing
 * here rather than by a detector that would have to own every tap to see the second one.
 */
@Composable
private fun SheetGrid(sheet: Sheet, zoom: Float, place: SheetPlace, part: Int, onZoom: (Float) -> Unit) {
    val colors = Filet.colors
    val density = LocalDensity.current
    val plan = remember(sheet) { GridPlan(sheet) }
    val h = remember(sheet) { ScrollState(0) }
    val list = rememberLazyListState()
    var selected by remember(sheet) { mutableStateOf<Pair<Int, Int>?>(null) }

    val geo = remember(plan, zoom, density) {
        SheetGeometry(plan.widths, plan.gutter, plan.pinCols, zoom, density.density)
    }
    fun rowPxAt(scale: Float) = Math.round(ROW_DP * scale * density.density).coerceAtLeast(1)
    val rowPx = rowPxAt(zoom)
    val headPx = Math.round(HEAD_DP * zoom * density.density).coerceAtLeast(1)
    fun px(value: Int): Dp = with(density) { value.toDp() }

    val screenPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    var viewport by remember { mutableIntStateOf(screenPx) }
    val visible by remember(geo) {
        derivedStateOf { visibleColumns(geo.starts, geo.widths, h.value, viewport) }
    }
    val freeze = colors.fg3.copy(alpha = 0.55f)

    // The pinch in flight. Read only by the grid's layer, so a pinch frame redraws the grid and
    // composes nothing.
    var live by remember { mutableStateOf(SheetPinch()) }
    val pending = remember { PendingScroll() }
    val box = remember { GridBox() }
    val zoomRef = rememberUpdatedState(zoom)
    val geoRef = rememberUpdatedState(geo)
    val zoomChange = rememberUpdatedState(onZoom)
    val unscaledPx = remember(plan, density) {
        (plan.pinCols until plan.columns).sumOf { plan.widths[it].toDouble() }.toFloat() * density.density
    }
    val ceiling = SheetZoom.clamp(SheetZoom.MAX, unscaledPx)

    fun bandAt(scale: Float, g: SheetGeometry): Int =
        Math.round(HEAD_DP * scale * density.density) + plan.pinRows * rowPxAt(scale) +
            (if (plan.pinRows > 0) g.freeze else 0)

    // Both directions of the grid as it is laid out now.
    fun axes(): Pair<SheetAxis, SheetAxis> {
        val z = zoomRef.value
        val g = geoRef.value
        val row = rowPxAt(z)
        val scrollY = list.firstVisibleItemIndex.toFloat() * row + list.firstVisibleItemScrollOffset
        return SheetAxis(h.value.toFloat(), g.movingLeft.toFloat(), g.total.toFloat(), box.width.toFloat()) to
            SheetAxis(scrollY, bandAt(z, g).toFloat(), plan.body.size.toFloat() * row, box.height.toFloat())
    }

    // Landing: the zoom the picture showed, and the scroll that puts every cell where the
    // picture had it, set together so the frame that lays out the new size draws it in place.
    // The list takes its position at its next measure. The sideways one is set at once unless it
    // is past the old width, which only the new layout makes room for; that one waits a frame.
    val land: (SheetPinch) -> Unit = land@{ p ->
        live = SheetPinch()
        if (p.idle) return@land
        val from = zoomRef.value
        val to = SheetZoom.clamp(from * p.scale, unscaledPx)
        val (across, down) = axes()
        val after = SheetGeometry(plan.widths, plan.gutter, plan.pinCols, to, density.density)
        val row = rowPxAt(to)
        val bandAfter = bandAt(to, after)
        val s = p.copy(scale = to / from)
        val farX = (after.total - (box.width - after.movingLeft)).coerceAtLeast(0)
        val farY = (plan.body.size * row - (box.height - bandAfter)).coerceAtLeast(0)
        val x = Math.round(s.lands(across, after.movingLeft.toFloat(), p.x)).coerceIn(0, farX)
        val y = Math.round(s.lands(down, bandAfter.toFloat(), p.y)).coerceIn(0, farY)
        if (to != from) {
            box.landingAt = to
            zoomChange.value(to)
        }
        list.requestScrollToItem(y / row, y % row)
        h.dispatchRawDelta((x - h.value).toFloat())
        pending.x = if (h.value == x) -1f else x.toFloat()
    }
    LaunchedEffect(zoom) {
        if (pending.x < 0f) return@LaunchedEffect
        withFrameNanos { }
        h.scrollTo(Math.round(pending.x))
        pending.x = -1f
    }
    // The reading spot. Read through held objects, so the workbook can ask after this has gone.
    SideEffect {
        place.read = {
            val i = list.firstVisibleItemIndex
            val row = when (val line = plan.body.getOrNull(i)) {
                is Line.Data -> line.index
                is Line.Gap -> line.from
                null -> plan.pinRows
            }
            val starts = geoRef.value.starts
            val k = starts.lastIndexAtOrBelow(h.value).coerceAtLeast(0)
            SheetAt(part, row, if (h.value == 0) 0 else plan.pinCols + k, top = i == 0 && h.value == 0)
        }
    }
    val go = place.go
    LaunchedEffect(go) {
        if (go == null || go.part != part) return@LaunchedEffect
        // A frame first, so the scroll states know the grid's extent and nothing is clamped to 0.
        withFrameNanos { }
        val at = plan.body.indexOfFirst {
            (it is Line.Data && it.index >= go.row) || (it is Line.Gap && go.row < it.from + it.count)
        }
        list.scrollToItem(if (at >= 0) at else (plan.body.size - 1).coerceAtLeast(0))
        val starts = geoRef.value.starts
        val k = go.column - plan.pinCols
        h.scrollTo(if (k <= 0 || starts.isEmpty()) 0 else starts[k.coerceAtMost(starts.size - 1)])
        // Cleared last: clearing it recomposes, which would cancel this before it had scrolled.
        place.go = null
    }
    val pinch = rememberUpdatedState { factor: Float, at: Offset ->
        // After a landing mid-pinch, the events before the new layout would be read against the
        // old one. Their ratios are kept and spent on the first event that sees the new one.
        if (box.landingAt >= 0f && zoomRef.value != box.landingAt) {
            box.held *= factor
        } else {
            box.landingAt = -1f
            val ratio = factor * box.held
            box.held = 1f
            val (across, down) = axes()
            val next = live.by(ratio, at.x, at.y, zoomRef.value, SheetZoom.MIN, ceiling).held(across, down)
            if (next.outOfRows) land(next) else live = next
        }
    }
    val lift = rememberUpdatedState {
        box.held = 1f
        if (!live.idle) land(live)
    }
    val doubleTap = rememberUpdatedState { at: Offset ->
        val from = zoomRef.value
        val (across, down) = axes()
        land(SheetPinch().by(SheetZoom.doubleTap(from) / from, at.x, at.y, from, SheetZoom.MIN, ceiling).held(across, down))
    }

    val cellAt: @Composable (Line.Data, Int, Boolean) -> Unit = { line, col, pinned ->
        CellBox(
            cell = line.row?.at(col),
            look = sheet.lookAt(line.row, col),
            selected = selected == (line.index to col),
            pinned = pinned,
            headerRow = line.index < plan.pinRows,
            scale = zoom,
            onClick = { selected = if (selected == (line.index to col)) null else line.index to col },
        )
    }

    val dataRow: @Composable (Line.Data) -> Unit = { line ->
        Row(Modifier.fillMaxWidth().height(px(rowPx))) {
            Gutter((line.index + 1).toString(), px(geo.gutter), zoom)
            for (c in 0 until plan.pinCols) {
                Box(Modifier.width(px(geo.pinned[c])).fillMaxHeight()) { cellAt(line, c, true) }
            }
            if (plan.pinCols > 0) FreezeLine(px(geo.freeze), vertical = true, color = freeze)
            ScrollStrip(
                shift = { h.value },
                starts = geo.starts,
                widths = geo.widths,
                range = visible,
                heightPx = rowPx,
                modifier = Modifier.weight(1f),
            ) { k -> cellAt(line, plan.pinCols + k, false) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        sheet.note?.let {
            Text(
                it,
                fontSize = 11.sp,
                color = colors.warn,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    var lastTapAt = 0L
                    var lastTapPos = Offset.Zero
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var pinched = false
                        var moved = false
                        var upAt = down.uptimeMillis
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.count { it.pressed } >= 2) {
                                    pinched = true
                                    val factor = event.calculateZoom()
                                    if (factor != 1f) pinch.value(factor, event.calculateCentroid(useCurrent = true))
                                    event.changes.forEach { it.consume() }
                                } else {
                                    // One finger lifted and one is left: the pinch is over, and the
                                    // finger left scrolls a grid that is laid out, not a picture of one.
                                    if (pinched) lift.value()
                                    if (!moved) {
                                        val c = event.changes.firstOrNull { it.id == down.id }
                                        if (c != null && (c.position - down.position).getDistance() > slop) moved = true
                                    }
                                }
                                if (event.changes.none { it.pressed }) {
                                    upAt = event.changes.firstOrNull()?.uptimeMillis ?: upAt
                                    break
                                }
                            }
                        } finally {
                            // A gesture cut off half way still lands, or the grid stays a picture.
                            if (pinched) lift.value()
                        }
                        if (pinched || moved || upAt - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) {
                            lastTapAt = 0L
                        } else if (down.uptimeMillis - lastTapAt < viewConfiguration.doubleTapTimeoutMillis &&
                            (down.position - lastTapPos).getDistance() < slop * 3
                        ) {
                            doubleTap.value(down.position)
                            lastTapAt = 0L
                        } else {
                            lastTapAt = upAt
                            lastTapPos = down.position
                        }
                    }
                }
                // A sideways drag anywhere on the grid moves the one shared offset. Vertical
                // drags fall through to the LazyColumn; each axis claims only its own.
                .scrollable(h, Orientation.Horizontal, reverseDirection = true)
                .onSizeChanged {
                    box.width = it.width
                    box.height = it.height
                }
                // The pinch in flight, about the grid's corner. After the input and the size
                // above, so touches and the size are read in the grid's own untransformed space.
                .graphicsLayer {
                    val p = live
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = p.scale
                    scaleY = p.scale
                    translationX = p.x
                    translationY = p.y
                },
        ) {
            // Column letters. This strip owns the horizontal state - see the class note.
            Row(Modifier.fillMaxWidth().height(px(headPx)).background(colors.sunken)) {
                Gutter("", px(geo.gutter), zoom)
                for (c in 0 until plan.pinCols) {
                    Box(Modifier.width(px(geo.pinned[c])).fillMaxHeight()) { Letter(c, zoom) }
                }
                if (plan.pinCols > 0) FreezeLine(px(geo.freeze), vertical = true, color = freeze)
                HScroll(
                    ground = colors.sunken,
                    state = h,
                    modifier = Modifier.weight(1f).onSizeChanged { viewport = it.width },
                ) {
                    ScrollStrip(
                        shift = { 0 },
                        starts = geo.starts,
                        widths = geo.widths,
                        range = visible,
                        heightPx = headPx,
                        modifier = Modifier.width(px(geo.total)),
                    ) { k -> Letter(plan.pinCols + k, zoom) }
                }
            }
            for (line in plan.pinned) dataRow(line)
            if (plan.pinRows > 0) FreezeLine(px(geo.freeze), vertical = false, color = freeze)

            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(
                    plan.body,
                    key = { if (it is Line.Data) it.index else -1 - (it as Line.Gap).from },
                ) { line ->
                    when (line) {
                        is Line.Data -> dataRow(line)
                        is Line.Gap -> GapRow(line, px(geo.gutter), px(rowPx), zoom)
                    }
                }
            }
        }

        selected?.let { (r, c) ->
            val cell = sheet.rows.binarySearchBy(r) { it.index }.let { i -> if (i >= 0) sheet.rows[i].at(c) else null }
            CellDetail(CellRef.name(c) + (r + 1), cell) { selected = null }
        }
    }
}

/**
 * Which moving columns intersect the viewport, with one either side so a fling does not show
 * an empty edge for a frame before the next column composes.
 */
private fun visibleColumns(starts: IntArray, widths: IntArray, offset: Int, viewport: Int): IntRange {
    if (starts.isEmpty()) return IntRange.EMPTY
    val first = (starts.lastIndexAtOrBelow(offset)).coerceAtLeast(0)
    val last = starts.lastIndexAtOrBelow(offset + viewport.coerceAtLeast(1))
    return (first - 1).coerceAtLeast(0)..(last + 1).coerceAtMost(starts.size - 1)
}

/** The last index whose value is at or below [x], or -1. [this] is ascending. */
private fun IntArray.lastIndexAtOrBelow(x: Int): Int {
    var lo = 0
    var hi = size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid] <= x) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

/**
 * A row of cells at fixed positions, shifted by an offset read at LAYOUT time.
 *
 * Reading the scroll value here rather than in composition is the whole performance story of
 * the grid: a sideways drag re-places the visible cells of each row and composes nothing.
 * Each cell is measured at exactly its column's width and the row's height, so a long value is
 * cut at the column edge as on a desktop, rather than pushing its neighbours along.
 */
@Composable
private fun ScrollStrip(
    shift: () -> Int,
    starts: IntArray,
    widths: IntArray,
    range: IntRange,
    heightPx: Int,
    modifier: Modifier,
    cell: @Composable (Int) -> Unit,
) {
    Layout(
        content = { for (k in range) key(k) { cell(k) } },
        modifier = modifier.fillMaxHeight().clipToBounds(),
    ) { measurables, constraints ->
        val placeables = measurables.mapIndexed { i, m ->
            m.measure(Constraints.fixed(widths[range.first + i], heightPx))
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        layout(width, heightPx) {
            val x0 = shift()
            placeables.forEachIndexed { i, p -> p.place(starts[range.first + i] - x0, 0) }
        }
    }
}

/**
 * One cell, drawn the way the file formats it.
 *
 * The file's fill replaces the grid's own shading of the header row and pinned columns: a
 * coloured band is the author's statement about which rows are headers, and the grid's guess
 * is only for files that make none. The selection is a tint OVER whatever is there, so a filled
 * cell still says it is selected without losing its colour. Which ink is used is
 * [SheetLook.ink], which is where the readability decision lives and is tested.
 */
@Composable
private fun CellBox(
    cell: Cell?,
    look: CellStyle?,
    selected: Boolean,
    pinned: Boolean,
    headerRow: Boolean,
    scale: Float,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val line = colors.lineSoft
    val shade = if (pinned || headerRow) colors.raised else Color.Transparent
    val fill = look?.fill?.let { Color(it) }
    // What is actually behind the text when the file gives no fill, so a font colour is judged
    // against the ground it will be read on.
    val behind = if (shade == Color.Transparent) MaterialTheme.colorScheme.background else shade
    val ink = remember(look, behind) { SheetLook.ink(look, behind.toArgb())?.let { Color(it) } }
    Box(
        Modifier
            .fillMaxSize()
            .background(fill ?: shade)
            .then(if (selected) Modifier.background(colors.accent.copy(alpha = 0.16f)) else Modifier)
            .drawBehind {
                // Right and bottom edges only; the neighbours draw the other two.
                drawLine(line, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
                drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
            }
            .then(if (selected) Modifier.border(1.5.dp, colors.accent) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = (6 * scale).dp),
        contentAlignment = if (cell?.kind?.alignEnd == true) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        if (cell != null && cell.text.isNotEmpty()) {
            Text(
                // One line per cell, as a desktop draws an unwrapped cell. The whole value,
                // line breaks and all, is in the detail bar a tap away.
                cell.text.replace('\n', ' '),
                fontSize = (12 * scale).sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                fontWeight = when {
                    look?.bold == true -> FontWeight.Bold
                    headerRow -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
                fontStyle = if (look?.italic == true) FontStyle.Italic else FontStyle.Normal,
                textDecoration = when {
                    look == null -> null
                    look.underline && look.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    look.underline -> TextDecoration.Underline
                    look.strike -> TextDecoration.LineThrough
                    else -> null
                },
                color = ink ?: when (cell.kind) {
                    CellKind.ERROR -> colors.warn
                    CellKind.BOOL -> colors.fg2
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun Letter(col: Int, scale: Float) {
    val colors = Filet.colors
    val line = colors.lineSoft
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawLine(line, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(CellRef.name(col), fontSize = (10 * scale).sp, color = colors.fg3, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Gutter(label: String, width: Dp, scale: Float) {
    val colors = Filet.colors
    Box(
        Modifier.width(width).fillMaxHeight().background(colors.sunken).padding(end = (6 * scale).dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (label.isNotEmpty()) Text(label, fontSize = (10 * scale).sp, color = colors.fg3, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun FreezeLine(thickness: Dp, vertical: Boolean, color: Color) {
    Box(
        (if (vertical) Modifier.width(thickness).fillMaxHeight() else Modifier.fillMaxWidth().height(thickness))
            .background(color),
    )
}

@Composable
private fun GapRow(gap: Line.Gap, gutter: Dp, height: Dp, scale: Float) {
    val colors = Filet.colors
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        Gutter("⋮", gutter, scale)
        // Collapsed rather than drawn: a stray value at row 90,000 would otherwise mean
        // scrolling through ninety thousand blank rows to reach it.
        Text(
            "%,d empty rows, %,d to %,d".format(gap.count, gap.from + 1, gap.from + gap.count),
            fontSize = (10.5f * scale).sp,
            maxLines = 1,
            softWrap = false,
            color = colors.fg3,
            modifier = Modifier.padding(horizontal = (10 * scale).dp),
        )
    }
}

/**
 * What a tapped cell holds: its whole text, its formula, and the stored value behind a
 * formatted one.
 *
 * This is where the formula lives. A grid shows the cached result, as a desktop does; the
 * formula is a tap away, as a desktop's formula bar is, rather than discarded.
 */
@Composable
private fun CellDetail(ref: String, cell: Cell?, onClose: () -> Unit) {
    val colors = Filet.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.raised)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ref,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.accent.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when (cell?.kind) {
                    null -> "empty"
                    CellKind.TEXT -> "text"
                    CellKind.NUMBER -> "number"
                    CellKind.DATE -> "date"
                    CellKind.BOOL -> "true or false"
                    CellKind.ERROR -> "error"
                },
                fontSize = 10.5.sp,
                color = colors.fg3,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Close",
                fontSize = 11.sp,
                color = colors.fg2,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClose)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (cell != null) {
            SelectionContainer {
                Column {
                    if (cell.text.isNotEmpty()) {
                        Text(
                            cell.text,
                            fontSize = 13.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    cell.formula?.let {
                        Text(
                            "=$it",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            color = colors.fg2,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    cell.raw?.let {
                        Text("Stored as $it", fontSize = 10.5.sp, color = colors.fg3, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

/** A workbook that cannot be shown says what it is, and offers somewhere it can be. */
@Composable
private fun SheetProblemView(failed: SheetOpen.Failed, onElsewhere: () -> Unit, onClose: () -> Unit) {
    val colors = Filet.colors
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            failed.problem.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(failed.problem.body, fontSize = 12.sp, color = colors.fg2, textAlign = TextAlign.Center)
        if (failed.detail.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(failed.detail, fontSize = 10.5.sp, color = colors.fg3, textAlign = TextAlign.Center, maxLines = 3)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Open in another app", onElsewhere)
            Pill("Close", onClose, quiet = true)
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit, quiet: Boolean = false) {
    val colors = Filet.colors
    Text(
        label,
        fontSize = 12.sp,
        color = if (quiet) colors.fg2 else colors.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (quiet) colors.sunken else colors.accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

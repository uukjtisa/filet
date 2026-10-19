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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
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

    when (val o = opened) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }

        is SheetOpen.Failed -> SheetProblemView(
            o,
            onElsewhere = { vm.openExternally(node, force = true) },
            onClose = vm::closeHandler,
        )

        is SheetOpen.Ready -> WorkbookView(o.book, onElsewhere = { vm.openExternally(node, force = true) })
    }
}

@Composable
private fun WorkbookView(book: Workbook, onElsewhere: () -> Unit) {
    val colors = Filet.colors
    // The first tab a person would see. A hidden first sheet is common in exported workbooks
    // (lookup tables, macros) and is never the one the file was opened to read.
    var index by remember(book) {
        mutableIntStateOf(book.sheets.indexOfFirst { !it.hidden && !it.chart }.coerceAtLeast(0))
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
            else -> key(index) { SheetGrid(current, zoom) { zoom = it } }
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

/** An offset waiting for the layout at a new scale, so it is applied against the new bounds. */
private class PendingScroll {
    var x = -1f
    var y = -1f
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
private fun SheetGrid(sheet: Sheet, zoom: Float, onZoom: (Float) -> Unit) {
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

    // Zooming about a point: the new offsets are worked out against the OLD scale and applied
    // after the next layout, when the scroll states know the new extent. Applied at once, the
    // horizontal one would be clamped to the old width and the zoom would jump.
    val pending = remember { PendingScroll() }
    val zoomRef = rememberUpdatedState(zoom)
    val geoRef = rememberUpdatedState(geo)
    val zoomChange = rememberUpdatedState(onZoom)
    val unscaledPx = remember(plan, density) {
        (plan.pinCols until plan.columns).sumOf { plan.widths[it].toDouble() }.toFloat() * density.density
    }
    val zoomTo: (Float, Offset) -> Unit = { target, at ->
        val from = zoomRef.value
        val to = SheetZoom.clamp(target, unscaledPx)
        if (to != from) {
            val g = geoRef.value
            val band = Math.round(HEAD_DP * from * density.density) + plan.pinRows * rowPxAt(from) +
                (if (plan.pinRows > 0) g.freeze else 0)
            val scrollY = list.firstVisibleItemIndex.toFloat() * rowPxAt(from) + list.firstVisibleItemScrollOffset
            pending.x = SheetZoom.offsetAbout(h.value.toFloat(), (at.x - g.movingLeft).coerceAtLeast(0f), from, to)
            pending.y = SheetZoom.offsetAbout(scrollY, (at.y - band).coerceAtLeast(0f), from, to)
            zoomChange.value(to)
        }
    }
    LaunchedEffect(zoom) {
        if (pending.x < 0f) return@LaunchedEffect
        withFrameNanos { }
        h.scrollTo(Math.round(pending.x))
        val y = Math.round(pending.y)
        val row = rowPxAt(zoom)
        list.scrollToItem(y / row, y % row)
        pending.x = -1f
        pending.y = -1f
    }
    val pinch = rememberUpdatedState { factor: Float, at: Offset -> zoomTo(zoomRef.value * factor, at) }
    val doubleTap = rememberUpdatedState { at: Offset -> zoomTo(SheetZoom.doubleTap(zoomRef.value), at) }

    val cellAt: @Composable (Line.Data, Int, Boolean) -> Unit = { line, col, pinned ->
        CellBox(
            cell = line.row?.at(col),
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
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                pinched = true
                                val factor = event.calculateZoom()
                                if (factor != 1f) pinch.value(factor, event.calculateCentroid(useCurrent = true))
                                event.changes.forEach { it.consume() }
                            } else if (!moved) {
                                val c = event.changes.firstOrNull { it.id == down.id }
                                if (c != null && (c.position - down.position).getDistance() > slop) moved = true
                            }
                            if (event.changes.none { it.pressed }) {
                                upAt = event.changes.firstOrNull()?.uptimeMillis ?: upAt
                                break
                            }
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
                .scrollable(h, Orientation.Horizontal, reverseDirection = true),
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

@Composable
private fun CellBox(
    cell: Cell?,
    selected: Boolean,
    pinned: Boolean,
    headerRow: Boolean,
    scale: Float,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val line = colors.lineSoft
    val ground = when {
        selected -> colors.accent.copy(alpha = 0.16f)
        pinned || headerRow -> colors.raised
        else -> Color.Transparent
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(ground)
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
                fontWeight = if (headerRow) FontWeight.Medium else FontWeight.Normal,
                color = when (cell.kind) {
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

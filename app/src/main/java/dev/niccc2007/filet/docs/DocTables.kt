package dev.niccc2007.filet.docs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet

/**
 * How wide each column of a table is - the decision a table gets wrong, kept where it can be
 * tested.
 *
 * ## The rule: a browser's, for a table with no widths
 *
 * Every column has a narrowest width (its longest word, which cannot break) and a widest (its
 * longest cell on one line). Then:
 *
 * - if every column fits at its widest, that is what it gets - a small table stays small;
 * - if the narrowest widths fit, each column gets its narrowest plus a share of the spare width in
 *   proportion to how much wider it wanted to be, which is CSS's automatic table layout. A column
 *   of short numbers stays narrow and the column of sentences takes the room;
 * - if even the narrowest widths do not fit, the columns go below them, each in proportion to
 *   its narrowest above a floor of [FLOOR] dp, and the words inside break - CSS's
 *   `overflow-wrap: anywhere`. Only a table too wide at that floor scrolls.
 *
 * Decision: Fit fits. The rule before the last one was the browser default, keep the words whole
 * and scroll the table, and on a phone it produced a table that wrapped every cell AND scrolled
 * sideways: a path in code or a date is one unbreakable word, a few of them outgrew the screen,
 * and the setting that promises a table the width of the page delivered one that was not. A word
 * broken at the edge of a cell is the price, and it is a smaller one than a sideways scroll inside
 * a table that says it wraps. Comfortable is the mode that keeps every word whole.
 *
 * The fixed 124 dp cell this replaces failed both ways: a two-column table of words was mostly
 * empty, and a column of sentences wrapped to a word a line inside a table that then scrolled.
 */
internal object TableLayout {

    data class Widths(val columns: List<Float>, val scrolls: Boolean) {
        val total: Float get() = columns.sum()
    }

    /** The narrowest a column goes when words have to break to fit: two or three letters and its padding. */
    const val FLOOR = 40f

    fun fit(min: List<Float>, max: List<Float>, available: Float): Widths {
        val n = min.size
        if (n == 0) return Widths(emptyList(), false)
        val lo = List(n) { min[it].coerceAtLeast(0f) }
        val hi = List(n) { max(lo[it], max.getOrElse(it) { 0f }) }
        val sumMin = lo.sum()
        val sumMax = hi.sum()
        return when {
            sumMax <= available -> Widths(hi, false)
            sumMin <= available -> {
                val spare = available - sumMin
                val want = sumMax - sumMin
                Widths(if (want <= 0f) lo else List(n) { lo[it] + (hi[it] - lo[it]) * spare / want }, false)
            }
            else -> {
                // Below the narrowest: each column keeps its floor, and the room left over goes by
                // how far above the floor its narrowest was, so the long words keep the most.
                val floor = List(n) { minOf(lo[it], FLOOR) }
                val sumFloor = floor.sum()
                if (sumFloor >= available) {
                    Widths(floor, sumFloor > available)
                } else {
                    val k = (available - sumFloor) / (sumMin - sumFloor)
                    Widths(List(n) { floor[it] + (lo[it] - floor[it]) * k }, false)
                }
            }
        }
    }

    /** Every column at its widest - Comfortable - scrolled when that is wider than the room. */
    fun comfortable(min: List<Float>, max: List<Float>, available: Float): Widths {
        val hi = List(min.size) { max(min[it], max.getOrElse(it) { 0f }) }
        return Widths(hi, hi.sum() > available)
    }

    /**
     * Each column's narrowest and widest, from its cells'.
     *
     * A cell spanning several columns is placed after the single ones and only widens them by
     * what they lack between them, shared evenly - so a merged title row cannot blow one column
     * up on its own.
     *
     * @param cells per row, per cell: its narrowest and widest.
     * @param spans per row, per cell: how many columns it covers. Missing means one.
     */
    fun bounds(cells: List<List<Pair<Float, Float>>>, spans: List<List<Int>>, columns: Int): Pair<List<Float>, List<Float>> {
        val lo = FloatArray(columns)
        val hi = FloatArray(columns)
        val wide = ArrayList<Triple<Int, Int, Pair<Float, Float>>>()
        for ((r, row) in cells.withIndex()) {
            var col = 0
            for ((c, cell) in row.withIndex()) {
                if (col >= columns) break
                val span = spans.getOrNull(r)?.getOrNull(c)?.coerceAtLeast(1) ?: 1
                if (span == 1) {
                    lo[col] = max(lo[col], cell.first)
                    hi[col] = max(hi[col], cell.second)
                } else {
                    wide += Triple(col, minOf(span, columns - col), cell)
                }
                col += span
            }
        }
        for ((start, span, cell) in wide) {
            val range = start until start + span
            val haveLo = range.sumOf { lo[it].toDouble() }.toFloat()
            if (cell.first > haveLo) for (i in range) lo[i] += (cell.first - haveLo) / span
            val haveHi = range.sumOf { hi[it].toDouble() }.toFloat()
            if (cell.second > haveHi) for (i in range) hi[i] += (cell.second - haveHi) / span
        }
        return lo.toList() to List(columns) { max(lo[it], hi[it]) }
    }

    /** How many grid columns a table has: its widest row, counting merges. */
    fun columnsOf(rows: List<List<String>>, spans: List<List<Int>>): Int =
        rows.withIndex().maxOfOrNull { (r, row) ->
            row.indices.sumOf { c -> spans.getOrNull(r)?.getOrNull(c)?.coerceAtLeast(1) ?: 1 }
        } ?: 0

    /** The words a cell cannot break inside, longest first by characters - the ones worth measuring. */
    fun longestWords(text: String, take: Int = 2): List<String> =
        text.split(' ', '\n', '\t').filter { it.isNotEmpty() }.sortedByDescending { it.length }.take(take)

    private fun max(a: Float, b: Float) = if (a > b) a else b
}

/** A table's type and cell padding, by mode. */
private class TableLook(val size: TextUnit, val line: TextUnit, val padX: Dp, val padY: Dp)

private fun lookOf(mode: TableMode) = when (mode) {
    TableMode.COMPACT -> TableLook(10.5.sp, 14.sp, 6.dp, 3.dp)
    TableMode.FIT, TableMode.COMFORTABLE -> TableLook(12.sp, 17.sp, 10.dp, 4.dp)
}

/** How tall a table may be before it scrolls inside itself, unless it has been sized by hand. */
private val TABLE_MAX = 460.dp

/** More rows than this and a table earns a Resize chip even when it fits. */
private const val RESIZE_ROWS = 8

/** What a picture in a cell counts as, for the widths, before it has loaded. */
private const val CELL_PICTURE_DP = 22f

/**
 * A table: ruled cells, a header on the raised colour, columns set by [TableLayout].
 *
 * The widths are measured once, at the page's own density, and given in dp - which the reader's
 * zoom then scales with everything else, so a pinch does not re-measure every cell every frame.
 */
@Composable
internal fun TableView(block: Block.Table, env: DocEnv) {
    val colors = Filet.colors
    var override by remember(block) { mutableStateOf<TableMode?>(null) }
    val mode = override ?: env.reading.tables
    val look = lookOf(mode)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val columns = remember(block) { TableLayout.columnsOf(block.rows, block.colSpans) }
    val bounds = remember(block, mode) {
        val body = TextStyle(fontSize = look.size, lineHeight = look.line)
        val head = body.copy(fontWeight = FontWeight.SemiBold)
        val pad = 2 * look.padX.value + 1f
        val cells = block.rows.mapIndexed { r, row ->
            val style = if (block.header && r == 0) head else body
            row.mapIndexed { c, text ->
                val pictures = block.rich.getOrNull(r)?.getOrNull(c)?.pictures.orEmpty()
                val plain = text.replace("￼", "")
                val widest = if (plain.isBlank()) 0f else env.dp(measurer.measure(plain, style, softWrap = false, constraints = Constraints()).size.width)
                val words = TableLayout.longestWords(plain).maxOfOrNull { w ->
                    env.dp(measurer.measure(w, style, softWrap = false, constraints = Constraints()).size.width)
                } ?: 0f
                val pic = pictures.maxOfOrNull { if (it.width > 0) it.width.toFloat() else CELL_PICTURE_DP } ?: 0f
                val picsRow = pictures.sumOf { (if (it.width > 0) it.width.toFloat() else CELL_PICTURE_DP).toDouble() }.toFloat()
                (maxOf(words, pic) + pad) to (widest + picsRow + pad)
            }
        }
        TableLayout.bounds(cells, block.colSpans, columns)
    }
    val size = remember(block) { BlockSize() }
    val scroll = rememberScrollState()
    val density = LocalDensity.current.density
    size.overflow = { scroll.maxValue / density }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val page = maxWidth.value
        // A table sized by hand lays its columns out to its own width, so narrowing it wraps
        // more rather than cropping.
        val room = size.width ?: page
        Column(Modifier.fillMaxWidth()) {
            val widths = when (mode) {
                TableMode.COMFORTABLE -> TableLayout.comfortable(bounds.first, bounds.second, room)
                TableMode.FIT, TableMode.COMPACT -> TableLayout.fit(bounds.first, bounds.second, room)
            }
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .then(size.width?.let { Modifier.width(it.dp) } ?: Modifier)
                    .onSizeChanged { size.shownW = it.width / density }
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, colors.lineSoft, shape)
                    .heightIn(max = size.height?.dp ?: TABLE_MAX)
                    .onSizeChanged { size.shownH = it.height / density }
                    .verticalScroll(scroll),
            ) {
                // One scroller for the WHOLE table, so the columns stay lined up. A scroller per
                // row is what a Column of scrollable Rows gives and it comes apart when touched.
                dev.niccc2007.filet.ui.HScroll(ground = MaterialTheme.colorScheme.background, contentPadding = 0.dp) {
                    Column {
                        for ((r, row) in block.rows.withIndex()) {
                            val head = block.header && r == 0
                            Row(Modifier.height(IntrinsicSize.Min)) {
                                var col = 0
                                for ((c, cellText) in row.withIndex()) {
                                    if (col >= columns) break
                                    val span = block.colSpans.getOrNull(r)?.getOrNull(c)?.coerceAtLeast(1) ?: 1
                                    val w = (col until minOf(col + span, columns)).sumOf { widths.columns.getOrElse(it) { 0f }.toDouble() }
                                    val rich = block.rich.getOrNull(r)?.getOrNull(c) ?: Rich()
                                    Box(
                                        Modifier
                                            .width(w.toFloat().dp)
                                            .fillMaxHeight()
                                            .background(if (head) colors.high else Color.Transparent)
                                            .border(0.5.dp, colors.lineSoft)
                                            .padding(horizontal = look.padX, vertical = look.padY),
                                    ) {
                                        RichText(
                                            text = cellText,
                                            spans = rich.spans,
                                            pictures = rich.pictures,
                                            size = look.size,
                                            line = look.line,
                                            weight = if (head) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (head) colors.accent else MaterialTheme.colorScheme.onSurface,
                                            align = block.aligns.getOrElse(col) { Align.START },
                                            ground = if (head) colors.high else MaterialTheme.colorScheme.background,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                    col += span
                                }
                            }
                        }
                    }
                }
            }
            // Each chip only where it changes something: a table whose every column already fits
            // on one line has nothing to unwrap, and a short narrow one has nothing to resize.
            val wraps = bounds.second.sum() > room
            val sizable = wraps || block.rows.size > RESIZE_ROWS || size.resized
            if (wraps || sizable) Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (wraps) {
                    val wrapping = mode != TableMode.COMFORTABLE
                    Chip(if (wrapping) "↵ Wrap ✓" else "↵ Wrap", on = wrapping) {
                        override = if (wrapping) TableMode.COMFORTABLE else env.reading.tables.takeIf { it != TableMode.COMFORTABLE } ?: TableMode.FIT
                    }
                }
                if (sizable) ResizeChip(size, page)
            }
        }
    }
}

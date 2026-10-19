package dev.niccc2007.filet.docs

/**
 * A spreadsheet, as something that can be drawn.
 *
 * ## Why this is not `Block.Table`
 *
 * `Block.Table` is rows of strings, which is the right shape for a table inside a document and
 * the wrong one for a sheet, for four reasons that each lose data:
 *
 * - **Position.** A sheet is sparse. A row holding A, B and F has two empty columns in it, and a
 *   list of strings has nowhere to say so - the F value lands under C. Every cell here carries
 *   its own column index, so a gap is a gap.
 * - **Value versus text.** A cell shows `3/14/2026` and holds `46095`; it shows `12.5%` and holds
 *   `0.125`; it shows `42` and was computed by `=SUM(B1:B3)`. A reader wants the first and
 *   sometimes needs the other two. A string keeps one of the three.
 * - **Sheets.** A workbook is several grids, by name, in an order the file decides.
 * - **Frozen panes.** The file says which rows and columns it keeps on screen, and a grid that
 *   loses its header row while scrolling is unreadable at any size.
 *
 * So the spreadsheet readers produce this, and `SheetScreen` draws it. Nothing in the document
 * stack had to change for it, and nothing in it depends on the document stack.
 */
class Workbook internal constructor(
    /** Every sheet, in workbook order - which is the tab order, not the order of the parts. */
    val sheets: List<SheetInfo>,
    /**
     * How to get a sheet's cells. Kept behind a function because an XLSX sheet is read on
     * demand - a workbook of twelve 50,000-row sheets must not be held whole to show one - while
     * an ODS keeps every sheet in one part and is read in one pass.
     */
    private val load: (Int) -> Sheet,
) {
    fun sheet(index: Int): Sheet = load(index)
}

/** One tab of a workbook, before its cells are read. */
data class SheetInfo(
    val name: String,
    val hidden: Boolean = false,
    /** A chart sheet holds a chart and no cells. Listed so the tab order matches the file. */
    val chart: Boolean = false,
)

/**
 * One sheet's cells.
 *
 * @param rows non-empty rows only, ascending by [SheetRow.index]. The grid between them is
 *   implied, which is what lets a sheet with one value in row 1 and one in row 90,000 cost two
 *   rows and not ninety thousand.
 * @param columns how many columns the grid is wide: one past the right-most cell kept.
 * @param frozenRows rows the file asks to keep on screen. See [SheetInfo] and `SheetScreen`.
 * @param note said at the top when the sheet could only be read in part - see [SheetLimits].
 */
data class Sheet(
    val name: String,
    val rows: List<SheetRow>,
    val columns: Int,
    val frozenRows: Int = 0,
    val frozenCols: Int = 0,
    val note: String? = null,
) {
    val empty: Boolean get() = rows.isEmpty()

    /** One past the last row with anything in it, as a 0-based count. */
    val lastRow: Int get() = rows.lastOrNull()?.index?.plus(1) ?: 0
}

/** One row. [index] is 0-based: row "1" in the file is index 0. */
data class SheetRow(val index: Int, val cells: List<Cell>) {
    /** The cell in column [col], or null for a gap. Cells are sorted, so this is a search. */
    fun at(col: Int): Cell? {
        var lo = 0
        var hi = cells.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cells[mid].col
            when {
                c < col -> lo = mid + 1
                c > col -> hi = mid - 1
                else -> return cells[mid]
            }
        }
        return null
    }
}

/**
 * One cell.
 *
 * @param col 0-based column: A is 0, Z is 25, AA is 26.
 * @param text what a spreadsheet would show - the formatted value.
 * @param raw the stored value, when it differs from [text]: the serial number behind a date,
 *   the fraction behind a percentage. Null when they are the same, which is most cells.
 * @param formula the formula without its `=`, when the cell has one. [text] is then its cached
 *   result, which is what the producing application computed on its last save.
 */
data class Cell(
    val col: Int,
    val text: String,
    val kind: CellKind = CellKind.TEXT,
    val raw: String? = null,
    val formula: String? = null,
)

/** What a cell holds, which decides alignment the same way a desktop spreadsheet does. */
enum class CellKind {
    TEXT, NUMBER, DATE, BOOL, ERROR;

    /** Numbers and dates sit on the right, so their digits line up down a column. */
    val alignEnd: Boolean get() = this == NUMBER || this == DATE
}

/**
 * How much of a sheet is held, and why the numbers are these.
 *
 * A phone has a heap measured in hundreds of megabytes and a CSV-style export of 100,000 rows by
 * 30 columns is three million cells. At around a hundred bytes per cell once its strings are
 * counted, holding all of it is a third of a gigabyte and the app is killed - not slowed. So the
 * readers stop, and say where.
 *
 * The cap is on CELLS rather than rows because cells are what cost memory: a 2-column sheet can
 * afford many more rows than a 200-column one, and a row cap would refuse the first to protect
 * against the second.
 */
object SheetLimits {
    /** Cells kept per sheet. Around twenty megabytes at worst, and far beyond what is read on a phone. */
    const val MAX_CELLS = 200_000

    /**
     * Columns drawn. 256 is IV, the width of every spreadsheet before 2007, and at the widest
     * cell size it keeps the grid under the largest width Compose can measure. A cell past it is
     * counted and reported rather than dropped silently.
     */
    const val MAX_COLS = 256

    /** Characters kept from one shared-strings table, across all of its strings. */
    const val MAX_STRING_CHARS = 8_000_000

    /** Characters kept per cell. A cell holding a pasted novel is drawn as its opening. */
    const val MAX_CELL_CHARS = 2_000
}

/**
 * The grid's zoom, as arithmetic.
 *
 * Pure so that the three things that go wrong with zoom - escaping its bounds, a double tap that
 * cycles somewhere unexpected, and the content sliding out from under the fingers - are tests
 * rather than things noticed on a phone.
 *
 * ## The bounds
 *
 * [MIN] is half size: cell text at 6sp is unreadable, but a zoomed-out sheet is for seeing its
 * SHAPE - where the blocks of data are, how wide it runs - and half size doubles what fits.
 * Below that every cell is a grey dash and shows nothing a smaller scale would add.
 * [MAX] is three times: body text at 36sp, past which a phone shows one or two cells and the
 * grid stops being a grid.
 */
object SheetZoom {
    const val MIN = 0.5f
    const val MAX = 3f

    /** Where a double tap goes from 1x. Twice the size reads a dense sheet without hunting. */
    const val DOUBLE_TAP = 2f

    /**
     * The widest a grid may be laid out, in pixels. Compose packs both dimensions of a
     * measurement into one Long, and a dimension past about 262,000 pixels cannot be expressed
     * - the layout throws. 256 wide columns at 3x on a dense screen are twice that, so the
     * ceiling for a wide sheet is lower than [MAX], decided by its width.
     */
    const val MAX_LAYOUT_PX = 250_000f

    /**
     * [scale] within the bounds, and within what a grid [unscaledPx] wide can lay out. Never
     * below 1x on account of width: every sheet the reader keeps fits at 1x.
     */
    fun clamp(scale: Float, unscaledPx: Float = 0f): Float {
        val ceiling = if (unscaledPx <= 0f) MAX else (MAX_LAYOUT_PX / unscaledPx).coerceIn(1f, MAX)
        return scale.coerceIn(MIN, ceiling)
    }

    /**
     * The double-tap cycle: at (or below) normal size it zooms in, anywhere zoomed in it comes
     * back to normal. Two states rather than three steps, because a gesture whose result
     * depends on how many times it has been made before cannot be done without looking.
     */
    fun doubleTap(scale: Float): Float = if (scale < 1.25f) DOUBLE_TAP else 1f

    /**
     * The scroll offset that keeps the content under [focus] where it is when the scale goes
     * from [from] to [to].
     *
     * The point under the fingers is at content position `(offset + focus) / from` in unscaled
     * units; after the change it is at that times [to], and the offset that puts it back under
     * [focus] is that minus [focus]. Without this a pinch zooms about the top-left corner and the
     * cell being reached for slides away. Never negative; the far bound is the scroll state's.
     */
    fun offsetAbout(offset: Float, focus: Float, from: Float, to: Float): Float =
        ((offset + focus) / from * to - focus).coerceAtLeast(0f)
}

/**
 * Where every column sits on screen, at one scale, in pixels.
 *
 * ONE object computes the geometry for the column letters and for every row of cells, because
 * the four quadrants of the grid must agree to the pixel at every zoom. Scaling the pinned
 * strips and the body separately - each rounding its own widths - is exactly how letters drift
 * off their columns a pixel per column until column Z is labelled as Y.
 *
 * Widths are rounded per column and the starts summed from the rounded widths, so a column's
 * left edge is a whole pixel that every strip reaches by the same addition.
 */
class SheetGeometry(
    widthsDp: FloatArray,
    gutterDp: Float,
    val pinCols: Int,
    val scale: Float,
    private val density: Float,
    freezeDp: Float = 1.5f,
) {
    private fun px(dp: Float): Int = Math.round(dp * scale * density).coerceAtLeast(1)

    val gutter: Int = px(gutterDp)

    /** The pinned columns' widths, left to right. */
    val pinned: IntArray = IntArray(pinCols) { px(widthsDp[it]) }

    /** The line drawn after the pinned columns. Not scaled: it is a rule, not content. */
    val freeze: Int = if (pinCols > 0) Math.round(freezeDp * density).coerceAtLeast(1) else 0

    /** The moving columns' widths, from the first one that is not pinned. */
    val widths: IntArray = IntArray(widthsDp.size - pinCols) { px(widthsDp[pinCols + it]) }

    /** Each moving column's left edge within the moving area. */
    val starts: IntArray = IntArray(widths.size).also { s ->
        for (i in 1 until widths.size) s[i] = s[i - 1] + widths[i - 1]
    }

    val total: Int = if (widths.isEmpty()) 0 else starts.last() + widths.last()

    /** Where the moving area begins: after the gutter, the pinned columns and the rule. */
    val movingLeft: Int = gutter + pinned.sum() + freeze

    /** A body cell's left edge on screen, with the grid scrolled sideways by [offset]. */
    fun cellLeft(col: Int, offset: Int): Int =
        if (col < pinCols) gutter + pinned.take(col).sum()
        else movingLeft + starts[col - pinCols] - offset

    /**
     * A column letter's left edge on screen. Computed through the header's own parts - its
     * corner, its pinned letters, its rule and its scrolled strip - so that a header that ever
     * stops being laid out from this object's numbers is caught by comparing the two.
     */
    fun letterLeft(col: Int, offset: Int): Int {
        val corner = gutter
        if (col < pinCols) return corner + (0 until col).sumOf { pinned[it] }
        val strip = corner + (0 until pinCols).sumOf { pinned[it] } + freeze
        return strip + starts[col - pinCols] - offset
    }
}

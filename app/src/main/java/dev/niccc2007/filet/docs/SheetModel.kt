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
 * @param columnStyles the look a column gives the cells in it that the file never wrote. See
 *   [lookAt].
 */
data class Sheet(
    val name: String,
    val rows: List<SheetRow>,
    val columns: Int,
    val frozenRows: Int = 0,
    val frozenCols: Int = 0,
    val note: String? = null,
    val columnStyles: List<ColumnStyle> = emptyList(),
) {
    val empty: Boolean get() = rows.isEmpty()

    /**
     * How the cell at [col] of [row] is drawn.
     *
     * A cell the file wrote carries its own style, and that is the whole answer - a written cell
     * with no style attribute is style 0, not "whatever the row says". A cell it did not write
     * takes the row's look when the row has one, and the column's after that, which is the
     * order a desktop resolves them in.
     */
    fun lookAt(row: SheetRow?, col: Int): CellStyle? {
        val cell = row?.at(col)
        if (cell != null) return cell.style
        row?.style?.let { return it }
        for (c in columnStyles) if (col in c.first..c.last) return c.style
        return null
    }

    /** One past the last row with anything in it, as a 0-based count. */
    val lastRow: Int get() = rows.lastOrNull()?.index?.plus(1) ?: 0
}

/**
 * One row. [index] is 0-based: row "1" in the file is index 0.
 *
 * @param style the look of the cells in this row the file did not write, when the whole row was
 *   formatted. Null for an ordinary row.
 */
data class SheetRow(val index: Int, val cells: List<Cell>, val style: CellStyle? = null) {
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
 * @param style how the file draws it. Null for a plain cell, which is most of them - and the
 *   instances are shared out of the workbook's style table, so a styled cell costs a reference.
 */
data class Cell(
    val col: Int,
    val text: String,
    val kind: CellKind = CellKind.TEXT,
    val raw: String? = null,
    val formula: String? = null,
    val style: CellStyle? = null,
)

/**
 * The part of a cell's formatting that changes how it reads: weight, slant, lines, and colour.
 *
 * Colours are opaque ARGB and already resolved - a theme slot with a tint, or an entry in the
 * legacy palette, is turned into the colour it names when the workbook is read, so drawing a
 * cell is a lookup and not a colour computation. See [SheetLook].
 *
 * @param fill the cell's background, null for none.
 * @param ink the font's colour, null for automatic. Whether it is USED is a separate question,
 *   answered by [SheetLook.ink], because a colour chosen against white paper can vanish on a
 *   dark screen.
 */
data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val fill: Int? = null,
    val ink: Int? = null,
) {
    /** Whether there is anything to draw. A style table is mostly entries that change nothing. */
    val plain: Boolean get() = this == PLAIN

    companion object {
        val PLAIN = CellStyle()
    }
}

/** A run of columns, [first] to [last] inclusive and 0-based, formatted as a whole. */
data class ColumnStyle(val first: Int, val last: Int, val style: CellStyle)

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
}

/**
 * A pinch in flight on the grid, drawn rather than laid out: the grid as it was last laid out,
 * scaled by [scale] about its top-left corner and moved by [x] and [y], in pixels. Pure.
 *
 * ## The lag this replaces
 *
 * Bug identified here: every pinch frame set the zoom, and the zoom is the font size of every
 * cell, so every frame laid out the text of every cell on screen again at a new size - a couple
 * of hundred text layouts a frame on a phone-sized sheet, which a phone cannot do in a frame.
 * The scroll that kept the cell under the fingers was then applied a frame later still, so what
 * did draw was drawn in the wrong place first. The pinch trailed the fingers and shook.
 *
 * Now the fingers move a transform, which costs no layout at all, and the grid is laid out again
 * once, at the size the transform shows, when the pinch [lands]. The cost of a pinch is one
 * layout instead of one per frame.
 *
 * ## Why the transform is not the whole answer
 *
 * A transform can only draw what was laid out. Growing, that is everything on screen and more.
 * Shrinking, the rows and columns that come into view were never composed and show as empty
 * ground, so a pinch that has shrunk the picture by [OUT] lands on the spot and carries on from
 * a fresh layout. Growing never needs to.
 */
data class SheetPinch(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {

    val idle: Boolean get() = scale == 1f && x == 0f && y == 0f

    /** Shrunk far enough that the picture would show ground that was never laid out. */
    val outOfRows: Boolean get() = scale < OUT

    /**
     * One touch event's [ratio] about ([cx], [cy]), on a grid laid out at [from]. The zoom it
     * shows is held within [lo] and [hi], so the picture never promises a size that cannot land.
     */
    fun by(ratio: Float, cx: Float, cy: Float, from: Float, lo: Float, hi: Float): SheetPinch {
        if (ratio.isNaN() || ratio <= 0f) return this
        val s = (from * scale * ratio).coerceIn(lo, hi) / from
        val r = s / scale
        // The point under the fingers stays under them: x -> c + (x - c) * r, on top of what
        // the transform already does.
        return SheetPinch(s, cx + (x - cx) * r, cy + (y - cy) * r)
    }

    /**
     * Moved, if need be, so that it lands on a real scroll position: nothing before the first
     * column or row, nothing past the last. Without this, shrinking at the top-left drags the
     * sheet away from its corner, and the landing snaps it back.
     *
     * The size each frozen band will have after landing is taken as its present size times the
     * scale. It is off by a pixel per frozen column for rounding, and [lands] works with the real
     * one, so the most that rounding costs is a pixel of settle.
     */
    fun held(across: SheetAxis, down: SheetAxis): SheetPinch {
        val nx = across.shiftWithin(scale, x)
        val ny = down.shiftWithin(scale, y)
        return if (nx == x && ny == y) this else copy(x = nx, y = ny)
    }

    /**
     * The scroll offset along [axis] that shows, laid out at the new size, what this transform
     * showed. [leadAfter] is the frozen band's real size at the new zoom.
     *
     * A point `u` of the old layout is drawn at `shift + scale * u`. In the moving area it shows
     * content at `offset + u - lead`; laid out again, that content is `scale` times as far in and
     * drawn at `leadAfter + content - newOffset`. Equal for every `u` when
     * `newOffset = scale * (offset - lead) + leadAfter - shift` - one offset puts the whole moving
     * area where the transform had it, not just the cell under the fingers.
     */
    fun lands(axis: SheetAxis, leadAfter: Float, shift: Float): Float =
        scale * (axis.offset - axis.lead) + leadAfter - shift

    companion object {
        /** Shrinking past this lands mid-pinch. See the class note. */
        const val OUT = 0.8f
    }
}

/**
 * One direction of the grid as last laid out, in pixels: how far it is scrolled, how big the
 * frozen band before the moving area is, how long the moving content is, and how big the grid
 * is on screen.
 */
data class SheetAxis(val offset: Float, val lead: Float, val content: Float, val size: Float) {

    /** The farthest the moving area can scroll at [scale], with the band scaled too. */
    fun farthest(scale: Float, leadAfter: Float = lead * scale): Float =
        (content * scale - (size - leadAfter)).coerceAtLeast(0f)

    /** [shift] moved so that landing at [scale] falls within 0 and [farthest]. */
    fun shiftWithin(scale: Float, shift: Float): Float {
        val leadAfter = lead * scale
        // The shift that would land at offset 0; any larger and the landing is negative.
        val atStart = scale * (offset - lead) + leadAfter
        // Where the start wins over the end: a sheet narrower than the screen sits at its start.
        return shift.coerceIn(atStart - farthest(scale, leadAfter), atStart)
    }
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

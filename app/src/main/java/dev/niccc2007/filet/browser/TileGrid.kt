package dev.niccc2007.filet.browser

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import dev.niccc2007.filet.data.ViewStep
import kotlin.math.floor
import kotlin.math.max

/**
 * How many tiles go across a grid pane.
 *
 * [GridCells.Adaptive] on its own fits as many cells of at least the step's width as the pane
 * allows, which is the right rule until the pane gets narrow: a split pane, or a large step on
 * a small phone, fits one and the grid becomes a list of very wide tiles. So the count never
 * drops below [FLOOR]. The widths themselves are `ViewStep.tile`, picked so a phone held
 * upright - 360dp to 412dp - gets four, three and two across, and a wider screen gets more.
 */
object TileGrid {

    /** The fewest columns a grid shows. One across is a list, and there is a list step for that. */
    const val FLOOR = 2

    /** Columns for [availableDp] of width, with cells no narrower than [minCellDp]. Pure. */
    fun columns(availableDp: Float, minCellDp: Float): Int {
        if (!availableDp.isFinite() || availableDp <= 0f || minCellDp <= 0f) return FLOOR
        return max(FLOOR, floor(availableDp / minCellDp).toInt())
    }

    /**
     * The picture inside a tile, for a grid [availableDp] wide at [step]. Pure; see `TileGridTest`.
     *
     * Bug identified here: the picture was `ViewStep.icon` - 30, 42 and 56dp - whatever the cell
     * came out at. Those were chosen against each step's NARROWEST cell, and the cell is almost
     * never the narrowest: the width is shared out, so Large grid on an upright phone is two
     * cells of 170 to 200dp holding a 56dp picture. Half of every tile was empty, and Large
     * looked like Medium with fewer columns - the step changed the count and not the thing the
     * step is for, which is a bigger picture.
     *
     * Decision: the slot is a share of the cell's inner width, and the share rises with the
     * step, so the three grids differ in how much picture they show as well as in how many go
     * across. Never smaller than the step's own icon, so a split pane forced to two narrow
     * cells keeps the size it always had.
     *
     * The glyph for a file with no picture is held well under the slot. A document glyph drawn
     * a hundred and thirty dp wide is a poster, not an icon; inside a slot that size it still
     * keeps every name in a row on the same line as its neighbours.
     */
    fun picture(availableDp: Float, step: ViewStep): TilePicture {
        val icon = step.icon.toFloat()
        val share = when (step) {
            ViewStep.COMPACT_LIST, ViewStep.LIST, ViewStep.COMFORTABLE -> return TilePicture(icon, icon)
            ViewStep.SMALL_GRID -> 0.45f
            ViewStep.MEDIUM_GRID -> 0.62f
            ViewStep.LARGE_GRID -> 0.82f
        }
        val cell = availableDp / columns(availableDp, step.tile!!.toFloat())
        val slot = if (cell.isFinite()) max(icon, (cell - CELL_PADDING) * share) else icon
        return TilePicture(slot = slot, glyph = max(icon, slot * 0.6f).coerceAtMost(slot))
    }

    /** A tile's own horizontal padding, both sides together. Matches `FileTileBody`. */
    private const val CELL_PADDING = 12f

    /** The grid's [GridCells]: [columns] across, sharing the width the way `Fixed` does. */
    fun cells(minCell: Dp): GridCells = AtLeast(minCell)

    private data class AtLeast(val minCell: Dp) : GridCells {
        override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
            // The same sum as Adaptive's, so a gap between cells counts against the fit.
            val count = columns((availableSize + spacing).toDp().value, (minCell + spacing.toDp()).value)
            val total = availableSize - spacing * (count - 1)
            val slot = total / count
            val extra = total % count
            return List(count) { slot + if (it < extra) 1 else 0 }
        }
    }
}

/**
 * A tile's picture slot and the glyph drawn in it when there is no picture, both in dp.
 *
 * The slot is the same for every tile in a grid, picture or not, so the names along a row
 * start at one height.
 */
data class TilePicture(val slot: Float, val glyph: Float)

package dev.niccc2007.filet.browser

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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

package dev.niccc2007.filet.browser

import dev.niccc2007.filet.data.ViewStep
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Grid columns at the widths a pane actually has.
 *
 * The grid sits 6dp in from each side, so a 360dp phone leaves it 348dp.
 */
class TileGridTest {

    private fun across(screenDp: Int, step: ViewStep) =
        TileGrid.columns(screenDp - 12f, step.tile!!.toFloat())

    @Test
    fun `medium grid is three across on every upright phone`() {
        for (w in listOf(360, 384, 393, 411, 412)) {
            assertEquals("$w dp", 3, across(w, ViewStep.MEDIUM_GRID))
        }
    }

    @Test
    fun `small is four and large is two on an upright phone`() {
        for (w in listOf(360, 384, 393, 411, 412)) {
            assertEquals("small at $w dp", 4, across(w, ViewStep.SMALL_GRID))
            assertEquals("large at $w dp", 2, across(w, ViewStep.LARGE_GRID))
        }
    }

    @Test
    fun `wider screens get more`() {
        assertEquals(5, across(600, ViewStep.MEDIUM_GRID))
        assertEquals(7, across(800, ViewStep.MEDIUM_GRID))
    }

    @Test
    fun `the narrowest pane that shows a grid is still two across`() {
        // Below 270dp a pane falls back to a list - see PaneMetrics.forWidth - so 270 is the
        // narrowest grid there is, and a split half or a shrunk pane lands near it.
        for (step in listOf(ViewStep.SMALL_GRID, ViewStep.MEDIUM_GRID, ViewStep.LARGE_GRID)) {
            assertEquals(step.name, true, across(270, step) >= 2)
        }
        assertEquals(2, across(270, ViewStep.LARGE_GRID))
    }

    @Test
    fun `nothing measured yet is the floor, not zero`() {
        assertEquals(TileGrid.FLOOR, TileGrid.columns(0f, 108f))
        assertEquals(TileGrid.FLOOR, TileGrid.columns(Float.NaN, 108f))
    }
}

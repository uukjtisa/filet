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

    private fun slot(screenDp: Int, step: ViewStep) = TileGrid.picture(screenDp - 12f, step).slot

    @Test
    fun `large grid fills most of its cell on an upright phone`() {
        // The defect: two cells of 170 to 200dp each holding the step's 56dp icon.
        for (w in listOf(360, 384, 393, 411, 412)) {
            val cell = (w - 12f) / 2
            assertEquals("large at $w dp", true, slot(w, ViewStep.LARGE_GRID) >= cell * 0.7f)
        }
    }

    @Test
    fun `each grid step shows a bigger picture than the one before it`() {
        for (w in listOf(360, 412, 600, 800)) {
            val small = slot(w, ViewStep.SMALL_GRID)
            val medium = slot(w, ViewStep.MEDIUM_GRID)
            val large = slot(w, ViewStep.LARGE_GRID)
            assertEquals("$w dp: $small < $medium < $large", true, small < medium && medium < large)
        }
    }

    @Test
    fun `a picture is never smaller than the step's own icon and never wider than its cell`() {
        for (w in listOf(0, 270, 300, 360, 412, 600, 1280)) {
            for (step in listOf(ViewStep.SMALL_GRID, ViewStep.MEDIUM_GRID, ViewStep.LARGE_GRID)) {
                val p = TileGrid.picture(w - 12f, step)
                assertEquals("$step at $w dp", true, p.slot >= step.icon)
                if (w > 0) {
                    val cell = (w - 12f) / TileGrid.columns(w - 12f, step.tile!!.toFloat())
                    assertEquals("$step at $w dp", true, p.slot <= cell)
                }
            }
        }
    }

    @Test
    fun `a glyph stays inside the slot and under the picture's size`() {
        val p = TileGrid.picture(400f, ViewStep.LARGE_GRID)
        assertEquals(true, p.glyph < p.slot)
        assertEquals(true, p.glyph >= ViewStep.LARGE_GRID.icon)
    }

    @Test
    fun `list steps keep their icon`() {
        for (step in listOf(ViewStep.COMPACT_LIST, ViewStep.LIST, ViewStep.COMFORTABLE)) {
            assertEquals(TilePicture(step.icon.toFloat(), step.icon.toFloat()), TileGrid.picture(400f, step))
        }
    }

    @Test
    fun `nothing measured yet is the floor, not zero`() {
        assertEquals(TileGrid.FLOOR, TileGrid.columns(0f, 108f))
        assertEquals(TileGrid.FLOOR, TileGrid.columns(Float.NaN, 108f))
    }
}

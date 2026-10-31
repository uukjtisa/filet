package dev.niccc2007.filet.browser

import dev.niccc2007.filet.browser.TileName.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grid names, fitted with a stand-in for the text measurer: a name takes `length * 0.55 * sp`
 * dp across, wrapped over the lines a step allows, in a tile [WIDTH] dp wide.
 */
class TileNameTest {

    /** Medium, the default grid, on a phone. */
    private val phone = TileName.ladder(realTileDp = 116f, wide = false, startSp = 13f)

    /** A fake layout: the nominal size it was asked for, and whether it overflowed. */
    private data class Laid(val sp: Float, val lines: Int, val over: Boolean)

    private fun fitOf(name: String, ladder: List<Step> = phone, scale: Float = 1f, width: Float = WIDTH) =
        TileName.fit(
            ladder,
            scale,
            measure = { sp, lines -> Laid(sp, lines, name.length * 0.55f * sp > width / scale * lines) },
            overflows = { it.over },
        )

    @Test
    fun `a short name stays at its grid's size on two lines`() {
        val fit = fitOf("7z")
        assertEquals(Step(13f, 2), fit.step)
        assertFalse(fit.fade)
    }

    @Test
    fun `each grid size starts at its own size`() {
        assertEquals(11f, TileName.startFor(dev.niccc2007.filet.data.ViewStep.SMALL_GRID))
        assertEquals(13f, TileName.startFor(dev.niccc2007.filet.data.ViewStep.MEDIUM_GRID))
        assertEquals(15f, TileName.startFor(dev.niccc2007.filet.data.ViewStep.LARGE_GRID))
        assertEquals(15f, TileName.ladder(140f, wide = false, startSp = 15f).first().sp)
        assertEquals(listOf(Step(11f, 2), Step(10f, 3), Step(9f, 3)), TileName.ladder(116f, false, startSp = 11f))
    }

    @Test
    fun `a longer name steps down a point at a time, taking a third line from 10`() {
        assertEquals(listOf(Step(13f, 2), Step(12f, 2), Step(11f, 2), Step(10f, 3), Step(9f, 3)), phone)
        assertEquals(Step(12f, 2), fitOf("x".repeat(30)).step)
        assertEquals(Step(11f, 2), fitOf("x".repeat(33)).step)
        assertEquals(Step(10f, 3), fitOf("x".repeat(40)).step)
        assertEquals(Step(9f, 3), fitOf("x".repeat(60)).step)
    }

    @Test
    fun `the fade starts only once the last step overflows`() {
        assertFalse(fitOf("x".repeat(60)).fade)
        val long = fitOf("x".repeat(80))
        assertTrue(long.fade)
        assertEquals(Step(9f, 3), long.step)
    }

    @Test
    fun `under a shrunk pane the real size never drops below the floor`() {
        // A pane drawn at 0.72 of the density: every size is asked for larger by the same factor,
        // so what lands on the glass is the step itself.
        val fit = fitOf("x".repeat(80), scale = 0.72f)
        assertEquals(9f / 0.72f, fit.layout.sp, 0.001f)
        assertTrue(fit.layout.sp * 0.72f >= TileName.FLOOR_SP - 0.001f)
    }

    @Test
    fun `a split half two across starts no higher than 11`() {
        assertEquals(11f, TileName.ladder(realTileDp = 86f, wide = false, startSp = 13f).first().sp)
        assertEquals(11f, TileName.ladder(realTileDp = 86f, wide = false, startSp = 15f).first().sp)
    }

    @Test
    fun `a wide screen starts at the grid's size and keeps the higher floor`() {
        val wide = TileName.ladder(realTileDp = 112f, wide = true, startSp = 13f)
        assertEquals(13f, wide.first().sp)
        assertEquals(TileName.WIDE_FLOOR_SP, wide.minOf { it.sp })
    }

    @Test
    fun `the name area is the tallest step, so every tile is the same height`() =
        assertEquals(10f * TileName.LINE * 3, TileName.areaSp(phone))

    // ── lists ──

    @Test
    fun `a list name keeps its lines and gives up size a point at a time to 11`() {
        assertEquals(
            listOf(Step(14f, 2), Step(13f, 2), Step(12f, 2), Step(11f, 2)),
            ListName.ladder(startSp = 14f, lines = 2),
        )
        assertEquals(listOf(Step(12.5f, 1), Step(11.5f, 1), Step(11f, 1)), ListName.ladder(12.5f, 1))
    }

    @Test
    fun `a list already at the floor has one step, and fades past it`() {
        assertEquals(listOf(Step(11f, 2)), ListName.ladder(11f, 2))
        val fit = fitOf("x".repeat(80), ladder = ListName.ladder(13f, 1))
        assertTrue(fit.fade)
        assertEquals(ListName.FLOOR_SP, fit.step.sp)
    }

    // ── what a fade keeps ──

    @Test
    fun `a file keeps its extension through a fade`() {
        assertEquals(".pdf", NameEnd.extensionOf("CHEM1.U2.L2.3_NAMING_COMPOUNDS.pdf", isDir = false))
        assertEquals(".gz", NameEnd.extensionOf("backup.tar.gz", isDir = false))
    }

    @Test
    fun `a folder, a dotfile and a dot with no type keep nothing`() {
        assertEquals(null, NameEnd.extensionOf("CHEM1.U2", isDir = true))
        assertEquals(null, NameEnd.extensionOf(".nomedia", isDir = false))
        assertEquals(null, NameEnd.extensionOf("trailing.", isDir = false))
        assertEquals(null, NameEnd.extensionOf("no extension", isDir = false))
        assertEquals(null, NameEnd.extensionOf("v1.not an extension", isDir = false))
        assertEquals(null, NameEnd.extensionOf("x.waytoolongtobeatype", isDir = false))
    }

    private companion object {
        /** A Medium tile on a 360dp phone: 116dp, less 6dp padding each side. */
        const val WIDTH = 104f
    }
}

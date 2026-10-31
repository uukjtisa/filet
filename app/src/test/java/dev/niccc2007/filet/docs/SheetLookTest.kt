package dev.niccc2007.filet.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A workbook's colour references, and the ink chosen for them. See [SheetLook]. */
class SheetLookTest {

    private fun hex(c: Int?) = c?.let { "%06X".format(it and 0xFFFFFF) }

    @Test
    fun `rgb drops the alpha byte rather than honouring it`() {
        assertEquals("4472C4", hex(SheetLook.rgb("FF4472C4")))
        // Written by several producers for an opaque colour. Honoured, it is an invisible fill.
        assertEquals("4472C4", hex(SheetLook.rgb("004472C4")))
        assertEquals("4472C4", hex(SheetLook.rgb("4472C4")))
        assertNull(SheetLook.rgb("nonsense"))
        assertNull(SheetLook.rgb(null))
    }

    @Test
    fun `theme indexes light-first although the scheme lists dark-first`() {
        val t = SheetLook.OFFICE_THEME
        assertEquals("FFFFFF", hex(SheetLook.resolve(SheetLook.Ref(theme = 0), t, null)))
        assertEquals("000000", hex(SheetLook.resolve(SheetLook.Ref(theme = 1), t, null)))
        assertEquals("44546A", hex(SheetLook.resolve(SheetLook.Ref(theme = 3), t, null)))
        assertEquals("4472C4", hex(SheetLook.resolve(SheetLook.Ref(theme = 4), t, null)))
    }

    @Test
    fun `a theme part's scheme lands in index order, gaps from the default`() {
        val t = SheetLook.theme(mapOf("dk1" to 0xFF111111.toInt(), "lt1" to 0xFFEEEEEE.toInt(), "accent1" to 0xFF123456.toInt()))
        assertEquals("EEEEEE", hex(t[0]))
        assertEquals("111111", hex(t[1]))
        assertEquals("123456", hex(t[4]))
        assertEquals("ED7D31", hex(t[5]))
    }

    /** Every channel within [by] of [want]. The desktop rounds in integer HLS; this does not. */
    private fun near(want: Int, got: Int, by: Int = 2) {
        for (shift in intArrayOf(16, 8, 0)) {
            val d = kotlin.math.abs(((want shr shift) and 0xFF) - ((got shr shift) and 0xFF))
            assertTrue("${hex(want)} vs ${hex(got)}", d <= by)
        }
    }

    @Test
    fun `tint moves luminance the way the desktop's swatches do`() {
        val accent = 0xFF4472C4.toInt()
        // The spreadsheet's swatches under Accent 1, as it writes them: "Lighter 80%", "Lighter
        // 40%" and "Darker 25%". A mix with white or black misses the dark one by a long way.
        near(0xFFD9E1F2.toInt(), SheetLook.tint(accent, 0.7999816888943144))
        near(0xFF8EA9DB.toInt(), SheetLook.tint(accent, 0.3999755851924192))
        near(0xFF305496.toInt(), SheetLook.tint(accent, -0.249977111117893))
        assertEquals("FFFFFF", hex(SheetLook.tint(accent, 1.0)))
        assertEquals("000000", hex(SheetLook.tint(accent, -1.0)))
    }

    @Test
    fun `indexed uses the legacy palette, a custom one when given, and nothing for the system pair`() {
        assertEquals("C0C0C0", hex(SheetLook.indexed(22, null)))
        assertEquals("123456", hex(SheetLook.indexed(1, intArrayOf(0xFF000000.toInt(), 0xFF123456.toInt()))))
        assertNull(SheetLook.indexed(SheetLook.SYSTEM_FOREGROUND, null))
        assertNull(SheetLook.indexed(SheetLook.SYSTEM_BACKGROUND, null))
    }

    @Test
    fun `automatic resolves to nothing`() {
        assertNull(SheetLook.resolve(SheetLook.Ref(auto = true), SheetLook.OFFICE_THEME, null))
        assertNull(SheetLook.resolve(SheetLook.Ref(), SheetLook.OFFICE_THEME, null))
    }

    @Test
    fun `patterns read as the colour they make at a distance`() {
        assertNull(SheetLook.pattern("none", 0xFFFF0000.toInt(), null))
        assertNull(SheetLook.pattern(null, 0xFFFF0000.toInt(), null))
        assertEquals("FF0000", hex(SheetLook.pattern("solid", 0xFFFF0000.toInt(), 0xFF00FF00.toInt())))
        // A missing foreground is the system foreground: black.
        assertEquals("000000", hex(SheetLook.pattern("solid", null, null)))
        // Half-covered black lines on white is mid grey.
        assertEquals("7F7F7F", hex(SheetLook.pattern("mediumGray", null, null)))
        assertNull(SheetLook.pattern("someFuturePattern", 0xFFFF0000.toInt(), null))
    }

    @Test
    fun `a gradient is the mean of its stops`() {
        assertEquals("7F7F7F", hex(SheetLook.gradient(listOf(0xFF000000.toInt(), 0xFFFEFEFE.toInt()))))
        assertNull(SheetLook.gradient(emptyList()))
    }

    private val dark = 0xFF1C1C1A.toInt()
    private val light = 0xFFFFFFFF.toInt()

    @Test
    fun `a font colour on its own fill is drawn as written`() {
        val band = CellStyle(fill = 0xFF1F3864.toInt(), ink = SheetLook.WHITE)
        assertEquals(SheetLook.WHITE, SheetLook.ink(band, dark))
        assertEquals(SheetLook.WHITE, SheetLook.ink(band, light))
    }

    @Test
    fun `an automatic font on a fill is whichever of black and white reads`() {
        assertEquals(SheetLook.BLACK, SheetLook.ink(CellStyle(fill = 0xFFFFFF00.toInt()), dark))
        assertEquals(SheetLook.WHITE, SheetLook.ink(CellStyle(fill = 0xFF1F3864.toInt()), light))
    }

    @Test
    fun `with no fill a font colour survives only where it can be read`() {
        val red = 0xFFFF0000.toInt()
        val navy = 0xFF1F3864.toInt()
        assertEquals(red, SheetLook.ink(CellStyle(ink = red), dark))
        // Chosen against paper; on a dark screen it is a column of empty-looking cells.
        assertNull(SheetLook.ink(CellStyle(ink = navy), dark))
        assertEquals(navy, SheetLook.ink(CellStyle(ink = navy), light))
        assertNull(SheetLook.ink(CellStyle(bold = true), dark))
        assertNull(SheetLook.ink(null, dark))
    }

    @Test
    fun `contrast is the wcag ratio`() {
        assertEquals(21.0, SheetLook.contrast(SheetLook.BLACK, SheetLook.WHITE), 0.01)
        assertEquals(1.0, SheetLook.contrast(dark, dark), 0.0001)
        assertTrue(SheetLook.contrast(0xFF767676.toInt(), SheetLook.WHITE) >= 4.5)
    }
}

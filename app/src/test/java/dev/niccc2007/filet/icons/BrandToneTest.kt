package dev.niccc2007.filet.icons

import dev.niccc2007.filet.shortcuts.TabIconRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Filet's blues turned to a build's own icon colours: exact at the icon's two colours, nothing else
 * touched, and the same answers as the generator that wrote the tab icons' colours.
 */
class BrandToneTest {

    private val debugShade = 0xFF8A5A2BL
    private val debugLight = 0xFFD99A5BL
    private val debug = BrandTone(TabIconRes.BRAND_FROM_SHADE, TabIconRes.BRAND_FROM_LIGHT, debugShade, debugLight)

    @Test
    fun `the release build moves nothing`() {
        val release = BrandTone(TabIconRes.BRAND_FROM_SHADE, TabIconRes.BRAND_FROM_LIGHT, TabIconRes.BRAND_FROM_SHADE, TabIconRes.BRAND_FROM_LIGHT)
        assertTrue(release.isIdentity)
        assertEquals(0xFF93B8C6L, release.of(0xFF93B8C6L))
    }

    @Test
    fun `the icon's own two colours land exactly on the build's`() {
        assertEquals(debugShade, debug.of(TabIconRes.BRAND_FROM_SHADE))
        assertEquals(debugLight, debug.of(TabIconRes.BRAND_FROM_LIGHT))
    }

    @Test
    fun `only Filet's blues move`() {
        // Gold pages, the sky accent, white, black and a near-grey are not Filet blue.
        for (c in listOf(0xFFE0B25CL, 0xFF3F80B0L, 0xFF6FB7E8L, 0xFFFFFFFFL, 0xFF000000L, 0xFF0E0F10L, 0xFF5FCDB5L)) {
            assertFalse("%08X".format(c), BrandTone.isBrand(c))
            assertEquals(c, debug.of(c))
        }
        assertTrue(BrandTone.isBrand(0xFFC6DDE6L))
        assertTrue(BrandTone.isBrand(0xFF22343CL))
    }

    @Test
    fun `the app turns a blue the way the generator turned it`() {
        // The tab icons' colours were written by tools/make-tab-icons.mjs; pinned folder icons are
        // turned here at run time. A folder and a tab side by side on a home screen must match.
        val file = listOf(File("src/debug/res/values/tabicon_colors.xml"), File("app/src/debug/res/values/tabicon_colors.xml"))
            .first { it.isFile }
        val pairs = Regex("""name="tabicon_([0-9a-f]{8})">#([0-9A-F]{8})<""").findAll(file.readText())
            .map { it.groupValues[1].toLong(16) to it.groupValues[2].toLong(16) }.toList()
        assertTrue("the generated file has the blues", pairs.size >= 10)
        for ((from, written) in pairs) {
            val ours = debug.of(from)
            for (shift in listOf(24, 16, 8, 0)) {
                val a = (ours shr shift) and 0xFF
                val b = (written shr shift) and 0xFF
                assertTrue("%08X: generator %08X, app %08X".format(from, written, ours), abs(a - b) <= 1)
            }
        }
    }
}

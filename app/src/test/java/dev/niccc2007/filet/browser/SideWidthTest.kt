package dev.niccc2007.filet.browser

import dev.niccc2007.filet.browser.SideWidth.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

/** The side bar's limits and the pushed card's position. See [SideWidth]. */
class SideWidthTest {

    private fun leftEdge(push: Float, drawer: Float, window: Float): Float =
        (1f - SideWidth.cardScale(push)) / 2f * window + SideWidth.cardShift(push, drawer, window)

    @Test
    fun `the default fits a phone, and nothing goes below the minimum`() {
        assertEquals(236f, SideWidth.clamp(236f, Surface.DRAWER, 393f), 0.01f)
        assertEquals(SideWidth.MIN_DP.toFloat(), SideWidth.clamp(90f, Surface.DRAWER, 393f), 0.01f)
        assertEquals(SideWidth.MIN_DP.toFloat(), SideWidth.clamp(90f, Surface.RAIL, 1280f), 0.01f)
    }

    @Test
    fun `the drawer always leaves some of the card to tap back to`() {
        assertEquals(393f - SideWidth.DRAWER_KEEPS_DP, SideWidth.clamp(600f, Surface.DRAWER, 393f), 0.01f)
    }

    @Test
    fun `the rail never takes most of the window`() {
        assertEquals(800f * SideWidth.RAIL_SHARE, SideWidth.clamp(600f, Surface.RAIL, 800f), 0.01f)
        assertEquals(SideWidth.MAX_DP.toFloat(), SideWidth.clamp(600f, Surface.RAIL, 2000f), 0.01f)
    }

    @Test
    fun `a width squeezed by a small window is not lost`() {
        // The stored value is not clamped, only what is drawn: the wide window gets it back.
        val stored = 330f
        assertEquals(321f, SideWidth.clamp(stored, Surface.DRAWER, 393f), 0.01f)
        assertEquals(330f, SideWidth.clamp(stored, Surface.DRAWER, 900f), 0.01f)
    }

    @Test
    fun `the card's edge sits the same gap past the drawer at every width`() {
        for (window in listOf(360f, 393f, 640f, 900f, 1280f)) {
            for (drawer in listOf(180f, 236f, 300f)) {
                assertEquals(
                    "window $window drawer $drawer",
                    drawer + SideWidth.GAP_DP,
                    leftEdge(1f, drawer, window),
                    0.01f,
                )
            }
        }
    }

    @Test
    fun `closed is exactly where it started, and the edge moves steadily on the way`() {
        assertEquals(0f, SideWidth.cardShift(0f, 236f, 900f), 0.0001f)
        assertEquals(1f, SideWidth.cardScale(0f), 0.0001f)
        assertEquals((236f + SideWidth.GAP_DP) / 2f, leftEdge(0.5f, 236f, 900f), 0.01f)
    }
}

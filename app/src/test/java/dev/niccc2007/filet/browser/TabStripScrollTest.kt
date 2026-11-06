package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #26: the tab being shown stays in view, and the + stays where it can be reached. */
class TabStripScrollTest {

    @Test
    fun `a tab in the middle of a long strip is centred`() {
        // A row 2000 wide in a 400 box: it can move 1600. A chip centred at 1000 goes to 800.
        assertEquals(800, TabStripScroll.centreOn(1000f, 400, 1600))
    }

    @Test
    fun `a tab near either end goes as near the middle as the row allows`() {
        assertEquals("the first tab cannot be pulled past the start", 0, TabStripScroll.centreOn(60f, 400, 1600))
        assertEquals("the last tab cannot be pushed past the end", 1600, TabStripScroll.centreOn(1950f, 400, 1600))
    }

    @Test
    fun `a strip that fits, or has not been measured, does not scroll`() {
        assertEquals(0, TabStripScroll.centreOn(300f, 400, 0))
        assertEquals(0, TabStripScroll.centreOn(300f, 400, Int.MAX_VALUE))
    }

    @Test
    fun `the + pins only once the tabs overflow`() {
        assertFalse("they fit", TabStripScroll.pinned(0))
        assertFalse("not measured yet: drawn as if they fit", TabStripScroll.pinned(Int.MAX_VALUE))
        assertTrue(TabStripScroll.pinned(1))
        assertTrue(TabStripScroll.pinned(5_000))
    }

    @Test
    fun `the + cannot flip back and forth at the boundary`() {
        // Tabs of width t, a + of width p, a strip of width w. In the row, the + adds p to what
        // scrolls; pinned, it takes p from the box. Either way the overflow is t + p - w, so the
        // two layouts agree about whether to pin, for every width.
        val p = 40
        val w = 400
        for (t in 0..800 step 7) {
            val inRow = TabStripScroll.pinned((t + p - w).coerceAtLeast(0))
            val outside = TabStripScroll.pinned((t - (w - p)).coerceAtLeast(0))
            assertEquals("tabs $t wide", inRow, outside)
        }
    }

    @Test
    fun `the shown tab is the focused pane's in a split, and A's otherwise`() {
        assertEquals(2, TabStripScroll.shown(2, 5, SplitMode.OFF, Side.B))
        assertEquals(2, TabStripScroll.shown(2, 5, SplitMode.entries.first { it != SplitMode.OFF }, Side.A))
        assertEquals(5, TabStripScroll.shown(2, 5, SplitMode.entries.first { it != SplitMode.OFF }, Side.B))
    }
}

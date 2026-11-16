package dev.niccc2007.filet.browser

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubMenuTest {
    // A 360 x 640 screen with an 8 px margin.
    private val room = MenuRoom(8, 8, 352, 632)

    private fun land(parent: IntRect, rowTop: Int, w: Int, h: Int, r: MenuRoom = room) =
        MenuPlace.land(parent, rowTop, IntSize(w, h), r, overlap = 4)

    private fun IntRect.within(r: MenuRoom) = left >= r.left && top >= r.top && right <= r.right && bottom <= r.bottom

    @Test
    fun `a submenu opens right of its menu, level with the row, over the edge a little`() {
        assertEquals(IntRect(196, 120, 336, 320), land(IntRect(40, 100, 200, 400), 120, 140, 200))
    }

    @Test
    fun `with no room on the right it opens on the left`() {
        // Menu from 240 to 330: 326 + 140 runs past 352, so it goes to 240 - 140 + 4.
        assertEquals(IntRect(104, 120, 244, 320), land(IntRect(240, 100, 330, 400), 120, 140, 200))
    }

    @Test
    fun `it moves up rather than run off the bottom, and is never bigger than the room`() {
        assertEquals(632 - 300, land(IntRect(40, 100, 200, 600), 500, 140, 300).top)
        val tall = land(IntRect(40, 100, 200, 600), 500, 140, 900)
        assertEquals(8, tall.top)
        assertEquals(632, tall.bottom)
    }

    /**
     * A 360 dp phone, in dp: the room is the width less 8 at each side, under a 24 dp status bar.
     * The hold menu is 262 wide and each submenu 236, so nothing fits beside anything.
     */
    private val phone = MenuRoom(8, 32, 352, 760)

    @Test
    fun `levels two and three on a 360 dp phone stay on screen and each leaves its parent showing`() {
        val menu = IntRect(40, 120, 302, 520)
        val two = land(menu, rowTop = 300, w = 236, h = 260, r = phone)
        val three = land(two, rowTop = 420, w = 236, h = 200, r = phone)
        assertTrue("$two", two.within(phone))
        assertTrue("$three", three.within(phone))
        // Level two covers the menu from the right, leaving the menu's left strip in view.
        assertEquals(116, two.left)
        // Level three goes to the other edge rather than on top of level two.
        assertEquals(8, three.left)
        assertTrue("level two shows past level three", two.right > three.right)
    }

    @Test
    fun `held at the right edge, the levels alternate the other way and still stay on screen`() {
        val menu = IntRect(90, 120, 352, 520)
        val two = land(menu, rowTop = 300, w = 236, h = 260, r = phone)
        val three = land(two, rowTop = 420, w = 236, h = 200, r = phone)
        assertEquals(8, two.left)
        assertEquals(116, three.left)
        assertTrue(two.within(phone) && three.within(phone))
    }

    @Test
    fun `a wide screen puts every level beside the last`() {
        val tablet = MenuRoom(8, 32, 1272, 792)
        val menu = IntRect(100, 120, 362, 520)
        val two = land(menu, 300, 236, 260, tablet)
        val three = land(two, 420, 236, 200, tablet)
        assertEquals(358, two.left)
        assertEquals(two.right - 4, three.left)
    }

    @Test
    fun `a menu at a press is pushed in from the edges it would cross`() {
        assertEquals(IntOffset(100, 100), MenuPlace.atPress(100, 100, 200, 300, room))
        assertEquals(IntOffset(352 - 200, 632 - 300), MenuPlace.atPress(340, 600, 200, 300, room))
    }

    private val icon = androidx.compose.ui.graphics.vector.ImageVector.Builder("x", 1.dp, 1.dp, 1f, 1f).build()
    private fun row(id: String, kids: List<SelectionAction> = emptyList()) =
        SelectionAction(id, id, icon, children = kids, run = {})

    @Test
    fun `the row that opened a submenu closes it, and Back closes the newest first`() {
        val inner = row("inner", listOf(row("leaf")))
        val share = row("share", listOf(inner))
        val subs = SubMenus()
        subs.toggle(0, share, Rect.Zero)
        subs.toggle(1, inner, Rect.Zero)
        assertTrue(subs.isOpen(0, "share") && subs.isOpen(1, "inner"))
        assertTrue(subs.back())
        assertFalse(subs.isOpen(1, "inner"))
        assertTrue(subs.isOpen(0, "share"))
        subs.toggle(0, share, Rect.Zero)
        assertTrue(subs.levels.isEmpty())
        assertFalse(subs.back())
    }

    @Test
    fun `opening another row's submenu closes the first and everything above it`() {
        val a = row("a", listOf(row("a1", listOf(row("x")))))
        val b = row("b", listOf(row("b1")))
        val subs = SubMenus()
        subs.toggle(0, a, Rect.Zero)
        subs.toggle(1, a.children[0], Rect.Zero)
        subs.toggle(0, b, Rect.Zero)
        assertEquals(listOf("b"), subs.levels.map { it.action.id })
    }
}

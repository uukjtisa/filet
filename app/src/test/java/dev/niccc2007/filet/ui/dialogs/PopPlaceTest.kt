package dev.niccc2007.filet.ui.dialogs

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a menu card opens, and that it never opens off the screen. See [PopPlace]. */
class PopPlaceTest {

    private val window = IntSize(1080, 2340)
    private val card = IntSize(600, 500)

    private fun place(
        anchor: IntRect,
        gap: Int = 100,
        above: Boolean = false,
        centred: Boolean = false,
        ltr: Boolean = true,
        size: IntSize = card,
        room: IntSize = window,
    ) = PopPlace.of(anchor, room, size, gap, above, centred, ltr, side = 20, edge = 60)

    @Test
    fun `a toolbar button's card opens under it with their end edges lined up`() {
        assertEquals(IntOffset(400, 200), place(IntRect(900, 100, 1000, 200)))
    }

    @Test
    fun `a row near the bottom of a list opens its card above itself instead of off the screen`() {
        // Under would put the card's foot at 2640 on a 2340 screen.
        // End-aligned to the full-width row would touch the edge; it is held 20 in from it.
        assertEquals(IntOffset(460, 1710), place(IntRect(0, 2100, 1080, 2250), gap = 40))
    }

    @Test
    fun `a bottom bar's card opens upwards, and downwards only when there is no room above`() {
        assertEquals(IntOffset(400, 1640), place(IntRect(900, 2140, 1000, 2240), above = true))
        // A bar at the top of the screen asked to open upwards has nowhere to go but down.
        assertEquals(IntOffset(400, 150), place(IntRect(900, 50, 1000, 150), above = true))
    }

    @Test
    fun `the card never leaves the side of the screen`() {
        // End-aligned to something near the start edge would put its start at -586.
        assertEquals(20, place(IntRect(0, 500, 14, 500)).x)
        // And past the far edge, centred on a button at the end of a bar.
        assertEquals(1080 - 600 - 20, place(IntRect(1000, 500, 1080, 600), centred = true).x)
    }

    @Test
    fun `a centred card is centred on its button`() {
        // The button's middle is 500; a 600 wide card centred there starts at 200.
        assertEquals(200, place(IntRect(400, 500, 600, 600), centred = true).x)
    }

    @Test
    fun `right to left lines up the start edges`() {
        assertEquals(300, place(IntRect(300, 500, 400, 600), ltr = false).x)
    }

    @Test
    fun `a card bigger than the window keeps its top and start edge on screen`() {
        val big = IntSize(2000, 3000)
        assertEquals(IntOffset(20, 60), place(IntRect(900, 1000, 1000, 1100), size = big))
    }

    @Test
    fun `neither side fits, so the preferred side is kept and held inside the edges`() {
        val room = IntSize(1080, 900)
        // Under: 500 + 500 > 840. Over: 600 - 100 - 500 = 0 < 60. Kept under, then held.
        assertEquals(IntOffset(400, 340), place(IntRect(900, 400, 1000, 600), room = room))
    }
}

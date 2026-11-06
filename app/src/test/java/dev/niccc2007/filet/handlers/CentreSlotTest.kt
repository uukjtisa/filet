package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Test

/** The player's top bar: its words centred on the bar, not on the room its buttons leave. */
class CentreSlotTest {

    @Test
    fun `the middle is centred on the whole bar however lopsided the ends are`() {
        // One 36 button on the left, two with a 10 gap on the right: the bar #40 reported.
        val total = 400
        val room = CentreSlot.room(total, start = 36, end = 82, gap = 10)
        // As wide as it can be while staying a gap clear of the wider end, on both sides.
        assertEquals(400 - 2 * (82 + 10), room)
        val words = 150
        val x = CentreSlot.x(total, words)
        // Its middle is the bar's middle, not the middle of the room between the buttons.
        assertEquals(total / 2, x + words / 2)
        // And it clears both ends.
        assertEquals(true, x >= 36 + 10 && x + words <= total - 82 - 10)
    }

    @Test
    fun `no room is none, not a negative width`() {
        assertEquals(0, CentreSlot.room(100, start = 36, end = 82, gap = 10))
        assertEquals(0, CentreSlot.room(0, start = 0, end = 0, gap = 0))
    }
}

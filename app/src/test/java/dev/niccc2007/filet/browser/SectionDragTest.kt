package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/** Where a dragged card lands in the section editor, when cards are not all one height. */
class SectionDragTest {

    @Test
    fun `equal rows land where rounding always put them`() {
        val rows = List(5) { 48f }
        for (from in rows.indices) {
            for (dy in -300..300 step 7) {
                // The old rule, kept for downward drags and away from the exact half-way point,
                // where rounding a negative half is the one place the two may differ by a row.
                val old = (from + (dy / 48f).roundToInt()).coerceIn(0, rows.lastIndex)
                if ((dy % 24) != 0) assertEquals("from $from by $dy", old, SectionDrag.target(rows, from, dy.toFloat()))
            }
        }
    }

    @Test
    fun `a tall card is passed at its middle, not a row's height in`() {
        // Row 1 carries its settings: 200 px. Dragging row 0 (48) down past it.
        val rows = listOf(48f, 200f, 48f)
        assertEquals(0, SectionDrag.target(rows, 0, 60f))
        assertEquals(0, SectionDrag.target(rows, 0, 99f))
        assertEquals(1, SectionDrag.target(rows, 0, 100f))
        assertEquals(2, SectionDrag.target(rows, 0, 100f + 200f))
    }

    @Test
    fun `a tall card dragged up passes a short one at the short one's middle`() {
        val rows = listOf(48f, 48f, 200f)
        assertEquals(2, SectionDrag.target(rows, 2, -23f))
        assertEquals(1, SectionDrag.target(rows, 2, -24f))
        assertEquals(0, SectionDrag.target(rows, 2, -72f))
        assertEquals(0, SectionDrag.target(rows, 2, -900f))
    }

    @Test
    fun `no drag stays put, and a drag past the end stops at the end`() {
        val rows = listOf(48f, 120f, 48f)
        assertEquals(1, SectionDrag.target(rows, 1, 0f))
        assertEquals(2, SectionDrag.target(rows, 0, 5_000f))
    }

    @Test
    fun `a stale index is clamped rather than thrown`() {
        assertEquals(1, SectionDrag.target(listOf(48f, 48f), 4, 10f))
        assertEquals(0, SectionDrag.target(emptyList(), 0, 10f))
    }
}

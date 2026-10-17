package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the queue's cursor goes when one row is taken out of it.
 *
 * Four cases and three of them are off-by-one traps, which is why the arithmetic is a pure
 * function rather than three lines inside `Playback.dropFromQueue`. Getting any of them wrong
 * does not throw at the moment of the mistake - it skips a track, or crashes on an index, some
 * time later, for somebody who did not do anything unusual.
 */
class QueueDropTest {

    @Test
    fun `a row above the cursor shifts it up`() {
        // Playing track 4 of 10, removing track 2: the same track is still playing and it is
        // now track 3. Forgetting this is how the queue jumps.
        assertEquals(2, indexAfterDrop(size = 10, index = 3, removed = 1))
    }

    @Test
    fun `a row below the cursor does not move it`() {
        assertEquals(3, indexAfterDrop(size = 10, index = 3, removed = 7))
    }

    @Test
    fun `removing the playing row leaves the cursor on what was next`() {
        // The row that was next slides into this slot, so the index does not change - which is
        // right and looks wrong, and is the case most likely to be "fixed" by somebody later.
        assertEquals(3, indexAfterDrop(size = 10, index = 3, removed = 3))
    }

    @Test
    fun `removing the last row while playing it steps back`() {
        // There is no next to slide in, so the cursor has to come back one or it points past
        // the end of the list.
        assertEquals(8, indexAfterDrop(size = 10, index = 9, removed = 9))
    }

    @Test
    fun `the only track left is a stop, not an index`() {
        assertEquals(-1, indexAfterDrop(size = 1, index = 0, removed = 0))
        assertEquals(-1, indexAfterDrop(size = 0, index = 0, removed = 0))
    }

    @Test
    fun `a row that is not there changes nothing`() {
        assertEquals(3, indexAfterDrop(size = 10, index = 3, removed = 10))
        assertEquals(3, indexAfterDrop(size = 10, index = 3, removed = -1))
    }

    @Test
    fun `the result is always a valid index into what remains`() {
        // The property, over every combination rather than the cases above: whatever comes
        // back can be used to index the shortened list.
        for (size in 2..12) {
            for (index in 0 until size) {
                for (removed in 0 until size) {
                    val next = indexAfterDrop(size, index, removed)
                    assertEquals(
                        "size=$size index=$index removed=$removed gave $next",
                        true,
                        next in 0..(size - 2),
                    )
                }
            }
        }
    }
}

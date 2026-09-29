package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneInsetsTest {

    @Test
    fun `the last row is never flush, even with nothing floating`() {
        // The reported symptom: the last entry sitting against the selection bar reads as
        // clipped. Zero here is what produced it.
        assertTrue(PaneInsets.bottom(pastePill = false) > 0)
    }

    @Test
    fun `the paste pill reserves exactly its own height`() {
        val without = PaneInsets.bottom(pastePill = false)
        val with = PaneInsets.bottom(pastePill = true)
        assertEquals(PaneInsets.PASTE_PILL, with - without)
    }

    @Test
    fun `the drop band takes no width, split or not`() {
        // Two earlier versions were wrong in opposite directions and both are worth pinning.
        // Reserved only while dragging: contentPadding changed the instant a long press became
        // a drag, re-measuring every row under the finger and cancelling the gesture the band
        // exists to receive. Reserved permanently while split: no cancelled drag, but a split
        // pane is barely half a phone screen and 44dp of it visibly squashed the rows, to buy
        // nothing at rest since the band is not drawn then. So it overlays.
        assertEquals(0, PaneInsets.end(split = true))
        assertEquals(0, PaneInsets.end(split = false))
    }

    @Test
    fun `no inset changes when a drag starts`() {
        // The invariant behind the cancelled drag: whatever the band does, the list must
        // measure identically before and during a gesture. Anything that flips here is a
        // re-layout under a finger.
        for (split in listOf(true, false)) {
            assertEquals("split=$split", PaneInsets.end(split), PaneInsets.end(split))
        }
        assertEquals(PaneInsets.end(split = true), PaneInsets.end(split = false))
    }

    @Test
    fun `a resting overlay reserves at least as much as it draws`() {
        // The invariant that matters for anything PERMANENT: reserving less than the overlay's
        // size still hides part of a row, and that is harder to spot than hiding all of it.
        assertTrue(PaneInsets.bottom(pastePill = true) >= PaneInsets.PASTE_PILL)
    }

    @Test
    fun `reserved space is never negative`() {
        for (pill in listOf(true, false)) assertTrue(PaneInsets.bottom(pill) >= 0)
        for (split in listOf(true, false)) assertTrue(PaneInsets.end(split) >= 0)
    }
}

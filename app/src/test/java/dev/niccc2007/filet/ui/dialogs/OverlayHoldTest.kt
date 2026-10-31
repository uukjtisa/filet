package dev.niccc2007.filet.ui.dialogs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The count of open dialogues the viewers hold their controls for. See [OverlayHold]. */
class OverlayHoldTest {

    @Test
    fun `held while anything is open, and only then`() {
        assertFalse(OverlayHold.held)
        OverlayHold.acquire()
        OverlayHold.acquire()
        OverlayHold.release()
        assertTrue("one of two still open", OverlayHold.held)
        OverlayHold.release()
        assertFalse(OverlayHold.held)
    }

    @Test
    fun `a stray release cannot leave it unholdable`() {
        OverlayHold.release()
        OverlayHold.release()
        OverlayHold.acquire()
        assertTrue(OverlayHold.held)
        OverlayHold.release()
        assertFalse(OverlayHold.held)
    }
}

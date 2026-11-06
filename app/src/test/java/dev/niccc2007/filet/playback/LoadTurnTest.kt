package dev.niccc2007.filet.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A prepared player is used only by the newest, uncancelled load. See #55. */
class LoadTurnTest {

    @Test fun `the newest load hands its player over`() {
        assertTrue(LoadTurn.handsOver(mine = 7, newest = 7, cancelled = false))
    }

    @Test fun `a load a quicker skip replaced releases its player - the crash`() {
        assertFalse(LoadTurn.handsOver(mine = 6, newest = 7, cancelled = false))
    }

    @Test fun `a load stop cancelled releases its player even while it is the newest`() {
        assertFalse(LoadTurn.handsOver(mine = 7, newest = 7, cancelled = true))
    }

    @Test fun `ten presses in a row leave exactly one player`() {
        val newest = 10L
        val kept = (1L..10L).count { LoadTurn.handsOver(it, newest, cancelled = false) }
        assertTrue("kept $kept", kept == 1)
    }
}

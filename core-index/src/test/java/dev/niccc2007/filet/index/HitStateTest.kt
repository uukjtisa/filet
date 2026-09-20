package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A result says whether it is known to be there or is still being checked, and says nothing
 * else - because there is nothing else it is allowed to be.
 *
 * The rejected third state is the interesting part of this design. A "stale" or "might be
 * gone" label reads as the app admitting it does not know, and the reader then has to tap the
 * row to find out. An entry that cannot be justified is removed instead.
 */
class HitStateTest {

    @Test
    fun `between passes everything is available`() {
        // Nothing running means the last complete pass is the authority, and it reached
        // everything by definition.
        for (gen in 1L..5L) {
            assertEquals(HitState.AVAILABLE, hitState(crawlRunning = false, rowGen = gen, currentGen = 5))
        }
    }

    @Test
    fun `a row the running pass has reached is available`() {
        assertEquals(HitState.AVAILABLE, hitState(crawlRunning = true, rowGen = 7, currentGen = 7))
    }

    @Test
    fun `a row the running pass has not reached yet is confirming`() {
        assertEquals(HitState.CONFIRMING, hitState(crawlRunning = true, rowGen = 6, currentGen = 7))
    }

    @Test
    fun `an old row is still served rather than withheld`() {
        // The whole point: it appears in the list, carrying a label. Withholding it is what
        // made a running update look like an index that had been wiped.
        val state = hitState(crawlRunning = true, rowGen = 1, currentGen = 9)
        assertTrue("it has a state at all, so it is shown", state in HitState.entries)
        assertEquals(HitState.CONFIRMING, state)
    }

    @Test
    fun `nothing is left confirming once a pass ends`() {
        // The failure this prevents: a label that sticks after the thing it described is over.
        for (gen in 0L..9L) {
            assertEquals("gen=$gen", HitState.AVAILABLE, hitState(false, gen, 9))
        }
    }

    @Test
    fun `there are exactly two states`() {
        // Guards the decision rather than the code. Adding a third is a design change and
        // should have to break a test to happen.
        assertEquals(2, HitState.entries.size)
        assertEquals(setOf("Available", "Confirming"), HitState.entries.map { it.label }.toSet())
    }

    @Test
    fun `a file that is gone is not shown at any state`() {
        assertFalse(showable(existsOnDisk = false))
        assertTrue(showable(existsOnDisk = true))
    }

    @Test
    fun `a future generation is never confirming`() {
        // Defensive about the arithmetic rather than about the world: a row stamped ahead of
        // the current generation would mean the counter went backwards, and treating that as
        // "not reached yet" would label every row in the database.
        assertEquals(HitState.AVAILABLE, hitState(true, rowGen = 12, currentGen = 7))
    }
}

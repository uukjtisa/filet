package dev.niccc2007.filet.qr

import dev.niccc2007.filet.qr.WifiAdd.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the "save this network?" sheet's answer means. See [WifiAdd]. */
class WifiAddTest {

    @Test
    fun `the sheet's own answers`() {
        assertEquals(Outcome.SAVED, WifiAdd.outcome(true, listOf(0), 4_000))
        assertEquals(Outcome.ALREADY, WifiAdd.outcome(true, listOf(2), 4_000))
        assertEquals(Outcome.FAILED, WifiAdd.outcome(true, listOf(1), 4_000))
        assertEquals(Outcome.SAVED, WifiAdd.outcome(true, null, 4_000))
    }

    @Test
    fun `a cancel is a person only if a person had time to make it`() {
        assertEquals(Outcome.DECLINED, WifiAdd.outcome(false, null, 3_000))
        assertEquals(Outcome.NOT_SHOWN, WifiAdd.outcome(false, null, 40))
        assertEquals(Outcome.DECLINED, WifiAdd.outcome(false, null, WifiAdd.HUMAN_MS))
    }
}

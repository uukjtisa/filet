package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumePollingTest {

    @Test
    fun `a volume that is answering is always measured`() {
        // The common case, and it must not be slowed down by the rule that exists for the
        // uncommon one: a local disk answers in a millisecond.
        for (pass in 1L..20L) assertTrue(VolumePolling.shouldProbe(pass, misses = 0))
    }

    @Test
    fun `one failure halves how often it is asked`() {
        assertFalse(VolumePolling.shouldProbe(1, misses = 1))
        assertTrue(VolumePolling.shouldProbe(2, misses = 1))
        assertFalse(VolumePolling.shouldProbe(3, misses = 1))
        assertTrue(VolumePolling.shouldProbe(4, misses = 1))
    }

    @Test
    fun `the gap doubles as it keeps failing`() {
        val probedWithTwo = (1L..16L).count { VolumePolling.shouldProbe(it, misses = 2) }
        val probedWithThree = (1L..16L).count { VolumePolling.shouldProbe(it, misses = 3) }
        assertEquals(4, probedWithTwo)
        assertEquals(2, probedWithThree)
    }

    @Test
    fun `the gap stops growing, so a drive that comes back is always found`() {
        // Without a ceiling, a share switched off overnight returns to a gap of hours and the
        // automatic recovery has quietly become "restart the app".
        val far = VolumePolling.MAX_DOUBLINGS + 6
        val every = 1L shl VolumePolling.MAX_DOUBLINGS
        assertTrue(VolumePolling.shouldProbe(every, misses = far))
        assertTrue(VolumePolling.shouldProbe(every * 2, misses = far))
        // And it is genuinely reached: one pass in sixteen, not one in never.
        assertEquals(1, (1L..every).count { VolumePolling.shouldProbe(it, misses = far) })
    }
}

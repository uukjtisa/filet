package dev.niccc2007.filet.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stopping sharing must not be able to ask itself to stop.
 *
 * Bug identified: the manager's stop ended by telling the foreground service to stop, and the
 * service's stop began by telling the manager to stop. One press of Stop then ran that lap
 * without end, re-posting the ongoing notification each time, and every stale stop intent left
 * in the queue pulled down the server that the next press of Start had just brought up. That
 * is why sharing could not be restarted and only flickered.
 *
 * The cycle is cut by origin, so the origin is what is tested.
 */
class NearbyStopTest {

    @Test
    fun `a stop from the screen still has a service to stop`() {
        assertTrue(tellsTheService(StopOrigin.SCREEN))
    }

    @Test
    fun `nothing running inside the service asks the service to stop`() {
        // The whole fix in one assertion: if any of these is ever true the loop is back.
        for (origin in INSIDE_THE_SERVICE) {
            assertFalse("$origin would re-enter the service", tellsTheService(origin))
        }
    }

    @Test
    fun `every origin is either inside the service or the screen`() {
        // A new origin added later must land on one side or the other deliberately, rather
        // than defaulting into whichever branch happens to be written first.
        for (origin in StopOrigin.entries) {
            val inside = origin in INSIDE_THE_SERVICE
            assertEquals("$origin", !inside, tellsTheService(origin))
        }
    }

    @Test
    fun `exactly one origin sends the intent`() {
        assertEquals(1, StopOrigin.entries.count { tellsTheService(it) })
    }

    /**
     * The fault itself, as a model rather than as a claim.
     *
     * Each stop is played out: a stop that sends the intent causes another stop, this time
     * from inside the service. Before the fix that second stop sent the intent as well and the
     * count never stopped rising. It is capped so a regression fails the test instead of
     * hanging the suite.
     */
    private fun lapsUntilQuiet(from: StopOrigin, cap: Int = 100): Int {
        var laps = 0
        var origin = from
        while (tellsTheService(origin) && laps < cap) {
            laps++
            // Delivering the intent runs the service's stop branch, which stops again from
            // inside the service.
            origin = StopOrigin.SERVICE
        }
        return laps
    }

    @Test
    fun `a stop from the screen settles after one intent`() {
        assertEquals(1, lapsUntilQuiet(StopOrigin.SCREEN))
    }

    @Test
    fun `a stop from the notification sends nothing at all`() {
        assertEquals(0, lapsUntilQuiet(StopOrigin.SERVICE))
    }

    @Test
    fun `the idle watch sends nothing at all`() {
        assertEquals(0, lapsUntilQuiet(StopOrigin.IDLE))
    }
}

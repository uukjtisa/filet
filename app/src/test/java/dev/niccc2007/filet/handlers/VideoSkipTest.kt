package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Test

/** How far a double tap moves a video: the step Settings names, added once per tap of a run. */
class VideoSkipTest {

    @Test
    fun `the skip is 2, 5 or 10 seconds, and anything else stored reads as 10`() {
        assertEquals(listOf(2, 5, 10), Prefs.VIDEO_SKIP_CHOICES)
        assertEquals(10, Prefs.VIDEO_SKIP_DEFAULT)
        for (s in Prefs.VIDEO_SKIP_CHOICES) assertEquals(s, Prefs.videoSkipOf(s))
        for (bad in listOf(0, -5, 1, 3, 15, 30, Int.MAX_VALUE)) assertEquals("$bad", 10, Prefs.videoSkipOf(bad))
    }

    @Test
    fun `each tap of a run adds the chosen step, and the ends still hold`() {
        val minute = 60_000L
        val length = 10 * minute
        // Two seconds, three taps forward: six seconds on.
        assertEquals(minute + 6_000L, doubleTapTarget(minute, length, TapZone.FORWARD, taps = 3, stepMs = 2_000L))
        // Five seconds, two taps back: ten seconds back.
        assertEquals(minute - 10_000L, doubleTapTarget(minute, length, TapZone.BACK, taps = 2, stepMs = 5_000L))
        // The default is still the ten every other player uses.
        assertEquals(minute + 10_000L, doubleTapTarget(minute, length, TapZone.FORWARD))
        // Back from three seconds in is the start; forward near the end stops a second short.
        assertEquals(0L, doubleTapTarget(3_000L, length, TapZone.BACK, stepMs = 5_000L))
        assertEquals(length - 1_000L, doubleTapTarget(length - 1_500L, length, TapZone.FORWARD, stepMs = 2_000L))
        // The middle is play and pause, never a jump, whatever the step.
        assertEquals(minute, doubleTapTarget(minute, length, TapZone.MIDDLE, stepMs = 2_000L))
    }
}

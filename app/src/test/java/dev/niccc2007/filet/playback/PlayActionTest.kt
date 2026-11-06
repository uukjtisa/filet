package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** "From the start" restarts even the track already playing; coming back to it does not. */
class PlayActionTest {

    @Test fun `from the start on the track already playing goes back to 0 - the reported edge`() {
        assertEquals(PlayAction.FROM_START, PlayAction.of(sameTrack = true, loaded = true, fromStart = true, startAtMs = 0L))
    }

    @Test fun `coming back to the track already playing leaves it alone`() {
        assertEquals(PlayAction.KEEP, PlayAction.of(sameTrack = true, loaded = true, fromStart = false, startAtMs = 0L))
    }

    @Test fun `continuing at a position reopens it there`() {
        assertEquals(PlayAction.LOAD, PlayAction.of(sameTrack = true, loaded = true, fromStart = false, startAtMs = 90_000L))
    }

    @Test fun `another track, or nothing loaded, always loads`() {
        assertEquals(PlayAction.LOAD, PlayAction.of(sameTrack = false, loaded = true, fromStart = true, startAtMs = 0L))
        assertEquals(PlayAction.LOAD, PlayAction.of(sameTrack = true, loaded = false, fromStart = true, startAtMs = 0L))
    }
}

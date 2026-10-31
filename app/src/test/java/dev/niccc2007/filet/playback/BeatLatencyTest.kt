package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the light reads the map, against a position that is already at the speaker. */
class BeatLatencyTest {

    @Test
    fun `the light takes the window bias off the position and nothing for the output`() {
        // A logged reading over Bluetooth: position 7 722 ms, fresh, window bias 36 ms. The old
        // reading also took 200 ms of output off and drove the light from 7 486, 200 ms of music
        // ago, because the position had counted the output already.
        assertEquals(7_686L, BeatLatency.earMs(reportedMs = 7_722L, ageMs = 0L, biasMs = 36L))
    }

    @Test
    fun `a position read a while ago runs on by its age`() {
        // Read 16 ms ago at 10 000: the sound in the room is 16 ms further on, less the bias.
        assertEquals(9_980L, BeatLatency.earMs(reportedMs = 10_000L, ageMs = 16L, biasMs = 36L))
        // The halved analysis rate of a long track has a larger bias, and the same rule.
        assertEquals(9_934L, BeatLatency.earMs(reportedMs = 10_000L, ageMs = 16L, biasMs = 82L))
    }
}

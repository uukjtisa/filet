package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScratchTest {

    @Test
    fun `a clockwise quarter turn is ninety degrees forward`() {
        // Screen coordinates: y grows downward, so going from the right of the centre to
        // below it is clockwise on screen.
        val turn = Scratch.turn(100f, 0f, 0f, 100f, 0f, 0f)
        assertEquals(90f, turn, 0.01f)
    }

    @Test
    fun `crossing the top is the short way round`() {
        // The wrap from 179 to -179 is a two-degree move. Read naively it is 358, which on a
        // scrub throws the track most of a minute - the one case this has to get right.
        val before = Scratch.turn(-100f, -1f, -100f, 1f, 0f, 0f)
        assertTrue("the wrap went the long way: $before", kotlin.math.abs(before) < 5f)
    }

    @Test
    fun `a full turn is one turn of the track`() {
        assertEquals(Scratch.MS_PER_TURN.toLong(), Scratch.msFor(360f))
        assertEquals(-Scratch.MS_PER_TURN.toLong() / 2, Scratch.msFor(-180f))
    }

    @Test
    fun `a scratch cannot run off either end`() {
        assertEquals(0L, Scratch.seekTo(1_000L, -3600f, 200_000L))
        assertEquals(200_000L, Scratch.seekTo(199_000L, 3600f, 200_000L))
    }

    @Test
    fun `a track with no duration yet cannot be scratched into nonsense`() {
        assertEquals(0L, Scratch.seekTo(5_000L, 180f, 0L))
    }

    @Test
    fun `normalise folds any angle into the short way round`() {
        assertEquals(-10f, Scratch.normalise(350f), 0.01f)
        assertEquals(10f, Scratch.normalise(-350f), 0.01f)
        assertEquals(180f, Scratch.normalise(180f), 0.01f)
    }
}

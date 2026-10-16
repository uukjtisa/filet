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

    // ── the sound following the hand ──

    @Test
    fun `a hand moving at a record's pace plays at about normal speed`() {
        // Roughly what an ordinary drag across a phone-sized disc measures.
        val speed = Scratch.speedFor(200f, Scratch.MS_PER_TURN_DJ)
        assertTrue("got ${speed}x, which is not a sound", speed > 0.7f && speed < 2.5f)
    }

    @Test
    fun `the silent setting would have been a chirp`() {
        // The reason the disc turns at two speeds: the same hand against twenty seconds a
        // revolution is eleven times speed.
        val speed = Scratch.speedFor(200f, Scratch.MS_PER_TURN)
        assertTrue("expected an unusable rate, got ${speed}x", speed > 8f)
    }

    @Test
    fun `backwards is just a negative speed`() {
        val back = Scratch.speedFor(-200f, Scratch.MS_PER_TURN_DJ)
        val forward = Scratch.speedFor(200f, Scratch.MS_PER_TURN_DJ)
        assertEquals(-forward, back, 0.0001f)
    }

    @Test
    fun `a still hand is a stopped record`() {
        assertEquals(0f, Scratch.speedFor(0f, Scratch.MS_PER_TURN_DJ), 0.0001f)
    }

    @Test
    fun `a turn covers less of the track when the sound is following`() {
        assertTrue(
            Scratch.msFor(360f, Scratch.MS_PER_TURN_DJ) <
                Scratch.msFor(360f, Scratch.MS_PER_TURN),
        )
    }
}

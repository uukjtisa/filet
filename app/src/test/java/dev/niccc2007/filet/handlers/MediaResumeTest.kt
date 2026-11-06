package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.handlers.MediaResume.Card
import dev.niccc2007.filet.handlers.MediaResume.Card.Kind
import dev.niccc2007.filet.playback.Opened
import dev.niccc2007.filet.playback.OpenedBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The continue card for videos and music, with Resume playback on and off. */
class MediaResumeTest {

    private val tenMinutes = 10 * 60_000L

    // ── the preview ──

    @Test
    fun `on, a stop at 0 09 survives a scroll away and back`() {
        // Every stop is saved; the preview built on coming back reads it.
        val p = MediaResume.preview(resumeOn = true, savedMs = 9_000L, durationMs = tenMinutes, stoppedAtMs = 0L)
        assertEquals(9_000L, p.playFromMs)
        assertEquals(Card(Kind.PLAY_FROM, 9_000L), p.card)
        assertEquals("Play from 0:09", p.card!!.title)
        assertEquals("Start over", p.card!!.detail)
        assertEquals("a tap on the card starts over", 0L, p.card!!.goesTo)
    }

    @Test
    fun `off, the preview plays from the start and the card offers the place`() {
        val p = MediaResume.preview(resumeOn = false, savedMs = 9_000L, durationMs = tenMinutes, stoppedAtMs = 0L)
        assertEquals(0L, p.playFromMs)
        assertEquals(Card(Kind.OFFER, 9_000L), p.card)
        assertEquals("Continue where you left off", p.card!!.title)
        assertEquals("0:09", p.card!!.detail)
        assertEquals("a tap on the card goes to the place", 9_000L, p.card!!.goesTo)
    }

    @Test
    fun `Back on this visit carries on, on or off`() {
        // A step back from playing, not an opening.
        for (on in listOf(true, false)) {
            val p = MediaResume.preview(on, savedMs = 180_000L, durationMs = tenMinutes, stoppedAtMs = 180_000L)
            assertEquals("resume $on", 180_000L, p.playFromMs)
            assertEquals(Kind.PLAY_FROM, p.card?.kind)
        }
    }

    @Test
    fun `on, the saved place wins over this visit's stop`() {
        // Left at 3:00 here, carried on to 8:00 as music elsewhere: 8:00 is the later word.
        assertEquals(480_000L, MediaResume.preview(true, 480_000L, tenMinutes, stoppedAtMs = 180_000L).playFromMs)
    }

    @Test
    fun `a finished place is the start, with no card`() {
        for (on in listOf(true, false)) {
            val p = MediaResume.preview(on, savedMs = 594_000L, durationMs = tenMinutes, stoppedAtMs = 0L)
            assertEquals(0L, p.playFromMs)
            assertNull(p.card)
        }
    }

    @Test
    fun `nothing saved and nothing stopped has no card`() {
        val p = MediaResume.preview(true, savedMs = null, durationMs = 0L, stoppedAtMs = 0L)
        assertEquals(0L, p.playFromMs)
        assertNull(p.card)
    }

    @Test
    fun `a place with no known length is carried on from`() {
        assertEquals(4_000L, MediaResume.preview(true, savedMs = 4_000L, durationMs = 0L, stoppedAtMs = 0L).playFromMs)
    }

    // ── opened straight into playing ──

    @Test
    fun `on, it opens where it stopped and says so`() {
        val o = MediaResume.opening(resumeOn = true, savedMs = 754_000L, durationMs = 3_600_000L)
        assertEquals(754_000L, o.startMs)
        assertEquals(Card(Kind.CONTINUED, 754_000L), o.card)
        assertEquals("Continued from 12:34", o.card!!.title)
        assertEquals(0L, o.card!!.goesTo)
    }

    @Test
    fun `off, it opens at the start and offers the place, as a document does`() {
        val o = MediaResume.opening(resumeOn = false, savedMs = 754_000L, durationMs = 3_600_000L)
        assertEquals(0L, o.startMs)
        assertEquals(Card(Kind.OFFER, 754_000L), o.card)
        assertEquals("12:34", o.card!!.detail)
        assertEquals(754_000L, o.card!!.goesTo)
    }

    @Test
    fun `nothing saved, or finished, opens at the start with no card`() {
        for (on in listOf(true, false)) {
            assertEquals(MediaResume.Opening(0L, null), MediaResume.opening(on, null, 0L))
            assertEquals(MediaResume.Opening(0L, null), MediaResume.opening(on, 3_600_000L, 3_600_000L))
        }
    }

    // ── the music player, from how the track opened ──

    private fun opened(started: Long, stored: Long?, by: OpenedBy) = Opened("/m/a.mp3", started, stored, by)

    @Test
    fun `a track opened where it stopped says so`() {
        for (by in listOf(OpenedBy.OPEN, OpenedBy.QUEUE)) {
            assertEquals("$by", Card(Kind.CONTINUED, 133_000L), MediaResume.forTrack(opened(133_000L, 133_000L, by)))
        }
    }

    @Test
    fun `a track opened at the start offers its place`() {
        for (by in listOf(OpenedBy.OPEN, OpenedBy.QUEUE)) {
            assertEquals("$by", Card(Kind.OFFER, 133_000L), MediaResume.forTrack(opened(0L, 133_000L, by)))
        }
    }

    @Test
    fun `a track with no place has no card`() {
        assertNull(MediaResume.forTrack(opened(0L, null, OpenedBy.OPEN)))
        assertNull(MediaResume.forTrack(opened(0L, 0L, OpenedBy.QUEUE)))
        assertNull(MediaResume.forTrack(null))
    }

    @Test
    fun `a track started somewhere asked for has no card`() {
        assertNull(MediaResume.forTrack(opened(40_000L, 133_000L, OpenedBy.OPEN)))
    }

    @Test
    fun `carry on, the track already playing and a video's sound have asked already`() {
        for (by in listOf(OpenedBy.CARRY_ON, OpenedBy.RETURN, OpenedBy.HAND_OFF)) {
            assertNull("$by", MediaResume.forTrack(opened(133_000L, 133_000L, by)))
            assertNull("$by", MediaResume.forTrack(opened(0L, 133_000L, by)))
        }
    }

    @Test
    fun `every way a track opens is decided`() {
        // A new way in has to be placed in forTrack's when; this keeps the list honest.
        assertEquals(5, OpenedBy.entries.size)
    }

    @Test
    fun `the card's lines read as times`() {
        assertEquals("Play from 1:02:03", Card(Kind.PLAY_FROM, 3_723_000L).title)
        assertEquals("1:02:03", Card(Kind.OFFER, 3_723_000L).detail)
    }
}

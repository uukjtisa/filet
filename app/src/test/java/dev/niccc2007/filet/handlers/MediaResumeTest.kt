package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.handlers.MediaResume.Offer
import dev.niccc2007.filet.playback.Opened
import dev.niccc2007.filet.playback.OpenedBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The preview's pill, and the players' continue card, with Resume playback on and off. */
class MediaResumeTest {

    private val tenMinutes = 10 * 60_000L

    // ── the preview ──

    @Test
    fun `on, Play and the pill both go to the place`() {
        val p = MediaResume.preview(resumeOn = true, savedMs = 30_000L, durationMs = tenMinutes, stoppedAtMs = 0L)
        assertEquals(30_000L, p.playFromMs)
        assertEquals(30_000L, p.pillMs)
        assertEquals("Play from 0:30", p.pillLabel)
    }

    @Test
    fun `off, Play starts at 0 00 and the pill still goes to the place`() {
        val p = MediaResume.preview(resumeOn = false, savedMs = 30_000L, durationMs = tenMinutes, stoppedAtMs = 0L)
        assertEquals(0L, p.playFromMs)
        assertEquals(30_000L, p.pillMs)
    }

    @Test
    fun `Back on this visit carries on, on or off`() {
        for (on in listOf(true, false)) {
            val p = MediaResume.preview(on, savedMs = 180_000L, durationMs = tenMinutes, stoppedAtMs = 180_000L)
            assertEquals("resume $on", 180_000L, p.playFromMs)
            assertEquals("resume $on", 180_000L, p.pillMs)
        }
    }

    @Test
    fun `a finished or missing place has no pill and plays from the start`() {
        for (on in listOf(true, false)) {
            val done = MediaResume.preview(on, savedMs = 594_000L, durationMs = tenMinutes, stoppedAtMs = 0L)
            assertEquals(0L, done.playFromMs)
            assertNull(done.pillMs)
            assertNull(MediaResume.preview(on, savedMs = null, durationMs = 0L, stoppedAtMs = 0L).pillMs)
        }
    }

    // ── the card: an offer, only for playing that started at 0:00 with a place saved ──

    @Test
    fun `the card says only where it continues`() {
        assertEquals("Continue playing at 12:34", Offer(754_000L).title)
        assertEquals("Continue playing at 1:02:03", Offer(3_723_000L).title)
    }

    @Test
    fun `started at 0 00 with a place offers it, started anywhere else offers nothing`() {
        assertEquals(Offer(30_000L), MediaResume.offer(0L, 30_000L))
        assertNull(MediaResume.offer(30_000L, 30_000L))
        assertNull(MediaResume.offer(0L, null))
    }

    @Test
    fun `a video opened into playing - off offers the place, on opens at it with no card`() {
        assertEquals(MediaResume.Opening(0L, Offer(754_000L)), MediaResume.opening(false, 754_000L, 3_600_000L))
        assertEquals(MediaResume.Opening(754_000L, null), MediaResume.opening(true, 754_000L, 3_600_000L))
        assertEquals(MediaResume.Opening(0L, null), MediaResume.opening(false, 3_600_000L, 3_600_000L))
    }

    // ── the music player, from how the track opened ──

    private fun opened(started: Long, stored: Long?, by: OpenedBy) = Opened("/m/a.mp3", started, stored, by)

    @Test
    fun `a track opened or moved on to at 0 00 offers its place`() {
        for (by in listOf(OpenedBy.OPEN, OpenedBy.QUEUE)) {
            assertEquals("$by", Offer(133_000L), MediaResume.forTrack(opened(0L, 133_000L, by)))
            assertNull("$by", MediaResume.forTrack(opened(133_000L, 133_000L, by)))
            assertNull("$by", MediaResume.forTrack(opened(0L, null, by)))
        }
        assertNull(MediaResume.forTrack(null))
    }

    @Test
    fun `carry on, the track already playing and a video's sound have asked already`() {
        for (by in listOf(OpenedBy.CARRY_ON, OpenedBy.RETURN, OpenedBy.HAND_OFF)) {
            assertNull("$by", MediaResume.forTrack(opened(0L, 133_000L, by)))
        }
    }
}

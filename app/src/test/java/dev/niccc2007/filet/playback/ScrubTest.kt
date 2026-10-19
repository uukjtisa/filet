package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bar and disc drags, resolved to whole seconds, and never against an unknown length.
 *
 * The snap to the start was a drag resolved against a duration of 0, which the player reports
 * while a track's header is being read. These pin that it is now held instead.
 */
class ScrubTest {

    @Test fun an_unknown_duration_never_yields_zero_it_yields_nothing() {
        assertNull(Scrub.wholeSecond(4_200.0, 0L))
        assertNull(Scrub.barStart(100f, 300f, 0L))
        assertNull(Scrub.barMove(4_200.0, 10f, 300f, 0L, 0f, 2.75f))
        assertNull(Scrub.discMove(4_200.0, 30f, 1_800f, 0L))
        assertNull(Scrub.discMove(4_200.0, 30f, 1_800f, -1L))
    }

    @Test fun targets_are_whole_seconds_inside_the_track() {
        for (d in listOf(1_500L, 7_250L, 61_000L, 600_000L, 3_600_000L)) {
            for (ms in listOf(-500.0, 0.0, 499.0, 501.0, d / 2.0, d.toDouble(), d + 9_000.0)) {
                val t = Scrub.wholeSecond(ms, d)!!
                assertEquals("whole second for $ms of $d", 0L, t % 1000L)
                assertTrue("inside for $ms of $d", t in 0L..d)
            }
        }
        assertEquals(1_000L, Scrub.wholeSecond(1_499.0, 7_250L))
        assertEquals(2_000L, Scrub.wholeSecond(1_500.0, 7_250L))
        // Rounding up past the end would land after the last whole second the track has.
        assertEquals(7_000L, Scrub.wholeSecond(7_240.0, 7_250L))
    }

    @Test fun normal_drag_maps_the_bar_width_to_the_duration() {
        // 300px over 300 s: one second a pixel.
        assertEquals(150_000.0, Scrub.barStart(150f, 300f, 300_000L)!!, 0.01)
        assertEquals(160_000.0, Scrub.barMove(150_000.0, 10f, 300f, 300_000L, 0f, 2.75f)!!, 0.01)
        // 300px over 10 minutes: two seconds a pixel, the case that skipped seconds.
        assertEquals(20_000.0, Scrub.barMove(0.0, 10f, 300f, 600_000L, 0f, 2.75f)!!, 0.01)
    }

    @Test fun pulling_away_from_the_bar_slows_it_and_fine_reaches_every_second() {
        val pxPerDp = 2.75f
        val quarter = Scrub.barMove(0.0, 12f, 300f, 600_000L, 60f, pxPerDp)!!
        assertEquals(6_000.0, quarter, 0.01)
        // Fine: 12dp per second, whatever the length - so one second is 33px here.
        val oneSecondPx = Scrub.FINE_DP_PER_SECOND * pxPerDp
        assertEquals(1_000.0, Scrub.barMove(0.0, oneSecondPx, 300f, 600_000L, 140f, pxPerDp)!!, 0.5)
        assertEquals(1_000.0, Scrub.barMove(0.0, oneSecondPx, 300f, 3_600_000L, -140f, pxPerDp)!!, 0.5)
    }

    @Test fun fine_is_never_faster_than_quarter_on_a_short_track() {
        // 300px over 6 s: quarter speed is already finer than 12dp per second.
        val fine = Scrub.barMove(0.0, 100f, 300f, 6_000L, 200f, 2.75f)!!
        val quarter = Scrub.barMove(0.0, 100f, 300f, 6_000L, 60f, 2.75f)!!
        assertTrue(fine <= quarter + 0.001)
    }

    @Test fun drags_stay_inside_the_track() {
        assertEquals(0.0, Scrub.barMove(1_000.0, -500f, 300f, 60_000L, 0f, 2.75f)!!, 0.0)
        assertEquals(60_000.0, Scrub.barMove(59_000.0, 500f, 300f, 60_000L, 0f, 2.75f)!!, 0.0)
        assertEquals(0.0, Scrub.discMove(500.0, -360f, 1_800f, 8_000L)!!, 0.0)
    }

    @Test fun the_disc_moves_by_the_turn_length() {
        assertEquals(1_900.0, Scrub.discMove(1_000.0, 180f, 1_800f, 8_000L)!!, 0.01)
    }

    @Test fun mode_follows_the_pull_either_side() {
        assertEquals(Scrub.Mode.NORMAL, Scrub.modeFor(20f))
        assertEquals(Scrub.Mode.QUARTER, Scrub.modeFor(-60f))
        assertEquals(Scrub.Mode.FINE, Scrub.modeFor(150f))
    }

    // ── the PCM plan ──

    @Test fun the_decode_starts_a_little_behind_the_playhead() {
        assertEquals(176_000L, TrackPcm.startFor(180_000L, 240_000L))
        assertEquals(0L, TrackPcm.startFor(1_000L, 240_000L))
        assertEquals(0L, TrackPcm.startFor(0L, 0L))
    }

    @Test fun the_buffer_is_bounded_whatever_the_length() {
        for (d in listOf(0L, 5_000L, 240_000L, 600_000L, 1_200_000L)) {
            val r = TrackPcm.rateFor(d)
            assertTrue(r in TrackPcm.LOWEST_RATE..TrackPcm.TARGET_RATE)
            assertTrue("bytes for $d", TrackPcm.framesFor(d, r) * 2L <= TrackPcm.MAX_BYTES)
        }
        assertEquals(TrackPcm.TARGET_RATE, TrackPcm.rateFor(240_000L))
        // Ten minutes overflows even the floor, so it drops below it and stays whole.
        val ten = TrackPcm.rateFor(600_000L)
        assertTrue(ten < TrackPcm.FLOOR_RATE)
        assertTrue(600L * ten * 2 <= TrackPcm.MAX_BYTES)
    }

    @Test fun presentation_time_places_samples() {
        assertEquals(22_050, TrackPcm.frameAt(1_000_000L, 22_050))
        assertEquals(0, TrackPcm.frameAt(0L, 22_050))
    }
}

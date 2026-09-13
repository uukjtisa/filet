package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The video scrub and the double-tap seek, as arithmetic. */
class SeekGestureTest {

    private val tenMinutes = 600_000L

    // ── dragging the bar ──

    @Test fun the_position_under_the_finger_is_the_position_it_seeks_to() {
        // Not a delta from where the thumb was: an absolute map, so the first touch already
        // means something and there is no thumb to catch first.
        assertEquals(0L, seekPosition(0f, 1000f, tenMinutes))
        assertEquals(300_000L, seekPosition(500f, 1000f, tenMinutes))
        assertEquals(tenMinutes, seekPosition(1000f, 1000f, tenMinutes))
    }

    @Test fun a_drag_past_either_end_of_the_bar_is_clamped() {
        assertEquals(0L, seekPosition(-400f, 1000f, tenMinutes))
        assertEquals(tenMinutes, seekPosition(4000f, 1000f, tenMinutes))
    }

    @Test fun a_bar_with_no_width_or_a_file_with_no_duration_does_not_divide_by_zero() {
        assertEquals(0L, seekPosition(120f, 0f, tenMinutes))
        assertEquals(0L, seekPosition(120f, 1000f, 0L))
        assertEquals(0L, seekPosition(120f, 1000f, -1L))
    }

    @Test fun the_drawn_playhead_and_the_drag_agree() {
        for (px in intArrayOf(0, 137, 500, 863, 1000)) {
            val position = seekPosition(px.toFloat(), 1000f, tenMinutes)
            assertEquals(px / 1000f, seekFraction(position, tenMinutes), 0.001f)
        }
    }

    @Test fun the_fraction_stays_inside_the_bar_even_when_the_player_reports_nonsense() {
        // MediaPlayer can report a position past the duration around the end of a stream.
        assertEquals(1f, seekFraction(tenMinutes + 5_000L, tenMinutes), 0f)
        assertEquals(0f, seekFraction(-10L, tenMinutes), 0f)
        assertEquals(0f, seekFraction(1_000L, 0L), 0f)
    }

    // ── which side was tapped ──

    @Test fun the_left_edge_goes_back_and_the_right_edge_goes_forward() {
        assertEquals(TapZone.BACK, tapZone(40f, 1000f))
        assertEquals(TapZone.FORWARD, tapZone(960f, 1000f))
    }

    @Test fun the_middle_is_neither() {
        // A thumb rests in the middle of the picture. Jumping ten seconds because of that is
        // the behaviour this band exists to prevent.
        assertEquals(TapZone.MIDDLE, tapZone(500f, 1000f))
        assertEquals(TapZone.MIDDLE, tapZone(420f, 1000f))
        assertEquals(TapZone.MIDDLE, tapZone(580f, 1000f))
    }

    @Test fun the_band_boundaries_are_where_they_are_claimed_to_be() {
        // A 30% middle band leaves 35% each side.
        assertEquals(TapZone.BACK, tapZone(340f, 1000f))
        assertEquals(TapZone.MIDDLE, tapZone(360f, 1000f))
        assertEquals(TapZone.MIDDLE, tapZone(640f, 1000f))
        assertEquals(TapZone.FORWARD, tapZone(660f, 1000f))
    }

    @Test fun a_view_with_no_width_never_seeks_by_accident() {
        assertEquals(TapZone.MIDDLE, tapZone(0f, 0f))
    }

    // ── the jump itself ──

    @Test fun a_double_tap_left_goes_back_ten_seconds_and_right_goes_forward_ten() {
        assertEquals(90_000L, doubleTapTarget(100_000L, tenMinutes, TapZone.BACK))
        assertEquals(110_000L, doubleTapTarget(100_000L, tenMinutes, TapZone.FORWARD))
    }

    @Test fun the_middle_band_does_not_move_the_playhead() {
        assertEquals(100_000L, doubleTapTarget(100_000L, tenMinutes, TapZone.MIDDLE))
    }

    @Test fun tapping_again_keeps_adding() {
        assertEquals(70_000L, doubleTapTarget(100_000L, tenMinutes, TapZone.BACK, taps = 3))
        assertEquals(130_000L, doubleTapTarget(100_000L, tenMinutes, TapZone.FORWARD, taps = 3))
    }

    @Test fun going_back_from_the_start_stops_at_the_start_rather_than_wrapping() {
        assertEquals(0L, doubleTapTarget(3_000L, tenMinutes, TapZone.BACK))
        assertEquals(0L, doubleTapTarget(3_000L, tenMinutes, TapZone.BACK, taps = 9))
    }

    @Test fun going_forward_near_the_end_stops_short_of_it() {
        // Landing exactly on the duration just ends playback, which reads as the gesture
        // having skipped the whole track.
        val end = doubleTapTarget(tenMinutes - 2_000L, tenMinutes, TapZone.FORWARD)
        assertTrue("$end should be inside the file", end < tenMinutes)
        assertNotEquals(tenMinutes, end)
    }

    @Test fun a_file_with_no_known_duration_is_left_alone() {
        assertEquals(42_000L, doubleTapTarget(42_000L, 0L, TapZone.FORWARD))
    }

    // ── runs ──

    @Test fun two_taps_on_the_same_side_inside_the_window_accumulate() {
        val first = advanceRun(null, TapZone.FORWARD, nowMs = 1_000L)
        val second = advanceRun(first, TapZone.FORWARD, nowMs = 1_300L)
        assertEquals(1, first.taps)
        assertEquals(2, second.taps)
    }

    @Test fun crossing_to_the_other_side_starts_the_count_again() {
        val first = advanceRun(null, TapZone.FORWARD, nowMs = 1_000L)
        val other = advanceRun(first, TapZone.BACK, nowMs = 1_100L)
        assertEquals(1, other.taps)
        assertEquals(TapZone.BACK, other.zone)
    }

    @Test fun a_pause_longer_than_the_window_starts_the_count_again() {
        val first = advanceRun(null, TapZone.FORWARD, nowMs = 1_000L)
        val late = advanceRun(first, TapZone.FORWARD, nowMs = 5_000L)
        assertEquals(1, late.taps)
    }

    // ── the readout ──

    @Test fun the_clock_pads_seconds_and_shows_hours_only_when_there_are_any() {
        assertEquals("0:00", clockOf(0L))
        assertEquals("0:07", clockOf(7_400L))
        assertEquals("4:03", clockOf(243_000L))
        assertEquals("1:02:59", clockOf(3_779_000L))
        assertEquals("0:00", clockOf(-5L))
    }

    @Test fun the_remaining_time_counts_down_and_never_goes_negative() {
        assertEquals("-1:23", remainingOf(517_000L, tenMinutes))
        assertEquals("-0:00", remainingOf(tenMinutes, tenMinutes))
        assertEquals("-0:00", remainingOf(tenMinutes + 9_000L, tenMinutes))
    }
}

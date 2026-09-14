package dev.niccc2007.filet.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resize arithmetic for the release-notes pane.
 *
 * The drag inversion is the assertion worth having. Compose reports a downward drag as a
 * positive delta, and the handle is at the TOP of the pane, so pulling it down must make the
 * pane *smaller*. Written the obvious way round it feels broken in a way that is hard to
 * describe and easy to ship.
 */
class SheetSizeTest {

    private val SCREEN = 2400f

    @Test fun dragging_the_handle_up_makes_the_pane_bigger() {
        // Negative delta = upward.
        val bigger = nextNotesFraction(current = 0.5f, dragPx = -240f, screenPx = SCREEN)
        assertTrue("up must grow, got $bigger", bigger > 0.5f)
    }

    @Test fun dragging_the_handle_down_makes_the_pane_smaller() {
        val smaller = nextNotesFraction(current = 0.5f, dragPx = 240f, screenPx = SCREEN)
        assertTrue("down must shrink, got $smaller", smaller < 0.5f)
    }

    @Test fun a_drag_moves_the_pane_by_the_distance_the_finger_moved() {
        // Not "some amount in the right direction": the edge should track the finger, or the
        // handle feels like it is slipping.
        val moved = nextNotesFraction(current = 0.5f, dragPx = -240f, screenPx = SCREEN)
        assertEquals(0.5f + 240f / SCREEN, moved, 0.0001f)
    }

    @Test fun it_never_leaves_its_bounds_however_hard_you_pull() {
        assertEquals(NOTES_MAX_FRACTION, nextNotesFraction(0.5f, -99_999f, SCREEN), 0.0001f)
        assertEquals(NOTES_MIN_FRACTION, nextNotesFraction(0.5f, 99_999f, SCREEN), 0.0001f)
    }

    @Test fun the_bounds_hold_for_every_starting_point_and_every_drag() {
        var f = NOTES_DEFAULT_FRACTION
        var drag = -500f
        repeat(200) {
            f = nextNotesFraction(f, drag, SCREEN)
            assertTrue("escaped to $f", f in NOTES_MIN_FRACTION..NOTES_MAX_FRACTION)
            drag = -drag * 0.97f
        }
    }

    @Test fun a_screen_height_that_is_not_known_yet_does_not_produce_a_nonsense_number() {
        // First frame, before layout. Dividing by zero here would give NaN or Infinity and the
        // pane would render as nothing at all.
        val f = nextNotesFraction(0.5f, -200f, screenPx = 0f)
        assertEquals(0.5f, f, 0.0001f)
        assertTrue(!f.isNaN())
    }

    // ── what gets stored ──

    @Test fun nothing_stored_means_the_default_not_the_minimum() {
        // A stored 0 read as a fraction would clamp to the minimum and open as a slot.
        assertEquals(NOTES_DEFAULT_FRACTION, usableNotesFraction(0f), 0.0001f)
        assertEquals(NOTES_DEFAULT_FRACTION, usableNotesFraction(-1f), 0.0001f)
    }

    @Test fun a_stored_choice_is_kept() {
        assertEquals(0.4f, usableNotesFraction(0.4f), 0.0001f)
    }

    @Test fun a_stored_value_from_a_different_build_is_brought_back_into_range() {
        assertEquals(NOTES_MAX_FRACTION, usableNotesFraction(0.99f), 0.0001f)
        assertEquals(NOTES_MIN_FRACTION, usableNotesFraction(0.05f), 0.0001f)
    }

    @Test fun the_default_is_actually_big() {
        // The complaint this exists to answer: "theres a lot of content and only a small amount
        // of space". The old pane was a flat 260dp, which on this phone is about 11% of the
        // screen and less than one 208dp screenshot strip.
        assertTrue("the default must be more than half the screen", NOTES_DEFAULT_FRACTION > 0.5f)
        assertTrue(NOTES_DEFAULT_FRACTION in NOTES_MIN_FRACTION..NOTES_MAX_FRACTION)
        assertTrue("and the buttons still have to fit", NOTES_MAX_FRACTION < 0.85f)
    }
}

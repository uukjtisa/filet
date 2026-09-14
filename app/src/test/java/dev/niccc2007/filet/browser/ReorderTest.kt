package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Dragging a tab into a new position, which is three lines each containing an off-by-one. */
class ReorderTest {

    private val tabs = listOf("a", "b", "c", "d", "e")

    @Test fun dragging_right_closes_the_gap_behind_it() {
        assertEquals(listOf("b", "c", "a", "d", "e"), tabs.movedItem(from = 0, to = 2))
    }

    @Test fun dragging_left_pushes_the_others_along() {
        assertEquals(listOf("a", "d", "b", "c", "e"), tabs.movedItem(from = 3, to = 1))
    }

    @Test fun dragging_to_either_end_works() {
        assertEquals(listOf("c", "a", "b", "d", "e"), tabs.movedItem(2, 0))
        assertEquals(listOf("b", "c", "d", "e", "a"), tabs.movedItem(0, 4))
    }

    @Test fun a_move_to_where_it_already_is_changes_nothing_and_allocates_nothing() {
        assertSame(tabs, tabs.movedItem(2, 2))
    }

    @Test fun an_index_outside_the_list_is_the_identity_rather_than_a_crash() {
        // A drag can end after the tab it was dragging has been closed by something else, and
        // a reorder is never worth taking the app down for.
        assertSame(tabs, tabs.movedItem(-1, 2))
        assertSame(tabs, tabs.movedItem(2, 9))
        assertSame(tabs, tabs.movedItem(9, 9))
        assertSame(emptyList<String>(), emptyList<String>().movedItem(0, 0))
    }

    @Test fun nothing_is_lost_or_duplicated_by_any_move() {
        for (from in tabs.indices) {
            for (to in tabs.indices) {
                val out = tabs.movedItem(from, to)
                assertEquals("$from -> $to changed the size", tabs.size, out.size)
                assertEquals("$from -> $to lost or duplicated", tabs.toSet(), out.toSet())
                assertEquals("$from -> $to moved the wrong item", tabs[from], out[to])
            }
        }
    }

    // ── which slot the finger is over ──

    /** Five 100-wide tabs starting at zero, so the centres are 50, 150, 250, 350, 450. */
    private val centres = listOf(50f, 150f, 250f, 350f, 450f)

    @Test fun an_item_swaps_once_it_has_moved_more_than_half_a_slot() {
        // The midpoint between the first two centres is 100. Swapping earlier than that means
        // a tab dragged one pixel too far jitters between two positions on every frame.
        assertEquals(0, slotFor(51f, centres))
        assertEquals(0, slotFor(99f, centres))
        assertEquals(1, slotFor(101f, centres))
        assertEquals(1, slotFor(199f, centres))
        assertEquals(2, slotFor(201f, centres))
    }

    @Test fun sitting_on_a_centre_is_that_slot() {
        for (i in centres.indices) {
            assertEquals(i, slotFor(centres[i], centres))
        }
    }

    @Test fun a_finger_past_either_end_pins_to_that_end() {
        assertEquals(0, slotFor(-400f, centres))
        assertEquals(4, slotFor(9_000f, centres))
    }

    @Test fun an_empty_strip_does_not_divide_by_anything() {
        assertEquals(0, slotFor(120f, emptyList()))
        assertEquals(0, slotFor(0f, listOf(10f)))
    }

    @Test fun the_drag_target_is_the_slot_under_the_finger() {
        assertEquals(3, dragTarget(from = 0, x = 360f, centres = centres))
        assertEquals(0, dragTarget(from = 4, x = 10f, centres = centres))
    }

    @Test fun a_drag_from_an_index_that_no_longer_exists_stays_put() {
        assertEquals(7, dragTarget(from = 7, x = 360f, centres = centres))
    }

    @Test fun a_drag_that_ends_where_it_started_commits_nothing() {
        val target = dragTarget(from = 2, x = 251f, centres = centres)
        assertEquals(2, target)
        assertSame(tabs, tabs.movedItem(2, target))
    }
}

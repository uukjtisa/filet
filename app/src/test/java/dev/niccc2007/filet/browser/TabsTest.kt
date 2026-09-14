package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tab pointer, which could land outside the list and blank the screen.
 *
 * The old expression clamped before it decremented, so with enough tabs the active pointer
 * could move two places instead of one and end up past the end. `paneFor` returned null and
 * the pane rendered as nothing. The reason it needed "some kind of tabs shown limit" to
 * appear is that with two or three tabs the clamp almost never bites.
 *
 * So the tests here are mostly exhaustive rather than illustrative: every close from every
 * position, at several list sizes, with the invariant that the answer is always inside the
 * list.
 */
class TabsTest {

    // ── closing ──

    @Test fun closing_a_tab_after_the_active_one_leaves_it_alone() {
        assertEquals(2, indexAfterClose(index = 2, closed = 5, newSize = 7))
    }

    @Test fun closing_a_tab_before_the_active_one_shifts_it_back_by_exactly_one() {
        // Exactly one. The old code took one off in the clamp and another in the decrement.
        assertEquals(4, indexAfterClose(index = 5, closed = 1, newSize = 7))
    }

    @Test fun closing_the_last_tab_while_it_is_active_lands_on_the_new_last() {
        // The case that produced an out-of-range pointer and a blank pane.
        assertEquals(6, indexAfterClose(index = 7, closed = 7, newSize = 7))
    }

    @Test fun closing_the_active_tab_lands_on_the_one_that_took_its_place() {
        assertEquals(3, indexAfterClose(index = 3, closed = 3, newSize = 7))
    }

    @Test fun closing_the_first_tab_while_it_is_active_stays_at_the_front() {
        assertEquals(0, indexAfterClose(index = 0, closed = 0, newSize = 4))
    }

    @Test fun the_answer_is_always_a_tab_that_exists() {
        // The invariant the bug broke. Exhaustive, because "it only happens with a lot of
        // tabs" is exactly the shape of thing an example-based test misses.
        for (size in 1..24) {
            for (active in 0 until size) {
                for (closed in 0 until size) {
                    val newSize = size - 1
                    if (newSize == 0) continue
                    val out = indexAfterClose(active, closed, newSize)
                    assertTrue(
                        "size=$size active=$active closed=$closed gave $out",
                        out in 0 until newSize,
                    )
                }
            }
        }
    }

    @Test fun closing_one_of_many_keeps_you_on_the_same_tab_you_were_looking_at() {
        // Identity check: the pane you were on should still be the pane you are on, whichever
        // OTHER tab was closed.
        val names = (0 until 20).map { "tab$it" }
        for (active in names.indices) {
            for (closed in names.indices) {
                if (closed == active) continue
                val left = names.filterIndexed { i, _ -> i != closed }
                val out = indexAfterClose(active, closed, left.size)
                assertEquals("closed=$closed active=$active", names[active], left[out])
            }
        }
    }

    @Test fun an_empty_list_answers_zero_rather_than_minus_one() {
        assertEquals(0, indexAfterClose(index = 0, closed = 0, newSize = 0))
    }

    // ── moving ──

    @Test fun a_dragged_tab_takes_the_pointer_with_it() {
        assertEquals(4, indexAfterMove(index = 1, from = 1, to = 4))
    }

    @Test fun a_tab_the_drag_passed_shifts_the_other_way() {
        assertEquals(1, indexAfterMove(index = 2, from = 1, to = 4))
        assertEquals(3, indexAfterMove(index = 2, from = 4, to = 1))
    }

    @Test fun a_tab_the_drag_did_not_touch_stays_put() {
        assertEquals(6, indexAfterMove(index = 6, from = 1, to = 4))
        assertEquals(0, indexAfterMove(index = 0, from = 1, to = 4))
    }

    @Test fun a_move_keeps_you_on_the_same_tab_for_every_pair() {
        val names = (0 until 12).map { "tab$it" }
        for (from in names.indices) {
            for (to in names.indices) {
                val moved = names.movedItem(from, to)
                for (active in names.indices) {
                    val out = indexAfterMove(active, from, to)
                    assertTrue("from=$from to=$to active=$active gave $out", out in names.indices)
                    assertEquals("from=$from to=$to active=$active", names[active], moved[out])
                }
            }
        }
    }

    // ── the last line of defence ──

    @Test fun a_pointer_that_slipped_anyway_is_clamped_rather_than_blanking_the_screen() {
        assertEquals(3, safeTabIndex(9, 4))
        assertEquals(0, safeTabIndex(-2, 4))
        assertEquals(2, safeTabIndex(2, 4))
    }

    @Test fun with_no_tabs_there_is_honestly_no_answer() {
        assertNull(safeTabIndex(0, 0))
    }
}

package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shift-click on a touchscreen.
 *
 * Two failures are worth more than the rest:
 *
 * 1. **A range that runs the wrong way.** Selecting upwards is exactly as common as selecting
 *    downwards and an off-by-one or an unordered `subList` throws rather than misbehaving, so
 *    it fails loudly in a test and silently in review.
 * 2. **A range measured against the wrong list.** The rows the user sees are sorted and
 *    filtered; extending across the underlying order would select files that were never on
 *    screen. Every test here works against the DISPLAY order for that reason.
 */
class RangeSelectTest {

    private fun p(name: String) = VPath.of("local", "/d/$name")

    /** Deliberately not alphabetical: the display order is whatever the sort produced. */
    private val visible = listOf("e", "c", "a", "d", "b", "f", "g").map(::p)

    @Test fun a_downward_range_is_inclusive_at_both_ends() {
        val out = rangeSelect(visible, setOf(p("c")), anchor = p("c"), target = p("d"))
        assertEquals(setOf(p("c"), p("a"), p("d")), out)
    }

    @Test fun an_upward_range_selects_the_same_rows_as_the_downward_one() {
        // The gesture is symmetric and the arithmetic has to be too.
        val down = rangeSelect(visible, setOf(p("c")), anchor = p("c"), target = p("f"))
        val up = rangeSelect(visible, setOf(p("f")), anchor = p("f"), target = p("c"))
        assertEquals(down, up)
    }

    @Test fun the_range_follows_display_order_not_the_names() {
        // "c" to "b" spans c,a,d,b as shown. Sorted by name it would be b,c - two rows, the
        // wrong two. This is the assertion that catches a range taken against the wrong list.
        val out = rangeSelect(visible, setOf(p("c")), anchor = p("c"), target = p("b"))
        assertEquals(setOf(p("c"), p("a"), p("d"), p("b")), out)
    }

    @Test fun pressing_the_anchor_itself_selects_exactly_it() {
        val out = rangeSelect(visible, setOf(p("a")), anchor = p("a"), target = p("a"))
        assertEquals(setOf(p("a")), out)
    }

    @Test fun an_earlier_selection_is_kept_rather_than_replaced() {
        // A desktop shift-click replaces. On a phone the only way to build a selection is one
        // gesture at a time, so wiping four deliberate picks for the sake of a fifth is losing
        // work that cannot be cheaply redone.
        val existing = setOf(p("g"), p("e"))
        val out = rangeSelect(visible, existing, anchor = p("c"), target = p("d"))
        assertTrue(out.containsAll(existing))
        assertTrue(out.containsAll(listOf(p("c"), p("a"), p("d"))))
    }

    @Test fun with_no_anchor_it_selects_the_one_row_pressed() {
        assertEquals(setOf(p("d")), rangeSelect(visible, emptySet(), anchor = null, target = p("d")))
    }

    @Test fun a_list_that_changed_under_the_gesture_selects_only_what_was_pressed() {
        // A rename, a refresh, a filter typed while the finger was down. Guessing a range
        // against a list that no longer matches the screen would select arbitrary files.
        val out = rangeSelect(visible, setOf(p("c")), anchor = p("gone"), target = p("d"))
        assertEquals(setOf(p("c"), p("d")), out)
    }

    @Test fun a_target_that_is_no_longer_there_still_does_not_throw() {
        val out = rangeSelect(visible, setOf(p("c")), anchor = p("c"), target = p("vanished"))
        assertTrue(out.contains(p("c")))
    }

    @Test fun every_pair_of_rows_produces_a_range_of_the_right_size() {
        // Exhaustive, because off-by-one is the whole risk and it only shows at the ends.
        for (i in visible.indices) {
            for (j in visible.indices) {
                val out = rangeSelect(visible, emptySet(), visible[i], visible[j])
                assertEquals(
                    "range $i..$j",
                    kotlin.math.abs(i - j) + 1,
                    out.size,
                )
            }
        }
    }

    // ── which meaning a long press has ──

    @Test fun with_nothing_selected_a_long_press_opens_the_menu() {
        assertEquals(LongPressAction.MENU, longPressAction(0, targetIsSelected = false))
    }

    @Test fun with_a_selection_running_a_press_outside_it_extends() {
        assertEquals(LongPressAction.EXTEND, longPressAction(3, targetIsSelected = false))
    }

    @Test fun pressing_one_of_the_selected_items_opens_the_menu_for_all_of_them() {
        assertEquals(LongPressAction.MENU_FOR_SELECTION, longPressAction(3, targetIsSelected = true))
    }

    @Test fun one_selected_item_pressed_again_still_means_the_selection() {
        // The boundary between "a selection" and "an item". One IS a selection - the bottom bar
        // says "1 selected" - so the menu has to agree with it.
        assertEquals(LongPressAction.MENU_FOR_SELECTION, longPressAction(1, targetIsSelected = true))
    }

    @Test fun every_state_has_exactly_one_meaning() {
        // No combination may fall through to a default, because a long press that quietly does
        // nothing reads as the app ignoring you.
        for (size in 0..5) {
            for (selected in listOf(true, false)) {
                if (size == 0 && selected) continue
                assertTrue(longPressAction(size, selected) in LongPressAction.entries)
            }
        }
    }
}

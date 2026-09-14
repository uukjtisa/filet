package dev.niccc2007.filet.browser

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one list both renderings draw.
 *
 * The reason this is a list rather than two composables: the bar and the menu show the same
 * actions, and two hand-written copies drift. The drift is always the same shape - an action
 * added to one, or blocked in one and live in the other - and the second one is a real bug,
 * because the live copy then lets somebody rename four files at once.
 *
 * So the assertions here are about the *rules*, not about any one button.
 */
class SelectionActionsTest {

    private val icon = ImageVector.Builder("x", 1.dp, 1.dp, 1f, 1f).build()
    private val icons = SelectionIcons(icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon)

    private var ran = ""
    private val callbacks = SelectionCallbacks(
        copy = { ran = "copy" },
        move = { ran = "move" },
        send = { ran = "send" },
        delete = { ran = "delete" },
        compress = { ran = "compress" },
        rename = { ran = "rename" },
        openWith = { ran = "openWith" },
        details = { ran = "details" },
        bookmark = { ran = "bookmark" },
        nearby = { ran = "nearby" },
        shortcut = { ran = "shortcut" },
    )

    private fun actions(count: Int, readOnly: String? = null) =
        selectionActions(count, readOnly, icons, callbacks)

    @Test fun the_same_actions_exist_whatever_is_selected() {
        // Disabled, never hidden. Hiding makes the menu change length between one file and two,
        // and leaves somebody hunting for where "Open with" went.
        val one = actions(1).map { it.id }
        val many = actions(7).map { it.id }
        assertEquals(one, many)
    }

    @Test fun every_action_has_a_unique_id_and_a_label() {
        val all = actions(1)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.none { it.label.isBlank() })
    }

    @Test fun single_file_actions_are_blocked_with_a_reason_when_several_are_selected() {
        val many = actions(4).associateBy { it.id }
        for (id in listOf("rename", "openwith", "details")) {
            val reason = many.getValue(id).blocked
            assertNotNull("$id should be blocked for a multi-selection", reason)
            assertTrue("$id must say WHY", reason!!.length > 10)
        }
    }

    @Test fun those_same_actions_are_live_for_exactly_one_file() {
        val one = actions(1).associateBy { it.id }
        for (id in listOf("rename", "openwith", "details")) {
            assertNull("$id should be available for one file", one.getValue(id).blocked)
        }
    }

    @Test fun a_read_only_volume_blocks_everything_that_writes_and_nothing_that_does_not() {
        val ro = actions(1, readOnly = "This volume is read-only").associateBy { it.id }
        for (id in listOf("move", "delete", "compress", "rename")) {
            assertNotNull("$id writes and must be blocked", ro.getValue(id).blocked)
        }
        for (id in listOf("copy", "send", "details", "bookmark", "shortcut")) {
            assertNull("$id only reads and must stay available", ro.getValue(id).blocked)
        }
    }

    @Test fun read_only_beats_the_one_file_rule_because_it_is_the_more_basic_obstacle() {
        // Telling somebody to select one file, when selecting one file still would not work,
        // is worse than telling them nothing.
        val ro = actions(5, readOnly = "This volume is read-only").associateBy { it.id }
        assertEquals("This volume is read-only", ro.getValue("rename").blocked)
    }

    @Test fun delete_is_the_only_dangerous_one_and_it_is_last() {
        val all = actions(1)
        assertEquals(listOf("delete"), all.filter { it.danger }.map { it.id })
        assertEquals("delete", all.last().id)
    }

    @Test fun the_groups_run_in_order_and_never_interleave() {
        // The divider is drawn where the group changes, so a group appearing twice would draw
        // a divider in the middle of nothing.
        val seen = ArrayList<SelectionAction.Group>()
        for (a in actions(1)) if (seen.lastOrNull() != a.group) seen += a.group
        assertEquals(seen, seen.distinct())
    }

    @Test fun each_action_runs_its_own_callback() {
        // The failure this catches is a copy-paste in the list: two entries wired to the same
        // lambda, which in a menu of eleven is genuinely hard to see by reading.
        for (action in actions(1)) {
            ran = ""
            action.run()
            assertTrue("${action.id} ran nothing", ran.isNotEmpty())
        }
    }

    @Test fun no_two_actions_share_a_callback() {
        val fired = actions(1).map { action ->
            ran = ""
            action.run()
            ran
        }
        assertEquals(fired.size, fired.toSet().size)
    }

    // ── the style setting ──

    @Test fun the_menu_is_the_default_and_an_unknown_value_falls_back_to_it() {
        assertEquals(SelectionStyle.MENU, SelectionStyle.valueOfOr(null, SelectionStyle.MENU))
        assertEquals(SelectionStyle.MENU, SelectionStyle.valueOfOr("NONSENSE", SelectionStyle.MENU))
    }

    @Test fun the_legacy_bar_is_still_selectable() {
        // R1, no dead switches: the option in Settings has to reach a real rendering.
        assertEquals(SelectionStyle.BAR, SelectionStyle.valueOfOr("BAR", SelectionStyle.MENU))
    }
}

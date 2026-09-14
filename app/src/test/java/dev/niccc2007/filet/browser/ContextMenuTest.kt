package dev.niccc2007.filet.browser

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The split between the icon row and the labelled rows.
 *
 * This is the one decision in the context menu that can be wrong without anybody noticing.
 * `QUICK_IDS` is a list of strings matched against action ids, so a typo does not fail to
 * compile - it silently drops a verb out of the icon row and leaves it further down the list,
 * where the whole point of the row was that the common five sit at a fixed position.
 *
 * Both halves come from the SAME list the selection bar uses, which is what stops an action
 * existing on one surface and not another. That is asserted here rather than assumed.
 */
class ContextMenuTest {

    private val icon = ImageVector.Builder("x", 1.dp, 1.dp, 1f, 1f).build()
    private val icons = SelectionIcons(icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon)
    private val callbacks = SelectionCallbacks(
        copy = {}, move = {}, send = {}, delete = {}, compress = {}, rename = {},
        openWith = {}, details = {}, bookmark = {}, nearby = {}, shortcut = {},
    )

    private fun actions(readOnly: String? = null) = selectionActions(1, readOnly, icons, callbacks)

    @Test fun every_quick_id_names_an_action_that_really_exists() {
        // The failure this catches: a renamed action id leaves QUICK_IDS pointing at nothing,
        // the icon row quietly loses a verb, and the menu still compiles and still draws.
        val ids = actions().map { it.id }.toSet()
        for (id in QUICK_IDS) {
            assertTrue("QUICK_IDS names \"$id\", which is not an action", id in ids)
        }
    }

    @Test fun the_icon_row_is_the_five_verbs_in_windows_order() {
        val (quick, _) = splitForContextMenu(actions())
        assertEquals(listOf("copy", "move", "rename", "send", "delete"), quick.map { it.id })
    }

    @Test fun nothing_appears_in_both_halves() {
        val (quick, rest) = splitForContextMenu(actions())
        val shared = quick.map { it.id }.toSet() intersect rest.map { it.id }.toSet()
        assertTrue("an action is in the icon row AND the list: $shared", shared.isEmpty())
    }

    @Test fun nothing_is_lost_between_the_two_halves() {
        // The menu is built from the selection's own action list, so an action that exists on
        // the bottom bar and not in the menu would be a surface with fewer verbs than another.
        val all = actions()
        val (quick, rest) = splitForContextMenu(all)
        assertEquals(all.size, quick.size + rest.size)
        assertEquals(all.map { it.id }.toSet(), (quick + rest).map { it.id }.toSet())
    }

    @Test fun the_rest_keeps_the_order_the_action_list_gave_it() {
        val all = actions()
        val (_, rest) = splitForContextMenu(all)
        val expected = all.map { it.id }.filterNot { it in QUICK_IDS }
        assertEquals(expected, rest.map { it.id })
    }

    @Test fun the_groups_in_the_rest_are_still_contiguous() {
        // The menu draws a divider wherever the group changes, so a group that appears twice
        // would draw two sets of dividers around the same kind of thing.
        val (_, rest) = splitForContextMenu(actions())
        val seen = mutableListOf<SelectionAction.Group>()
        for (a in rest) if (seen.lastOrNull() != a.group) seen.add(a.group)
        assertEquals(seen.size, seen.toSet().size)
    }

    @Test fun a_read_only_volume_still_fills_the_icon_row_with_reasons_not_gaps() {
        // A blocked action stays in the row and answers with why. Dropping it would move the
        // remaining icons, so the same verb would sit under a different finger position
        // depending on the volume.
        val (quick, _) = splitForContextMenu(actions(readOnly = "This volume is read-only"))
        assertEquals(QUICK_IDS.size, quick.size)
        assertTrue(quick.any { it.blocked != null })
    }

    @Test fun an_extra_row_is_a_real_action_with_its_own_callback() {
        var ran = false
        val a = menuAction("select", "Select", icon) { ran = true }
        assertEquals("select", a.id)
        assertEquals(null, a.blocked)
        a.run()
        assertTrue(ran)
    }
}

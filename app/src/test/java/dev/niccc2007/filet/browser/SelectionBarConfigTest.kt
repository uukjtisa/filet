package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import org.junit.Test

class SelectionBarConfigTest {

    private fun action(id: String) = SelectionAction(
        id = id,
        label = id,
        icon = androidx.compose.ui.graphics.vector.ImageVector
            .Builder("x", 1.dp, 1.dp, 1f, 1f).build(),
        run = {},
    )

    private val live = listOf(
        "copy", "move", "send", "compress", "rename", "openwith",
        "details", "bookmark", "nearby", "shortcut", "delete",
    ).map(::action)

    @Test
    fun `the split loses nothing and duplicates nothing`() {
        // The invariant the whole object exists for. An action in neither list is unreachable;
        // an action in both is two buttons doing one thing. Both are silent.
        for (bar in listOf(
            SelectionBarConfig.DEFAULT,
            listOf("copy"),
            listOf("delete", "copy", "nearby"),
            emptyList(),
        )) {
            val (onBar, more) = SelectionBarConfig.split(live, bar)
            val ids = (onBar + more).map { it.id }
            assertEquals("$bar lost or duplicated", live.size, ids.size)
            assertEquals("$bar", live.map { it.id }.toSet(), ids.toSet())
        }
    }

    @Test
    fun `the bar follows the chosen order, the rest keep theirs`() {
        val (onBar, more) = SelectionBarConfig.split(live, listOf("delete", "copy"))
        assertEquals(listOf("delete", "copy"), onBar.map { it.id })
        // The remainder keeps the order selectionActions produced, which carries the grouping.
        assertEquals(live.map { it.id } - setOf("delete", "copy"), more.map { it.id })
    }

    @Test
    fun `an id with no live action is skipped, not drawn as a gap`() {
        // `install` only exists for an APK. A permanent hole in the bar for every other
        // selection is worse than a shorter bar.
        val (onBar, _) = SelectionBarConfig.split(live, listOf("copy", "install", "delete"))
        assertEquals(listOf("copy", "delete"), onBar.map { it.id })
    }

    @Test
    fun `a stored bar survives renames and bad edits`() {
        assertEquals(SelectionBarConfig.DEFAULT, SelectionBarConfig.normalise(null))
        assertEquals(SelectionBarConfig.DEFAULT, SelectionBarConfig.normalise(""))
        // Every id gone means the fallback, not a blank strip with no way back to Settings.
        assertEquals(SelectionBarConfig.DEFAULT, SelectionBarConfig.normalise("nope,gone"))
        assertEquals(listOf("copy"), SelectionBarConfig.normalise("copy,copy, copy "))
        assertEquals(listOf("copy", "delete"), SelectionBarConfig.normalise("copy,nope,delete"))
    }

    @Test
    fun `a stored bar cannot exceed the cap`() {
        val tooMany = SelectionBarConfig.IDS.joinToString(",")
        assertEquals(SelectionBarConfig.MAX_ON_BAR, SelectionBarConfig.normalise(tooMany).size)
    }

    @Test
    fun `encode and normalise round trip`() {
        val ids = listOf("delete", "copy", "nearby")
        assertEquals(ids, SelectionBarConfig.normalise(SelectionBarConfig.encode(ids)))
    }

    @Test
    fun `the last entry cannot be removed`() {
        // A bar with nothing on it is one More button and a count, which reads as a failure to
        // load rather than as a choice.
        assertEquals(listOf("copy"), SelectionBarConfig.toggled(listOf("copy"), "copy"))
    }

    @Test
    fun `adding past the cap is refused rather than silently dropping one`() {
        val full = SelectionBarConfig.IDS.take(SelectionBarConfig.MAX_ON_BAR)
        val after = SelectionBarConfig.toggled(full, SelectionBarConfig.IDS.last())
        assertEquals(full, after)
        assertTrue(SelectionBarConfig.full(full))
    }

    @Test
    fun `an unknown id changes nothing`() {
        val ids = listOf("copy", "delete")
        assertEquals(ids, SelectionBarConfig.toggled(ids, "not-an-action"))
    }

    @Test
    fun `every choosable id is one the action list actually produces`() {
        // The drift this catches: renaming an action's id and leaving the table alone gives a
        // Settings row that switches on something nobody can find.
        val icon = androidx.compose.ui.graphics.vector.ImageVector
            .Builder("x", 1.dp, 1.dp, 1f, 1f).build()
        val produced = selectionActions(
            count = 1,
            readOnly = null,
            icons = SelectionIcons(
                icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon,
            ),
            on = SelectionCallbacks(
                copy = {}, move = {}, send = {}, delete = {}, compress = {}, rename = {},
                openWith = {}, details = {}, bookmark = {}, nearby = {}, shortcut = {},
            ),
            installable = true,
        ).map { it.id }.toSet()

        for (id in SelectionBarConfig.IDS) {
            assertTrue("$id is choosable but never produced", id in produced)
        }
    }

    @Test
    fun `the default bar is valid and fits`() {
        assertTrue(SelectionBarConfig.DEFAULT.size <= SelectionBarConfig.MAX_ON_BAR)
        for (id in SelectionBarConfig.DEFAULT) {
            assertNotNull("$id has no label", SelectionBarConfig.labelFor(id))
            assertTrue("$id is not choosable", id in SelectionBarConfig.IDS)
        }
    }
}

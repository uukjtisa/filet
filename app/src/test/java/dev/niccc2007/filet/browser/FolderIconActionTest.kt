package dev.niccc2007.filet.browser

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Change icon is offered for folders, any number of them, and only for folders. */
class FolderIconActionTest {

    private val icon = ImageVector.Builder("x", 1.dp, 1.dp, 1f, 1f).build()
    private val icons = SelectionIcons(
        icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon,
    )
    private var ran = false
    private val on = SelectionCallbacks(
        copy = {}, move = {}, send = {}, delete = {}, compress = {}, rename = {},
        openWith = {}, details = {}, bookmark = {}, nearby = {}, shortcut = {},
        folderIcon = { ran = true },
    )

    private fun ids(shape: SelectionShape, readOnly: String? = null, callbacks: SelectionCallbacks = on) =
        selectionActions(shape.count, readOnly, icons, callbacks, shape = shape).map { it.id }

    @Test fun one_folder_or_many_get_it() {
        assertTrue("foldericon" in ids(SelectionShape(count = 1, folders = 1)))
        assertTrue("foldericon" in ids(SelectionShape(count = 7, folders = 7)))
    }

    @Test fun a_file_in_the_selection_takes_it_away() {
        assertTrue("foldericon" !in ids(SelectionShape(count = 3, folders = 2)))
        assertTrue("foldericon" !in ids(SelectionShape(count = 1, folders = 0)))
    }

    @Test fun a_surface_that_does_not_offer_it_draws_no_row() {
        val without = on.copy(folderIcon = null)
        assertTrue("foldericon" !in ids(SelectionShape(count = 1, folders = 1), callbacks = without))
    }

    @Test fun a_read_only_volume_does_not_block_it_since_the_look_is_kept_in_filet() {
        val row = selectionActions(
            1, "Read-only", icons, on, shape = SelectionShape(count = 1, folders = 1),
        ).first { it.id == "foldericon" }
        assertEquals(null, row.blocked)
        row.run()
        assertTrue(ran)
    }

    @Test fun it_can_go_on_the_bar() {
        assertEquals("Change icon", SelectionBarConfig.labelFor("foldericon"))
    }
}

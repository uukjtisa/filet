package dev.niccc2007.filet.handlers

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.browser.SelectionCallbacks
import dev.niccc2007.filet.browser.SelectionIcons
import dev.niccc2007.filet.browser.SelectionShape
import dev.niccc2007.filet.browser.selectionActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerMenuTest {
    private val icon = ImageVector.Builder("x", 1.dp, 1.dp, 1f, 1f).build()
    private val icons = SelectionIcons(icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon)
    private val on = SelectionCallbacks(
        copy = {}, move = {}, send = {}, delete = {}, compress = {}, rename = {},
        openWith = {}, details = {}, bookmark = {}, nearby = {}, shortcut = {},
        fastShare = {}, copyPath = {}, invert = {}, reveal = {}, folderIcon = {}, toLocker = {},
    )

    /** The registry for one file, as a viewer asks for it. */
    private fun one(readOnly: String? = null) = selectionActions(
        count = 1,
        readOnly = readOnly,
        icons = icons,
        on = on,
        archive = true,
        otherPane = true,
        shape = SelectionShape.files(1),
        elsewhere = true,
    )

    @Test
    fun `a viewer drops what means nothing for one open file`() {
        val ids = forViewer(one(), unsaved = false).map { it.id }
        for (gone in NOT_IN_VIEWERS) assertFalse(gone, gone in ids)
        assertTrue("send" in ids && "details" in ids && "delete" in ids && "share" in ids)
        val share = forViewer(one(), unsaved = false).single { it.id == "share" }
        assertTrue("fastshare" in share.children.map { it.id })
    }

    @Test
    fun `open location heads the list`() {
        assertEquals("reveal", forViewer(one(), unsaved = false).first().id)
    }

    @Test
    fun `an unsaved edit blocks only the rows that would lose it`() {
        val rows = forViewer(one(), unsaved = true)
        for (r in rows) {
            if (r.id in LOSES_EDITS) assertEquals(r.id, SAVE_FIRST, r.blocked) else assertNull(r.id, r.blocked)
        }
        assertTrue(rows.any { it.id == "rename" })
        assertTrue(rows.any { it.id == "copy" && it.blocked == null })
    }

    @Test
    fun `a read-only folder's own reason is kept`() {
        val rows = forViewer(one(readOnly = "This location is read-only"), unsaved = false)
        assertEquals("This location is read-only", rows.first { it.id == "rename" }.blocked)
    }
}

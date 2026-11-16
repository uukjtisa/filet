package dev.niccc2007.filet.browser

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF password is offered from the file list for exactly one PDF, blocked where the file cannot
 * be saved over, and never in the Locker or on a surface with its own (the reader).
 */
class PdfPasswordActionTest {

    private val icon = ImageVector.Builder("x", 1.dp, 1.dp, 1f, 1f).build()
    private val icons = SelectionIcons(
        icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon,
    )
    private var ran = false
    private val on = SelectionCallbacks(
        copy = {}, move = {}, send = {}, delete = {}, compress = {}, rename = {},
        openWith = {}, details = {}, bookmark = {}, nearby = {}, shortcut = {},
        pdfPassword = { ran = true },
    )

    private fun ids(
        shape: SelectionShape,
        readOnly: String? = null,
        callbacks: SelectionCallbacks = on,
        locker: Boolean = false,
    ) = selectionActions(shape.count, readOnly, icons, callbacks, shape = shape, locker = locker).map { it.id }

    @Test fun one_pdf_gets_it() {
        assertTrue("pdfpassword" in ids(SelectionShape(count = 1, pdf = 1)))
    }

    @Test fun anything_else_does_not() {
        assertTrue("pdfpassword" !in ids(SelectionShape(count = 1)))
        assertTrue("pdfpassword" !in ids(SelectionShape(count = 2, pdf = 2)))
        assertTrue("pdfpassword" !in ids(SelectionShape(count = 2, pdf = 1)))
        assertTrue("pdfpassword" !in ids(SelectionShape(count = 1, folders = 1)))
    }

    @Test fun a_surface_with_its_own_draws_no_row() {
        assertTrue("pdfpassword" !in ids(SelectionShape(count = 1, pdf = 1), callbacks = on.copy(pdfPassword = null)))
    }

    @Test fun not_in_the_locker() {
        assertTrue("pdfpassword" !in ids(SelectionShape(count = 1, pdf = 1), locker = true))
    }

    @Test fun a_read_only_volume_blocks_it_since_the_file_is_saved_over() {
        val row = selectionActions(1, "Read-only", icons, on, shape = SelectionShape(count = 1, pdf = 1))
            .first { it.id == "pdfpassword" }
        assertEquals("Read-only", row.blocked)
        val open = selectionActions(1, null, icons, on, shape = SelectionShape(count = 1, pdf = 1))
            .first { it.id == "pdfpassword" }
        assertEquals(null, open.blocked)
        open.run()
        assertTrue(ran)
    }
}

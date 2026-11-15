package dev.niccc2007.filet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a file shared in from another app is called from then on. */
class IncomingNameTest {

    private val types = mapOf("image/jpeg" to "jpg", "video/mp4" to "mp4", "application/pdf" to "pdf")
    private fun name(display: String?, last: String?, mime: String?) = IncomingName.of(display, last, mime) { types[it] }

    @Test fun `a gallery picture keeps the name the gallery gives it, not its row number`() {
        assertEquals("IMG_20251115_101010.jpg", name("IMG_20251115_101010.jpg", "14536", "image/jpeg"))
    }

    @Test fun `with no name from the app, the row number gets the extension its type implies`() {
        assertEquals("14536.jpg", name(null, "14536", "image/jpeg"))
        assertEquals("14123.mp4", name("  ", "14123", "video/mp4"))
    }

    @Test fun `a name without an extension gets one, a name with one is left alone`() {
        assertEquals("Scan 3.pdf", name("Scan 3", "99", "application/pdf"))
        assertEquals("notes.TXT", name("notes.TXT", "1", "image/jpeg"))
    }

    @Test fun `an unknown or vague type leaves the name as it is`() {
        assertEquals("14536", name(null, "14536", "image/*"))
        assertEquals("14536", name(null, "14536", null))
        assertEquals("14536", name(null, "14536", "application/x-unheard-of"))
    }

    @Test fun `a name can never be a path`() {
        assertEquals("a_b.jpg", name("a/b.jpg", null, null))
        assertEquals("shared", name("..", null, null))
        assertEquals("shared.jpg", name(null, null, "image/jpeg"))
    }

    @Test fun `what counts as an extension`() {
        assertTrue(IncomingName.hasExtension("a.mp4"))
        assertFalse(IncomingName.hasExtension("14536"))
        assertFalse(IncomingName.hasExtension(".nomedia"))
        assertFalse(IncomingName.hasExtension("trailing."))
        assertFalse(IncomingName.hasExtension("v1.2 final copy"))
    }

    @Test fun `a second file of the same name is kept beside the first, not over it`() {
        val taken = setOf("image.jpg", "image (2).jpg", "14536")
        assertEquals("image (3).jpg", IncomingName.free("image.jpg") { it in taken })
        assertEquals("14536 (2)", IncomingName.free("14536") { it in taken })
        assertEquals("new.jpg", IncomingName.free("new.jpg") { it in taken })
    }
}

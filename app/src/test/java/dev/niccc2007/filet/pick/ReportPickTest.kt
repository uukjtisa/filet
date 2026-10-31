package dev.niccc2007.filet.pick

import dev.niccc2007.filet.report.FiletPictures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report form's pictures, asked of Filet's own picker through the door other apps use.
 *
 * What is pinned: the request is the ordinary external one (images, several, openable), and the
 * sentence names the operation the way Filet's own picks do rather than naming Filet as an
 * outside app.
 */
class ReportPickTest {

    private fun request() = PickRequest.of(
        type = "image/*",
        extraMimeTypes = null,
        allowMultiple = true,
        localOnly = true,
        openable = true,
        verb = FiletPictures.VERB,
        subject = FiletPictures.SUBJECT,
    )

    @Test
    fun `the card says what the pick is for`() {
        val r = request()
        val s = PickSentence.of(PickSpec.Want.FILES, r.verb, r.subject, asker = null, whatQualifies = r.what)
        assertEquals("Choose files to attach to your report", s.headline)
        assertEquals("Use these", s.confirm)
    }

    @Test
    fun `it is the same request another app would send, so the same rows qualify`() {
        val r = request()
        assertTrue(r.allowMultiple)
        assertTrue(r.openable)
        assertEquals(listOf("image/*"), r.mimeTypes)
        assertTrue(MimeMatch.accepts(r, "image/jpeg"))
        assertTrue(!MimeMatch.accepts(r, "application/pdf"))
    }

    @Test
    fun `an outside app's request carries no operation words`() {
        val r = PickRequest.of("image/*", null, allowMultiple = true, localOnly = false, openable = true)
        assertEquals("", r.verb)
        assertEquals("", r.subject)
    }

    @Test
    fun `a multi-pick with more than the form takes is still handed over whole`() {
        // The form, not the picker, enforces the picture limit, with the server's own numbers
        // and a sentence - see ReportForm.imageVerdict. The picker must not silently drop any.
        assertEquals(null, PickRules.refusal(request(), count = 5, anyDirectories = false))
    }
}

package dev.niccc2007.filet.pick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the caller asked for, and what matches it.
 *
 * These are the decisions that make Filet usable as a picker or useless as one, and none of
 * them fails loudly: get the mime rule wrong and the browser shows an empty folder, which reads
 * as "Filet cannot see my files" rather than as a filter doing its job.
 *
 * The fields are absent far more often than present - most callers send `ACTION_GET_CONTENT`,
 * a type, and nothing else - so the DEFAULTS get as many tests as the flags do.
 */
class PickRequestTest {

    private fun req(
        type: String? = "*/*",
        extras: Array<String>? = null,
        multiple: Boolean = false,
        localOnly: Boolean = false,
        openable: Boolean = true,
    ) = PickRequest.of(type, extras, multiple, localOnly, openable)

    // ── parsing ──

    @Test fun no_type_at_all_means_anything_rather_than_nothing() {
        // The failure this prevents is total: a picker that accepts nothing shows an empty
        // device, and the person blames the file manager.
        val r = PickRequest.of(null, null, false, false, true)
        assertTrue(r.acceptsAnything)
        assertTrue(MimeMatch.accepts(r, "image/png"))
        assertTrue(MimeMatch.accepts(r, null))
    }

    @Test fun extra_mime_types_override_the_type_field() {
        // The platform's own rule, and getting it backwards is quiet: callers that send several
        // types also send a placeholder `type`, so the wrong precedence narrows every one of
        // them to that placeholder and nothing else.
        val r = req(type = "*/*", extras = arrayOf("image/png", "image/jpeg"))
        assertEquals(listOf("image/png", "image/jpeg"), r.mimeTypes)
        assertFalse(r.acceptsAnything)
        assertTrue(MimeMatch.accepts(r, "image/png"))
        assertFalse(MimeMatch.accepts(r, "image/gif"))
    }

    @Test fun an_empty_extras_array_falls_back_to_the_type() {
        val r = req(type = "application/pdf", extras = emptyArray())
        assertEquals(listOf("application/pdf"), r.mimeTypes)
    }

    @Test fun blank_entries_in_extras_are_dropped_rather_than_matched() {
        val r = req(type = "*/*", extras = arrayOf(" ", "image/png", ""))
        assertEquals(listOf("image/png"), r.mimeTypes)
    }

    @Test fun types_are_lowercased_and_deduplicated() {
        val r = req(extras = arrayOf("Image/PNG", "image/png", "IMAGE/JPEG"))
        assertEquals(listOf("image/png", "image/jpeg"), r.mimeTypes)
    }

    @Test fun the_flags_default_to_the_conservative_answer() {
        val r = PickRequest.of("*/*", null, false, false, false)
        assertFalse("one file unless the caller said otherwise", r.allowMultiple)
        assertFalse(r.localOnly)
        assertFalse(r.openable)
    }

    // ── matching ──

    @Test fun a_family_wildcard_matches_its_family_and_nothing_else() {
        val r = req(type = "image/*")
        assertTrue(MimeMatch.accepts(r, "image/png"))
        assertTrue(MimeMatch.accepts(r, "image/svg+xml"))
        assertFalse(MimeMatch.accepts(r, "video/mp4"))
        // Not a prefix match on the string: "imagex/png" is a different family.
        assertFalse(MimeMatch.matches("image/*", "imagex/png"))
    }

    @Test fun an_exact_request_matches_only_that_type() {
        val r = req(type = "application/pdf")
        assertTrue(MimeMatch.accepts(r, "application/pdf"))
        assertFalse(MimeMatch.accepts(r, "application/zip"))
        assertFalse(MimeMatch.accepts(r, "application/pdf+x"))
    }

    @Test fun a_file_the_system_cannot_name_passes_a_wildcard_and_fails_an_exact_request() {
        // The case that decides whether this is usable. A file manager is full of files Android
        // has no mime type for - .7z, .ino, a download with no extension - and refusing them
        // under `*/*` would show an empty device to a caller that asked for anything.
        assertTrue(MimeMatch.accepts(req(type = "*/*"), null))
        assertTrue(MimeMatch.accepts(req(type = "application/*"), null))
        assertTrue(MimeMatch.accepts(req(type = "*/*"), ""))
        // But an exact request has said precisely what it can open, so a guess is not helpful.
        assertFalse(MimeMatch.accepts(req(type = "application/pdf"), null))
    }

    @Test fun matching_ignores_case_on_the_file_side_too() {
        assertTrue(MimeMatch.accepts(req(type = "image/*"), "IMAGE/PNG".lowercase()))
    }

    @Test fun several_requested_types_accept_any_of_them() {
        val r = req(extras = arrayOf("image/*", "application/pdf"))
        assertTrue(MimeMatch.accepts(r, "image/webp"))
        assertTrue(MimeMatch.accepts(r, "application/pdf"))
        assertFalse(MimeMatch.accepts(r, "audio/mpeg"))
    }

    @Test fun a_star_anywhere_in_the_list_opens_the_whole_thing() {
        val r = req(extras = arrayOf("application/pdf", "*/*"))
        assertTrue(r.acceptsAnything)
        assertTrue(MimeMatch.accepts(r, "audio/mpeg"))
    }

    // ── what the bar says ──

    @Test fun the_bar_names_what_the_caller_wants() {
        assertEquals("any file", req(type = "*/*").what)
        assertEquals("an image", req(type = "image/*").what)
        assertEquals("a video", req(type = "video/*").what)
        assertEquals("a PDF", req(type = "application/pdf").what)
        assertEquals("an APK", req(type = "application/vnd.android.package-archive").what)
    }

    @Test fun a_mixed_request_says_how_many_types_rather_than_listing_them() {
        val r = req(extras = arrayOf("image/*", "application/pdf", "text/plain"))
        assertTrue("got: ${r.what}", r.what.contains("3"))
    }

    @Test fun the_bar_never_comes_out_empty() {
        // It is the only thing telling somebody why the app opened like this, so a blank is a
        // worse failure than a clumsy phrase.
        for (t in listOf(null, "", "*/*", "image/*", "application/pdf", "weird/type")) {
            assertTrue("empty for $t", req(type = t).what.isNotBlank())
        }
    }
}

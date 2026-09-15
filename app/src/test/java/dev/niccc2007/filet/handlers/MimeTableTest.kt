package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extension-to-type table, which decides which apps a chooser offers.
 *
 * Its absence was invisible until review opened the picker for a `.pptx`: the type came back as
 * the wildcard, the package-manager query with a wildcard matches every app that declares one,
 * and the "apps that handle this type" list for a PowerPoint deck was Certificate Installer,
 * HTML Viewer and Manage SIM contacts. Nothing crashed and nothing looked broken.
 */
class MimeTableTest {

    @Test fun the_office_formats_are_named_not_guessed() {
        // These are the ones somebody actually sets by hand, and every one of them used to
        // fall through to the wildcard.
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            knownMime("pptx"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            knownMime("docx"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            knownMime("xlsx"),
        )
        assertEquals("application/msword", knownMime("doc"))
        assertEquals("application/vnd.ms-excel", knownMime("xls"))
        assertEquals("application/vnd.ms-powerpoint", knownMime("ppt"))
        assertEquals("text/csv", knownMime("csv"))
        assertEquals("application/epub+zip", knownMime("epub"))
    }

    @Test fun everything_the_settings_presets_offer_has_a_type() {
        // A preset row that resolves to the wildcard is a row whose picker shows junk. If a
        // preset gains an extension, this fails until the table gains it too.
        val offered = OPENER_PRESETS.flatMap { it.extensions }
        val missing = offered.filter { knownMime(it) == null }
        assertTrue("no type for: $missing", missing.isEmpty())
    }

    @Test fun a_leading_dot_and_any_case_are_accepted() {
        assertEquals(knownMime("pptx"), knownMime(".PPTX"))
        assertEquals(knownMime("png"), knownMime(".Png"))
    }

    @Test fun an_unknown_extension_says_so_rather_than_guessing() {
        // Null, not the wildcard: the caller then gets to try Android's own table before
        // giving up, and only the last step in that chain is allowed to shrug.
        assertNull(knownMime("blend"))
        assertNull(knownMime("qqq"))
        assertNull(knownMime(""))
    }

    @Test fun the_wildcard_is_the_one_value_that_means_nothing_is_known() {
        assertEquals("*/*", ANY_TYPE)
    }

    @Test fun the_media_types_still_resolve_the_way_the_viewers_expect() {
        assertEquals("image/png", knownMime("png"))
        assertEquals("video/mp4", knownMime("mp4"))
        assertEquals("video/x-matroska", knownMime("mkv"))
        assertEquals("audio/mpeg", knownMime("mp3"))
        assertEquals("audio/flac", knownMime("flac"))
        assertEquals("application/pdf", knownMime("pdf"))
    }

    @Test fun every_type_in_the_table_is_shaped_like_a_type() {
        // A typo such as "applicaton/pdf" resolves to nothing and fails silently forever.
        val everything = SAMPLE_EXTENSIONS.mapNotNull { knownMime(it) }
        assertTrue("the table should be well populated, got ${everything.size}", everything.size > 40)
        for (t in everything) {
            assertTrue("not a media type: $t", t.matches(Regex("^[a-z]+/[a-z0-9.+\\-]+$")))
        }
    }

    @Test fun the_source_extensions_read_as_text_so_a_text_editor_is_offered() {
        for (e in listOf("kt", "java", "py", "sh", "yml", "gradle", "sql")) {
            assertEquals("text/plain for .$e", "text/plain", knownMime(e))
        }
    }

    @Test fun a_code_file_type_is_not_left_to_chance() {
        assertNotNull(knownMime("json"))
        assertNotNull(knownMime("xml"))
        assertNotNull(knownMime("html"))
    }

    private companion object {
        /** Everything the table is expected to know, used to count and shape-check it. */
        val SAMPLE_EXTENSIONS = listOf(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "heic", "avif", "ico",
            "mp4", "mkv", "webm", "3gp", "avi", "mov", "wmv", "ts", "flv", "m4v",
            "mp3", "m4a", "ogg", "opus", "flac", "wav", "aac", "wma", "amr", "mid",
            "pdf", "zip", "apk", "7z", "rar", "tar", "gz", "torrent",
            "txt", "log", "md", "html", "json", "xml", "css", "js", "srt", "vtt",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf",
            "csv", "epub", "kt", "java", "py",
        )
    }
}

package dev.niccc2007.filet.vfs.types

import dev.niccc2007.filet.vfs.provider.Archives
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one table of file types.
 *
 * The defect it replaced was a set of lists that disagreed, so the tests that matter most are
 * the ones that would have caught that: one row per extension, the JFIF case answered the
 * same way by every question, and nothing the old lists promised quietly dropped.
 */
class FileTypesTest {

    private val modern = 34

    @Test fun every_extension_is_declared_once_and_in_the_form_it_is_looked_up() {
        val seen = HashSet<String>()
        for (t in FileTypes.ALL) {
            assertTrue("declared twice: ${t.ext}", seen.add(t.ext))
            assertEquals("not normalised: ${t.ext}", FileTypes.norm(t.ext), t.ext)
            assertFalse("a dotted suffix is never a name's extension: ${t.ext}", '.' in t.ext)
        }
    }

    @Test fun every_type_named_is_shaped_like_a_type() {
        for (t in FileTypes.ALL) {
            val m = t.mime ?: continue
            assertTrue("not a media type for .${t.ext}: $m", m.matches(Regex("^[a-z]+/[a-z0-9.+\\-]+$")))
        }
    }

    @Test fun a_jfif_is_a_jpeg_to_every_question() {
        // The bug: a JPEG to the rotation code, nothing at all to the file kind and thumbnails.
        for (e in listOf("jfif", "jpe", "jif", "JFIF", ".jfif")) {
            assertEquals(".$e kind", TypeKind.IMAGE, FileTypes.kindOf(e))
            assertTrue(".$e jpeg", FileTypes.isJpeg(e))
            assertEquals(".$e mime", "image/jpeg", FileTypes.mime(e))
            assertEquals(".$e thumbnail", TypeKind.IMAGE, FileTypes.thumbnailKind(e, 26))
            assertTrue(".$e opens in Filet's viewer", FileTypes.claimsHere(e, 26))
            assertTrue(".$e reaches the gallery", FileTypes.galleryLists(e))
        }
        assertEquals(setOf("jpg", "jpeg", "jpe", "jfif", "jif"), FileTypes.spellings("jfif"))
    }

    @Test fun wma_reads_as_music_and_is_never_claimed() {
        assertEquals(TypeKind.AUDIO, FileTypes.kindOf("wma"))
        assertFalse(FileTypes.claimsHere("wma", modern))
        assertNull("no decoder means no cover art either", FileTypes.thumbnailKind("wma", modern))
        assertFalse(FileTypes.galleryLists("wma"))
        val note = FileTypes.notPlayableHere("wma", modern)
        assertNotNull(note)
        assertTrue(note!!, "WMA" in note)
    }

    @Test fun nothing_is_said_about_a_type_that_plays() {
        assertNull(FileTypes.notPlayableHere("mp3", modern))
        assertNull(FileTypes.notPlayableHere("jpg", modern))
        assertNull("video is always tried", FileTypes.notPlayableHere("wmv", modern))
        assertNull("not a media type at all", FileTypes.notPlayableHere("pdf", modern))
        assertNull("unknown", FileTypes.notPlayableHere("qqq", modern))
    }

    @Test fun a_format_is_claimed_from_the_api_level_that_decodes_it() {
        assertFalse(FileTypes.claimsHere("heic", 27))
        assertTrue(FileTypes.claimsHere("heic", 28))
        assertNull(FileTypes.thumbnailKind("heic", 27))
        assertEquals(TypeKind.IMAGE, FileTypes.thumbnailKind("heic", 28))
        assertFalse(FileTypes.claimsHere("avif", 30))
        assertTrue(FileTypes.claimsHere("avif", 31))
        assertNotNull(FileTypes.notPlayableHere("heic", 27))
        assertFalse("never decodable here", FileTypes.claimsHere("tiff", modern))
    }

    @Test fun video_is_always_claimed_and_only_decodable_video_gets_a_frame() {
        for (e in listOf("mp4", "mkv", "flv", "wmv", "mpg")) assertTrue(e, FileTypes.claimsHere(e, 26))
        assertNull(FileTypes.thumbnailKind("flv", modern))
        assertNull(FileTypes.thumbnailKind("wmv", modern))
        assertEquals(TypeKind.VIDEO, FileTypes.thumbnailKind("mpeg", modern))
    }

    @Test fun a_vector_picture_is_viewed_but_never_thumbnailed() {
        assertTrue(FileTypes.claimsHere("svg", modern))
        assertNull(FileTypes.thumbnailKind("svg", modern))
        assertFalse(FileTypes.galleryLists("svg"))
    }

    @Test fun the_formats_every_phone_decodes_decode_under_the_unit_test_stub() {
        // `Build.VERSION.SDK_INT` is 0 in a JVM test; see ANY_API.
        assertTrue(FileTypes.claimsHere("png", 0))
        assertTrue(FileTypes.claimsHere("mp3", 0))
        assertFalse(FileTypes.claimsHere("heic", 0))
    }

    @Test fun nothing_the_old_thumbnail_lists_previewed_is_dropped() {
        for (e in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")) {
            assertEquals(e, TypeKind.IMAGE, FileTypes.thumbnailKind(e, modern))
        }
        for (e in listOf("mp4", "mkv", "webm", "3gp", "mov", "m4v", "avi", "ts")) {
            assertEquals(e, TypeKind.VIDEO, FileTypes.thumbnailKind(e, modern))
        }
        for (e in listOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "mp4a", "m4b")) {
            assertEquals(e, TypeKind.AUDIO, FileTypes.thumbnailKind(e, modern))
        }
        assertNull("a bare WAV has no tag container", FileTypes.thumbnailKind("wav", modern))
    }

    @Test fun nothing_the_old_gallery_sweep_announced_is_dropped() {
        val old = listOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "dng", "tiff",
            "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "mpg", "mpeg",
            "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "amr", "mid",
        )
        for (e in old) assertTrue(e, FileTypes.galleryLists(e))
        for (e in listOf("pdf", "txt", "zip", "apk", "ico")) assertFalse(e, FileTypes.galleryLists(e))
    }

    @Test fun the_archive_rows_come_from_the_archive_table() {
        for (format in Archives.ALL) for (ext in format.extensions) {
            if ('.' in ext) continue
            val kind = FileTypes.kindOf(ext)
            assertTrue(".$ext is $kind", kind == TypeKind.ARCHIVE || kind == TypeKind.APK)
        }
        for (e in listOf("apk", "xapk", "apkm", "apks")) assertEquals(e, TypeKind.APK, FileTypes.kindOf(e))
        assertEquals(TypeKind.ARCHIVE, FileTypes.kindOf("epub"))
        assertEquals("application/epub+zip", FileTypes.mime("epub"))
        assertEquals("the last dot names it", "gz", FileTypes.extensionOf("backup.tar.gz"))
    }

    @Test fun every_row_of_the_archive_kind_is_one_the_archive_table_reads() {
        // The other direction: an ARCHIVE row the readers know nothing about would draw a zip
        // icon on a file that opens onto nothing.
        val known = Archives.allExtensions
        for (e in FileTypes.extensions(TypeKind.ARCHIVE)) assertTrue(e, e in known)
    }

    @Test fun already_compressed_means_a_zip_stores_it() {
        for (e in listOf("jpg", "jfif", "png", "mp4", "mkv", "mp3", "flac", "zip", "7z", "apk", "docx", "pdf", "gz", "xz")) {
            assertTrue(e, FileTypes.isCompressed(e))
        }
        for (e in listOf("txt", "wav", "bmp", "tar", "kt", "csv", "mid")) assertFalse(e, FileTypes.isCompressed(e))
    }

    @Test fun the_editor_writes_lossless_sources_back_as_png() {
        // An SVG is drawn to pixels to be edited; PNG keeps the transparency most of them have.
        for (e in listOf("png", "webp", "gif", "bmp", "svg")) assertTrue(e, FileTypes.isLossless(e))
        for (e in listOf("jpg", "heic", "avif")) assertFalse(e, FileTypes.isLossless(e))
    }

    @Test fun only_the_formats_that_can_hold_frames_animate() {
        assertTrue(FileTypes.isAnimated("gif"))
        assertTrue(FileTypes.isAnimated("webp"))
        assertFalse(FileTypes.isAnimated("png"))
        assertFalse(FileTypes.isAnimated("jpg"))
    }

    @Test fun a_type_with_one_spelling_is_its_own_family() {
        assertEquals(setOf("png"), FileTypes.spellings("png"))
        assertEquals(setOf("heic", "heif"), FileTypes.spellings("HEIF"))
        assertEquals(setOf("tif", "tiff"), FileTypes.spellings("tif"))
        assertEquals(setOf("qqq"), FileTypes.spellings("qqq"))
    }

    @Test fun an_unknown_extension_is_unknown_to_every_question() {
        for (e in listOf("qqq", "blend", "")) {
            assertNull(FileTypes.of(e))
            assertNull(FileTypes.mime(e))
            assertFalse(FileTypes.claimsHere(e, modern))
            assertNull(FileTypes.thumbnailKind(e, modern))
        }
    }
}

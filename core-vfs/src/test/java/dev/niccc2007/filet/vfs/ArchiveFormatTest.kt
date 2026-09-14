package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.ArchiveKind
import dev.niccc2007.filet.vfs.provider.Archives
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one table that decides what Filet does with an archive.
 *
 * It replaced four disagreeing answers in four files, so the tests here are mostly about the
 * table staying internally consistent - two formats claiming one extension, or a format that
 * says it cannot be read and then does not say why, are the failures that would put the old
 * disagreement back.
 */
class ArchiveFormatTest {

    @Test fun the_longest_suffix_wins() {
        // The whole reason this is not `substringAfterLast('.')`. Reading backup.tar.gz as a
        // gzip shows one member called "backup.tar" instead of the tree inside it.
        assertEquals("tar.gz", Archives.of("backup.tar.gz")?.id)
        assertEquals("gz", Archives.of("notes.txt.gz")?.id)
        assertEquals("tar.xz", Archives.of("src.tar.xz")?.id)
        assertEquals("xz", Archives.of("dump.sql.xz")?.id)
        assertEquals("tar.bz2", Archives.of("old.tar.bz2")?.id)
    }

    @Test fun the_short_aliases_land_on_the_same_format() {
        assertEquals(Archives.of("a.tar.gz")?.id, Archives.of("a.tgz")?.id)
        assertEquals(Archives.of("a.tar.bz2")?.id, Archives.of("a.tbz2")?.id)
        assertEquals(Archives.of("a.tar.xz")?.id, Archives.of("a.txz")?.id)
    }

    @Test fun case_does_not_matter() {
        assertEquals("zip", Archives.of("PHOTOS.ZIP")?.id)
        assertEquals("tar.gz", Archives.of("Backup.TAR.GZ")?.id)
    }

    @Test fun an_apk_is_a_zip_and_that_is_why_walking_into_one_works() {
        for (name in listOf("app.apk", "lib.jar", "x.aar", "book.epub", "split.xapk", "c.cbz")) {
            assertEquals("$name should be a zip", ArchiveKind.ZIP, Archives.of(name)?.kind)
        }
    }

    @Test fun something_that_is_not_an_archive_is_not_one() {
        assertNull(Archives.of("photo.jpg"))
        assertNull(Archives.of("Makefile"))
        assertNull(Archives.of(""))
        // The suffix has to be preceded by a dot. A file actually called "zip" is not one.
        assertNull(Archives.of("zip"))
        assertNull(Archives.of("gunzip"))
    }

    @Test fun rar_is_recognised_refused_and_says_why() {
        val rar = Archives.of("archive.rar")
        assertNotNull(rar)
        assertFalse(rar!!.canList)
        assertFalse(rar.canCreate)
        assertTrue("the refusal must name the reason", (rar.refusal ?: "").length > 40)
        assertTrue("the reason is the licence", rar.refusal!!.contains("UnRAR"))
        assertFalse(Archives.canList("archive.rar"))
    }

    @Test fun a_format_that_cannot_be_listed_always_says_why_and_one_that_can_never_does() {
        for (f in Archives.ALL) {
            if (f.canList) {
                assertNull("${f.id} can be listed but carries a refusal", f.refusal)
            } else {
                assertNotNull("${f.id} cannot be listed and says nothing", f.refusal)
            }
        }
    }

    @Test fun nothing_claims_to_be_creatable_without_being_readable() {
        for (f in Archives.ALL) {
            if (f.canCreate) assertTrue("${f.id} writes but cannot read", f.canList)
        }
    }

    @Test fun no_two_formats_claim_the_same_extension() {
        // Two claims means `of` picks by declaration order, which is a coin toss dressed up
        // as a lookup.
        val seen = HashSet<String>()
        for (f in Archives.ALL) {
            for (e in f.extensions) {
                assertTrue("$e is claimed twice", seen.add(e))
            }
        }
    }

    @Test fun every_extension_resolves_back_to_its_own_format() {
        for (f in Archives.ALL) {
            for (e in f.extensions) {
                assertEquals("$e", f.id, Archives.of("sample.$e")?.id)
            }
        }
        assertEquals(Archives.allExtensions, Archives.ALL.flatMap { it.extensions }.toSet())
    }

    @Test fun the_creatable_list_is_the_one_the_picker_offers() {
        val ids = Archives.creatable.map { it.id }
        assertEquals(listOf("zip", "tar", "tar.gz", "tar.bz2", "tar.xz", "7z"), ids)
        assertEquals(".zip", Archives.creatable.first().suffix)
        assertEquals(".tar.gz", Archives.creatable[2].suffix)
    }

    @Test fun the_base_name_strips_the_whole_suffix_not_half_of_it() {
        // "photos.tar" as a folder name reads as a step in unwrapping rather than the thing
        // that was in the archive.
        assertEquals("photos", Archives.baseName("photos.tar.gz"))
        assertEquals("photos", Archives.baseName("photos.zip"))
        assertEquals("notes.txt", Archives.baseName("notes.txt.gz"))
        assertEquals("app", Archives.baseName("app.apk"))
    }

    @Test fun the_base_name_of_something_that_is_not_an_archive_still_answers() {
        assertEquals("photo", Archives.baseName("photo.jpg"))
        assertEquals("Makefile", Archives.baseName("Makefile"))
    }

    @Test fun a_format_that_needs_a_real_path_says_so() {
        // The readers for these seek; a stream is not enough, which is what rules out a 7z
        // nested inside another archive.
        assertTrue(Archives.of("a.7z")!!.needsRealPath)
        assertTrue(Archives.of("a.zip")!!.needsRealPath)
        assertFalse("a tar streams, so it does not", Archives.of("a.tar.gz")!!.needsRealPath)
    }

    @Test fun a_single_stream_format_holds_exactly_one_member() {
        for (name in listOf("a.gz", "a.bz2", "a.xz")) {
            assertEquals(ArchiveKind.STREAM, Archives.of(name)!!.kind)
            assertFalse("$name cannot be created - there is nothing to put in it but one file",
                Archives.of(name)!!.canCreate)
        }
    }
}

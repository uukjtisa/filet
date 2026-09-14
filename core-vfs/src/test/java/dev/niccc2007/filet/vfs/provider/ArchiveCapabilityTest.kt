package dev.niccc2007.filet.vfs.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table the creation window is built from.
 *
 * The failures worth catching here are all the same shape: a control offered for a format that
 * cannot honour it. That is invisible in review, because the dialog looks right - it is the
 * writer, several hundred milliseconds later, that disagrees.
 *
 * So most of these assertions are about the *relationship* between two fields rather than
 * about any one number, because it is the relationships that drift.
 */
class ArchiveCapabilityTest {

    private fun format(id: String) = Archives.ALL.first { it.id == id }
    private fun cap(id: String) = ArchiveCapabilities.of(format(id))

    // ── every creatable format has an entry, and nothing else decides these ──

    @Test fun every_creatable_format_has_a_capability_entry() {
        // `of` throws for a creatable format it does not know, so adding a format to Archives
        // and forgetting this table fails here rather than at the moment somebody picks it.
        for (f in Archives.creatable) {
            assertEquals(f.id, ArchiveCapabilities.of(f).formatId)
        }
    }

    @Test fun the_creatable_list_is_the_same_list_in_the_same_order() {
        assertEquals(
            Archives.creatable.map { it.id },
            ArchiveCapabilities.creatable().map { it.formatId },
        )
    }

    @Test fun rar_has_no_creation_capability_at_all() {
        // It is readable, so it must not simply be absent - but nothing about creating it may
        // be offered, and the refusal must say Filet's part rather than blaming the format.
        val rar = ArchiveCapabilities.of(format("rar"))
        assertNull(rar.strength)
        assertFalse(rar.password.supported)
        assertFalse(rar.canSplit)
        assertEquals(EditMode.COPY_ONLY, rar.edit)
        assertTrue(rar.password.refusal!!.contains("cannot create"))
    }

    // ── strength: the scales are genuinely different and none is a dead control ──

    @Test fun a_format_that_does_not_compress_has_no_strength_control() {
        // Rule R1. A .tar is a container; a slider on it would move and change nothing.
        assertNull(cap("tar").strength)
        assertFalse(cap("tar").canCompress)
    }

    @Test fun every_compressing_format_has_a_strength_control() {
        for (id in listOf("zip", "tar.gz", "tar.bz2", "tar.xz", "7z")) {
            assertNotNull("$id compresses and must offer a strength", cap(id).strength)
        }
    }

    @Test fun the_scales_are_not_all_the_same_scale() {
        // The assertion that stops one 0-9 slider being reused for everything. If these ever
        // collapse into one range with one default, this fails and it should.
        val scales = listOf("zip", "tar.gz", "tar.bz2", "tar.xz", "7z").map { cap(it).strength!! }
        val shapes = scales.map { Triple(it.min, it.max, it.default) }.toSet()
        assertTrue("every format ended up on an identical scale", shapes.size > 1)
    }

    @Test fun gzip_starts_at_one_because_level_zero_is_what_plain_tar_is_for() {
        assertEquals(1, cap("tar.gz").strength!!.min)
        assertEquals(9, cap("tar.gz").strength!!.max)
    }

    @Test fun bzip2_says_its_number_is_a_block_size_not_an_effort() {
        // It is a memory figure. The UI calls the control strength; the unit keeps that honest.
        val s = cap("tar.bz2").strength!!
        assertTrue(s.unit.contains("block size"))
        assertEquals(9, s.default)
    }

    @Test fun zip_can_be_set_to_store_without_compressing() {
        assertEquals(0, cap("zip").strength!!.min)
        assertEquals("Store", cap("zip").strength!!.minLabel)
    }

    @Test fun every_scale_has_room_in_it_and_a_default_inside_it() {
        for (c in ArchiveCapabilities.creatable()) {
            val s = c.strength ?: continue
            assertTrue("${c.formatId}: ${s.min}..${s.max}", s.min < s.max)
            assertTrue("${c.formatId} default ${s.default}", s.default in s.min..s.max)
            assertTrue("${c.formatId} labels", s.minLabel.isNotBlank() && s.maxLabel.isNotBlank())
            assertTrue("${c.formatId} unit", s.unit.isNotBlank())
        }
    }

    @Test fun a_stored_setting_outside_the_range_is_clamped_rather_than_thrown() {
        // A saved preference can outlive a change to the range, and a crash on opening the
        // compress dialog is a worse outcome than a value quietly moving to the edge.
        val s = cap("zip").strength!!
        assertEquals(s.min, s.coerce(-5))
        assertEquals(s.max, s.coerce(99))
        assertEquals(4, s.coerce(4))
    }

    // ── passwords: two different no's, and they must not be merged ──

    @Test fun zip_takes_a_password_strongest_first() {
        val p = cap("zip").password
        assertTrue(p.supported)
        assertEquals(EncryptionMethod.AES_256, p.default)
        assertNull(p.refusal)
    }

    @Test fun the_weak_cipher_is_offered_last_and_labelled_weak() {
        val methods = cap("zip").password.methods
        assertEquals(EncryptionMethod.ZIP_CRYPTO, methods.last())
        assertTrue(methods.last().weak)
        assertFalse("the default must never be the broken one", methods.first().weak)
    }

    @Test fun a_zip_never_claims_to_hide_the_file_names() {
        // A zip's central directory is plain text whatever the contents are encrypted with.
        // Claiming otherwise is the worst available bug in this file.
        assertFalse(cap("zip").password.canEncryptNames)
    }

    @Test fun the_tar_family_says_the_format_has_no_encryption() {
        for (id in listOf("tar", "tar.gz", "tar.bz2", "tar.xz")) {
            val p = cap(id).password
            assertFalse(p.supported)
            assertTrue("$id: ${p.refusal}", p.refusal!!.contains("no encryption in it"))
        }
    }

    @Test fun seven_zip_blames_the_missing_writer_and_not_the_format() {
        // Gate C4, and gate E13 after the native writer was abandoned. "Not supported" would be
        // false - 7z supports AES-256 perfectly well, and somebody told that would go looking
        // for a setting. What is missing is a writer, and the sentence has to say so and then
        // say what to do instead.
        val p = cap("7z").password
        assertFalse(p.supported)
        assertTrue("it must name AES-256 as real: ${p.refusal}", p.refusal!!.contains("AES-256"))
        assertFalse("it must not read as a limitation of 7z", p.refusal.contains("no encryption in it"))
        assertTrue("it must say what to do instead: ${p.refusal}", p.refusal.contains("zip"))
    }

    @Test fun no_format_claims_a_password_it_cannot_write() {
        // The blast radius of the abandonment. Exactly one format encrypts, and it is the one
        // with a tested writer behind it.
        val encrypting = Archives.ALL.filter { ArchiveCapabilities.of(it).password.supported }.map { it.id }
        assertEquals(listOf("zip"), encrypting)
    }

    @Test fun a_refusal_is_present_exactly_when_a_password_is_not() {
        for (f in Archives.ALL) {
            val p = ArchiveCapabilities.of(f).password
            assertEquals(
                "${f.id}: supported=${p.supported} refusal=${p.refusal}",
                p.supported,
                p.refusal == null,
            )
            if (!p.supported) assertTrue("${f.id} offers methods it refuses", p.methods.isEmpty())
        }
    }

    // ── splitting ──

    @Test fun zip_uses_its_own_volume_layout_and_the_rest_split_the_byte_stream() {
        assertEquals(SplitStyle.ZIP_VOLUMES, cap("zip").split)
        for (id in listOf("tar", "tar.gz", "tar.bz2", "tar.xz", "7z")) {
            assertEquals(id, SplitStyle.NUMBERED_STREAM, cap(id).split)
        }
    }

    @Test fun a_split_refusal_is_present_exactly_when_splitting_is_not_possible() {
        for (f in Archives.ALL) {
            val c = ArchiveCapabilities.of(f)
            assertEquals("${f.id}", c.canSplit, c.splitRefusal == null)
        }
    }

    @Test fun a_part_smaller_than_the_format_allows_is_refused_before_writing() {
        val zip = cap("zip")
        val why = ArchiveCapabilities.splitProblem(zip, partBytes = 1024, totalBytes = 10_000_000)
        assertNotNull(why)
        assertTrue(why!!.contains("at least"))
    }

    @Test fun a_part_size_that_needs_more_parts_than_the_naming_allows_is_refused() {
        // The one that costs an evening: it only fails at part 100 of a 4 GB write.
        val zip = cap("zip")
        val why = ArchiveCapabilities.splitProblem(zip, partBytes = 64 * 1024, totalBytes = 4L * 1024 * 1024 * 1024)
        assertNotNull(why)
        assertTrue(why!!.contains("parts"))
    }

    @Test fun a_stream_split_allows_more_parts_than_zip_does() {
        // .001-.999 against .z01-.z99, so the same part size can be fine for one and not the
        // other. Asserted because the limits live in one `when` and are easy to transpose.
        val total = 200L * 1024 * 1024
        val part = 3L * 1024 * 1024 // ~67 parts: fine for both
        assertNull(ArchiveCapabilities.splitProblem(cap("zip"), part, total))
        assertNull(ArchiveCapabilities.splitProblem(cap("tar.gz"), part, total))

        val small = 1L * 1024 * 1024 // 200 parts: too many for zip, fine for a stream
        assertNotNull(ArchiveCapabilities.splitProblem(cap("zip"), small, total))
        assertNull(ArchiveCapabilities.splitProblem(cap("tar.gz"), small, total))
    }

    @Test fun a_part_bigger_than_the_whole_archive_is_allowed() {
        // It just means one part. Refusing would make the field fail whenever somebody picks
        // 100 MB for a 40 MB archive, which is most of the time.
        assertNull(ArchiveCapabilities.splitProblem(cap("zip"), 100L * 1024 * 1024, 40L * 1024 * 1024))
    }

    @Test fun splitting_an_unsplittable_format_gives_back_its_own_reason() {
        val rar = ArchiveCapabilities.of(format("rar"))
        assertEquals(rar.splitRefusal, ArchiveCapabilities.splitProblem(rar, 1024 * 1024, 10_000_000))
    }

    // ── editing a member ──

    @Test fun a_zip_member_can_be_replaced_without_touching_the_others() {
        assertEquals(EditMode.PATCH, ArchiveCapabilities.editModeFor("backup.zip"))
    }

    @Test fun an_uncompressed_tar_is_also_cheap_to_patch() {
        assertEquals(EditMode.PATCH, ArchiveCapabilities.editModeFor("backup.tar"))
    }

    @Test fun one_compression_stream_means_a_rebuild_and_the_table_says_so() {
        for (name in listOf("backup.tar.gz", "backup.tar.bz2", "backup.tar.xz", "backup.7z")) {
            assertEquals(name, EditMode.REBUILD, ArchiveCapabilities.editModeFor(name))
        }
    }

    @Test fun a_single_compressed_file_is_a_rebuild_because_there_is_nothing_to_copy() {
        assertEquals(EditMode.REBUILD, ArchiveCapabilities.editModeFor("notes.txt.gz"))
        assertEquals(EditMode.REBUILD, ArchiveCapabilities.editModeFor("notes.txt.xz"))
    }

    @Test fun a_rar_member_can_only_be_saved_elsewhere() {
        assertEquals(EditMode.COPY_ONLY, ArchiveCapabilities.editModeFor("photos.rar"))
    }

    @Test fun something_that_is_not_an_archive_is_never_offered_an_archive_edit() {
        assertEquals(EditMode.COPY_ONLY, ArchiveCapabilities.editModeFor("notes.txt"))
    }

    @Test fun the_double_extension_is_read_as_one_format_not_two() {
        // `backup.tar.gz` must not be read as a gzip of something called backup.tar, which
        // would give it the wrong edit mode AND the wrong strength scale.
        assertEquals(EditMode.REBUILD, ArchiveCapabilities.editModeFor("backup.tar.gz"))
        assertEquals(ArchiveCapabilities.GZIP, cap("tar.gz").strength)
    }
}

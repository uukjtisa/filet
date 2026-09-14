package dev.niccc2007.filet.vfs.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which files are one archive, and which file merely ends in a number.
 *
 * Both failure modes here are quiet. Missing a part gives "corrupt archive" for a set that is
 * only incomplete, and somebody re-downloads gigabytes. Swallowing an unrelated file gives a
 * read failure that blames a file nobody touched.
 */
class PartSetTest {

    // ── recognising one name ──

    @Test fun a_modern_rar_set_is_recognised_with_its_index() {
        val r = PartSets.refFor("movie.part2.rar")!!
        assertEquals("movie", r.setName)
        assertEquals(PartStyle.RAR_PART, r.style)
        assertEquals(2, r.index)
    }

    @Test fun a_zero_padded_rar_set_keeps_its_width() {
        // Width matters: part01..part12 sorts correctly in a listing and part1..part12 does not.
        val r = PartSets.refFor("movie.part07.rar")!!
        assertEquals(7, r.index)
        assertEquals(2, r.padding)
    }

    @Test fun an_old_style_rar_volume_is_the_second_part_not_the_first() {
        // .r00 follows the bare .rar. Getting this backwards hands the reader a file with no
        // header on it, which fails as a corrupt archive.
        val r = PartSets.refFor("movie.r00")!!
        assertEquals(PartStyle.RAR_OLD, r.style)
        assertEquals(2, r.index)
    }

    @Test fun a_zip_volume_is_recognised() {
        val r = PartSets.refFor("backup.z03")!!
        assertEquals(PartStyle.ZIP_VOLUMES, r.style)
        assertEquals(3, r.index)
    }

    @Test fun a_numbered_split_of_a_real_archive_is_a_part() {
        val r = PartSets.refFor("backup.7z.001")!!
        assertEquals("backup.7z", r.setName)
        assertEquals(PartStyle.NUMBERED, r.style)
        assertEquals(1, r.index)
    }

    @Test fun a_numbered_split_of_a_double_extension_keeps_the_whole_extension() {
        val r = PartSets.refFor("backup.tar.gz.002")!!
        assertEquals("backup.tar.gz", r.setName)
        assertEquals(2, r.index)
    }

    @Test fun a_file_that_merely_ends_in_a_number_is_not_swallowed() {
        // The guard. `holiday.001` from some other tool must stay an ordinary file, or opening
        // it produces an archive error about a file that was never an archive.
        assertNull(PartSets.refFor("holiday.001"))
        assertNull(PartSets.refFor("scan.003"))
        assertNull(PartSets.refFor("notes.txt"))
    }

    @Test fun an_ordinary_archive_on_its_own_is_not_a_part() {
        assertNull(PartSets.refFor("backup.zip"))
        assertNull(PartSets.refFor("movie.rar"))
        assertNull(PartSets.refFor("backup.7z"))
    }

    @Test fun a_part_name_is_read_as_a_part_and_not_as_an_ordinary_rar() {
        // `movie.part1.rar` matches both patterns. Order of matching decides, and getting it
        // wrong means every set is read as N unrelated single archives.
        assertEquals(PartStyle.RAR_PART, PartSets.refFor("movie.part1.rar")!!.style)
    }

    @Test fun names_round_trip_through_the_generator() {
        // Every scheme, every index: parse(generate(i)) == i. This is what stops the off-by-one
        // in RAR_OLD from being reintroduced on the writing side.
        for (style in PartStyle.entries) {
            for (i in 1..12) {
                // A NUMBERED part is only a part when what is under the number is itself an
                // archive name, so the base here has to be one - that guard is the point of
                // `a_file_that_merely_ends_in_a_number_is_not_swallowed`.
                val base = if (style == PartStyle.NUMBERED) "set.7z" else "set"
                val name = PartSets.nameFor(base, style, i, padding = 3)
                val back = PartSets.refFor(name) ?: PartSets.refFor(name)
                if (style == PartStyle.RAR_OLD && i == 1) {
                    // part 1 is the bare `.rar`, which is only a part in context.
                    assertEquals("set.rar", name)
                    continue
                }
                assertNotNull("$style $i -> $name", back)
                assertEquals("$style $i -> $name", i, back!!.index)
                assertEquals("$style $i -> $name", style, back.style)
            }
        }
    }

    // ── assembling a set from a folder ──

    private val rarSet = listOf("movie.part1.rar", "movie.part2.rar", "movie.part3.rar", "unrelated.txt")

    @Test fun opening_any_part_finds_the_whole_set() {
        // Tapping part 3 must not be an error - which is the entire user-facing point of M2.
        for (member in rarSet.take(3)) {
            val state = PartSets.stateOf(member, rarSet)!!
            assertEquals(3, state.present.size)
            assertEquals("movie.part1.rar", state.firstPart)
        }
    }

    @Test fun an_unrelated_file_in_the_same_folder_is_not_part_of_the_set() {
        val state = PartSets.stateOf("movie.part1.rar", rarSet)!!
        assertFalse("unrelated.txt" in state.present)
    }

    @Test fun a_missing_middle_part_is_named_rather_than_failing_as_corrupt() {
        val here = listOf("movie.part1.rar", "movie.part3.rar")
        val state = PartSets.stateOf("movie.part1.rar", here)!!
        assertEquals(listOf("movie.part2.rar"), state.gaps)
        assertFalse(state.complete)
    }

    @Test fun a_gap_is_reported_with_the_name_to_go_and_find() {
        val here = listOf("backup.7z.001", "backup.7z.004")
        val state = PartSets.stateOf("backup.7z.004", here)!!
        assertEquals(listOf("backup.7z.002", "backup.7z.003"), state.gaps)
    }

    @Test fun a_zip_set_knows_where_it_ends_because_the_zip_is_always_last() {
        val here = listOf("backup.z01", "backup.z02", "backup.zip")
        val state = PartSets.stateOf("backup.z01", here)!!
        assertTrue(state.endKnown)
        assertTrue(state.complete)
        assertEquals("backup.zip", state.present.last())
    }

    @Test fun a_zip_set_missing_its_final_zip_is_not_reported_as_complete() {
        val state = PartSets.stateOf("backup.z01", listOf("backup.z01", "backup.z02"))!!
        assertFalse(state.endKnown)
        assertFalse(state.complete)
    }

    @Test fun the_final_zip_also_opens_the_set() {
        val here = listOf("backup.z01", "backup.z02", "backup.zip")
        val state = PartSets.stateOf("backup.zip", here)!!
        assertEquals(3, state.present.size)
    }

    @Test fun a_set_whose_end_cannot_be_known_never_claims_to_be_complete() {
        // RAR and numbered sets flag the last part inside the archive, not in the name. Saying
        // "complete" from names alone would be a guess presented as a fact.
        val state = PartSets.stateOf("movie.part1.rar", rarSet)!!
        assertFalse(state.endKnown)
        assertFalse(state.complete)
        assertTrue("but there are no gaps to report", state.gaps.isEmpty())
    }

    @Test fun an_old_style_rar_set_starts_at_the_bare_rar() {
        val here = listOf("movie.rar", "movie.r00", "movie.r01")
        val state = PartSets.stateOf("movie.r01", here)!!
        assertEquals(PartStyle.RAR_OLD, state.style)
        assertEquals("movie.rar", state.firstPart)
        assertEquals(3, state.present.size)
    }

    @Test fun a_lone_archive_with_no_siblings_is_not_a_set() {
        assertNull(PartSets.stateOf("backup.zip", listOf("backup.zip", "notes.txt")))
        assertNull(PartSets.stateOf("movie.rar", listOf("movie.rar")))
    }

    @Test fun two_different_sets_in_one_folder_do_not_bleed_into_each_other() {
        val here = listOf("a.part1.rar", "a.part2.rar", "b.part1.rar", "b.part2.rar")
        val a = PartSets.stateOf("a.part1.rar", here)!!
        assertEquals(listOf("a.part1.rar", "a.part2.rar"), a.present)
    }

    @Test fun matching_is_case_insensitive_because_some_volumes_are_uppercase() {
        val here = listOf("Movie.PART1.RAR", "Movie.Part2.rar")
        val state = PartSets.stateOf("Movie.Part2.rar", here)!!
        assertEquals(2, state.present.size)
    }

    // ── planning a split ──

    @Test fun a_planned_split_has_one_name_per_part() {
        val names = PartSets.plannedNames("backup.7z", PartStyle.NUMBERED, totalBytes = 250, partBytes = 100)
        assertEquals(listOf("backup.7z.001", "backup.7z.002", "backup.7z.003"), names)
    }

    @Test fun a_planned_zip_split_ends_with_the_plain_zip() {
        val names = PartSets.plannedNames("backup", PartStyle.ZIP_VOLUMES, totalBytes = 250, partBytes = 100)
        assertEquals(listOf("backup.z01", "backup.z02", "backup.zip"), names)
    }

    @Test fun a_split_that_needs_four_digits_gets_four_digits() {
        // .999 rolling into .1000 stops the set sorting, and some tools stop seeing it at all.
        val names = PartSets.plannedNames("big.7z", PartStyle.NUMBERED, totalBytes = 1500, partBytes = 1)
        assertEquals("big.7z.0001", names.first())
        assertEquals(1500, names.size)
    }

    @Test fun every_planned_name_parses_back_to_its_own_index() {
        // The round trip that proves a written set is a readable one.
        val names = PartSets.plannedNames("backup.7z", PartStyle.NUMBERED, totalBytes = 1000, partBytes = 100)
        names.forEachIndexed { i, n ->
            assertEquals(n, i + 1, PartSets.refFor(n)!!.index)
            assertEquals(n, "backup.7z", PartSets.refFor(n)!!.setName)
        }
    }

    @Test fun a_part_size_bigger_than_the_archive_gives_exactly_one_part() {
        assertEquals(1, PartSets.plannedNames("a.7z", PartStyle.NUMBERED, 50, 100).size)
    }

    @Test fun a_zero_part_size_is_refused_rather_than_looping_forever() {
        try {
            PartSets.plannedNames("a.7z", PartStyle.NUMBERED, 100, 0)
            throw AssertionError("a zero part size should not be accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("size"))
        }
    }
}

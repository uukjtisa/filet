package dev.niccc2007.filet.vfs.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of an extraction, decided before anything is written.
 *
 * Two things make this the most important test file in round 9.
 *
 * 1. **It is the bug was reported.** A tarbomb is not a crash and not an error message; it is
 *    forty files where you expected one folder, discovered after the fact, in a directory you
 *    now have to clean by hand. Every "bomb" case below is one he could actually hit.
 * 2. **The preview and the extractor read the same value.** So an off-by-one here is not a
 *    cosmetic bug in a dialog - it is a dialog that truthfully describes something, and an
 *    extractor that then does something else.
 */
class ExtractPlanTest {

    private fun f(path: String, size: Long = 10) = ArchiveEntry(path, isDir = false, size = size)
    private fun d(path: String) = ArchiveEntry(path, isDir = true, size = 0)

    private fun paths(plan: ExtractPlan) = plan.items.map { it.path }

    // ── the chain: how far "move content out" goes ──

    @Test fun nothing_comes_off_by_default_and_the_whole_chain_is_offered() {
        // archive.zip/archive/archivehere/archive.txt
        //
        // Both levels are redundant by the chain rule and NEITHER is lifted. No planner can
        // tell `archive/archivehere/` - two meaningless wrappers - from `firms/firmware/`,
        // which is two folders that say what the file is; the two are structurally identical.
        // Since they cannot be told apart, the reading that destroys nothing wins by default,
        // and the whole chain is reported so the preview can offer to lift it.
        val entries = listOf(d("archive"), d("archive/archivehere"), f("archive/archivehere/archive.txt"))
        val plan = ExtractPlanner.plan("archive.zip", entries)
        assertTrue(plan.strippedFolders.isEmpty())
        assertEquals(2, plan.strippableLevels)
        assertEquals(
            listOf("archive", "archive/archivehere", "archive/archivehere/archive.txt"),
            paths(plan),
        )
        assertNull("nothing was scattered, so nothing needs wrapping", plan.wrapFolder)
    }

    @Test fun every_level_still_comes_off_when_it_is_asked_for() {
        // The old default, now a choice. The control that reaches it is in `ExtractSheet`.
        val entries = listOf(d("archive"), d("archive/archivehere"), f("archive/archivehere/archive.txt"))
        val plan = ExtractPlanner.plan(
            "archive.zip", entries, options = ExtractOptions(stripLevels = 2),
        )
        assertEquals(listOf("archive", "archivehere"), plan.strippedFolders)
        assertEquals(listOf("archive.txt"), paths(plan))
    }

    @Test fun keeping_every_wrapper_is_also_reachable() {
        val entries = listOf(d("archive"), d("archive/archivehere"), f("archive/archivehere/archive.txt"))
        val plan = ExtractPlanner.plan(
            "archive.zip", entries, options = ExtractOptions(stripLevels = 0),
        )
        assertTrue(plan.strippedFolders.isEmpty())
        assertEquals(listOf("archive", "archive/archivehere", "archive/archivehere/archive.txt"), paths(plan))
    }

    @Test fun the_chain_stops_at_the_first_level_holding_two_things() {
        // The stop condition is the whole safety of the feature: lifting here would drop a
        // readme AND a folder into the destination, which is the bomb one level down.
        val entries = listOf(
            d("archive"), f("archive/readme.txt"), d("archive/inner"), f("archive/inner/a.txt"),
        )
        val plan = ExtractPlanner.plan("archive.zip", entries)
        // One level is redundant and no more - that is what this pins. Nothing is lifted by
        // default, so the assertion is on what COULD be.
        assertEquals(1, plan.strippableLevels)
        assertTrue(plan.strippedFolders.isEmpty())
        assertEquals(
            listOf("archive", "archive/inner", "archive/inner/a.txt", "archive/readme.txt"),
            paths(plan),
        )
    }

    @Test fun a_lone_file_at_the_top_is_not_a_folder_to_lift() {
        val plan = ExtractPlanner.plan("notes.zip", listOf(f("notes.txt")))
        assertTrue(plan.strippedFolders.isEmpty())
        assertEquals(listOf("notes.txt"), paths(plan))
    }

    @Test fun a_lone_empty_folder_is_not_lifted_into_nothing() {
        // Lifting would leave an empty plan and an extraction that produced no files at all.
        val plan = ExtractPlanner.plan("empty.zip", listOf(d("stuff")))
        assertTrue(plan.strippedFolders.isEmpty())
        assertEquals(listOf("stuff"), paths(plan))
    }

    @Test fun a_deep_chain_is_measured_in_full_and_none_of_it_comes_off() {
        // The whole chain is FOUND - that is what the control is offered from - and none of it
        // is taken. A five-deep archive flattened to a bare file by default is how
        // `firms/firmware/stuff.txt` became `stuff.txt`.
        val entries = listOf(f("a/b/c/d/e/deep.txt"))
        val plan = ExtractPlanner.plan("a.zip", entries)
        assertEquals(5, plan.strippableLevels)
        assertTrue(plan.strippedFolders.isEmpty())
        // Every wrapper the author chose survives. Asserted on the file rather than on the
        // whole list, because whether a writer's missing folder records are re-invented as
        // entries is a separate decision with its own tests.
        assertTrue(paths(plan).toString(), paths(plan).contains("a/b/c/d/e/deep.txt"))
    }

    @Test fun a_chain_is_found_even_when_the_archive_stores_no_folder_records() {
        // Plenty of writers store only file entries, so the top level has to come from the
        // paths. It is found - it is simply not taken off unless somebody asks for it.
        val entries = listOf(f("proj/a.txt"), f("proj/b.txt"))
        val plan = ExtractPlanner.plan("proj.zip", entries)
        assertEquals(1, plan.strippableLevels)
        assertTrue(plan.strippedFolders.isEmpty())
        assertEquals(listOf("proj/a.txt", "proj/b.txt"), paths(plan))
        val lifted = ExtractPlanner.plan("proj.zip", entries, options = ExtractOptions(stripLevels = 1))
        assertEquals(listOf("proj"), lifted.strippedFolders)
        assertEquals(listOf("a.txt", "b.txt"), paths(lifted))
    }

    @Test fun the_plan_reports_how_many_levels_could_be_lifted_even_when_it_did_not() {
        // The preview needs this to decide whether to offer the button at all.
        val entries = listOf(f("a/b/c.txt"))
        val kept = ExtractPlanner.plan("a.zip", entries, options = ExtractOptions(stripLevels = 0))
        assertEquals(2, kept.strippableLevels)
        assertTrue(kept.strippedFolders.isEmpty())
        assertEquals(listOf("a/b/c.txt"), paths(kept))
    }

    @Test fun undo_one_level_keeps_the_outer_folder_and_lifts_the_rest() {
        val entries = listOf(f("a/b/c.txt"))
        val plan = ExtractPlanner.plan("a.zip", entries, options = ExtractOptions(stripLevels = 1))
        assertEquals(listOf("a"), plan.strippedFolders)
        assertEquals(listOf("b/c.txt"), paths(plan))
    }

    @Test fun asking_to_lift_more_levels_than_exist_lifts_what_there_is() {
        val plan = ExtractPlanner.plan("a.zip", listOf(f("a/b.txt")), options = ExtractOptions(stripLevels = 9))
        assertEquals(listOf("a"), plan.strippedFolders)
        assertEquals(listOf("b.txt"), paths(plan))
    }

    // ── wrapping: the bomb was reported ──

    @Test fun loose_files_are_wrapped_in_a_folder_named_after_the_archive_when_asked() {
        // Automatic once, and no longer. The wrap existed to stop an archive of loose files
        // spraying into the folder you were standing in - a problem only because you found
        // out afterwards. The ghosts draw every entry into the destination before anything is
        // written, so the guess is unnecessary and "Put in a folder" is one tap. The CAPABILITY
        // is unchanged; only who decides.
        val entries = listOf(f("sunset.jpg"), f("harbour.jpg"), f("readme.txt"))
        assertNull("nothing is invented by default", ExtractPlanner.plan("photos.rar", entries).wrapFolder)
        val plan = ExtractPlanner.plan("photos.rar", entries, options = ExtractOptions(wrap = WrapChoice.FORCE_ON))
        assertEquals("photos", plan.wrapFolder)
        assertEquals(
            listOf("photos", "photos/harbour.jpg", "photos/readme.txt", "photos/sunset.jpg"),
            paths(plan),
        )
        assertTrue(plan.items.first().added)
    }

    @Test fun the_wrapping_folder_drops_a_double_extension_whole() {
        // `backup.tar.gz` must give `backup`, not `backup.tar`, or the folder reads as a step
        // in unwrapping rather than as the contents.
        val plan = ExtractPlanner.plan(
            "backup.tar.gz", listOf(f("a.txt"), f("b.txt")), options = ExtractOptions(wrap = WrapChoice.FORCE_ON),
        )
        assertEquals("backup", plan.wrapFolder)
    }

    @Test fun one_file_is_not_a_mess_and_is_not_wrapped() {
        val plan = ExtractPlanner.plan("notes.zip", listOf(f("notes.txt")))
        assertNull(plan.wrapFolder)
    }

    @Test fun an_archive_with_a_single_root_folder_is_never_also_wrapped() {
        // The two transforms are opposites. Doing both would give backup/backup/, which is the
        // thing he was complaining about in the other direction.
        val plan = ExtractPlanner.plan("backup.zip", listOf(d("backup"), f("backup/a.txt"), f("backup/b.txt")))
        assertNull(plan.wrapFolder)
        // Nothing is lifted by default now, so there is nothing to double up WITH - but the
        // guarantee still has to hold when a lift IS asked for, which is the case that could
        // otherwise produce `backup/backup/`.
        assertTrue(plan.strippedFolders.isEmpty())
        val lifted = ExtractPlanner.plan(
            "backup.zip",
            listOf(d("backup"), f("backup/a.txt"), f("backup/b.txt")),
            options = ExtractOptions(stripLevels = 1),
        )
        assertNull(lifted.wrapFolder)
        assertEquals(listOf("backup"), lifted.strippedFolders)
    }

    @Test fun auto_applies_at_most_one_of_the_two_transforms_whatever_the_archive() {
        val cases = listOf(
            listOf(f("a.txt"), f("b.txt")),
            listOf(d("root"), f("root/a.txt"), f("root/b.txt")),
            listOf(f("x/y/z.txt")),
            listOf(f("only.txt")),
            listOf(d("root"), f("root/a.txt"), f("other.txt")),
        )
        for (entries in cases) {
            val plan = ExtractPlanner.plan("thing.zip", entries)
            assertFalse(
                "wrapped AND stripped: $entries",
                plan.wrapFolder != null && plan.strippedFolders.isNotEmpty(),
            )
        }
    }

    @Test fun the_wrap_button_forces_a_folder_on_when_auto_did_not() {
        val entries = listOf(d("backup"), f("backup/a.txt"), f("backup/b.txt"))
        val plan = ExtractPlanner.plan("backup.zip", entries, options = ExtractOptions(wrap = WrapChoice.FORCE_ON))
        assertEquals("backup", plan.wrapFolder)
    }

    @Test fun the_same_button_forces_a_folder_off_when_auto_added_one() {
        val entries = listOf(f("a.txt"), f("b.txt"))
        val plan = ExtractPlanner.plan("loose.zip", entries, options = ExtractOptions(wrap = WrapChoice.FORCE_OFF))
        assertNull(plan.wrapFolder)
        assertEquals(listOf("a.txt", "b.txt"), paths(plan))
    }

    @Test fun an_archive_whose_name_is_only_an_extension_still_gets_a_folder_name() {
        // `.zip` alone would otherwise produce a wrap folder called "", which extracts into
        // the destination's parent on some path joins.
        val plan = ExtractPlanner.plan(".zip", listOf(f("a.txt"), f("b.txt")), options = ExtractOptions(wrap = WrapChoice.FORCE_ON))
        assertTrue(plan.wrapFolder!!.isNotBlank())
    }

    // ── entries that would escape ──

    @Test fun an_entry_pointing_outside_the_folder_is_refused_and_counted() {
        val entries = listOf(f("a.txt"), f("../../etc/passwd"), f("ok/b.txt"))
        val plan = ExtractPlanner.plan("evil.zip", entries)
        assertEquals(1, plan.refused.size)
        assertEquals("../../etc/passwd", plan.refused.first().path)
        assertFalse(paths(plan).any { it.contains("passwd") })
    }

    @Test fun absolute_paths_and_drive_letters_are_refused_too() {
        val entries = listOf(f("/etc/shadow"), f("C:/Windows/x.dll"), f("fine.txt"))
        val plan = ExtractPlanner.plan("evil.zip", entries)
        assertEquals(2, plan.refused.size)
    }

    @Test fun a_backslash_separator_does_not_smuggle_a_traversal_past_the_check() {
        // Zips written on Windows store backslashes, and a check that only looks for "../"
        // misses "..\\..\\etc". This is the assertion that catches a normalise-after-check bug.
        val plan = ExtractPlanner.plan("evil.zip", listOf(f("..\\..\\etc\\passwd")))
        assertEquals(1, plan.refused.size)
    }

    @Test fun a_file_merely_containing_two_dots_is_not_refused() {
        // The positive control. `my..notes.txt` and `a/..b/c` are ordinary names, and a check
        // that refuses them would break real archives.
        val plan = ExtractPlanner.plan("ok.zip", listOf(f("my..notes.txt"), f("a/..b/c.txt")))
        assertTrue(plan.refused.isEmpty())
    }

    // ── collisions ──

    @Test fun a_file_already_at_the_destination_is_named_in_the_plan() {
        val plan = ExtractPlanner.plan(
            "loose.zip",
            listOf(f("a.txt"), f("b.txt")),
            existingPaths = setOf("loose", "loose/a.txt"),
            options = ExtractOptions(wrap = WrapChoice.FORCE_ON),
        )
        val hit = plan.collisions.map { it.path }
        assertTrue("loose/a.txt" in hit)
        assertFalse("loose/b.txt" in hit)
    }

    @Test fun skipping_a_collision_means_its_bytes_are_not_needed() {
        // The free-space figure has to match what is actually written, or the warning fires on
        // an extraction that would have fitted.
        val entries = listOf(f("a.txt", size = 1000), f("b.txt", size = 500))
        val skip = ExtractPlanner.plan(
            "x.zip", entries, existingPaths = setOf("a.txt"),
            options = ExtractOptions(wrap = WrapChoice.FORCE_OFF, onCollision = CollisionChoice.SKIP),
        )
        assertEquals(500, skip.needsBytes)
        val over = ExtractPlanner.plan(
            "x.zip", entries, existingPaths = setOf("a.txt"),
            options = ExtractOptions(wrap = WrapChoice.FORCE_OFF, onCollision = CollisionChoice.OVERWRITE),
        )
        assertEquals(1500, over.needsBytes)
    }

    @Test fun keep_both_renames_the_incoming_file_and_keeps_the_extension() {
        val plan = ExtractPlanner.plan(
            "x.zip", listOf(f("report.txt")), existingPaths = setOf("report.txt"),
            options = ExtractOptions(wrap = WrapChoice.FORCE_OFF, onCollision = CollisionChoice.KEEP_BOTH),
        )
        assertEquals(listOf("report (1).txt"), paths(plan))
    }

    @Test fun keep_both_on_a_dotfile_puts_the_number_at_the_end() {
        assertEquals(".gitignore (1)", ExtractPlanner.keepBothName(".gitignore", setOf(".gitignore")))
    }

    @Test fun keep_both_counts_up_until_it_finds_a_free_name() {
        assertEquals("a (3).txt", ExtractPlanner.keepBothName("a.txt", setOf("a.txt", "a (1).txt", "a (2).txt")))
    }

    @Test fun the_wrapping_folder_itself_can_collide_and_says_so() {
        val plan = ExtractPlanner.plan(
            "photos.zip", listOf(f("a.jpg"), f("b.jpg")), existingPaths = setOf("photos"),
            options = ExtractOptions(wrap = WrapChoice.FORCE_ON),
        )
        assertNotNull(plan.items.first().collides)
    }

    // ── what it costs ──

    @Test fun the_space_needed_is_the_uncompressed_total_of_what_will_be_written() {
        val entries = listOf(f("a.bin", size = 700), f("b.bin", size = 300), d("dir"))
        val plan = ExtractPlanner.plan("x.zip", entries)
        assertEquals(1000, plan.needsBytes)
    }

    @Test fun not_enough_room_is_known_before_anything_is_written() {
        val plan = ExtractPlanner.plan("x.zip", listOf(f("big.bin", size = 5000)), freeBytes = 1000)
        assertFalse(plan.fits)
    }

    @Test fun unknown_free_space_never_blocks_an_extraction() {
        // -1 means the volume could not be asked. Refusing on that would make network volumes
        // and some document providers permanently un-extractable.
        val plan = ExtractPlanner.plan("x.zip", listOf(f("big.bin", size = 5000)), freeBytes = -1)
        assertTrue(plan.fits)
    }

    @Test fun already_compressed_media_is_counted_separately() {
        // So the preview can say a 2 GB extract is mostly video and will not shrink, using the
        // writer's own list rather than a second opinion about what compresses.
        val entries = listOf(f("clip.mp4", size = 900), f("notes.txt", size = 100))
        val plan = ExtractPlanner.plan("x.zip", entries)
        assertEquals(900, plan.alreadyCompressedBytes)
        assertEquals(1000, plan.needsBytes)
    }

    @Test fun the_entry_count_is_files_not_folder_records() {
        val entries = listOf(d("a"), d("a/b"), f("a/b/one.txt"), f("a/b/two.txt"))
        val plan = ExtractPlanner.plan("x.zip", entries)
        assertEquals(2, plan.entryCount)
    }

    // ── the invariant that makes the preview trustworthy ──

    @Test fun no_planned_item_ever_escapes_the_destination() {
        // The single assertion that has to hold for every archive, however odd. If the preview
        // can show a path starting with .. or /, the extractor can write one.
        val nasty = listOf(
            f("../x"), f("/y"), f("C:/z"), f("a/../../b"), f("ok.txt"), f("deep/a/../b.txt"),
        )
        for (wrap in WrapChoice.entries) {
            val plan = ExtractPlanner.plan("t.zip", nasty, options = ExtractOptions(wrap = wrap))
            for (item in plan.items) {
                assertFalse(item.path, item.path.startsWith("/"))
                assertFalse(item.path, item.path.startsWith(".."))
                assertFalse(item.path, item.path.split('/').any { it == ".." })
                assertFalse(item.path, item.path.length > 1 && item.path[1] == ':')
            }
        }
    }

    @Test fun an_empty_archive_produces_an_empty_plan_rather_than_throwing() {
        val plan = ExtractPlanner.plan("empty.zip", emptyList())
        assertTrue(plan.items.isEmpty())
        assertEquals(0, plan.entryCount)
        assertNull(plan.wrapFolder)
    }

    @Test fun an_archive_of_nothing_but_refused_entries_is_empty_and_says_why() {
        val plan = ExtractPlanner.plan("evil.zip", listOf(f("../a"), f("/b")))
        assertTrue(plan.items.isEmpty())
        assertEquals(2, plan.refused.size)
    }

    // ── where each item is read FROM ──

    @Test fun every_item_says_where_to_read_it_from_inside_the_archive() {
        // The extractor walks this list rather than re-deriving the strip and the wrap. If it
        // re-derived them, the preview and the extraction would be two implementations of one
        // decision - which is the bug this whole file exists to prevent.
        // A readme beside the inner folder, so only ONE level is redundant and the item keeps
        // a path with a folder in it - which is what makes source and path differ visibly.
        val entries = listOf(
            d("archive"), d("archive/inner"), f("archive/inner/a.txt"), f("archive/readme.txt"),
        )
        // Asked WITH a lift, because the point is that `source` and `path` differ - and with
        // nothing lifted they are identical and this would prove nothing.
        val plan = ExtractPlanner.plan("archive.zip", entries, options = ExtractOptions(stripLevels = 1))
        assertEquals(listOf("archive"), plan.strippedFolders)
        val item = plan.items.first { it.path == "inner/a.txt" }
        assertEquals("archive/inner/a.txt", item.source)
    }

    @Test fun wrapping_changes_where_a_file_lands_and_not_where_it_comes_from() {
        val plan = ExtractPlanner.plan("photos.zip", listOf(f("a.jpg"), f("b.jpg")), options = ExtractOptions(wrap = WrapChoice.FORCE_ON))
        val item = plan.items.first { it.path == "photos/a.jpg" }
        assertEquals("a.jpg", item.source)
    }

    @Test fun an_invented_folder_has_no_source_because_nothing_is_read_for_it() {
        val plan = ExtractPlanner.plan("photos.zip", listOf(f("a.jpg"), f("b.jpg")), options = ExtractOptions(wrap = WrapChoice.FORCE_ON))
        assertEquals("", plan.items.first { it.added }.source)
    }

    @Test fun keeping_both_copies_still_reads_from_the_original_entry() {
        // The destination name changes to "report (1).txt"; the archive still holds
        // "report.txt", and reading from the renamed path would find nothing.
        val plan = ExtractPlanner.plan(
            "x.zip", listOf(f("report.txt")), existingPaths = setOf("report.txt"),
            options = ExtractOptions(wrap = WrapChoice.FORCE_OFF, onCollision = CollisionChoice.KEEP_BOTH),
        )
        assertEquals("report.txt", plan.items.single().source)
        assertEquals("report (1).txt", plan.items.single().path)
    }
}

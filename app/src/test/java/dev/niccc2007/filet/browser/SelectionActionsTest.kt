package dev.niccc2007.filet.browser

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one list both renderings draw.
 *
 * The reason this is a list rather than two composables: the bar and the menu show the same
 * actions, and two hand-written copies drift. The drift is always the same shape - an action
 * added to one, or blocked in one and live in the other - and the second one is a real bug,
 * because the live copy then lets somebody rename four files at once.
 *
 * So the assertions here are about the *rules*, not about any one button.
 */
class SelectionActionsTest {

    private val icon = ImageVector.Builder("x", 1.dp, 1.dp, 1f, 1f).build()
    private val icons = SelectionIcons(
        icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon, icon,
    )

    private var ran = ""
    private val callbacks = SelectionCallbacks(
        copy = { ran = "copy" },
        move = { ran = "move" },
        send = { ran = "send" },
        delete = { ran = "delete" },
        compress = { ran = "compress" },
        rename = { ran = "rename" },
        openWith = { ran = "openWith" },
        details = { ran = "details" },
        bookmark = { ran = "bookmark" },
        nearby = { ran = "nearby" },
        shortcut = { ran = "shortcut" },
        extractHere = { ran = "extractHere" },
        extractToOtherPane = { ran = "extractToOtherPane" },
        addToPlaylist = { ran = "addToPlaylist" },
        enqueue = { ran = "enqueue" },
    )

    private fun actions(
        count: Int,
        readOnly: String? = null,
        archive: Boolean = false,
        otherPane: Boolean = false,
        picking: Boolean = false,
        shape: SelectionShape = SelectionShape.files(count),
        announceable: Boolean = false,
    ) = selectionActions(
        count, readOnly, icons, callbacks, archive, otherPane, picking,
        announceable = announceable,
        shape = shape,
    )

    private val extractIds = listOf("extract", "extractother")

    @Test fun single_item_actions_are_absent_for_a_multi_selection_not_blocked() {
        // The rule since the menu review: a row that cannot act on this selection is not shown.
        // Rename on four files is not unavailable, it is meaningless.
        val many = actions(4).map { it.id }
        for (id in listOf("rename", "openwith", "shortcut")) {
            assertTrue("$id should be absent for a multi-selection", id !in many)
        }
        val one = actions(1).map { it.id }
        for (id in listOf("rename", "openwith", "details", "shortcut")) {
            assertTrue("$id should be offered for one file", id in one)
        }
    }

    @Test fun details_sums_up_a_multi_selection() {
        // Several items get one summary card: their size together and what they share.
        assertTrue("details" in actions(4).map { it.id })
    }

    @Test fun every_action_has_a_unique_id_and_a_label() {
        val all = actions(1)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.none { it.label.isBlank() })
    }

    @Test fun nothing_offered_is_blocked_for_a_reason_the_selection_could_not_fix() {
        // Only the volume being read-only may leave a row present and blocked. Every other
        // obstacle removes the row instead.
        for (shape in listOf(
            SelectionShape(1), SelectionShape(4, folders = 2), SelectionShape(3, playable = 3, media = 3),
        )) {
            assertTrue(actions(shape.count, shape = shape, announceable = true).all { it.blocked == null })
        }
    }

    // -- the gates, per type --

    @Test fun playlist_and_queue_exist_only_when_something_selected_can_play() {
        val pdf = actions(1, shape = SelectionShape(1)).map { it.id }
        assertTrue("playlist" !in pdf && "queue" !in pdf)
        val folder = actions(1, shape = SelectionShape(1, folders = 1)).map { it.id }
        assertTrue("playlist" !in folder && "queue" !in folder)
        val song = actions(1, shape = SelectionShape(1, playable = 1, media = 1)).associateBy { it.id }
        assertEquals("Add to playlist", song.getValue("playlist").label)
        assertEquals("Add to queue", song.getValue("queue").label)
    }

    @Test fun a_mixed_selection_keeps_the_row_and_says_how_many_it_takes() {
        // Eleven songs and a screenshot: the music actions stay, and do not claim the screenshot.
        val mixed = actions(12, shape = SelectionShape(12, playable = 11, media = 12), announceable = true)
            .associateBy { it.id }
        assertEquals("Add to playlist · 11 of 12", mixed.getValue("playlist").label)
        assertEquals("Add to queue · 11 of 12", mixed.getValue("queue").label)
        // All twelve are media, so the gallery row is unqualified.
        assertEquals("Add to gallery", mixed.getValue("gallery").label)
    }

    @Test fun gallery_needs_media_on_this_device() {
        val photo = SelectionShape(1, media = 1)
        assertTrue("gallery" in actions(1, shape = photo, announceable = true).map { it.id })
        // On a share: the phone's media index cannot hold it.
        assertTrue("gallery" !in actions(1, shape = photo, announceable = false).map { it.id })
        // A document on the phone: a gallery has nothing to show for it.
        assertTrue("gallery" !in actions(1, shape = SelectionShape(1), announceable = true).map { it.id })
        val some = actions(3, shape = SelectionShape(3, media = 1), announceable = true).associateBy { it.id }
        assertEquals("Add to gallery · 1 of 3", some.getValue("gallery").label)
    }

    @Test fun send_and_open_with_are_for_files_not_folders() {
        val folder = actions(1, shape = SelectionShape(1, folders = 1)).map { it.id }
        assertTrue("send" !in folder)
        assertTrue("openwith" !in folder)
        // But a folder can still be renamed, described, shortcut and bookmarked.
        for (id in listOf("rename", "details", "shortcut", "bookmark", "copy", "move", "compress")) {
            assertTrue("$id applies to a folder", id in folder)
        }
        val mixed = actions(3, shape = SelectionShape(3, folders = 1)).associateBy { it.id }
        assertEquals("Send · 2 of 3", mixed.getValue("send").label)
    }

    @Test fun a_read_only_volume_blocks_everything_that_writes_and_nothing_that_does_not() {
        val ro = actions(1, readOnly = "This volume is read-only").associateBy { it.id }
        for (id in listOf("move", "delete", "compress", "rename")) {
            assertNotNull("$id writes and must be blocked", ro.getValue(id).blocked)
        }
        for (id in listOf("copy", "send", "details", "bookmark", "shortcut")) {
            assertNull("$id only reads and must stay available", ro.getValue(id).blocked)
        }
    }

    @Test fun read_only_blocks_rename_for_one_file_and_the_multi_rule_still_removes_it() {
        val one = actions(1, readOnly = "This volume is read-only").associateBy { it.id }
        assertEquals("This volume is read-only", one.getValue("rename").blocked)
        assertTrue("rename" !in actions(5, readOnly = "This volume is read-only").map { it.id })
    }

    @Test fun delete_is_the_only_dangerous_one_and_it_is_last() {
        val all = actions(1)
        assertEquals(listOf("delete"), all.filter { it.danger }.map { it.id })
        assertEquals("delete", all.last().id)
    }

    @Test fun the_groups_run_in_order_and_never_interleave() {
        // The divider is drawn where the group changes, so a group appearing twice would draw
        // a divider in the middle of nothing.
        val seen = ArrayList<SelectionAction.Group>()
        for (a in actions(1)) if (seen.lastOrNull() != a.group) seen += a.group
        assertEquals(seen, seen.distinct())
    }

    @Test fun each_action_runs_its_own_callback() {
        // The failure this catches is a copy-paste in the list: two entries wired to the same
        // lambda, which in a menu of eleven is genuinely hard to see by reading.
        for (action in actions(1)) {
            ran = ""
            action.run()
            assertTrue("${action.id} ran nothing", ran.isNotEmpty())
        }
    }

    @Test fun no_two_actions_share_a_callback() {
        val fired = actions(1).map { action ->
            ran = ""
            action.run()
            ran
        }
        assertEquals(fired.size, fired.toSet().size)
    }

    // -- extracting, which is the one family of rows that is absent rather than blocked --

    @Test fun a_selection_that_is_not_an_archive_has_no_extract_rows_at_all() {
        // Absent, not disabled, and this is the one exception to the rule above. "Extract" on a
        // photo is not a thing that is unavailable, it is a thing that means nothing - and a
        // blocked row would need a sentence explaining that a JPEG is not an archive.
        val ids = actions(1, archive = false).map { it.id }
        for (id in extractIds) assertTrue("$id should not be offered", id !in ids)
    }

    @Test fun an_archive_gets_one_extract_and_the_other_pane_only_when_there_is_one() {
        val single = actions(1, archive = true).map { it.id }
        assertTrue("extract" in single)
        // "Extract here" and "Extract to..." were two rows differing only in where they
        // aimed. The ghosts follow the folder now, so standing still is "here" and walking is
        // "to", and a second row would describe a difference that no longer exists.
        assertTrue("extracthere" !in single)
        assertTrue("extractto" !in single)
        // No second pane on screen, so no row pointing at one.
        assertTrue("extractother" !in single)

        val split = actions(1, archive = true, otherPane = true).map { it.id }
        assertTrue("extractother" in split)
    }

    @Test fun extract_is_offered_on_a_read_only_volume_because_the_destination_can_move() {
        val all = actions(1, readOnly = "This volume is read-only.", archive = true).associateBy { it.id }
        // The near-miss this pins. "Extract here" WAS blocked here and "Extract to..." was
        // not, because the second put the files elsewhere. Merging them naively would have
        // carried the block onto the only row left - so browsing an archive inside a
        // read-only volume, which is precisely when extracting elsewhere is wanted, would
        // have offered one greyed-out row and no way forward.
        //
        // The row always starts; the DESTINATION refuses. `ExtractCard` names the reason and
        // goes live the moment somebody walks somewhere writable.
        assertNull(all.getValue("extract").blocked)
    }

    @Test fun the_extract_rows_sit_with_compress_rather_than_among_the_places() {
        val all = actions(1, archive = true, otherPane = true).associateBy { it.id }
        for (id in extractIds) {
            assertEquals(id, SelectionAction.Group.EDIT, all.getValue(id).group)
        }
    }

    @Test fun adding_the_extract_rows_changes_nothing_else() {
        // The blast radius. A flag that quietly reordered or reworded the other rows would be a
        // much harder bug to see than one that fails here.
        val without = actions(1, archive = false).map { it.id }
        val with = actions(1, archive = true, otherPane = true).map { it.id }
        assertEquals(without, with.filter { it !in extractIds })
    }

    @Test fun each_extract_row_reaches_its_own_callback() {
        val all = actions(1, archive = true, otherPane = true).associateBy { it.id }
        for ((id, expected) in listOf(
            "extract" to "extractHere",
            "extractother" to "extractToOtherPane",
        )) {
            ran = ""
            all.getValue(id).run()
            assertEquals(id, expected, ran)
        }
    }

    // -- picking for another app --

    @Test fun delete_is_absent_while_picking_rather_than_merely_blocked() {
        // Somebody who reached this screen through another app's chooser came to CHOOSE a
        // file. A destructive action one tap away from the one they meant is the screen
        // failing them. Blocked would not be enough: a blocked row is still a row they can
        // press, and the answer would be a sentence about why they cannot delete during a
        // pick, which nobody asked.
        val ids = actions(1, picking = true).map { it.id }
        assertTrue("delete must not be offered while picking", "delete" !in ids)
    }

    @Test fun everything_that_is_not_destructive_survives_a_pick() {
        // The other half, and the reason this is a removal of one row rather than a stripped
        // screen: a picker you cannot search, copy a path from or look at properties in is a
        // worse file manager than the one it was called to replace.
        val ids = actions(1, picking = true).map { it.id }
        for (id in listOf("copy", "send", "openwith", "details", "bookmark", "shortcut")) {
            assertTrue("$id should survive a pick", id in ids)
        }
    }

    @Test fun picking_changes_nothing_else_about_the_list() {
        val normal = actions(1).map { it.id }
        val picker = actions(1, picking = true).map { it.id }
        assertEquals(normal.filter { it != "delete" }, picker)
    }
}

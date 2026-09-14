package dev.niccc2007.filet.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a file was first noticed, remembered once.
 *
 * The rule that makes this worth storing at all is that it is written **once**. Overwrite it on
 * every scan and "first seen" quietly becomes "last scanned", which sorts identically to the
 * mtime ordering it is supposed to be different from - so the switch between the two orderings
 * appears to work and does nothing. That is the failure this file exists to catch, and it is
 * invisible from the screen.
 *
 * The second rule is that it stays bounded. Download churns; a store that grows forever would
 * make the feature a reason the app got slower on the one device it is meant to help.
 */
class FirstSeenTest {

    /** Storage as a string in memory, which is all `SharedPreferences` is to this class. */
    private class Fake {
        var blob: String? = null
        fun store() = FirstSeenStore({ blob }, { blob = it })
    }

    // ── written once ──

    @Test fun a_path_is_stamped_the_first_time_it_is_seen() {
        val s = Fake().store()
        assertEquals(1, s.record(listOf("/a.txt"), 1000))
        assertEquals(1000L, s.of("/a.txt"))
    }

    @Test fun seeing_it_again_does_not_move_its_date() {
        // THE test. Every scan sees every file again; if this moved, the ordering would be
        // meaningless and would look exactly like it worked.
        val s = Fake().store()
        s.record(listOf("/a.txt"), 1000)
        assertEquals(0, s.record(listOf("/a.txt"), 9999))
        assertEquals(1000L, s.of("/a.txt"))
    }

    @Test fun seeing_it_again_alongside_something_new_still_does_not_move_it() {
        val s = Fake().store()
        s.record(listOf("/a.txt"), 1000)
        assertEquals(1, s.record(listOf("/a.txt", "/b.txt"), 2000))
        assertEquals(1000L, s.of("/a.txt"))
        assertEquals(2000L, s.of("/b.txt"))
    }

    @Test fun a_path_never_seen_has_no_date_rather_than_a_zero() {
        // Zero is 1970, which would sort to the bottom of the history and look like real data.
        assertNull(Fake().store().of("/never.txt"))
    }

    // ── it survives a restart ──

    @Test fun what_was_recorded_is_still_there_in_a_new_instance() {
        val fake = Fake()
        fake.store().record(listOf("/a.txt", "/b.txt"), 4242)
        val reopened = fake.store()
        assertEquals(4242L, reopened.of("/a.txt"))
        assertEquals(4242L, reopened.of("/b.txt"))
        assertEquals(2, reopened.size())
    }

    @Test fun a_reopened_store_still_refuses_to_restamp() {
        val fake = Fake()
        fake.store().record(listOf("/a.txt"), 1000)
        val reopened = fake.store()
        reopened.record(listOf("/a.txt"), 5000)
        assertEquals(1000L, reopened.of("/a.txt"))
    }

    @Test fun a_corrupt_blob_reads_as_empty_rather_than_throwing() {
        // A truncated write, or a file edited by hand. Losing the history is a shame; crashing
        // the home screen every launch because of it is not acceptable.
        val fake = Fake()
        fake.blob = "{not json at all"
        val s = fake.store()
        assertEquals(0, s.size())
        s.record(listOf("/a.txt"), 7)
        assertEquals(7L, s.of("/a.txt"))
    }

    @Test fun nothing_is_written_when_nothing_is_new() {
        // Every feed refresh calls this. Rewriting the whole blob each time would turn a
        // read-only pass into a disk write per scan.
        val fake = Fake()
        fake.store().record(listOf("/a.txt"), 1000)
        val s = fake.store()
        val before = fake.blob
        s.record(listOf("/a.txt"), 2000)
        assertEquals(before, fake.blob)
    }

    // ── pruned and bounded ──

    @Test fun a_file_that_is_gone_is_forgotten() {
        val s = Fake().store()
        s.record(listOf("/a.txt", "/b.txt", "/c.txt"), 1000)
        s.prune(setOf("/a.txt", "/c.txt"))
        assertNotNull(s.of("/a.txt"))
        assertNull(s.of("/b.txt"))
        assertEquals(2, s.size())
    }

    @Test fun pruning_to_nothing_empties_the_store() {
        val s = Fake().store()
        s.record(listOf("/a.txt"), 1000)
        s.prune(emptySet())
        assertEquals(0, s.size())
    }

    @Test fun a_forgotten_file_that_comes_back_is_stamped_with_the_new_date() {
        // Correct rather than ideal, and worth being explicit about: the store is not a
        // permanent record, so a deleted and re-downloaded file is genuinely new to it.
        val s = Fake().store()
        s.record(listOf("/a.txt"), 1000)
        s.prune(emptySet())
        s.record(listOf("/a.txt"), 5000)
        assertEquals(5000L, s.of("/a.txt"))
    }

    @Test fun the_store_stops_growing_at_its_cap() {
        val s = Fake().store()
        s.record((1..FirstSeenStore.MAX + 500).map { "/f$it.txt" }, 1000)
        assertTrue("grew past the cap: ${s.size()}", s.size() <= FirstSeenStore.MAX)
    }

    @Test fun the_oldest_entries_are_the_ones_dropped_at_the_cap() {
        // A file whose first-seen date has aged past the cap is one the history no longer
        // reaches, so it costs nothing visible. Dropping the NEWEST would be the opposite.
        val s = Fake().store()
        s.record(listOf("/ancient.txt"), 1)
        s.record((1..FirstSeenStore.MAX + 10).map { "/f$it.txt" }, 9_000_000)
        assertNull("the oldest entry survived the trim", s.of("/ancient.txt"))
        assertNotNull(s.of("/f${FirstSeenStore.MAX + 10}.txt"))
    }

    @Test fun clearing_really_clears_and_the_clearing_is_persisted() {
        val fake = Fake()
        val s = fake.store()
        s.record(listOf("/a.txt"), 1000)
        s.clear()
        assertEquals(0, s.size())
        assertEquals(0, fake.store().size())
    }

    // ── it feeds the history ──

    @Test fun the_store_supplies_the_timestamp_the_history_sorts_on() {
        // The join between the two halves. A file whose mtime is old but whose first-seen is
        // today must carry both, or the two orderings collapse into one.
        val s = Fake().store()
        s.record(listOf("/dl/reviewer.pdf"), 1_700_000_000_000)
        val entry = HistoryEntry(
            path = "/dl/reviewer.pdf",
            name = "reviewer.pdf",
            bytes = 10,
            firstSeen = s.of("/dl/reviewer.pdf") ?: 0L,
            lastChanged = 1_500_000_000_000,
        )
        assertEquals(1_700_000_000_000, entry.timeFor(HistorySort.FIRST_SEEN))
        assertEquals(1_500_000_000_000, entry.timeFor(HistorySort.LAST_CHANGED))
    }

    @Test fun a_file_with_no_recorded_date_falls_back_to_its_mtime() {
        // Files already on the device before this feature existed have no first-seen. Showing
        // them at 1970 would put every pre-existing file at the bottom of the list forever.
        val s = Fake().store()
        val mtime = 1_500_000_000_000
        val firstSeen = s.of("/old.txt") ?: mtime
        assertEquals(mtime, firstSeen)
    }

    // ── the first run ──

    @Test fun an_empty_store_is_seeded_from_each_files_own_date() {
        // Found on the device rather than in review: opening the history for the first time
        // stamped 4,010 files with the clock and showed them all under "Today, 11:37pm". That
        // is not a history and it is not true - nothing was first seen at that moment, the
        // store simply did not exist yet.
        val s = Fake().store()
        s.record(mapOf("/a.txt" to 1_000L, "/b.txt" to 2_000L), now = 9_999L)
        assertEquals(1_000L, s.of("/a.txt"))
        assertEquals(2_000L, s.of("/b.txt"))
    }

    @Test fun after_the_seeding_window_a_genuinely_new_file_is_stamped_with_the_clock() {
        // And this is why seeding cannot simply become the rule: a file COPIED in later keeps
        // whatever mtime it was written with, and only the clock records that it turned up when
        // it did. Measured past the window, because inside it every new path is still part of
        // the first scan.
        val s = Fake().store()
        s.record(mapOf("/old.txt" to 1_000L), now = 5_000L)
        val later = 5_000L + FirstSeenStore.SEED_WINDOW + 1
        s.record(mapOf("/old.txt" to 1_000L, "/copied.txt" to 1L), now = later)
        assertEquals("a seeded file moved", 1_000L, s.of("/old.txt"))
        assertEquals("a later arrival should carry the clock", later, s.of("/copied.txt"))
    }

    @Test fun a_seeded_file_with_no_usable_mtime_falls_back_to_the_clock() {
        // Zero is 1970. A file whose mtime could not be read is better placed at now than at
        // the bottom of the list forever.
        val s = Fake().store()
        s.record(mapOf("/broken.txt" to 0L), now = 7_000L)
        assertEquals(7_000L, s.of("/broken.txt"))
    }

    @Test fun a_partial_first_pass_does_not_end_the_seeding() {
        // Found on the device, not in review. The feed publishes a fast partial result before
        // its full scan finishes, so "the store is empty" stopped being true after six files
        // and the remaining four thousand were stamped with the clock a second later - the same
        // symptom as the bug it was meant to fix. The window is what makes the whole first scan
        // one first run, however many passes it arrives in.
        val s = Fake().store()
        s.record(mapOf("/a.txt" to 1_000L), now = 5_000L)
        s.record(mapOf("/b.txt" to 2_000L), now = 6_000L)
        assertEquals(1_000L, s.of("/a.txt"))
        assertEquals("the rest of the first scan should still seed", 2_000L, s.of("/b.txt"))
    }

    @Test fun once_the_window_closes_a_new_file_takes_the_clock() {
        val s = Fake().store()
        s.record(mapOf("/a.txt" to 1_000L), now = 5_000L)
        val later = 5_000L + FirstSeenStore.SEED_WINDOW + 1
        s.record(mapOf("/copied.txt" to 1L), now = later)
        assertEquals("a file arriving later carries the clock", later, s.of("/copied.txt"))
    }

    @Test fun the_window_survives_a_restart() {
        // It is stored alongside the paths, so a first scan that spans an app restart is still
        // one first run rather than half a seeded history and half a wall of "Today".
        val fake = Fake()
        fake.store().record(mapOf("/a.txt" to 1_000L), now = 5_000L)
        val reopened = fake.store()
        reopened.record(mapOf("/b.txt" to 2_000L), now = 7_000L)
        assertEquals(2_000L, reopened.of("/b.txt"))
    }

    @Test fun clearing_reopens_the_seeding_window() {
        // After a deliberate clear the next pass is a first run again, and should not stamp
        // everything with the moment the button was pressed.
        val s = Fake().store()
        s.record(mapOf("/a.txt" to 1_000L), now = 5_000L)
        val late = 5_000L + FirstSeenStore.SEED_WINDOW + 1
        s.clear()
        s.record(mapOf("/a.txt" to 1_000L), now = late)
        assertEquals(1_000L, s.of("/a.txt"))
    }
}

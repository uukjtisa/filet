package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.data.SortKey
import dev.niccc2007.filet.data.SortSpec
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opening one track has to give you the folder it came from, in the order you can see. */
class QueueTest {

    private fun file(name: String, size: Long = 1_000, mtime: Long = 0) =
        VNode(VPath.of("local", "/storage/emulated/0/Music/$name"), isDir = false, size = size, mtime = mtime)

    private fun folder(name: String) =
        VNode(VPath.of("local", "/storage/emulated/0/Music/$name"), isDir = true, size = -1, mtime = 0)

    private val listing = listOf(
        file("track10.mp3"),
        file("cover.jpg"),
        file("track2.mp3"),
        folder("Live"),
        file("track1.flac"),
        file("notes.txt"),
    )

    private fun names(q: PlayQueue) = q.items.map { it.name }

    @Test fun the_folder_becomes_the_queue_and_the_pictures_stay_out_of_it() {
        val q = audioQueue(listing, file("track2.mp3"))
        assertEquals(listOf("track1.flac", "track2.mp3", "track10.mp3"), names(q))
    }

    @Test fun it_starts_on_the_track_that_was_opened_not_at_the_top() {
        val q = audioQueue(listing, file("track10.mp3"))
        assertEquals("track10.mp3", q.current.name)
        assertEquals(3, q.position)
        assertEquals(3, q.size)
    }

    @Test fun the_order_is_the_one_on_screen_including_digits_read_as_numbers() {
        // track2 before track10. This is the sort bug every file manager has, and a queue
        // that disagreed with the listing behind it would look like a shuffle nobody asked for.
        val q = audioQueue(listing, file("track1.flac"))
        assertEquals(listOf("track1.flac", "track2.mp3", "track10.mp3"), names(q))
    }

    @Test fun the_browsers_own_sort_is_honoured_rather_than_a_second_opinion() {
        // `descending` means most relevant first - A to Z for names - so the reversed order is
        // what the OTHER direction produces. See SortOrder.
        val q = audioQueue(listing, file("track1.flac"), SortSpec(key = SortKey.NAME, descending = false))
        assertEquals(listOf("track10.mp3", "track2.mp3", "track1.flac"), names(q))
        assertEquals("track1.flac", q.current.name)
    }

    @Test fun the_default_direction_plays_tracks_in_the_order_they_are_listed() {
        val q = audioQueue(listing, file("track1.flac"), SortSpec(key = SortKey.NAME, descending = true))
        assertEquals(listOf("track1.flac", "track2.mp3", "track10.mp3"), names(q))
    }

    @Test fun sorting_by_size_still_lands_on_the_opened_track() {
        // Largest first by default now - see SortOrder. The order is incidental here; what
        // matters is that the queue starts on the track that was opened whatever the order is.
        val sized = listOf(file("a.mp3", size = 900), file("b.mp3", size = 100), file("c.mp3", size = 500))
        val q = audioQueue(sized, file("a.mp3", size = 900), SortSpec(key = SortKey.SIZE))
        assertEquals(listOf("a.mp3", "c.mp3", "b.mp3"), names(q))
        assertEquals("a.mp3", q.current.name)

        val smallestFirst = audioQueue(sized, file("c.mp3", size = 500), SortSpec(key = SortKey.SIZE, descending = false))
        assertEquals(listOf("b.mp3", "c.mp3", "a.mp3"), names(smallestFirst))
        assertEquals("c.mp3", smallestFirst.current.name)
    }

    @Test fun a_folder_with_one_track_is_a_queue_of_one() {
        val q = audioQueue(listOf(file("only.mp3"), file("art.png")), file("only.mp3"))
        assertEquals(1, q.size)
        assertEquals(q, q.stepped(1))
        assertEquals(q, q.stepped(-1))
    }

    @Test fun a_track_that_is_not_in_the_listing_still_plays() {
        // Arrives from search, from a shortcut, or from another app - or is an extension this
        // build does not class as audio but MediaPlayer opens anyway. Refusing would be worse.
        val q = audioQueue(listing, file("elsewhere.mp3"))
        assertEquals(1, q.size)
        assertEquals("elsewhere.mp3", q.current.name)
    }

    @Test fun a_folder_with_no_audio_at_all_does_not_produce_an_empty_queue() {
        val q = audioQueue(listOf(file("cover.jpg"), folder("Live")), file("cover.jpg"))
        assertEquals(1, q.size)
        assertEquals("cover.jpg", q.current.name)
    }

    // ── moving ──

    @Test fun next_and_previous_walk_the_queue() {
        val q = audioQueue(listing, file("track1.flac"))
        assertEquals("track2.mp3", q.stepped(1).current.name)
        assertEquals("track10.mp3", q.stepped(1).stepped(1).current.name)
        assertEquals("track1.flac", q.stepped(1).stepped(-1).current.name)
    }

    @Test fun the_ends_wrap_rather_than_going_dead() {
        val q = audioQueue(listing, file("track1.flac"))
        assertEquals("track10.mp3", q.stepped(-1).current.name)
        assertEquals("track1.flac", q.at(2).stepped(1).current.name)
    }

    @Test fun a_big_step_wraps_the_right_number_of_times() {
        val q = audioQueue(listing, file("track1.flac"))
        assertEquals(0, q.stepped(9).index)
        assertEquals(2, q.stepped(-10).index)
    }

    @Test fun tapping_a_row_in_the_queue_jumps_to_it_and_a_stale_row_is_ignored() {
        val q = audioQueue(listing, file("track1.flac"))
        assertEquals("track10.mp3", q.at(2).current.name)
        assertEquals(q, q.at(7))
        assertEquals(q, q.at(-1))
    }

    @Test fun a_track_that_turns_out_to_be_gone_is_dropped_without_losing_your_place() {
        val q = audioQueue(listing, file("track10.mp3"))
        val left = q.without(file("track2.mp3"))!!
        assertEquals(listOf("track1.flac", "track10.mp3"), names(left))
        assertEquals("track10.mp3", left.current.name)
    }

    @Test fun dropping_the_last_remaining_track_gives_nothing_rather_than_a_broken_queue() {
        val q = audioQueue(listOf(file("only.mp3")), file("only.mp3"))
        assertNull(q.without(file("only.mp3")))
    }

    @Test fun a_queue_cannot_be_built_empty_or_out_of_range() {
        assertTrue(runCatching { PlayQueue(emptyList(), 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(
            runCatching { PlayQueue(listOf(file("a.mp3")), 4) }.exceptionOrNull() is IllegalArgumentException
        )
    }
}

package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** All Music's sort: remembered as one string, flipped by a second tap, never stored as paths. */
class MusicSortTest {

    @Test fun every_sort_survives_a_round_trip() {
        for (k in MusicSortKey.entries) for (d in listOf(true, false)) {
            val s = MusicSort(k, d)
            assertEquals(s, MusicSort.decode(s.encode()))
        }
    }

    @Test fun anything_unreadable_falls_back_to_newest_added() {
        assertEquals(MusicSort(), MusicSort.decode(null))
        assertEquals(MusicSort(), MusicSort.decode(""))
        assertEquals(MusicSort(), MusicSort.decode("ARTIST:asc"))
        assertEquals(MusicSortKey.ADDED, MusicSort().key)
        assertTrue(MusicSort().descending)
    }

    @Test fun the_same_key_again_flips_direction() {
        val s = MusicSort(MusicSortKey.SIZE, true)
        assertFalse(s.pick(MusicSortKey.SIZE).descending)
        assertTrue(s.pick(MusicSortKey.SIZE).pick(MusicSortKey.SIZE).descending)
    }

    @Test fun a_new_key_starts_in_its_natural_direction() {
        assertFalse("A to Z first", MusicSort(MusicSortKey.ADDED, true).pick(MusicSortKey.NAME).descending)
        assertTrue("longest first", MusicSort(MusicSortKey.NAME, false).pick(MusicSortKey.DURATION).descending)
    }

    @Test fun the_five_required_keys_exist() {
        assertEquals(
            setOf("NAME", "ADDED", "MODIFIED", "SIZE", "DURATION"),
            MusicSortKey.entries.map { it.name }.toSet(),
        )
    }

    @Test fun all_music_is_not_a_saved_playlist() {
        // Built-ins in the stored blob are Favourites only; All Music cannot appear there to be
        // renamed or deleted, because it is never written.
        val lists = Playlists.ensureFavourites(emptyList())
        assertTrue(lists.none { it.id == AllMusic.ID })
    }
}

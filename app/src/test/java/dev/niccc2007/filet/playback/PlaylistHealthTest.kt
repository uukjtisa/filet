package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The off-by-N that made a playlist play the wrong song.
 *
 * Every case here is a list with gaps in it, because a list with no gaps was the only one ever
 * tested by hand and it is the one case that could not fail.
 */
class PlaylistHealthTest {

    private val stored = listOf("a", "b", "c", "d", "e")

    @Test fun `with nothing missing a tap plays what it hit`() {
        for ((i, id) in stored.withIndex()) {
            assertEquals(i, PlaylistHealth.startIndex(stored, stored, id))
        }
    }

    @Test fun `a gap above the tap does not shift it`() {
        // The defect, written down. "b" and "c" are deleted, the rows are still on screen, and
        // tapping "e" used to hand index 4 to a three-track queue and play nothing like it.
        val surviving = listOf("a", "d", "e")
        assertEquals(2, PlaylistHealth.startIndex(stored, surviving, "e"))
        assertEquals(1, PlaylistHealth.startIndex(stored, surviving, "d"))
        assertEquals(0, PlaylistHealth.startIndex(stored, surviving, "a"))
    }

    @Test fun `tapping a row whose file has gone plays the next one that is there`() {
        val surviving = listOf("a", "d", "e")
        assertEquals(1, PlaylistHealth.startIndex(stored, surviving, "b"))
        assertEquals(1, PlaylistHealth.startIndex(stored, surviving, "c"))
    }

    @Test fun `a gone row with nothing below it looks upwards instead of restarting the list`() {
        // Restarting at the top would be the easy answer and it is the wrong one: the tap was
        // at the bottom of the list and the first track is the furthest thing from it.
        val surviving = listOf("a", "b")
        assertEquals(1, PlaylistHealth.startIndex(stored, surviving, "e"))
    }

    @Test fun `an empty queue has nowhere to start`() {
        assertEquals(-1, PlaylistHealth.startIndex(stored, emptyList(), "a"))
    }

    @Test fun `no tap means the top, which is what play-the-whole-list asks for`() {
        assertEquals(0, PlaylistHealth.startIndex(stored, listOf("c", "d"), null))
    }

    @Test fun `an id from some other list falls back to the top rather than to minus one`() {
        assertEquals(0, PlaylistHealth.startIndex(stored, listOf("a", "b"), "zzz"))
    }

    @Test fun `a position is converted against the stored list, never the surviving one`() {
        val surviving = listOf("a", "d", "e")
        // Position 4 is "e". Read against `surviving` it would be out of bounds, and clamping
        // it - which is what the old code did - gives "e" by luck here and the wrong track for
        // every other arrangement.
        assertEquals(2, PlaylistHealth.startIndexAt(stored, surviving, 4))
        assertEquals(1, PlaylistHealth.startIndexAt(stored, surviving, 3))
        assertEquals(1, PlaylistHealth.startIndexAt(stored, surviving, 1))
        assertEquals(0, PlaylistHealth.startIndexAt(stored, surviving, 99))
    }

    @Test fun `only a file on this phone counts as deleted`() {
        assertTrue(PlaylistHealth.removable("local:///storage/emulated/0/Music/x.mp3"))
        assertTrue(PlaylistHealth.removable("root:///data/x.mp3"))
        assertTrue(PlaylistHealth.removable("saf:///tree/x.mp3"))
        // A share that is off is not a deletion, and an archive that will not open has not
        // lost its member.
        assertFalse(PlaylistHealth.removable("peer:///Music/x.mp3"))
        assertFalse(PlaylistHealth.removable("smb:///Music/x.mp3"))
        assertFalse(PlaylistHealth.removable("zip:///inner/x.mp3"))
        assertFalse(PlaylistHealth.removable("/storage/emulated/0/x.mp3"))
        assertFalse(PlaylistHealth.removable(""))
    }
}

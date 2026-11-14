package dev.niccc2007.filet.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the phone's own preview of a file is used instead of a decode, and at what size. */
class SystemThumbsTest {

    @Test fun `a row is used only while it still describes the file`() {
        val mtime = 1_763_100_000_123L
        assertTrue(SystemThumbRules.fresh(5_000, mtime / 1000, 5_000, mtime))
        // The store keeps seconds; a second either way is the same moment rounded.
        assertTrue(SystemThumbRules.fresh(5_000, mtime / 1000 + 1, 5_000, mtime))
        // Edited in place and not scanned again: the size or the time gives it away, and its
        // preview is of a picture that is gone.
        assertFalse(SystemThumbRules.fresh(5_000, mtime / 1000, 5_120, mtime))
        assertFalse(SystemThumbRules.fresh(5_000, mtime / 1000 - 60, 5_000, mtime))
    }

    @Test fun `a folder name is literal inside the pattern`() {
        assertEquals("/storage/emulated/0/100\\%\\_done", SystemThumbRules.likeEscape("/storage/emulated/0/100%_done"))
        assertEquals("/storage/emulated/0/a\\\\b", SystemThumbRules.likeEscape("/storage/emulated/0/a\\b"))
        assertEquals("/storage/emulated/0/DCIM/Camera", SystemThumbRules.likeEscape("/storage/emulated/0/DCIM/Camera"))
    }

    @Test fun `the box asked for leaves the short edge at the slot for a square`() {
        // A 4:3 photo for a 352 px tile: fitted in 470, its short edge is 352.
        assertEquals(470, ThumbSize.boxFor(4000, 3000, 352, square = true))
        // Shown whole, the long edge is what matters.
        assertEquals(352, ThumbSize.boxFor(4000, 3000, 352, square = false))
        // Unknown size: ask for the slot and let covers judge it.
        assertEquals(352, ThumbSize.boxFor(0, 0, 352, square = true))
        // A panorama so long the box would ask for the whole file: not asked at all.
        assertEquals(0, ThumbSize.boxFor(12_000, 1_000, 352, square = true))
    }

    @Test fun `a preview that would be stretched past a tenth is put back`() {
        // A 512 x 384 system preview fills a 352 square (short edge 384).
        assertTrue(ThumbSize.covers(512, 384, 352, square = true))
        // A tall screenshot's comes back narrow: 173 across cannot fill 352.
        assertFalse(ThumbSize.covers(173, 384, 352, square = true))
        // Within a tenth is under what a phone screen shows.
        assertTrue(ThumbSize.covers(320, 512, 352, square = true))
        assertFalse(ThumbSize.covers(0, 384, 352, square = true))
    }
}

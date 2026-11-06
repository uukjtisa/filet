package dev.niccc2007.filet.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gallery's Select: choosing with nothing chosen, and the hold left to the peek otherwise. */
class GallerySelectTest {

    @Test
    fun `at rest a tap opens and a hold is the peek`() {
        assertFalse(GallerySelect.tapPicks(choosing = false, picked = 0))
        assertFalse(GallerySelect.holdStartsRun(choosing = false, picked = 0))
        assertFalse(GallerySelect.cancelShown(choosing = false, picked = 0))
    }

    @Test
    fun `after Select, with nothing picked, a tap picks and a hold starts a run`() {
        assertTrue(GallerySelect.tapPicks(choosing = true, picked = 0))
        assertTrue(GallerySelect.holdStartsRun(choosing = true, picked = 0))
        assertTrue(GallerySelect.cancelShown(choosing = true, picked = 0))
        assertEquals("Select files", GallerySelect.headline(0))
    }

    @Test
    fun `once something is picked the gallery's own hold rules take over`() {
        // Extending the selection, or the peek on a tile already picked: see `longPressAction`.
        assertFalse(GallerySelect.holdStartsRun(choosing = true, picked = 1))
        assertTrue(GallerySelect.tapPicks(choosing = true, picked = 3))
    }

    @Test
    fun `a selection started the old way - the peek's Select - still shows Cancel and the count`() {
        assertTrue(GallerySelect.tapPicks(choosing = false, picked = 2))
        assertTrue(GallerySelect.cancelShown(choosing = false, picked = 2))
        assertFalse("a hold extends it, as before", GallerySelect.holdStartsRun(choosing = false, picked = 2))
    }

    @Test
    fun `the count reads as words`() {
        assertEquals("1 selected", GallerySelect.headline(1))
        assertEquals("12 selected", GallerySelect.headline(12))
    }
}

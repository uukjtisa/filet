package dev.niccc2007.filet.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** The size a preview is decoded at, and which kept previews go when the disk cache is full. */
class ThumbSizeTest {

    @Test fun `a slot is decoded at the screen's own density, in 32 px steps`() {
        // A 128 dp gallery tile on a 2.75 density screen is 352 pixels: decoded at 352, not 320.
        assertEquals(352, ThumbSize.forSlot(128f, 2.75f))
        // A few dp either way shares the cached picture.
        assertEquals(ThumbSize.forSlot(126f, 2.75f), ThumbSize.forSlot(127f, 2.75f))
        assertEquals(ThumbSize.MIN, ThumbSize.forSlot(4f, 2.75f))
        assertEquals(ThumbSize.MAX, ThumbSize.forSlot(900f, 2.75f))
    }

    @Test fun `a square preview is measured on its short edge, a whole one on its long edge`() {
        // A 4000 x 3000 photo for a 352 px square: the short edge must stay at or above 352.
        val s = ThumbSize.sample(4000, 3000, 352, square = true)
        assertEquals(8, s)
        assertEquals(true, 3000 / s >= 352)
        // The same photo shown whole at 1080: the long edge stays at or above 1080.
        assertEquals(2, ThumbSize.sample(4000, 3000, 1080, square = false))
        assertEquals(1, ThumbSize.sample(0, 3000, 352, square = true))
    }

    @Test fun `scaled fills the square from the short edge and never enlarges`() {
        // Bug this replaces: the long edge was brought to the slot, leaving the short edge at
        // three-quarters of it, stretched again by the tile's crop.
        assertEquals(469 to 352, ThumbSize.scaled(1000, 750, 352, square = true))
        assertEquals(352 to 264, ThumbSize.scaled(1000, 750, 352, square = false))
        assertEquals(300 to 200, ThumbSize.scaled(300, 200, 352, square = true))
    }

    @Test fun `the crop keeps the middle square`() {
        assertEquals(Triple(58, 0, 352), ThumbSize.crop(469, 352))
        assertEquals(Triple(0, 100, 300), ThumbSize.crop(300, 500))
        assertEquals(Triple(0, 0, 64), ThumbSize.crop(64, 64))
    }

    @Test fun `the disk cache sheds the longest unread first, down to nine-tenths of its limit`() {
        val files = listOf(
            ThumbTrim.Kept("new", 40, readAt = 300),
            ThumbTrim.Kept("old", 40, readAt = 100),
            ThumbTrim.Kept("mid", 40, readAt = 200),
        )
        assertEquals(emptyList<String>(), ThumbTrim.drop(files, limit = 120))
        // 120 against a limit of 100: down to 90, and the oldest alone gets it there.
        assertEquals(listOf("old"), ThumbTrim.drop(files, limit = 100))
        // Against 60: down to 54, which takes the two read longest ago.
        assertEquals(listOf("old", "mid"), ThumbTrim.drop(files, limit = 60))
    }
}

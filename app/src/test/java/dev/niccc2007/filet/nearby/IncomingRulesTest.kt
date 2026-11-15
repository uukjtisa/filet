package dev.niccc2007.filet.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a send from another Filet asks, and what its card says. */
class IncomingRulesTest {

    private val card = IncomingSettings()
    private fun size(n: Long) = "$n B"
    private fun f(name: String, size: Long = 10, dir: Boolean = false) = PushFile(name, size, dir)

    @Test
    fun `on screen it is Filet's own dialogue whatever the setting`() {
        assertEquals(AskSurface.IN_APP, IncomingRules.surface(inFront = true, canOverlay = true, card, 3))
        assertEquals(AskSurface.IN_APP, IncomingRules.surface(inFront = true, canOverlay = false, card.copy(ask = AskStyle.NOTICE), 1))
    }

    @Test
    fun `away, several files get the side card and one file the mini card`() {
        assertEquals(AskSurface.SIDE_CARD, IncomingRules.surface(false, true, card, 3))
        assertEquals(AskSurface.MINI_CARD, IncomingRules.surface(false, true, card, 1))
        assertEquals(AskSurface.SIDE_CARD, IncomingRules.surface(false, true, card.copy(oneFile = OneFileStyle.SAME), 1))
    }

    @Test
    fun `without leave to draw over other apps it is a notification, never a card that cannot show`() {
        assertEquals(AskSurface.NOTIFICATION, IncomingRules.surface(false, canOverlay = false, card, 3))
        assertEquals(AskSurface.NOTIFICATION, IncomingRules.surface(false, canOverlay = false, card, 1))
        assertEquals(AskSurface.NOTIFICATION, IncomingRules.surface(false, true, card.copy(ask = AskStyle.NOTICE), 1))
    }

    @Test
    fun `the summary names the first file, the rest by kind, and the size`() {
        assertEquals("IMG_1.jpg · 10 B", IncomingRules.summary(listOf(f("IMG_1.jpg")), ::size))
        assertEquals(
            "IMG_1.jpg, a video and notes.md · 30 B",
            IncomingRules.summary(listOf(f("IMG_1.jpg"), f("VID.mp4"), f("notes.md")), ::size),
        )
        assertEquals("a.jpg and 4 more · 50 B", IncomingRules.summary((1..5).map { f("a.jpg") }, ::size))
    }

    @Test
    fun `a folder arrives as its zip and adds no size it does not know`() {
        assertEquals("Trip.zip", IncomingRules.summary(listOf(f("Trip", size = 0, dir = true)), ::size))
        assertEquals("a.jpg and a folder · 10 B", IncomingRules.summary(listOf(f("a.jpg"), f("Trip", 999, dir = true)), ::size))
    }

    @Test
    fun `three tiles at most, the third standing for the rest`() {
        assertEquals(1, IncomingRules.tiles(1))
        assertEquals(3, IncomingRules.tiles(7))
        assertEquals(0, IncomingRules.moreOnLast(3))
        assertEquals(5, IncomingRules.moreOnLast(7))
    }

    @Test
    fun `pictures and videos are asked for a thumbnail, others are not`() {
        assertTrue(IncomingRules.hasThumb("IMG.JPG"))
        assertTrue(IncomingRules.hasThumb("clip.mp4"))
        assertFalse(IncomingRules.hasThumb("notes.md"))
        assertFalse(IncomingRules.hasThumb("song.mp3"))
    }

    @Test
    fun `the badge is the first letter or digit`() {
        assertEquals("N", IncomingRules.initial("nova 10"))
        assertEquals("7", IncomingRules.initial(" 7d"))
        assertEquals("?", IncomingRules.initial("--"))
    }

    @Test
    fun `stored values read back, and anything unknown is the default`() {
        assertEquals(AskStyle.NOTICE, AskStyle.of("notice"))
        assertEquals(AskStyle.CARD, AskStyle.of(null))
        assertEquals(AskStyle.CARD, AskStyle.of("dialog"))
        assertEquals(OneFileStyle.SAME, OneFileStyle.of("same"))
        assertEquals(OneFileStyle.MINI, OneFileStyle.of("?"))
    }

    @Test
    fun `the Share page row says whether it is ready and how it asks`() {
        assertEquals("Only while Filet is open · asks with a side card", IncomingWords.row(card, overlay = true))
        assertEquals("Ready while closed · asks with a notification", IncomingWords.row(card.copy(ready = true), overlay = false))
    }
}

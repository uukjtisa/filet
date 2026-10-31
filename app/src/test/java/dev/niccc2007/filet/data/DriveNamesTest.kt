package dev.niccc2007.filet.data

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveNamesTest {

    private val card = DriveNames.idOf(VPath("local", "/storage/1A2B-3C4D"))
    private val phone = DriveNames.idOf(VPath("local", "/storage/emulated/0"))

    @Test
    fun `a given name wins over the system name`() =
        assertEquals("Music card", DriveNames.resolve(mapOf(card to "Music card"), card, "SD card"))

    @Test
    fun `a blank name is the system name`() {
        assertEquals("SD card", DriveNames.resolve(mapOf(card to ""), card, "SD card"))
        assertEquals("SD card", DriveNames.resolve(mapOf(card to "   "), card, "SD card"))
    }

    @Test
    fun `an unknown id is the system name`() =
        assertEquals("Internal storage", DriveNames.resolve(mapOf(card to "Music card"), phone, "Internal storage"))

    @Test
    fun `the id is the root, so a card is the same drive every time it goes back in`() {
        assertEquals(card, DriveNames.idOf(VPath("local", "/storage/1A2B-3C4D")))
        assertFalse(card == phone)
    }

    @Test
    fun `renaming to blank removes the entry rather than storing an empty name`() {
        val named = DriveNames.renamed(emptyMap(), card, "  Music card ")
        assertEquals(mapOf(card to "Music card"), named)
        assertTrue(DriveNames.renamed(named, card, " ").isEmpty())
    }

    @Test
    fun `names survive the trip through the stored JSON`() {
        val names = mapOf(card to "Music card", phone to "Phone")
        assertEquals(names, DriveNames.parse(DriveNames.encode(names)))
    }

    @Test
    fun `an unreadable stored value is no names, never a crash`() {
        assertTrue(DriveNames.parse(null).isEmpty())
        assertTrue(DriveNames.parse("").isEmpty())
        assertTrue(DriveNames.parse("not json").isEmpty())
        assertTrue(DriveNames.parse("""{"a":""}""").isEmpty())
    }
}

package dev.niccc2007.filet.vfs.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveNamingTest {

    private val zip = Archives.creatable.first { it.id.contains("zip", ignoreCase = true) && !it.id.contains("7") }
    private val sevenZip = Archives.creatable.first { it.suffix == ".7z" }
    private val targz = Archives.creatable.first { it.suffix == ".tar.gz" }

    @Test
    fun `a suffix of your own is kept`() {
        // The whole point. A 7z called .mcaddon is a real thing somebody ships, and the old
        // code could not express it at all.
        assertEquals(".mcaddon", ArchiveNaming.cleanSuffix("mcaddon"))
        assertEquals(".mcaddon", ArchiveNaming.cleanSuffix(".mcaddon"))
        assertEquals(".mcaddon", ArchiveNaming.cleanSuffix("  .mcaddon  "))
        assertEquals("addon.mcaddon", ArchiveNaming.fileName("addon", ".mcaddon") { false })
    }

    @Test
    fun `no extension at all is a legitimate answer`() {
        assertEquals("", ArchiveNaming.cleanSuffix(""))
        assertEquals("", ArchiveNaming.cleanSuffix("  . "))
        assertEquals("Archive", ArchiveNaming.fileName("Archive", "") { false })
        assertNotNull(ArchiveNaming.mismatchNote("", sevenZip))
    }

    @Test
    fun `separators and spaces cannot get into a name`() {
        assertEquals(".tar", ArchiveNaming.cleanSuffix("/ta r\\"))
        assertEquals("Photos", ArchiveNaming.cleanBase("Pho/to\\s"))
        assertEquals("Archive", ArchiveNaming.cleanBase("   "))
    }

    @Test
    fun `an absurd suffix is cut rather than accepted whole`() {
        assertEquals(ArchiveNaming.MAX_SUFFIX + 1, ArchiveNaming.cleanSuffix("x".repeat(80)).length)
    }

    @Test
    fun `the field follows the format while it still holds a default`() {
        // The stale-extension case the old behaviour existed to prevent, now solved by keeping
        // the field right instead of by overruling it at the end.
        assertEquals(targz.suffix, ArchiveNaming.suffixWhenFormatChanges(".zip", targz))
        assertEquals(sevenZip.suffix, ArchiveNaming.suffixWhenFormatChanges(".tar.gz", sevenZip))
        assertEquals(zip.suffix, ArchiveNaming.suffixWhenFormatChanges(".7z", zip))
    }

    @Test
    fun `a suffix somebody typed survives changing the format`() {
        assertEquals(".mcaddon", ArchiveNaming.suffixWhenFormatChanges(".mcaddon", targz))
        assertEquals(".mcaddon", ArchiveNaming.suffixWhenFormatChanges(".mcaddon", sevenZip))
        // Including an emptied field, which says "no extension" and is not a stale default.
        assertEquals("", ArchiveNaming.suffixWhenFormatChanges("", sevenZip))
    }

    @Test
    fun `a matching suffix says nothing and a mismatched one says what will happen`() {
        assertNull(ArchiveNaming.mismatchNote(".7z", sevenZip))
        assertNull(ArchiveNaming.mismatchNote("7Z", sevenZip))
        assertTrue(ArchiveNaming.matchesFormat(".zip", zip))
        assertFalse(ArchiveNaming.matchesFormat(".zip", sevenZip))
        val note = ArchiveNaming.mismatchNote(".mcaddon", sevenZip)
        assertNotNull(note)
        // It names the encoding, because that is the half that does not follow the name.
        assertTrue(note!!.contains(sevenZip.label))
        assertTrue(note.contains(".mcaddon"))
    }

    @Test
    fun `every alternative extension a format declares counts as matching`() {
        // tar.gz also answers to .tgz, and warning about a name the format itself lists would
        // be the check crying wolf.
        for (f in Archives.creatable) {
            for (e in f.extensions) {
                assertNull("$e on ${f.label}", ArchiveNaming.mismatchNote(".$e", f))
            }
        }
    }

    @Test
    fun `an existing name is never overwritten`() {
        val there = setOf("Photos.zip", "Photos (2).zip")
        assertEquals("Photos (3).zip", ArchiveNaming.fileName("Photos", ".zip") { it in there })
        assertEquals("Photos.7z", ArchiveNaming.fileName("Photos", ".7z") { it in there })
    }

    @Test
    fun `the numbering goes before the extension, not after it`() {
        // "Photos.zip (2)" is a file nothing will open.
        val name = ArchiveNaming.fileName("Photos", ".tar.gz") { it == "Photos.tar.gz" }
        assertTrue(name, name.endsWith(".tar.gz"))
    }
}

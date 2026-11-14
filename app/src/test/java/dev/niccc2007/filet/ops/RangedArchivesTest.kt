package dev.niccc2007.filet.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which zips on a share are read in place, and what a stand-in for one holds. */
class RangedArchivesTest {

    private val mb = 1024L * 1024

    @Test
    fun `a zip of two megabytes or more is read in place, whatever the zip family`() {
        for (name in listOf("backup.zip", "app.apk", "lib.jar")) {
            assertTrue(name, RangedArchives.worthIt(name, 2 * mb))
            assertTrue(name, RangedArchives.worthIt(name, 4L * 1024 * mb))
        }
    }

    @Test
    fun `a small zip is fetched whole, and an unknown size is never ranged`() {
        assertFalse(RangedArchives.worthIt("small.zip", 2 * mb - 1))
        assertFalse(RangedArchives.worthIt("small.zip", -1L))
    }

    @Test
    fun `an archive that has to be read from the front is copied as before`() {
        for (name in listOf("photos.tar.gz", "photos.tar", "photos.7z", "notes.txt", "film.mkv")) {
            assertFalse(name, RangedArchives.worthIt(name, 500 * mb))
        }
    }

    @Test
    fun `a stand-in keeps where the zip is, its size and its time`() {
        val s = RangedArchives.StandIn("webdav://desk/share/backup.zip", 3_000_000_000L, 1_794_600_000_000L)
        assertEquals(s, RangedArchives.StandIn.decode(s.encode()))
        assertNull(RangedArchives.StandIn.decode("not json"))
        assertNull(RangedArchives.StandIn.decode("""{"path":"","size":10,"mtime":0}"""))
        assertNull(RangedArchives.StandIn.decode("""{"path":"webdav://a/b.zip","size":-1,"mtime":0}"""))
    }

    @Test
    fun `a zip changed on the far end gets a stand-in of its own`() {
        val key = RangedArchives.keyOf("webdav://desk/a.zip", 10, 20)
        assertEquals(key, RangedArchives.keyOf("webdav://desk/a.zip", 10, 20))
        assertNotEquals(key, RangedArchives.keyOf("webdav://desk/a.zip", 11, 20))
        assertNotEquals(key, RangedArchives.keyOf("webdav://desk/a.zip", 10, 21))
        assertNotEquals(key, RangedArchives.keyOf("webdav://desk/b.zip", 10, 20))
        assertTrue(key.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `a stand-in is named as the zip, with what a memory card refuses replaced`() {
        assertEquals("Holiday 2026.zip", RangedArchives.StandIn.fileName("Holiday 2026.zip"))
        assertEquals("a_b_c_.zip", RangedArchives.StandIn.fileName("a:b?c*.zip"))
        assertEquals("archive.zip", RangedArchives.StandIn.fileName(""))
    }
}

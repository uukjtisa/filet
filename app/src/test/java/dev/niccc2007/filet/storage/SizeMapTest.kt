package dev.niccc2007.filet.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One walk's totals: what every folder holds, what is largest, and what a delete takes off. */
class SizeMapTest {

    private val root = "/storage/emulated/0"

    private fun walked() = SizeMap(root).apply {
        addFolder("$root/Download")
        addFolder("$root/Download/Movies")
        addFile("$root/Download/Movies/night.mkv", 4_000, SizeKind.VIDEOS)
        addFile("$root/Download/Movies/concert.mp4", 3_000, SizeKind.VIDEOS)
        addFile("$root/Download/notes.zip", 90, SizeKind.ARCHIVES)
        addFile("$root/photo.jpg", 10, SizeKind.PICTURES)
    }

    @Test fun `a file adds to every folder above it, up to the root and no further`() {
        val m = walked()
        assertEquals(7_000L, m.bytes("$root/Download/Movies"))
        assertEquals(7_090L, m.bytes("$root/Download"))
        assertEquals(7_100L, m.bytes(root))
        assertEquals(4, m.files(root))
        assertEquals(2, m.folders(root))
        // Outside the root: counted nowhere rather than somewhere wrong.
        m.addFile("/storage/1234-ABCD/x.bin", 50, SizeKind.OTHER)
        assertEquals(7_100L, m.bytes(root))
    }

    @Test fun `a total is a floor until its folder is settled`() {
        val m = walked()
        assertFalse(m.settled("$root/Download"))
        m.settle("$root/Download/Movies")
        assertTrue(m.settled("$root/Download/Movies"))
        assertFalse(m.settled("$root/Download"))
        // Never reached: no figure at all, not a zero that reads as empty.
        assertNull(m.bytes("$root/DCIM"))
    }

    @Test fun `the largest files are kept largest first, and found under any folder`() {
        val m = walked()
        assertEquals(listOf("night.mkv", "concert.mp4", "notes.zip"), m.largestUnder("$root/Download").map { it.path.substringAfterLast('/') })
        assertEquals(listOf("night.mkv"), m.largestUnder(root, limit = 1).map { it.path.substringAfterLast('/') })
        // A cap of two drops the smallest, not the latest.
        val small = SizeMap(root, keepLargest = 2).apply {
            addFile("$root/a", 5, SizeKind.OTHER)
            addFile("$root/b", 9, SizeKind.OTHER)
            addFile("$root/c", 1, SizeKind.OTHER)
            addFile("$root/d", 7, SizeKind.OTHER)
        }
        assertEquals(listOf(9L, 7L), small.largestUnder(root).map { it.bytes })
    }

    @Test fun `kinds are summed per folder, largest first, empty ones left out`() {
        val m = walked()
        assertEquals(listOf(SizeKind.VIDEOS to 7_000L, SizeKind.ARCHIVES to 90L), m.kinds("$root/Download"))
        assertEquals(SizeKind.PICTURES to 10L, m.kinds(root).last())
    }

    @Test fun `a deleted folder takes its whole size off every folder above it at once`() {
        val m = walked()
        m.remove("$root/Download/Movies")
        assertEquals(90L, m.bytes("$root/Download"))
        assertEquals(100L, m.bytes(root))
        assertEquals(1, m.folders(root))
        assertNull(m.bytes("$root/Download/Movies"))
        assertEquals(listOf(SizeKind.ARCHIVES to 90L), m.kinds("$root/Download"))
        assertTrue(m.largestUnder(root).none { it.path.contains("/Movies/") })
    }

    @Test fun `a deleted file comes off too, by what is remembered or what the list says`() {
        val m = walked()
        m.remove("$root/Download/Movies/night.mkv")
        assertEquals(3_000L, m.bytes("$root/Download/Movies"))
        m.removeFile("$root/photo.jpg", 10, SizeKind.PICTURES)
        assertEquals(3_090L, m.bytes(root))
    }

    @Test fun `a root with its scheme, or the filesystem's own root, still counts`() {
        val scheme = SizeMap("file:///").apply { addFile("file:///a/b.bin", 5, SizeKind.OTHER) }
        assertEquals(5L, scheme.bytes("file:///"))
        assertEquals(5L, scheme.bytes("file:///a"))
        val slash = SizeMap("/").apply { addFile("/a/b.bin", 5, SizeKind.OTHER) }
        assertEquals(5L, slash.bytes("/"))
        assertEquals("/x", SizeMap.norm("/x/"))
        assertEquals("file:///", SizeMap.norm("file:///"))
    }
}

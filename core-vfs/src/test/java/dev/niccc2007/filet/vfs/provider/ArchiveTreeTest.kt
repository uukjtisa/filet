package dev.niccc2007.filet.vfs.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** An archive's members as folders, and the table of contents read once. See [ArchiveTree]. */
class ArchiveTreeTest {

    private fun file(name: String, size: Long = 1L, mtime: Long = 0L) = Member(name, false, size, mtime)
    private fun dir(name: String, mtime: Long = 0L) = Member(name, true, -1L, mtime)
    private fun names(t: ArchiveTree, folder: String) = t.children(folder).map { it.name }

    @Test
    fun `folders that only appear inside names are listed - a zip stores none`() {
        val t = ArchiveTree(listOf(file("a/b/c.txt"), file("a/d.txt"), file("top.txt")))
        assertEquals(listOf("a", "top.txt"), names(t, ""))
        assertEquals(listOf("b", "d.txt"), names(t, "a"))
        assertEquals(listOf("c.txt"), names(t, "a/b"))
        assertTrue(t.children("a").first().isDir)
    }

    @Test
    fun `a folder stored as an entry is not listed twice - a tar stores them`() {
        val t = ArchiveTree(listOf(dir("a"), file("a/x"), dir("a/b"), file("a/b/y")))
        assertEquals(listOf("a"), names(t, ""))
        assertEquals(listOf("x", "b"), names(t, "a"))
    }

    @Test
    fun `an implicit folder takes the time of the first member that showed it`() {
        val t = ArchiveTree(listOf(file("a/one", mtime = 5L), file("a/two", mtime = 9L)))
        assertEquals(5L, t.children("").single().mtime)
    }

    @Test
    fun `a file replaces a folder of the same name listed before it, in its place`() {
        val t = ArchiveTree(listOf(file("n/inner"), file("m"), file("n", size = 42L)))
        assertEquals(listOf("n", "m"), names(t, ""))
        val n = t.children("").first()
        assertFalse(n.isDir)
        assertEquals(42L, n.size)
    }

    @Test
    fun `the member under a name is the first one stored`() {
        val t = ArchiveTree(listOf(file("same", size = 1L), file("same", size = 2L)))
        assertEquals(1L, t.member("same")?.size)
        assertNull(t.member("missing"))
    }

    @Test
    fun `a folder is a folder when something is under it, entry or not`() {
        val t = ArchiveTree(listOf(file("a/b/c")))
        assertTrue(t.isFolder("a"))
        assertTrue(t.isFolder("a/b"))
        assertTrue(t.isFolder("/a/b/"))
        assertFalse(t.isFolder("a/b/c"))
        assertFalse(t.isFolder("nope"))
    }

    @Test
    fun `an empty name and empty segments are skipped`() {
        val t = ArchiveTree(listOf(file(""), file("a//b")))
        assertEquals(listOf("a"), names(t, ""))
        assertEquals(listOf("b"), names(t, "a"))
    }

    // ── read once ──

    private fun temp(bytes: Int): File = Files.createTempFile("tree", ".zip").toFile().apply {
        writeBytes(ByteArray(bytes))
        deleteOnExit()
    }

    @Test
    fun `the table is read once while the file is unchanged`() {
        val host = temp(10)
        val listings = ArchiveListings()
        var reads = 0
        val read = { reads++; listOf(file("a/b")) }
        val first = listings.of(host, read)
        val second = listings.of(host, read)
        assertEquals(1, reads)
        assertSame(first, second)
    }

    @Test
    fun `a file changed by anything else is read again`() {
        val host = temp(10)
        val listings = ArchiveListings()
        var reads = 0
        listings.of(host) { reads++; listOf(file("x")) }
        host.writeBytes(ByteArray(20))
        listings.of(host) { reads++; listOf(file("x")) }
        assertEquals(2, reads)
    }

    @Test
    fun `Filet's own write forgets it outright`() {
        val host = temp(10)
        val listings = ArchiveListings()
        var reads = 0
        listings.of(host) { reads++; emptyList() }
        listings.forget(host)
        listings.of(host) { reads++; emptyList() }
        assertEquals(2, reads)
    }

    @Test
    fun `a file that is not there is read every time and nothing is kept`() {
        val gone = File(System.getProperty("java.io.tmpdir"), "no-such-archive-${System.nanoTime()}.zip")
        val listings = ArchiveListings()
        var reads = 0
        listings.of(gone) { reads++; emptyList() }
        listings.of(gone) { reads++; emptyList() }
        assertEquals(2, reads)
    }

    @Test
    fun `held to a total of members, the newest always kept`() {
        val listings = ArchiveListings(maxMembers = 3)
        val a = temp(1)
        val b = temp(2)
        var readsA = 0
        listings.of(a) { readsA++; List(2) { file("a$it") } }
        // Past the limit together: the eldest goes, the newest stays even alone over it.
        listings.of(b) { List(5) { file("b$it") } }
        var readsB = 0
        listings.of(b) { readsB++; emptyList() }
        assertEquals(0, readsB)
        listings.of(a) { readsA++; List(2) { file("a$it") } }
        assertEquals(2, readsA)
    }
}

package dev.niccc2007.filet.webdav

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The flat views, and the name collisions flattening creates. */
class DavViewsTest {

    private fun e(path: String, size: Long = 1000, mtime: Long = 1000) =
        DavViews.Entry(
            VPath.parse("local:///storage/emulated/0/$path"),
            path.substringAfterLast('/'),
            size,
            mtime,
        )

    private fun names(l: List<DavViews.Entry>) = l.map { it.name }

    // ── what goes in which view ───────────────────────────────────────────────────────

    @Test
    fun `files are sorted into views by extension`() {
        assertTrue(DavViews.matches(DavViews.Kind.IMAGES, "a.JPG", 1))
        assertTrue(DavViews.matches(DavViews.Kind.VIDEOS, "clip.mkv", 1))
        assertTrue(DavViews.matches(DavViews.Kind.AUDIO, "song.opus", 1))
        assertTrue(DavViews.matches(DavViews.Kind.DOCUMENTS, "notes.md", 1))
        assertTrue(DavViews.matches(DavViews.Kind.ARCHIVES, "a.7z", 1))
        assertTrue(DavViews.matches(DavViews.Kind.APPS, "app.xapk", 1))
        assertFalse(DavViews.matches(DavViews.Kind.IMAGES, "notes.txt", 1))
    }

    @Test
    fun `recent takes everything and large takes only what is large`() {
        assertTrue(DavViews.matches(DavViews.Kind.RECENT, "anything.xyz", 1))
        assertFalse(DavViews.matches(DavViews.Kind.LARGE, "small.mp4", 1024))
        assertTrue(DavViews.matches(DavViews.Kind.LARGE, "big.mp4", DavViews.LARGE_THRESHOLD))
    }

    @Test
    fun `recent is newest first and large is biggest first`() {
        val all = listOf(e("a.txt", 10, 100), e("b.txt", 30, 300), e("c.txt", 20, 200))
        assertEquals(listOf("b.txt", "c.txt", "a.txt"), names(DavViews.order(DavViews.Kind.RECENT, all)))
        assertEquals(listOf("b.txt", "c.txt", "a.txt"), names(DavViews.order(DavViews.Kind.LARGE, all)))
    }

    @Test
    fun `a view is capped so a huge one cannot hang the window`() {
        val many = (1..2000).map { e("Photos/img$it.jpg", 1, it.toLong()) }
        assertEquals(DavViews.LIMIT, DavViews.build(DavViews.Kind.IMAGES, many).size)
    }

    // ── the collisions flattening creates ─────────────────────────────────────────────

    @Test
    fun `a name that does not collide is left exactly as it is`() {
        val out = DavViews.build(DavViews.Kind.DOCUMENTS, listOf(e("A/notes.txt"), e("B/other.txt")))
        assertTrue("notes.txt" in names(out))
        assertTrue("other.txt" in names(out))
    }

    @Test
    fun `identical names from different folders are told apart`() {
        // Without this, Explorer shows one of the three and the other two are unreachable.
        val out = DavViews.build(
            DavViews.Kind.DOCUMENTS,
            listOf(e("Work/notes.txt", 1, 3), e("Home/notes.txt", 1, 2), e("Trip/notes.txt", 1, 1)),
        )
        assertEquals(3, out.size)
        assertEquals(3, names(out).toSet().size)
        assertTrue(names(out).all { it.startsWith("notes (") })
        assertTrue(names(out).all { it.endsWith(".txt") })
    }

    @Test
    fun `the suffix names the folder, which is what flattening threw away`() {
        val out = DavViews.build(
            DavViews.Kind.DOCUMENTS,
            listOf(e("Work/notes.txt"), e("Home/notes.txt")),
        )
        assertTrue("notes (Work).txt" in names(out))
        assertTrue("notes (Home).txt" in names(out))
    }

    @Test
    fun `two files with the same name in same-named folders still differ`() {
        val out = DavViews.build(
            DavViews.Kind.DOCUMENTS,
            listOf(e("a/Work/notes.txt"), e("b/Work/notes.txt")),
        )
        assertEquals(2, names(out).toSet().size)
    }

    @Test
    fun `a file with no extension survives disambiguation`() {
        val out = DavViews.build(
            DavViews.Kind.RECENT,
            listOf(e("A/README"), e("B/README")),
        )
        assertEquals(2, names(out).toSet().size)
        assertTrue(names(out).none { it.endsWith(".") })
    }

    @Test
    fun `every name in a built view is unique`() {
        val all = (1..50).map { e("F${it % 5}/same.jpg", 1, it.toLong()) }
        val out = DavViews.build(DavViews.Kind.IMAGES, all)
        assertEquals(out.size, names(out).toSet().size)
    }

    // ── reading a path ────────────────────────────────────────────────────────────────

    @Test
    fun `the views root lists the views`() {
        assertTrue(DavViews.isRoot("~Find"))
        assertFalse(DavViews.isRoot("~Find/Images"))
        assertFalse(DavViews.isRoot(""))
    }

    @Test
    fun `a view folder is recognised, case insensitively`() {
        assertEquals(DavViews.Kind.IMAGES, DavViews.viewOf("~Find/Images"))
        assertEquals(DavViews.Kind.IMAGES, DavViews.viewOf("~Find/images"))
        assertEquals(DavViews.Kind.LARGE, DavViews.viewOf("~Find/Large files"))
    }

    @Test
    fun `a real folder that happens to share a name is not a view`() {
        // The views live under a prefix precisely so a real folder called Images stays real.
        assertNull(DavViews.viewOf("Images"))
        assertNull(DavViews.viewOf("Photos/Images"))
        assertNull(DavViews.viewOf("~Find"))
        assertNull(DavViews.viewOf("~Find/Nonsense"))
    }

    @Test
    fun `a file inside a view is read as a file`() {
        assertEquals("a.jpg", DavViews.fileIn("~Find/Images/a.jpg"))
        assertNull(DavViews.fileIn("~Find/Images"))
        assertNull(DavViews.fileIn("Photos/a.jpg"))
    }

    @Test
    fun `every view has a distinct folder name`() {
        val folders = DavViews.Kind.entries.map { it.folder.lowercase() }
        assertEquals(folders.size, folders.toSet().size)
    }
}

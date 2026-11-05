package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** A folder selected with things inside it: what the selection holds, and what acts. */
class TreeSelectionTest {

    private fun p(path: String) = VPath.of("local", "/sdcard/$path")
    private fun node(path: String, dir: Boolean = false) = VNode(p(path), dir, 1, 0)

    @Test
    fun `selecting a folder absorbs what was selected inside it`() {
        val before = setOf(p("DCIM/a.jpg"), p("DCIM/Trip/b.jpg"), p("Music/c.mp3"))
        val after = TreeSelection.toggle(before, p("DCIM"))!!
        // The folder once, and the file outside it kept.
        assertEquals(setOf(p("Music/c.mp3"), p("DCIM")), after)
    }

    @Test
    fun `deselecting the folder leaves nothing of its contents behind`() {
        val held = TreeSelection.toggle(setOf(p("DCIM/a.jpg")), p("DCIM"))!!
        assertEquals(emptySet<VPath>(), TreeSelection.toggle(held, p("DCIM")))
    }

    @Test
    fun `a row already included cannot be picked on its own`() {
        val held = setOf(p("DCIM"))
        assertNull(TreeSelection.toggle(held, p("DCIM/Trip/b.jpg")))
        assertEquals(p("DCIM"), TreeSelection.includedBy(held, p("DCIM/Trip/b.jpg")))
        // A sibling whose name only starts the same is not inside it.
        assertNull(TreeSelection.includedBy(held, p("DCIM2/x.jpg")))
        assertNull(TreeSelection.includedBy(held, p("DCIM")))
    }

    @Test
    fun `absorb keeps the same set when nothing is nested`() {
        val flat = setOf(p("a.jpg"), p("b.jpg"))
        assertSame(flat, TreeSelection.absorb(flat))
        // Nested two deep: only the outermost survives.
        assertEquals(setOf(p("A")), TreeSelection.absorb(setOf(p("A/B/c"), p("A/B"), p("A"))))
    }

    @Test
    fun `invert treats included rows as picked`() {
        val rows = listOf(p("DCIM"), p("DCIM/a.jpg"), p("Music"), p("Music/c.mp3"))
        val inverted = TreeSelection.invert(rows, setOf(p("DCIM")))
        // Music and its file both unpicked before, so the folder takes its file in.
        assertEquals(setOf(p("Music")), inverted)
    }

    @Test
    fun `resolve finds a file inside a branch, not only the pane's own rows`() {
        val root = listOf(node("DCIM", dir = true), node("notes.txt"))
        val branch = listOf(node("DCIM/a.jpg"))
        val picked = TreeSelection.resolve(setOf(p("DCIM/a.jpg"), p("notes.txt")), root, branch)
        assertEquals(setOf("a.jpg", "notes.txt"), picked.map { it.name }.toSet())
        // And the file is a file, which is what decides the actions offered for it.
        assertEquals(false, picked.first { it.name == "a.jpg" }.isDir)
        assertEquals(emptyList<VNode>(), TreeSelection.resolve(setOf(p("gone.txt")), root, branch))
    }

    @Test
    fun `the common folder is the one every file sits directly in`() {
        val camera = listOf(p("DCIM/Camera/a.jpg"), p("DCIM/Camera/b.jpg"))
        assertEquals(p("DCIM/Camera"), TreeSelection.commonFolder(camera))
        assertEquals(p("DCIM/Camera"), TreeSelection.commonFolder(camera.take(1)))
    }

    @Test
    fun `files spread over folders have no common folder, and nothing has none`() {
        // A parent of both is not an answer: an archive "beside" these would land where neither is.
        assertNull(TreeSelection.commonFolder(listOf(p("DCIM/Camera/a.jpg"), p("DCIM/Screenshots/b.png"))))
        assertNull(TreeSelection.commonFolder(listOf(p("DCIM/a.jpg"), p("DCIM/Camera/b.jpg"))))
        assertNull(TreeSelection.commonFolder(emptyList()))
    }
}

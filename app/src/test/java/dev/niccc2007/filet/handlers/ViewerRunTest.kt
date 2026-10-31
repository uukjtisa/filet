package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which files a viewer swipes through, and what its counter says. */
class ViewerRunTest {

    private fun file(name: String, dir: Boolean = false) = VNode(VPath.of("local", "/sdcard/DCIM/$name"), dir, 1, 0)

    private val media = setOf("jpg", "png", "mp4")
    private val inside: (VNode) -> Boolean = { it.name.substringAfterLast('.') in media }

    @Test
    fun `pictures and videos in the list's own order, nothing else`() {
        val list = listOf(
            file("Trip", dir = true),
            file("b.jpg"),
            file("notes.pdf"),
            file("c.mp4"),
            file("song.mp3"),
            file("a.png"),
        )
        val run = ViewerRun.of(list, file("c.mp4"), inside)!!
        assertEquals(listOf("b.jpg", "c.mp4", "a.png"), run.items.map { it.name })
        assertEquals(1, run.index)
        assertEquals("2 / 3", run.label)
    }

    @Test
    fun `a deleted file leaves the run and the next one takes its place`() {
        val three = listOf(file("a.jpg"), file("b.jpg"), file("c.jpg"))
        val after = ViewerRun(three, 1).without(1)!!
        assertEquals(listOf("a.jpg", "c.jpg"), after.items.map { it.name })
        assertEquals("c.jpg", after.current.name)
        // Whatever slid into place was arrived at, so a video there waits for a tap.
        assertEquals(false, after.playOnOpen)
        // The last one going lands on the one before it.
        assertEquals("b.jpg", ViewerRun(three, 2).without(2)!!.current.name)
        // One before the current one going keeps the same file on screen.
        assertEquals("c.jpg", ViewerRun(three, 2).without(0)!!.current.name)
        assertNull(ViewerRun(listOf(file("a.jpg")), 0).without(0))
    }

    @Test
    fun `stepping stays inside the run`() {
        val run = ViewerRun.of(listOf(file("a.jpg"), file("b.jpg")), file("a.jpg"), inside)!!
        assertEquals("b.jpg", run.at(1).current.name)
        assertEquals(run, run.at(2))
        assertEquals(run, run.at(-1))
    }

    @Test
    fun `nothing to swipe to is no run`() {
        assertNull(ViewerRun.of(listOf(file("a.jpg"), file("x.pdf")), file("a.jpg"), inside))
        assertNull(ViewerRun.of(emptyList(), file("a.jpg"), inside))
        assertNull(ViewerRun.of(null, file("a.jpg"), inside))
    }

    @Test
    fun `a file opened in another app, or missing from the list, has no run`() {
        val list = listOf(file("a.jpg"), file("b.jpg"))
        assertNull(ViewerRun.of(list, file("x.pdf"), inside))
        assertNull(ViewerRun.of(list, file("z.jpg"), inside))
    }

    @Test
    fun `a file listed twice is one page`() {
        val list = listOf(file("a.jpg"), file("b.jpg"), file("a.jpg"))
        val run = ViewerRun.of(list, file("b.jpg"), inside)!!
        assertEquals(2, run.items.size)
        assertEquals("2 / 2", run.label)
    }
}

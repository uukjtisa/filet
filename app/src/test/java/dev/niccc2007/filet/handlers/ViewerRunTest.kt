package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val kind: (VNode) -> String = { if (it.name.endsWith(".mp4")) "video" else "picture" }

    @Test
    fun `a run of photos and videos is mixed, and one of a single kind is not`() {
        val mixed = ViewerRun(listOf(file("a.jpg"), file("b.mp4"), file("c.png")), 0)
        assertTrue(mixed.mixed(kind))
        assertFalse(ViewerRun(listOf(file("a.jpg"), file("c.png")), 1).mixed(kind))
        assertFalse(ViewerRun(listOf(file("a.mp4"), file("b.mp4")), 0).mixed(kind))
    }

    @Test
    fun `only this kind keeps the run's order and stands on the same file`() {
        val run = ViewerRun(listOf(file("a.jpg"), file("b.mp4"), file("c.png"), file("d.mp4"), file("e.jpg")), 2)
        val photos = run.onlyKind(kind)
        assertEquals(listOf("a.jpg", "c.png", "e.jpg"), photos.items.map { it.name })
        assertEquals("c.png", photos.current.name)
        assertEquals("2 / 3", photos.label)
        val videos = run.at(3).onlyKind(kind)
        assertEquals(listOf("b.mp4", "d.mp4"), videos.items.map { it.name })
        assertEquals("d.mp4", videos.current.name)
        // A step in the narrowed run is found again in the whole one, by the file.
        assertEquals(4, run.indexOf(photos.at(2).current))
        assertEquals(-1, run.indexOf(file("z.jpg")))
        // The whole run is untouched by narrowing it.
        assertEquals(5, run.items.size)
    }

    @Test
    fun `a delete in a narrowed run goes on to the next of the same kind`() {
        val run = ViewerRun(listOf(file("a.jpg"), file("b.jpg"), file("c.mp4"), file("d.jpg")), 1)
        val photo: (VNode) -> Boolean = { kind(it) == "picture" }
        // b goes: c, a video, took its place; d is the next photo.
        val after = run.without(1, photo)!!
        assertEquals(listOf("a.jpg", "c.mp4", "d.jpg"), after.items.map { it.name })
        assertEquals("d.jpg", after.current.name)
        // The last photo going with only a video after it stands on the photo before.
        val tail = ViewerRun(listOf(file("a.jpg"), file("b.jpg"), file("c.mp4")), 1).without(1, photo)!!
        assertEquals("a.jpg", tail.current.name)
        // No photo left: it stands where the plain delete would.
        assertEquals("c.mp4", ViewerRun(listOf(file("b.jpg"), file("c.mp4")), 0).without(0, photo)!!.current.name)
        assertNull(ViewerRun(listOf(file("b.jpg")), 0).without(0, photo))
    }

    @Test
    fun `a deleted file leaves the run and the next one takes its place`() {
        val three = listOf(file("a.jpg"), file("b.jpg"), file("c.jpg"))
        val after = ViewerRun(three, 1).without(1)!!
        assertEquals(listOf("a.jpg", "c.jpg"), after.items.map { it.name })
        assertEquals("c.jpg", after.current.name)
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

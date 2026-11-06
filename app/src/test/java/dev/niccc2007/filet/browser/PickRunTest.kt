package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Test

/** A range, a slide or Select all over the pick's list, as the browser does over its own. */
class PickRunTest {

    private fun p(name: String) = VPath.of("local", "/storage/emulated/0/DCIM/$name")
    private fun file(name: String) = VNode(p(name), isDir = false, size = 1, mtime = 0)
    private fun dir(name: String) = VNode(p(name), isDir = true, size = -1, mtime = 0)

    private val rows = listOf(file("a.jpg"), file("b.jpg"), dir("Sub"), file("c.txt"), file("d.jpg"), file("e.jpg"))
    private val all: (VNode) -> Boolean = { true }
    private val pictures: (VNode) -> Boolean = { it.path.path.endsWith(".jpg") }

    @Test fun `tap one card, hold one far down - the run between them is picked`() {
        val now = setOf(p("a.jpg"))
        val next = rangeSelect(rows.map { it.path }, now, anchor = p("a.jpg"), target = p("e.jpg"))
        val (add, remove) = PickRun.diff(rows, now, next)
        val after = PickRun.after(listOf(file("a.jpg")), add, remove, all)
        assertEquals(listOf("a.jpg", "b.jpg", "c.txt", "d.jpg", "e.jpg"), after.map { it.path.path.substringAfterLast('/') })
    }

    @Test fun `a folder in the run is never picked`() {
        val after = PickRun.after(emptyList(), rows, emptySet(), all)
        assertEquals(false, after.any { it.isDir })
    }

    @Test fun `files the pick refuses are skipped, the rest are taken`() {
        val after = PickRun.after(emptyList(), rows, emptySet(), pictures)
        assertEquals(listOf("a.jpg", "b.jpg", "d.jpg", "e.jpg"), after.map { it.path.path.substringAfterLast('/') })
    }

    @Test fun `a file chosen in another folder survives a gesture here`() {
        val elsewhere = VNode(VPath.of("local", "/storage/emulated/0/Download/x.jpg"), isDir = false, size = 1, mtime = 0)
        val now = setOf(elsewhere.path, p("b.jpg"))
        // Invert over these rows: b goes, the rest come in, x is not on screen and stays.
        val next = rows.map { it.path }.toSet() - p("b.jpg") + elsewhere.path
        val (add, remove) = PickRun.diff(rows, now, next)
        assertEquals(setOf(p("b.jpg")), remove)
        val after = PickRun.after(listOf(elsewhere, file("b.jpg")), add, remove, pictures)
        assertEquals(true, after.any { it.path == elsewhere.path })
        assertEquals(false, after.any { it.path == p("b.jpg") })
    }

    @Test fun `nothing already chosen is added twice`() {
        val after = PickRun.after(listOf(file("a.jpg")), listOf(file("a.jpg"), file("b.jpg")), emptySet(), all)
        assertEquals(2, after.size)
    }

    @Test fun `a slide back over picked files drops only the ones it left`() {
        val now = setOf(p("a.jpg"), p("b.jpg"), p("d.jpg"))
        val next = setOf(p("a.jpg"))
        val (add, remove) = PickRun.diff(rows, now, next)
        assertEquals(emptyList<VNode>(), add)
        assertEquals(setOf(p("b.jpg"), p("d.jpg")), remove)
    }
}

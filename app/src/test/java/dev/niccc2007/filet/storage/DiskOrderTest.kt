package dev.niccc2007.filet.storage

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Test

/** Disk map's order and shares. */
class DiskOrderTest {

    private fun n(name: String, dir: Boolean = false, size: Long = -1) =
        VNode(path = VPath.parse("file:///storage/emulated/0/$name"), isDir = dir, size = size, mtime = 0)

    @Test fun `largest first, folders and files mixed, unread last in name order`() {
        val sizes = mapOf("Movies" to 21_000L, "iso.img" to 6_000L, "Books" to 300L)
        val rows = listOf(n("Books", true), n("zeta", true), n("iso.img", size = 6_000), n("alpha", true), n("Movies", true))
        val out = DiskOrder.sorted(rows) { sizes[it.name] }
        assertEquals(listOf("Movies", "iso.img", "Books", "alpha", "zeta"), out.map { it.name })
    }

    @Test fun `equal sizes keep one order however they arrive`() {
        val rows = listOf(n("30.jpg"), n("4.jpg"), n("100.jpg"))
        val one = DiskOrder.sorted(rows) { 7L }.map { it.name }
        val two = DiskOrder.sorted(rows.reversed()) { 7L }.map { it.name }
        assertEquals(one, two)
        assertEquals(listOf("4.jpg", "30.jpg", "100.jpg"), one)
    }

    @Test fun `a share is never printed as empty when something is there`() {
        assertEquals("61%", DiskOrder.percent(61, 100))
        assertEquals("<1%", DiskOrder.percent(40, 128_000))
        assertEquals("0%", DiskOrder.percent(0, 100))
        assertEquals("", DiskOrder.percent(null, 100))
        assertEquals(0.5f, DiskOrder.share(50, 100), 0f)
        assertEquals(0f, DiskOrder.share(5, 0), 0f)
    }
}

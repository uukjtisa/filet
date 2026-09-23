package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The just-arrived tail, tested as gestures: things arrive, the list is drawn, the view
 * settles.
 */
class FreshTailTest {

    private fun p(name: String) = VPath.parse("local:///storage/emulated/0/Download/$name")
    private fun n(name: String) = VNode(p(name), isDir = false, size = 1, mtime = 0)

    /** A folder already in sort order. */
    private val sorted = listOf(n("aaa.txt"), n("bbb.txt"), n("ccc.txt"), n("ddd.txt"))

    private fun names(l: List<VNode>) = l.map { it.path.name }

    // ── the behaviour the feature exists for ───────────────────────────────────────────

    @Test
    fun `a new file goes to the bottom even though it sorts first`() {
        val tail = FreshTail().arrived(p("aaa.txt"))
        assertEquals(
            listOf("bbb.txt", "ccc.txt", "ddd.txt", "aaa.txt"),
            names(tail.applyTo(sorted)),
        )
    }

    @Test
    fun `several arrivals keep the order they arrived in, not their sort order`() {
        val tail = FreshTail().arrived(listOf(p("ddd.txt"), p("aaa.txt"), p("ccc.txt")))
        assertEquals(
            listOf("bbb.txt", "ddd.txt", "aaa.txt", "ccc.txt"),
            names(tail.applyTo(sorted)),
        )
    }

    @Test
    fun `everything else keeps the sort it had`() {
        val tail = FreshTail().arrived(p("ccc.txt"))
        assertEquals(listOf("aaa.txt", "bbb.txt", "ddd.txt", "ccc.txt"), names(tail.applyTo(sorted)))
    }

    @Test
    fun `settling puts the sort back`() {
        val tail = FreshTail().arrived(p("aaa.txt")).settled()
        assertTrue(tail.isEmpty)
        assertEquals(names(sorted), names(tail.applyTo(sorted)))
    }

    // ── the same file arriving twice ───────────────────────────────────────────────────

    @Test
    fun `copying over a file already in the tail does not move it`() {
        // The row is already at the bottom; moving it again under the finger is the exact
        // thing the tail exists to prevent.
        val tail = FreshTail()
            .arrived(p("aaa.txt"))
            .arrived(p("bbb.txt"))
            .arrived(p("aaa.txt"))
        assertEquals(listOf("ccc.txt", "ddd.txt", "aaa.txt", "bbb.txt"), names(tail.applyTo(sorted)))
    }

    // ── files that stop existing ───────────────────────────────────────────────────────

    @Test
    fun `a held path missing from the listing is dropped, not invented`() {
        val tail = FreshTail().arrived(listOf(p("aaa.txt"), p("zzz.txt")))
        val out = tail.applyTo(sorted)
        assertEquals(listOf("bbb.txt", "ccc.txt", "ddd.txt", "aaa.txt"), names(out))
        assertEquals("nothing conjured", sorted.size, out.size)
    }

    @Test
    fun `forgetting a deleted file takes it out of the tail`() {
        val tail = FreshTail().arrived(listOf(p("aaa.txt"), p("bbb.txt"))).forget(p("aaa.txt"))
        assertEquals(listOf("aaa.txt", "ccc.txt", "ddd.txt", "bbb.txt"), names(tail.applyTo(sorted)))
    }

    @Test
    fun `forgetting something never held changes nothing`() {
        val tail = FreshTail().arrived(p("aaa.txt"))
        assertEquals(tail, tail.forget(p("zzz.txt")))
    }

    @Test
    fun `a tail holding only vanished files leaves the listing exactly as it was`() {
        val tail = FreshTail().arrived(p("zzz.txt"))
        assertSame(sorted, tail.applyTo(sorted))
    }

    // ── nothing held ───────────────────────────────────────────────────────────────────

    @Test
    fun `an empty tail returns the very same list`() {
        // Not merely equal - the common case must not copy the listing on every recomposition.
        assertSame(sorted, FreshTail().applyTo(sorted))
    }

    @Test
    fun `an empty listing survives a tail`() {
        assertEquals(emptyList<String>(), names(FreshTail().arrived(p("aaa.txt")).applyTo(emptyList())))
    }

    @Test
    fun `everything arriving at once is still ordered by arrival`() {
        val tail = FreshTail().arrived(listOf(p("ddd.txt"), p("ccc.txt"), p("bbb.txt"), p("aaa.txt")))
        assertEquals(
            listOf("ddd.txt", "ccc.txt", "bbb.txt", "aaa.txt"),
            names(tail.applyTo(sorted)),
        )
    }

    @Test
    fun `membership answers for what it holds`() {
        val tail = FreshTail().arrived(p("aaa.txt"))
        assertTrue(p("aaa.txt") in tail)
        assertTrue(p("bbb.txt") !in tail)
    }
}

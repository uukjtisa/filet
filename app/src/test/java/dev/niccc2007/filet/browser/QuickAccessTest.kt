package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAccessTest {

    private fun e(name: String, visits: Int = 0, at: Long = 0, pinned: Boolean = false) =
        QuickAccess.Entry(VPath.of("local", "/storage/emulated/0/$name"), name, pinned, visits, at)

    @Test
    fun `a pin outranks any frequency`() {
        // A pin is a statement, a count is an inference. If the inference could reorder the
        // statement, somebody's pinned folder moves because they used another one this morning.
        val out = QuickAccess.quick(
            pinned = listOf(e("Pinned")),
            visited = listOf(e("Downloads", visits = 900, at = 99)),
        )
        assertEquals("Pinned", out.first().label)
        assertTrue(out.first().pinned)
    }

    @Test
    fun `pinned order is the order they were pinned, not their counts`() {
        val out = QuickAccess.quick(
            pinned = listOf(e("First", visits = 1), e("Second", visits = 500)),
            visited = emptyList(),
        )
        assertEquals(listOf("First", "Second"), out.map { it.label })
    }

    @Test
    fun `a pinned folder is never listed twice`() {
        val dl = e("Downloads", visits = 40, at = 10)
        val out = QuickAccess.quick(pinned = listOf(dl), visited = listOf(dl))
        assertEquals(1, out.count { it.label == "Downloads" })
    }

    @Test
    fun `frequency decides, and recency breaks the tie`() {
        val out = QuickAccess.quick(
            pinned = emptyList(),
            visited = listOf(
                e("older", visits = 3, at = 100),
                e("newer", visits = 3, at = 900),
                e("most", visits = 9, at = 1),
            ),
        )
        assertEquals(listOf("most", "newer", "older"), out.map { it.label })
    }

    @Test
    fun `the frequent list is capped`() {
        val many = (1..30).map { e("f$it", visits = it, at = it.toLong()) }
        val out = QuickAccess.quick(pinned = emptyList(), visited = many)
        assertEquals(QuickAccess.FREQUENT, out.size)
    }

    @Test
    fun `a pin never counts against the frequent cap`() {
        // Pinning two folders must not silently drop two frequent ones - the two groups answer
        // different questions and one should not eat the other's room.
        val many = (1..30).map { e("f$it", visits = it, at = it.toLong()) }
        val out = QuickAccess.quick(pinned = listOf(e("a"), e("b")), visited = many)
        assertEquals(2 + QuickAccess.FREQUENT, out.size)
    }

    @Test
    fun `recent never repeats what quick access already shows`() {
        // The same folder twice, a few rows apart, under two headings that both claim it,
        // reads as a bug rather than as two views of one fact.
        val dl = e("Downloads", visits = 40, at = 900)
        val doc = e("Documents", visits = 2, at = 800)
        val quick = QuickAccess.quick(pinned = emptyList(), visited = listOf(dl, doc))
        val recent = QuickAccess.recent(listOf(dl, doc), alreadyShown = quick)
        assertTrue(recent.isEmpty())
    }

    @Test
    fun `recent is newest first and capped`() {
        val many = (1..30).map { e("r$it", at = it.toLong()) }
        val out = QuickAccess.recent(many, alreadyShown = emptyList())
        assertEquals(QuickAccess.RECENT, out.size)
        assertEquals("r30", out.first().label)
    }

    @Test
    fun `nothing visited is an empty pane, not a crash`() {
        assertEquals(emptyList<QuickAccess.Entry>(), QuickAccess.quick(emptyList(), emptyList()))
        assertEquals(emptyList<QuickAccess.Entry>(), QuickAccess.recent(emptyList(), emptyList()))
    }
}

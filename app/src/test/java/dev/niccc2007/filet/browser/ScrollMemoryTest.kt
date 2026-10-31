package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A pane's scroll position per folder, put back by the row it named rather than by its index. */
class ScrollMemoryTest {

    private val mark = ScrollMemory.Mark(index = 40, offset = 12, key = "/sdcard/DCIM/a.jpg")

    @Test
    fun `the named row is found wherever the finished list puts it`() {
        val keys = (0 until 100).map { "/sdcard/DCIM/f$it.jpg" }.toMutableList<Any>()
        keys.add(55, "/sdcard/DCIM/a.jpg")
        assertEquals(ScrollMemory.Outcome.Scroll(55, 12), ScrollMemory.resolve(mark, keys, settled = true))
    }

    @Test
    fun `a half-read list waits rather than landing on whatever row 40 is so far`() {
        // Bug identified here: the index alone scrolled a chunked listing to a different file.
        val partial = (0 until 50).map { "/sdcard/DCIM/f$it.jpg" }
        assertEquals(ScrollMemory.Outcome.Wait, ScrollMemory.resolve(mark, partial, settled = false))
    }

    @Test
    fun `a row that is gone falls back to its index, clamped, once everything has arrived`() {
        val keys = (0 until 30).map { "/sdcard/DCIM/f$it.jpg" }
        // Clamped to the last row, and the offset dropped: it belonged to the row that went.
        assertEquals(ScrollMemory.Outcome.Scroll(29, 0), ScrollMemory.resolve(mark, keys, settled = true))
        assertEquals(ScrollMemory.Outcome.Scroll(0, 0), ScrollMemory.resolve(mark, emptyList(), settled = true))
    }

    @Test
    fun `the folders visited longest ago are the ones forgotten`() {
        val memory = ScrollMemory(cap = 2)
        memory.park("a", ScrollMemory.Marks(list = mark))
        memory.park("b", ScrollMemory.Marks(list = mark))
        memory.at("a") // a is used again, so b is now the oldest
        memory.park("c", ScrollMemory.Marks(list = mark))
        assertNull(memory.at("b"))
        assertEquals(mark, memory.at("a")?.list)
        assertEquals(mark, memory.at("c")?.list)
    }

    @Test
    fun `what is saved comes back in the same order, so the same places fall off next`() {
        val m = ScrollMemory.Mark(3, 0)
        val before = ScrollMemory(cap = 3)
        before.park("a", ScrollMemory.Marks(list = m))
        before.park("b", ScrollMemory.Marks(list = m))
        before.park("c", ScrollMemory.Marks(list = m))
        before.at("a")
        assertEquals(listOf("b", "c", "a"), before.all().map { it.first })

        val after = ScrollMemory(cap = 3)
        after.restore(before.all())
        after.park("d", ScrollMemory.Marks(list = m))
        assertNull("the one used longest ago went first, as it would have", after.at("b"))
        assertEquals(m, after.at("a")?.list)
    }
}

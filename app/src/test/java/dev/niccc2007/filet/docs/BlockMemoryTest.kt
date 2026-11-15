package dev.niccc2007.filet.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** How tall a table or code block is drawn, and what a block keeps while scrolled away. */
class BlockMemoryTest {

    private val short = 460f

    @Test
    fun `a block opens whole - no height limit at all`() {
        assertNull(BlockFold.cap(hand = null, collapsed = false, short = short))
    }

    @Test
    fun `collapsed it is held to the short window`() {
        assertEquals(short, BlockFold.cap(hand = null, collapsed = true, short = short))
    }

    @Test
    fun `a height set by hand wins over whole and over collapsed`() {
        assertEquals(300f, BlockFold.cap(hand = 300f, collapsed = false, short = short))
        assertEquals(300f, BlockFold.cap(hand = 300f, collapsed = true, short = short))
    }

    @Test
    fun `Collapse is offered only where folding would change something`() {
        assertFalse("shorter than the window", BlockFold.offered(full = 200f, short = short, collapsed = false, hand = null))
        assertFalse("not measured yet", BlockFold.offered(full = 0f, short = short, collapsed = false, hand = null))
        assertTrue("taller than the window", BlockFold.offered(full = 900f, short = short, collapsed = false, hand = null))
        assertTrue("already collapsed", BlockFold.offered(full = 200f, short = short, collapsed = true, hand = null))
        assertTrue("sized by hand", BlockFold.offered(full = 200f, short = short, collapsed = false, hand = 120f))
    }

    @Test
    fun `the chip folds a whole block and opens a folded one`() {
        val m = BlockMemory()
        m.fold()
        assertTrue(m.collapsed)
        m.fold()
        assertFalse(m.collapsed)
    }

    @Test
    fun `Expand on a block sized shorter by hand opens it whole and keeps its width`() {
        val m = BlockMemory()
        m.size.width = 240f
        m.size.height = 120f
        assertTrue(BlockFold.folded(m.collapsed, m.size.height))
        m.fold()
        assertNull(m.size.height)
        assertFalse(m.collapsed)
        assertEquals(240f, m.size.width)
    }

    @Test
    fun `a block's settings are the same object each time it comes back into view`() {
        val doc = DocMemory()
        val first = doc.block(7)
        first.size.height = 180f
        first.wrap = true
        first.table = TableMode.COMFORTABLE
        first.notesOpen = true
        // The list composes the block again from scratch; the page hands it the same memory.
        val again = doc.block(7)
        assertSame(first, again)
        assertEquals(180f, again.size.height)
        assertTrue(again.wrap)
        assertEquals(TableMode.COMFORTABLE, again.table)
        assertTrue(again.notesOpen)
        assertNotSame(first, doc.block(8))
    }

    @Test
    fun `a web picture tapped to load stays loaded, and an equal picture shares the answer`() {
        val doc = DocMemory()
        val p = Picture(at = 3, src = "https://example.org/badge.svg")
        doc.slot(p).allowed = true
        assertTrue(doc.slot(Picture(at = 3, src = "https://example.org/badge.svg")).allowed)
        assertFalse(doc.slot(Picture(at = 9, src = "https://example.org/other.svg")).allowed)
    }
}

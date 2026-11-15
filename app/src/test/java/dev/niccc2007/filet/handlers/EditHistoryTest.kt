package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    private fun plain() = EditHistory<String>()
    private fun typing() = EditHistory(TextEdit::merge)
    private fun key(at: Int, c: String) = TextEdit(at, "", c)
    private fun TextEdit.parts() = Triple(start, removed, inserted)

    @Test
    fun `undo and redo walk the steps`() {
        val h = plain()
        assertFalse(h.canUndo)
        h.push("a", 0)
        h.push("b", 5_000)
        assertEquals("b", h.undo())
        assertTrue(h.canRedo)
        assertEquals("a", h.undo())
        assertFalse(h.canUndo)
        assertNull(h.undo())
        assertEquals("a", h.redo())
        assertEquals("b", h.redo())
        assertNull(h.redo())
    }

    @Test
    fun `a new edit after an undo ends redo`() {
        val h = plain()
        h.push("a", 0)
        h.push("b", 5_000)
        h.undo()
        h.push("c", 10_000)
        assertFalse(h.canRedo)
        assertEquals("c", h.undo())
        // Redo follows the newest branch.
        assertEquals("c", h.redo())
    }

    @Test
    fun `clean is where the file was saved, and undoing back to it is clean again`() {
        val h = plain()
        assertTrue(h.isClean)
        h.push("a", 0)
        assertFalse(h.isClean)
        h.undo()
        assertTrue(h.isClean)
        h.redo()
        h.markClean()
        assertTrue(h.isClean)
        h.push("b", 5_000)
        assertFalse(h.isClean)
        h.undo()
        assertTrue(h.isClean)
        h.undo()
        assertFalse(h.isClean)
    }

    @Test
    fun `a branch left behind stays unclean`() {
        val h = plain()
        h.push("a", 0)
        h.markClean()
        h.undo()
        h.push("b", 5_000)
        assertFalse(h.isClean)
        h.undo()
        assertFalse(h.isClean)
    }

    @Test
    fun `a typed word is one step, a space starts the next`() {
        val h = typing()
        "hi you".forEachIndexed { i, c -> h.push(key(i, c.toString()), i * 100L) }
        assertEquals(Triple(2, "", " you"), h.undo()?.parts())
        assertEquals("hi", h.undo()?.inserted)
        assertFalse(h.canUndo)
    }

    @Test
    fun `a pause, a save or an undo ends a run`() {
        val h = typing()
        h.push(key(0, "a"), 0)
        h.push(key(1, "b"), 5_000)
        assertEquals(2, h.depth)
        h.markClean()
        h.push(key(2, "c"), 5_100)
        assertEquals(3, h.depth)
        h.undo()
        h.redo()
        h.push(key(3, "d"), 5_200)
        assertEquals(4, h.depth)
    }

    @Test
    fun `deleting runs fold either way, and a paste stands alone`() {
        assertEquals(Triple(3, "cd", ""), TextEdit.merge(TextEdit(4, "d", ""), TextEdit(3, "c", ""))?.parts())
        assertEquals(Triple(3, "cd", ""), TextEdit.merge(TextEdit(3, "c", ""), TextEdit(3, "d", ""))?.parts())
        assertNull(TextEdit.merge(key(0, "a"), TextEdit(1, "", "pasted")))
        assertNull(TextEdit.merge(key(0, "a"), key(1, "\n")))
        assertEquals(Triple(5, "x", "ab"), TextEdit(5, "ab", "x").inverse().parts())
    }
}

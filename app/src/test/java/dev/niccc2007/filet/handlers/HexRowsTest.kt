package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HexRowsTest {
    private val l = HexLayout(8)

    @Test
    fun `a row is the offset, sixteen bytes with a gap after eight, and the text`() {
        val row = l.text(0x1A20, ByteArray(16) { (0x41 + it).toByte() })
        assertEquals("00001A20  41 42 43 44 45 46 47 48  49 4A 4B 4C 4D 4E 4F 50  ABCDEFGHIJKLMNOP", row)
        assertEquals(l.chars, row.length)
    }

    @Test
    fun `a short last row keeps the full width and puts dots for the unprintable`() {
        val row = l.text(0x10, byteArrayOf(0x00, 0x7F, 0x20))
        assertEquals(l.chars, row.length)
        assertEquals("00 7F 20", row.substring(l.hexAt(0), l.hexAt(2) + 2))
        assertEquals(".. ", row.substring(l.ascii, l.ascii + 3))
        assertEquals("", row.substring(l.hexAt(3), l.ascii).trim())
    }

    @Test
    fun `a tap finds the byte under it in either column`() {
        assertNull(l.columnAt(3))
        assertEquals(0, l.columnAt(l.hexAt(0)))
        assertEquals(0, l.columnAt(l.hexAt(0) + 2))
        // The extra space after the eighth byte still belongs to it.
        assertEquals(7, l.columnAt(l.hexAt(8) - 1))
        assertEquals(15, l.columnAt(l.hexAt(15) + 1))
        assertNull(l.columnAt(l.ascii - 1))
        assertEquals(5, l.columnAt(l.ascii + 5))
        assertNull(l.columnAt(l.chars))
    }

    @Test
    fun `offsets past 4 GB get more digits`() {
        assertEquals(8, HexLayout.forSize(0x1_0000_0000L).digits)
        assertEquals(9, HexLayout.forSize(0x1_0000_0001L).digits)
        assertEquals("100000000", HexLayout(9).text(0x1_0000_0000L, ByteArray(1)).take(9))
    }

    @Test
    fun `a step undoes to the value shown, whatever the file holds`() {
        val file = mutableMapOf(4L to 0x00.toByte())
        val step = ByteEdit(listOf(ByteEdit.Change(4, 0x00, 0x4F)))
        var e = step.applyTo(HexEdits()) { file.getValue(it) }
        assertEquals(0x4F.toByte(), e.valueAt(4, 0))
        // Saved: the file now holds 4F and nothing is pending.
        e = ByteEdit.afterSave(e, e, file)
        assertTrue(e.isEmpty)
        // Undo after the save shows 00 again, which is now an edit against the file.
        e = step.inverse().applyTo(e) { file.getValue(it) }
        assertEquals(0x00.toByte(), e.valueAt(4, file.getValue(4)))
        assertEquals(1, e.count)
    }

    @Test
    fun `a byte changed while a save ran stays pending`() {
        val file = mutableMapOf(1L to 0.toByte(), 2L to 0.toByte())
        val saved = HexEdits().put(1, 1, 0)
        val now = saved.put(2, 2, 0).put(1, 0, 0)
        val left = ByteEdit.afterSave(saved, now, file)
        assertEquals(1.toByte(), file[1])
        assertEquals(mapOf(1L to 0.toByte(), 2L to 2.toByte()), left.changes)
    }
}

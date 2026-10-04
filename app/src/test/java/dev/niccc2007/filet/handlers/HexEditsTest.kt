package dev.niccc2007.filet.handlers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HexEditsTest {

    @Test
    fun `an edit is held, not applied`() {
        val e = HexEdits().put(4, 0xFF.toByte(), original = 0x00)
        assertEquals(1, e.count)
        assertTrue(e.isEdited(4))
        assertFalse(e.isEdited(5))
    }

    @Test
    fun `setting a byte back to what the file holds removes the edit`() {
        // Otherwise the pending count climbs while somebody tries values out, and a count that
        // cannot go back down means nothing.
        val e = HexEdits()
            .put(4, 0xFF.toByte(), original = 0x41)
            .put(4, 0x41, original = 0x41)
        assertTrue(e.isEmpty)
    }

    @Test
    fun `an edit can be reverted one at a time`() {
        val e = HexEdits().put(1, 1, 0).put(2, 2, 0).revert(1)
        assertEquals(1, e.count)
        assertFalse(e.isEdited(1))
        assertTrue(e.isEdited(2))
    }

    @Test
    fun `reverting something that was never edited changes nothing`() {
        val e = HexEdits().put(1, 1, 0)
        assertEquals(e, e.revert(99))
    }

    @Test
    fun `a page shows pending values over the file's own`() {
        val e = HexEdits().put(2, 0x99.toByte(), 0x00)
        assertEquals(0x99.toByte(), e.valueAt(2, 0x00))
        // Explicitly a Byte: an Int literal here compares a boxed Integer against a boxed Byte
        // and is never equal, which fails for a reason that has nothing to do with the code.
        assertEquals(0x41.toByte(), e.valueAt(3, 0x41))
    }

    @Test
    fun `applying uses the buffer's own position in the file`() {
        // The arithmetic that would eventually be wrong by one page and write a byte into the
        // wrong place, so it lives here with a test rather than at each call site.
        val page = byteArrayOf(0, 0, 0, 0)
        val e = HexEdits().put(offset = 18, value = 0x7F, original = 0)
        assertArrayEquals(byteArrayOf(0, 0, 0x7F, 0), e.applyTo(page, bufferStart = 16))
    }

    @Test
    fun `an edit outside the buffer does not touch it`() {
        val page = byteArrayOf(1, 2, 3, 4)
        val e = HexEdits().put(offset = 900, value = 0x7F, original = 0)
        assertArrayEquals(page, e.applyTo(page, bufferStart = 16))
        // And the one just past the end, which is where an off-by-one would land.
        assertArrayEquals(page, HexEdits().put(20, 0x7F, 0).applyTo(page, 16))
    }

    @Test
    fun `applying nothing returns the buffer untouched`() {
        val page = byteArrayOf(1, 2, 3)
        assertArrayEquals(page, HexEdits().applyTo(page, 0))
    }

    @Test
    fun `typed hex is read strictly`() {
        assertEquals(0x00.toByte(), HexEdits.parseByte("00"))
        assertEquals(0xFF.toByte(), HexEdits.parseByte("ff"))
        assertEquals(0xA0.toByte(), HexEdits.parseByte("A0"))
        assertEquals(0x07.toByte(), HexEdits.parseByte("7"))
    }

    @Test
    fun `anything that is not a byte is refused rather than guessed`() {
        // In a tool where a wrong byte silently corrupts a file, reading "1G" as 1 is worse than
        // refusing it.
        assertNull(HexEdits.parseByte("1G"))
        assertNull(HexEdits.parseByte("100"))
        assertNull(HexEdits.parseByte(""))
        assertNull(HexEdits.parseByte("-1"))
        assertNull(HexEdits.parseByte(" "))
    }
}

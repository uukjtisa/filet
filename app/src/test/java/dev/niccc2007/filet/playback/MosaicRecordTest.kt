package dev.niccc2007.filet.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The kept wall reads back whole or not at all; anything else is a rebuild from the covers. */
class MosaicRecordTest {

    private val key = "local:///storage/emulated/0/Music/b.mp3\nlocal:///storage/emulated/0/Music/a.mp3"
    private val tiles = listOf(byteArrayOf(1, 2, 3), ByteArray(4000) { it.toByte() })

    @Test
    fun `a wall kept comes back as it was`() {
        val back = MosaicCodec.decode(MosaicCodec.encode(MosaicRecord(key, 0xFF336699.toInt(), tiles)))
        assertNotNull(back)
        assertEquals(key, back!!.key)
        assertEquals(0xFF336699.toInt(), back.tint)
        assertEquals(tiles.size, back.tiles.size)
        for (i in tiles.indices) assertArrayEquals(tiles[i], back.tiles[i])
    }

    @Test
    fun `no tint stays no tint, and an empty wall is still a record`() {
        val back = MosaicCodec.decode(MosaicCodec.encode(MosaicRecord("", null, emptyList())))!!
        assertNull(back.tint)
        assertTrue(back.tiles.isEmpty())
    }

    @Test
    fun `a missing, cut short or padded file reads as nothing`() {
        val whole = MosaicCodec.encode(MosaicRecord(key, null, tiles))
        assertNull(MosaicCodec.decode(null))
        assertNull(MosaicCodec.decode(ByteArray(0)))
        // Bug identified here, in advance: a process killed mid-write must not draw half a wall.
        for (cut in listOf(3, 8, 20, whole.size - 1)) assertNull(MosaicCodec.decode(whole.copyOf(cut)))
        assertNull(MosaicCodec.decode(whole + byteArrayOf(0)))
    }

    @Test
    fun `garbage and other versions read as nothing, without believing their lengths`() {
        assertNull(MosaicCodec.decode(ByteArray(64) { 0x7F }))
        val whole = MosaicCodec.encode(MosaicRecord(key, null, tiles))
        val otherVersion = whole.copyOf().also { it[7] = 9 }
        assertNull(MosaicCodec.decode(otherVersion))
        // A key length of two gigabytes: refused from the header, never allocated.
        val huge = whole.copyOf().also { for (i in 8..11) it[i] = 0x7F }
        assertNull(MosaicCodec.decode(huge))
    }

    @Test
    fun `only the same newest tracks skip the rebuild`() {
        assertTrue(MosaicCodec.current(key, key))
        assertFalse(MosaicCodec.current(key, key + "\nlocal:///storage/emulated/0/Music/new.mp3"))
        assertFalse(MosaicCodec.current(null, key))
        assertFalse(MosaicCodec.current(key, null))
    }
}

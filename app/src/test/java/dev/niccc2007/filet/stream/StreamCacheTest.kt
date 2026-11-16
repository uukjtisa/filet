package dev.niccc2007.filet.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jumping about a stream: what is remembered, how much, and which kept reader carries on. */
class StreamCacheTest {

    @Test
    fun `a lock forgets the Locker's links and their pieces, and nothing else`() {
        var n = 0
        val tokens = StreamTokens { "t" + ++n }
        val locker = tokens.mint(StreamTokens.Entry(dev.niccc2007.filet.vfs.VPath("locker", "/v.mp4"), 10, "video/mp4"))
        val plain = tokens.mint(StreamTokens.Entry(dev.niccc2007.filet.vfs.VPath("local", "/sdcard/v.mp4"), 10, "video/mp4"))
        val cache = PieceCache(1024)
        cache.put(locker, 0, ByteArray(100))
        cache.put(locker, 256, ByteArray(100))
        cache.put(plain, 0, ByteArray(100))

        val gone = tokens.forgetPrivate()
        cache.forget(gone)

        assertEquals(setOf(locker), gone)
        assertNull(tokens.resolve(locker))
        assertNull(cache.get(locker, 0))
        assertNull(cache.get(locker, 256))
        assertEquals(plain, tokens.resolve(plain)?.let { plain })
        assertEquals(100L, cache.size())
        // Minted again after the lock, the file gets a new token rather than the old one back.
        assertTrue(tokens.mint(StreamTokens.Entry(dev.niccc2007.filet.vfs.VPath("locker", "/v.mp4"), 10, "video/mp4")) != locker)
    }

    @Test
    fun `a piece read once is found again by its link and offset`() {
        val cache = PieceCache(1024)
        val piece = ByteArray(100) { it.toByte() }
        cache.put("t", 256, piece)
        assertArrayEquals(piece, cache.get("t", 256))
        assertNull(cache.get("t", 512))
        assertNull(cache.get("other", 256))
    }

    @Test
    fun `memory stays under its bound, dropping the least recently used`() {
        val cache = PieceCache(300)
        cache.put("t", 0, ByteArray(100))
        cache.put("t", 100, ByteArray(100))
        cache.put("t", 200, ByteArray(100))
        cache.get("t", 0) // used again, so it outlives the one at 100
        cache.put("t", 300, ByteArray(100))
        assertTrue(cache.size() <= 300)
        assertNull(cache.get("t", 100))
        assertTrue(cache.get("t", 0) != null)
        assertTrue(cache.get("t", 300) != null)
    }

    @Test
    fun `a piece bigger than the whole memory is not kept at all`() {
        val cache = PieceCache(50)
        cache.put("t", 0, ByteArray(100))
        assertEquals(0L, cache.size())
    }

    @Test
    fun `the kept reader chosen is the one that skips least, never one past the start`() {
        val gap = 1000L
        assertEquals(1, StreamServer.nearestBehind(listOf(100, 900, 2000), 950, gap))
        assertNull(StreamServer.nearestBehind(listOf(2000), 950, gap))
        assertNull(StreamServer.nearestBehind(listOf(0), 5000, gap))
        assertEquals(0, StreamServer.nearestBehind(listOf(950), 950, gap))
    }
}

package dev.niccc2007.filet.vfs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException

/** A file on another device read as a seekable channel, in blocks that are fetched once. */
class RangedChannelTest {

    private val file = ByteArray(1000) { (it * 7 % 251).toByte() }

    private fun blocks(
        block: Int = 100,
        keep: Int = 8,
        maxRun: Int = 4,
        calls: MutableList<LongRange> = mutableListOf(),
        data: ByteArray = file,
    ) = RangedBlocks(data.size.toLong(), block, keep, maxRun) { from, until ->
        calls += from until until
        data.copyOfRange(from.toInt(), until.toInt())
    }

    private fun readAt(ch: RangedChannel, at: Long, n: Int): ByteArray {
        ch.position(at)
        val buf = ByteBuffer.allocate(n)
        while (buf.hasRemaining()) if (ch.read(buf) < 0) break
        return buf.array().copyOf(buf.position())
    }

    @Test
    fun `any read anywhere gives the file's own bytes, across block edges`() {
        val ch = RangedChannel(blocks())
        for ((at, n) in listOf(0L to 10, 95L to 10, 199L to 302, 990L to 10, 0L to 1000, 500L to 1)) {
            assertArrayEquals("$at+$n", file.copyOfRange(at.toInt(), at.toInt() + n), readAt(ch, at, n))
        }
    }

    @Test
    fun `a block is fetched once, however many small reads land in it`() {
        val calls = mutableListOf<LongRange>()
        val ch = RangedChannel(blocks(calls = calls))
        // The way a zip reader looks for its last record: small reads backwards from the end.
        for (at in 996L downTo 900L step 4) readAt(ch, at, 4)
        assertEquals(listOf(900L until 1000L), calls)
    }

    @Test
    fun `reading forward fetches longer runs, and a jump starts again at one block`() {
        val calls = mutableListOf<LongRange>()
        val ch = RangedChannel(blocks(calls = calls))
        readAt(ch, 0, 1000)
        // 1 block, then 2, then 4 (the most), then the 3 that are left.
        assertEquals(listOf(0L until 100L, 100L until 300L, 300L until 700L, 700L until 1000L), calls)

        val again = mutableListOf<LongRange>()
        val ch2 = RangedChannel(blocks(calls = again))
        readAt(ch2, 0, 10)
        readAt(ch2, 500, 10)
        readAt(ch2, 900, 10)
        assertEquals(listOf(0L until 100L, 500L until 600L, 900L until 1000L), again)
    }

    @Test
    fun `no more than keep blocks are held`() {
        val calls = mutableListOf<LongRange>()
        val b = blocks(keep = 4, maxRun = 1, calls = calls)
        val ch = RangedChannel(b)
        readAt(ch, 0, 1000) // ten blocks through a cache of four
        calls.clear()
        readAt(ch, 0, 10) // block 0 was dropped long ago
        readAt(ch, 900, 10) // block 9 is still held
        assertEquals(listOf(0L until 100L), calls)
        assertEquals(11, b.requests)
    }

    @Test
    fun `a run never fetches a block already held at its end`() {
        val calls = mutableListOf<LongRange>()
        val ch = RangedChannel(blocks(calls = calls))
        readAt(ch, 600, 10) // block 6
        readAt(ch, 0, 10) // block 0
        readAt(ch, 100, 10) // block 1 follows 0: a run of two, blocks 1 and 2
        readAt(ch, 300, 10) // block 3 follows 2: a run of four would be 3 to 6, and 6 is held
        assertEquals(listOf(600L until 700L, 0L until 100L, 100L until 300L, 300L until 600L), calls)
    }

    @Test
    fun `the end reads as -1, and size and position behave as a channel's`() {
        val ch = RangedChannel(blocks())
        assertEquals(1000L, ch.size())
        ch.position(1000)
        assertEquals(-1, ch.read(ByteBuffer.allocate(4)))
        ch.position(5000)
        assertEquals(-1, ch.read(ByteBuffer.allocate(4)))
        assertEquals(5000L, ch.position())
        ch.position(998)
        val buf = ByteBuffer.allocate(10)
        assertEquals(2, ch.read(buf))
        assertEquals(1000L, ch.position())
    }

    @Test
    fun `a short answer from the share is an error, never bytes in the wrong place`() {
        val b = RangedBlocks(1000, 100, 8, 4) { from, until -> file.copyOfRange(from.toInt(), until.toInt() - 1) }
        try {
            readAt(RangedChannel(b), 0, 10)
            fail("a short block was accepted")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("asked for 100 bytes"))
        }
    }

    @Test
    fun `a failed read reaches the reader as an IOException`() {
        val b = RangedBlocks(1000, 100, 8, 4) { _, _ -> throw IllegalStateException("range refused: HTTP 416") }
        try {
            readAt(RangedChannel(b), 0, 10)
            fail("the failure was swallowed")
        } catch (e: IOException) {
            assertEquals("range refused: HTTP 416", e.message)
        }
    }

    @Test
    fun `closing a channel keeps the blocks for the next one, and refuses reads on itself`() {
        val calls = mutableListOf<LongRange>()
        val b = blocks(calls = calls)
        val first = RangedChannel(b)
        readAt(first, 0, 10)
        first.close()
        try {
            first.read(ByteBuffer.allocate(1))
            fail("a closed channel read")
        } catch (_: ClosedChannelException) {
        }
        readAt(RangedChannel(b), 0, 10)
        assertEquals(1, calls.size)
    }

    @Test
    fun `an empty file reads as the end at once and fetches nothing`() {
        val calls = mutableListOf<LongRange>()
        val ch = RangedChannel(blocks(calls = calls, data = ByteArray(0)))
        assertEquals(-1, ch.read(ByteBuffer.allocate(4)))
        assertEquals(emptyList<LongRange>(), calls)
    }
}

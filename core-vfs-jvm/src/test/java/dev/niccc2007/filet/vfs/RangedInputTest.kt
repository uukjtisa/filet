package dev.niccc2007.filet.vfs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/** The fallback behind openReadAt: a range-capable backend read from an offset, a chunk at a time. */
class RangedInputTest {

    private val file = ByteArray(5000) { (it % 251).toByte() }

    private fun ranged(start: Long, chunk: Int, calls: MutableList<LongRange> = mutableListOf()) =
        RangedInput(start, file.size.toLong(), chunk) { from, until ->
            calls += from until until
            file.copyOfRange(from.toInt(), until.toInt())
        }

    @Test
    fun `reads from the offset to the end, whatever the chunk size`() {
        for (chunk in listOf(1, 7, 1000, 4096, 10_000)) {
            val got = ranged(1234, chunk).readBytes()
            assertArrayEquals("chunk $chunk", file.copyOfRange(1234, file.size), got)
        }
    }

    @Test
    fun `asks for nothing before the offset and nothing past the end`() {
        val calls = mutableListOf<LongRange>()
        ranged(4000, 600, calls).readBytes()
        assertEquals(listOf(4000L until 4600L, 4600L until 5000L), calls)
    }

    @Test
    fun `an empty chunk ends the stream instead of looping`() {
        val input = RangedInput(0, 100, 10) { _, _ -> ByteArray(0) }
        assertEquals(-1, input.read())
    }

    @Test
    fun `skipFully reaches the offset even on a stream that will not skip`() {
        val stubborn = object : ByteArrayInputStream(file) {
            override fun skip(n: Long): Long = 0
        }
        RangedInput.skipFully(stubborn, 300)
        assertEquals(file[300].toInt() and 0xff, stubborn.read())
    }
}

package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPeekTest {

    @Test
    fun `a duration that is not known yet loops the whole file`() {
        assertTrue(VideoPeek.windowOf(0L).isEmpty())
        assertTrue(VideoPeek.windowOf(-1L).isEmpty())
    }

    @Test
    fun `a short video is shown whole rather than sliced`() {
        // His case: a two-second video is already a glance.
        assertTrue(VideoPeek.windowOf(2_000L).isEmpty())
        assertTrue(VideoPeek.windowOf(VideoPeek.MAX_MS).isEmpty())
    }

    @Test
    fun `a long video gives a window inside it, never at the very start`() {
        val w = VideoPeek.windowOf(10 * 60_000L)
        assertFalse(w.isEmpty())
        assertTrue("starts after the opening frames", w.first > 0L)
        assertTrue("ends inside the file", w.last < 10 * 60_000L)
    }

    @Test
    fun `the window is never shorter than the floor or longer than the cap`() {
        // Every duration from just over the cap to two hours, in steps that land on the
        // awkward ones: the arithmetic must not produce a one-frame window anywhere.
        var d = VideoPeek.MAX_MS + 1
        while (d <= 2 * 60 * 60_000L) {
            val w = VideoPeek.windowOf(d)
            val length = w.last - w.first + 1
            assertTrue("$d gave a $length ms window", length >= VideoPeek.MIN_MS)
            assertTrue("$d gave a $length ms window", length <= VideoPeek.MAX_MS)
            assertTrue("$d ran past the end", w.last < d)
            d = (d * 1.37).toLong() + 991
        }
    }

    @Test
    fun `eight seconds takes about an eighth, floored at the minimum`() {
        val w = VideoPeek.windowOf(8_000L)
        assertEquals(VideoPeek.MIN_MS, w.last - w.first + 1)
    }

    @Test
    fun `an empty window never asks to loop and starts at zero`() {
        val w = VideoPeek.windowOf(0L)
        assertEquals(0L, VideoPeek.loopStart(w))
        assertFalse(VideoPeek.shouldLoop(999_999L, 1_000L, w))
    }

    @Test
    fun `a real window loops at its end and not before`() {
        val w = VideoPeek.windowOf(60_000L)
        assertEquals(w.first, VideoPeek.loopStart(w))
        assertFalse(VideoPeek.shouldLoop(w.first, 60_000L, w))
        assertFalse(VideoPeek.shouldLoop(w.last - 1, 60_000L, w))
        assertTrue(VideoPeek.shouldLoop(w.last, 60_000L, w))
        assertTrue(VideoPeek.shouldLoop(w.last + 500, 60_000L, w))
    }

    @Test
    fun `running past the end of the file loops even inside the window`() {
        // A decoder that reports a duration shorter than the window was computed from. Without
        // this the loop would sit on the last frame waiting for a position it never reaches.
        val w = 1_000L until 5_000L
        assertTrue(VideoPeek.shouldLoop(3_000L, 3_000L, w))
    }
}

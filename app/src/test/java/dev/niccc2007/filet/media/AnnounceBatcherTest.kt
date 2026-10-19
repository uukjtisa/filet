package dev.niccc2007.filet.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media announce queue: what reaches the scanner, how many times, and when.
 *
 * The fault these pin down is volume, not correctness - a gallery that is right after a
 * hundred scanner sessions looks exactly as right as one after a single session, and only the
 * battery tells them apart. Every case below is one a pass-through (one scan per call, nothing
 * remembered) gets wrong, and says so.
 */
class AnnounceBatcherTest {

    private fun drain(b: AnnounceBatcher, upTo: Long, step: Long = 100): List<List<String>> {
        val out = ArrayList<List<String>>()
        var t = 0L
        while (t <= upTo) {
            val batch = b.take(t)
            if (batch.isNotEmpty()) out += batch
            t += step
        }
        return out
    }

    @Test
    fun `the same path written twice before the batch goes is one announce`() {
        val b = AnnounceBatcher()
        b.written(listOf("/s/a.jpg"), now = 0)
        b.written(listOf("/s/a.jpg"), now = 300)
        // A pass-through hands the scanner two sessions here.
        assertEquals(listOf(listOf("/s/a.jpg")), drain(b, 5_000))
    }

    @Test
    fun `nothing goes before the writing has been quiet for the window`() {
        val b = AnnounceBatcher(quietMs = 1_500)
        b.written(listOf("/s/a.jpg"), now = 0)
        assertTrue(b.take(1_499).isEmpty())
        assertEquals(listOf("/s/a.jpg"), b.take(1_500))
        assertEquals(-1L, b.dueAt())
    }

    @Test
    fun `a burst of a hundred files collapses into one hand-over`() {
        val b = AnnounceBatcher(quietMs = 1_500, maxWaitMs = 8_000)
        val batches = ArrayList<List<String>>()
        for (i in 0 until 100) {
            val now = i * 10L
            b.written(listOf("/s/$i.jpg"), now)
            b.take(now).let { if (it.isNotEmpty()) batches += it }
        }
        for (t in 1_000L..4_000L step 100) b.take(t).let { if (it.isNotEmpty()) batches += it }
        assertEquals(1, batches.size)
        assertEquals(100, batches[0].size)
    }

    @Test
    fun `a transfer that never pauses still reaches the scanner every max-wait`() {
        val b = AnnounceBatcher(quietMs = 1_500, maxWaitMs = 8_000)
        val batches = ArrayList<Pair<Long, Int>>()
        // One file every second for twenty seconds: never quiet for 1.5 s.
        for (t in 0L..20_000L step 100) {
            if (t % 1_000 == 0L) b.written(listOf("/s/$t.mp4"), t)
            b.take(t).let { if (it.isNotEmpty()) batches += t to it.size }
        }
        assertTrue("flushed during the transfer: $batches", batches.isNotEmpty() && batches.first().first <= 8_000)
        assertTrue(batches.size in 2..3)
    }

    @Test
    fun `a batch is capped and the rest follows on the next turn`() {
        val b = AnnounceBatcher(maxBatch = 3)
        b.written((1..7).map { "/s/$it" }, now = 0)
        assertEquals(3, b.take(1_500).size)
        assertEquals(3, b.take(1_500).size)
        assertEquals(1, b.take(1_500).size)
        assertTrue(b.take(1_500).isEmpty())
    }

    @Test
    fun `an echo of a path Filet wrote is dropped`() {
        val b = AnnounceBatcher(echoMs = 120_000)
        b.written(listOf("/s/a.jpg"), now = 0)
        assertEquals(listOf("/s/a.jpg"), b.take(1_500))
        // The catch-up sweep finds it missing from MediaStore because the scan is in flight.
        b.found(listOf("/s/a.jpg"), now = 10_000)
        assertEquals(-1L, b.dueAt())
        assertTrue(b.wroteRecently("/s/a.jpg", 10_000))
    }

    @Test
    fun `a path Filet did not write is not suppressed`() {
        val b = AnnounceBatcher()
        b.written(listOf("/s/a.jpg"), now = 0)
        b.take(1_500)
        b.found(listOf("/s/b.jpg", "/s/a.jpg"), now = 10_000)
        assertEquals(listOf("/s/b.jpg"), b.take(11_500))
        assertFalse(b.wroteRecently("/s/b.jpg", 11_500))
    }

    @Test
    fun `after the echo window a still-missing path is announced again`() {
        val b = AnnounceBatcher(echoMs = 120_000)
        b.written(listOf("/s/a.jpg"), now = 0)
        b.take(1_500)
        b.found(listOf("/s/a.jpg"), now = 120_000)
        assertEquals(listOf("/s/a.jpg"), b.take(121_500))
    }

    @Test
    fun `a second genuine write is announced again - the content changed`() {
        val b = AnnounceBatcher()
        b.written(listOf("/s/a.jpg"), now = 0)
        b.take(1_500)
        b.written(listOf("/s/a.jpg"), now = 5_000)
        assertEquals(listOf("/s/a.jpg"), b.take(6_500))
    }

    @Test
    fun `visible means an activity on screen, not a foreground service`() {
        assertTrue(Visibility.isVisibleImportance(100))
        assertTrue(Visibility.isVisibleImportance(200))
        // FOREGROUND_SERVICE: the process is alive behind playback or sharing, nobody looking.
        assertFalse(Visibility.isVisibleImportance(125))
        assertFalse(Visibility.isVisibleImportance(300))
        assertFalse(Visibility.isVisibleImportance(400))
    }
}

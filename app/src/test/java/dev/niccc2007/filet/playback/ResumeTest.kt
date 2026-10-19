package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Carry on keeps, how it states a position, and how its thumbnail cache evicts. */
class ResumeTest {

    private fun keep(at: Long, length: Long) = ResumeStore.worthKeeping(at, length)

    @Test
    fun `halfway through anything fifteen seconds or longer is kept`() {
        for (length in listOf(15_000L, 30_000L, 39_000L, 40_000L, 90_000L, 600_000L, 3_000_000L, 14_400_000L)) {
            assertTrue("halfway through $length ms", keep(length / 2, length))
        }
    }

    @Test
    fun `a forty second clip at its middle was the case the old rule could not keep`() {
        // Old rule: at least 10s in AND under (length - 20s). At 40s long the middle is 20s,
        // and 20 < 20 is false.
        assertTrue(keep(20_000, 40_000))
    }

    @Test
    fun `a long film keeps the original thresholds exactly`() {
        val film = 3L * 60 * 60 * 1000
        assertFalse(keep(9_999, film))
        assertTrue(keep(10_000, film))
        assertTrue(keep(film - 20_001, film))
        assertFalse(keep(film - 20_000, film))
    }

    @Test
    fun `a short clip scales both thresholds to its length`() {
        // 15s: started at a tenth (1.5s), finished within a twentieth (0.75s) of the end.
        assertFalse(keep(1_499, 15_000))
        assertTrue(keep(1_500, 15_000))
        assertTrue(keep(14_249, 15_000))
        assertFalse(keep(14_250, 15_000))
    }

    @Test
    fun `under fifteen seconds is never kept`() {
        assertFalse(keep(7_000, 14_999))
    }

    @Test
    fun `an unknown length is kept on position alone`() {
        assertFalse(keep(9_999, 0))
        assertTrue(keep(10_000, 0))
    }

    private fun point(at: Long, length: Long) =
        ResumeStore.Point("local:///x.mp4", "x.mp4", true, at, length, 0L)

    @Test
    fun `the prompt states the position as a clock`() {
        assertEquals("23:41 of 48:02", resumeWhere(point(1_421_000, 2_882_000)))
        assertEquals("1:02:05 of 2:00:00", resumeWhere(point(3_725_000, 7_200_000)))
        assertEquals("23:41", resumeWhere(point(1_421_000, 0)))
    }

    @Test
    fun `the thumbnail cache evicts the least recently used first`() {
        val lru = Lru<String, Int>(3)
        lru.put("a", 1)
        lru.put("b", 2)
        lru.put("c", 3)
        assertEquals(1, lru["a"]) // a is now the most recent
        lru.put("d", 4)
        assertNull(lru["b"])
        assertEquals(listOf("c", "a", "d"), lru.keys())
        assertEquals(3, lru.size)
    }

    @Test
    fun `thumbnails are never larger than the bound and keep their shape`() {
        assertEquals(Pair(160, 90), fitInside(1920, 1080, ResumeThumbs.W, ResumeThumbs.H))
        assertEquals(Pair(56, 100), fitInside(1080, 1920, ResumeThumbs.W, ResumeThumbs.H))
        assertEquals(Pair(100, 50), fitInside(100, 50, ResumeThumbs.W, ResumeThumbs.H))
        assertEquals(4, sampleFor(1000, 1000, 160, 100))
        assertEquals(1, sampleFor(200, 120, 160, 100))
    }
}

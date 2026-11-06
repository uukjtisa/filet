package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Carry on keeps, how it states a position, and how its thumbnail cache evicts. */
class ResumeTest {

    @Test
    fun `every stop past 0 00 is kept, for every kind of media`() {
        // Issue #53: one record, no thresholds. 0:09 of a film, 0:05 of a short clip, a skip to
        // the middle, a song left by Next three seconds in, 9:50 of ten minutes, and the end.
        for (at in listOf(9_000L, 5_000L, 300_000L, 3_000L, 590_000L, 600_000L, 1L)) {
            assertTrue("$at ms", ResumeStore.keeps(at))
        }
    }

    @Test
    fun `a stop at 0 00 changes nothing`() {
        assertFalse(ResumeStore.keeps(0L))
        assertFalse(ResumeStore.keeps(-1L))
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

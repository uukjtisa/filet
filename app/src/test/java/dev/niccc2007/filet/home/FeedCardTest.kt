package dev.niccc2007.filet.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a Home feed card's Show and Density settings draw. */
class FeedCardTest {

    @Test
    fun `comfortable is the row as it was before density existed`() {
        val m = FeedCard.metrics(0f)
        assertEquals(7f, m.padV)
        assertEquals(30f, m.thumb)
        assertEquals(9f, m.corner)
        assertEquals(15f, m.glyph)
        assertEquals(11f, m.gap)
        assertEquals(12.5f, m.nameSp)
        assertFalse(m.folded)
    }

    @Test
    fun `every measure shrinks as density rises, and none grows`() {
        var last = FeedCard.metrics(0f)
        for (i in 1..20) {
            val m = FeedCard.metrics(i / 20f)
            assertTrue(m.padV <= last.padV && m.thumb <= last.thumb && m.gap <= last.gap && m.nameSp <= last.nameSp)
            last = m
        }
        assertTrue(last.thumb < 30f && last.padV < 7f)
    }

    @Test
    fun `only the compact end folds the second line`() {
        assertFalse(FeedCard.metrics(FeedCard.FOLD_AT - 0.01f).folded)
        assertTrue(FeedCard.metrics(FeedCard.FOLD_AT).folded)
        assertTrue(FeedCard.metrics(1f).folded)
    }

    @Test
    fun `a density out of range is clamped`() {
        assertEquals(FeedCard.metrics(0f), FeedCard.metrics(-3f))
        assertEquals(FeedCard.metrics(1f), FeedCard.metrics(9f))
    }

    @Test
    fun `counts are the four offered, eight by default, and the feed publishes the most`() {
        assertEquals(listOf(3, 5, 8, 12), FeedCard.COUNTS)
        assertTrue(FeedCard.DEFAULT_COUNT in FeedCard.COUNTS)
        assertEquals(FeedCard.COUNTS.max(), FeedCard.MAX_COUNT)
        assertEquals(FeedCard.MAX_COUNT, HomeFeed.SHOWN)
    }

    @Test
    fun `a typed count lands on the nearest offered`() {
        assertEquals(3, FeedCard.snap(0))
        assertEquals(5, FeedCard.snap(6))
        assertEquals(8, FeedCard.snap(8))
        assertEquals(12, FeedCard.snap(40))
    }
}

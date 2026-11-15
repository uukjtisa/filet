package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrawlFractionTest {
    @Test
    fun `a first build has nothing to measure against`() {
        assertNull(crawlFraction(500, 0))
    }

    @Test
    fun `a re-index measures against what it held before`() {
        assertEquals(0.25f, crawlFraction(250, 1000)!!, 0.0001f)
    }

    @Test
    fun `it never claims done before the crawl is`() {
        assertEquals(0.99f, crawlFraction(1000, 1000)!!, 0.0001f)
        assertEquals(0.99f, crawlFraction(5000, 1000)!!, 0.0001f)
    }
}

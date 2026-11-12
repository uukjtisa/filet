package dev.niccc2007.filet.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the other side is told about free space.
 *
 * Nothing was sent before, which is why a phone mounted on a PC drew the used/free bar from the
 * PC's own C: drive - Explorer had no size and filled the space itself.
 */
class DavQuotaTest {

    // ── the cache: one report per share, never one for the whole server ──

    private val c = DavQuota.Report(usedBytes = 200, availableBytes = 50)
    private val d = DavQuota.Report(usedBytes = 900, availableBytes = 1100)

    @Test
    fun `two shares asked inside one window each get their own volume's figures`() {
        val cache = QuotaCache(5_000)
        assertEquals(c, cache.get("local:/C:/Users", 1_000) { c })
        // D: asked a second later, well inside C:'s window: its own figure, not C:'s.
        assertEquals(d, cache.get("local:/D:/Media", 2_000) { d })
        assertEquals(c, cache.get("local:/C:/Users", 3_000) { error("C: is still cached") })
        assertEquals(d, cache.get("local:/D:/Media", 3_000) { error("D: is still cached") })
    }

    @Test
    fun `a report is read again after its window, and an unreadable volume is never cached`() {
        val cache = QuotaCache(5_000)
        cache.get("r", 0) { c }
        assertEquals(d, cache.get("r", 5_000) { d })
        assertNull(cache.get("x", 0) { null })
        assertEquals(c, cache.get("x", 1) { c })
    }

    private val GB = 1_000_000_000L

    @Test
    fun `used is total minus free`() {
        val r = DavQuota.of(totalBytes = 128 * GB, usableBytes = 28 * GB)!!
        assertEquals(100 * GB, r.usedBytes)
        assertEquals(28 * GB, r.availableBytes)
    }

    @Test
    fun `an unknown size says nothing rather than zero`() {
        // Zero free is a meaningful and alarming answer - a client that reads it refuses to copy
        // anything. A missing property is the honest way to say "no idea".
        assertNull(DavQuota.of(null, 10 * GB))
        assertNull(DavQuota.of(10 * GB, null))
        assertNull(DavQuota.of(null, null))
        assertEquals("", DavQuota.xml(null))
    }

    @Test
    fun `a nonsense total says nothing`() {
        assertNull(DavQuota.of(0, 10))
        assertNull(DavQuota.of(-1, 10))
        assertNull(DavQuota.of(10 * GB, -5))
    }

    @Test
    fun `free larger than total is clamped rather than passed on`() {
        // A provider reporting this is wrong rather than generous, and passing it through makes
        // the client's own arithmetic wrong.
        val r = DavQuota.of(totalBytes = 10 * GB, usableBytes = 50 * GB)!!
        assertEquals(10 * GB, r.availableBytes)
        assertEquals(0, r.usedBytes)
    }

    @Test
    fun `a transfer cap lowers what is advertised`() {
        // Otherwise a desktop starts a copy that is guaranteed to fail halfway, which is a worse
        // failure than being told up front.
        val r = DavQuota.of(totalBytes = 128 * GB, usableBytes = 40 * GB, capRemaining = 2 * GB)!!
        assertEquals(2 * GB, r.availableBytes)
        assertEquals(88 * GB, r.usedBytes)
    }

    @Test
    fun `a cap larger than the free space does not raise it`() {
        val r = DavQuota.of(totalBytes = 128 * GB, usableBytes = 4 * GB, capRemaining = 99 * GB)!!
        assertEquals(4 * GB, r.availableBytes)
    }

    @Test
    fun `an exhausted cap advertises nothing free`() {
        val r = DavQuota.of(totalBytes = 128 * GB, usableBytes = 40 * GB, capRemaining = 0)!!
        assertEquals(0, r.availableBytes)
    }

    @Test
    fun `a negative cap is treated as exhausted, not as unlimited`() {
        val r = DavQuota.of(totalBytes = 128 * GB, usableBytes = 40 * GB, capRemaining = -5)!!
        assertEquals(0, r.availableBytes)
    }

    @Test
    fun `the xml carries both properties in the DAV namespace`() {
        val x = DavQuota.xml(DavQuota.of(128 * GB, 28 * GB))
        assertTrue(x, x.contains("<D:quota-used-bytes>100000000000</D:quota-used-bytes>"))
        assertTrue(x, x.contains("<D:quota-available-bytes>28000000000</D:quota-available-bytes>"))
    }

    @Test
    fun `a collection entry carries the quota and a file does not`() {
        val q = DavQuota.of(128 * GB, 28 * GB)
        val dir = DavXml.Entry("/a/K/", true, 0, 0, "K", quota = q)
        val file = DavXml.Entry("/a/K/x.txt", false, 10, 0, "x.txt")
        val xml = DavXml.multiStatus(listOf(dir, file))

        // Per response, not per document: a PROPFIND answers for several resources at once, and
        // a quota on a file would be nonsense.
        val responses = xml.split("<D:response>").drop(1)
        assertEquals(2, responses.size)
        val forDir = responses.first { it.contains("/a/K/<") || it.contains(">K<") }
        val forFile = responses.first { it.contains("x.txt") }
        assertTrue("collection should carry it", forDir.contains("<D:quota-used-bytes>"))
        assertTrue("file should not", !forFile.contains("quota-"))
    }

    @Test
    fun `a collection with no known size carries no quota tags at all`() {
        val dir = DavXml.Entry("/a/K/", true, 0, 0, "K", quota = null)
        assertTrue(!DavXml.multiStatus(listOf(dir)).contains("quota-"))
    }
}

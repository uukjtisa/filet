package dev.niccc2007.filet.desktop.mount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The share's own space figure, the one place on the PC that does not show the local disk's. */
class ProbeTest {

    @Test
    fun `reads the phone's own multistatus`() {
        val xml = """<D:multistatus xmlns:D="DAV:"><D:response><D:propstat><D:prop>
            <D:quota-used-bytes>100000000000</D:quota-used-bytes>
            <D:quota-available-bytes>28000000000</D:quota-available-bytes>
            </D:prop></D:propstat></D:response></D:multistatus>"""
        assertEquals(Space(100_000_000_000L, 28_000_000_000L), Probe.parseSpace(xml))
    }

    @Test
    fun `any namespace prefix or none`() {
        assertEquals(Space(1, 2), Probe.parseSpace("<d:quota-used-bytes>1</d:quota-used-bytes><d:quota-available-bytes>2</d:quota-available-bytes>"))
        assertEquals(Space(1, 2), Probe.parseSpace("<quota-used-bytes xmlns=\"DAV:\"> 1 </quota-used-bytes><quota-available-bytes>2</quota-available-bytes>"))
    }

    @Test
    fun `missing or negative figures give nothing rather than a wrong number`() {
        assertNull(Probe.parseSpace("<D:quota-used-bytes>1</D:quota-used-bytes>"))
        assertNull(Probe.parseSpace("<D:quota-used-bytes>-1</D:quota-used-bytes><D:quota-available-bytes>2</D:quota-available-bytes>"))
        assertNull(Probe.parseSpace(""))
    }

    @Test
    fun `describe is decimal like Explorer`() {
        assertEquals("28.0 GB free of 128 GB", Probe.describe(Space(100_000_000_000L, 28_000_000_000L)))
        assertEquals("512 B", Probe.size(512))
        assertEquals("1.5 MB", Probe.size(1_500_000))
    }
}

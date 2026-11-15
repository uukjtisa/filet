package dev.niccc2007.filet.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One link format for every kind, read back exactly, and nothing a drive cannot be. */
class FiletLinkTest {

    private val drive = DriveLink("Studio Pixel", "k3f9q2", listOf("192.168.5.22", "10.0.0.4"), 8080, "a/tres3")

    @Test
    fun `both forms read back to the same drive`() {
        val web = drive.link().web()
        assertTrue(web, web.startsWith(FiletLink.WEB + "#drive?v=1&"))
        assertEquals(drive, DriveLink.of(FiletLink.parse(web)!!))
        assertEquals(drive, DriveLink.of(FiletLink.parse(drive.link().app())!!))
    }

    @Test
    fun `the details stay in the fragment, where no server sees them`() {
        val web = drive.link().web()
        assertTrue(web.substringBefore('#') == FiletLink.WEB)
        assertTrue("tres3" !in web.substringBefore('#'))
    }

    @Test
    fun `a link from a newer Filet, or not a Filet link at all, is not read`() {
        assertNull(FiletLink.parse(FiletLink.WEB + "#drive?v=2&h=1.2.3.4&p=1&s=a/x"))
        assertNull(FiletLink.parse(FiletLink.WEB + "#nothing?v=1"))
        assertNull(FiletLink.parse("https://example.com/#drive?v=1&h=1.2.3.4&p=80&s=a/x"))
        assertNull(FiletLink.parse("filet://drive?h=1.2.3.4&p=80&s=a/x"))
    }

    @Test
    fun `a link from a newer Filet is still Filet's, so it is never opened as a page or a file`() {
        val newer = FiletLink.WEB + "#drive?v=2&h=1.2.3.4&p=1&s=a/x"
        assertNull(FiletLink.parse(newer))
        assertTrue(FiletLink.claims(newer))
        assertTrue(FiletLink.claims(drive.link().app()))
        assertTrue(FiletLink.claims("FILET://drive?v=1"))
        assertTrue(FiletLink.claims(FiletLink.WEB + "/#drive?v=1"))
        assertEquals(false, FiletLink.claims("https://example.com/filet/l#drive?v=1"))
        assertEquals(false, FiletLink.claims("content://com.android.providers.downloads.documents/document/12"))
    }

    @Test
    fun `a drive refuses hosts, ports and shares that are not one`() {
        fun d(h: String, p: String, s: String) =
            DriveLink.of(FiletLink(LinkKind.DRIVE, mapOf("h" to h, "p" to p, "s" to s)))
        assertNull(d("evil.com/x", "80", "a/x"))
        assertNull(d("http://1.2.3.4", "80", "a/x"))
        assertNull(d("1.2.3.4", "70000", "a/x"))
        assertNull(d("1.2.3.4", "80", "../etc"))
        assertNull(d("", "80", "a/x"))
        assertEquals("1.2.3.4", d("1.2.3.4", "80", "/a/x/")!!.name)
        assertEquals("a/x", d("1.2.3.4", "80", "/a/x/")!!.share)
        assertEquals(listOf("fe80::1"), d("fe80::1", "80", "a/x")!!.hosts)
        // A zone names the SENDER's interface, which means nothing on the phone that scans it.
        assertNull(d("fe80::1%wlan0", "80", "a/x"))
    }

    @Test
    fun `a Portal link reads back, key and all, and nothing else passes`() {
        val key = "A".repeat(43)
        val p = PortalLink("https://litter.catbox.moe/x.bin", key)
        val web = p.link().web()
        // The key rides after the #, which no browser sends to the site.
        assertTrue(web.substringBefore('#').none { it == '?' } && "k=" in web.substringAfter('#'))
        assertEquals(p, PortalLink.of(FiletLink.parse(web)!!))
        fun l(m: String, k: String) = PortalLink.of(FiletLink(LinkKind.PORTAL, mapOf("m" to m, "k" to k)))
        assertNull(l("http://litter.catbox.moe/x.bin", key))
        assertNull(l("https://a b", key))
        assertNull(l("https://litter.catbox.moe/x.bin", "short"))
        assertNull(l("https://litter.catbox.moe/x.bin", "A".repeat(42) + "="))
    }
}

package dev.niccc2007.filet.desktop.mount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The addresses each platform is handed. A wrong one mounts nothing and reports a network fault. */
class DavTargetTest {
    private val t = DavTarget("192.168.100.6", 11111, false, "/a/", "tres3")

    @Test
    fun `url is scheme host port base code with a trailing slash`() {
        assertEquals("http://192.168.100.6:11111/a/tres3/", t.url)
        assertEquals("dav://192.168.100.6:11111/a/tres3/", t.gioUrl)
    }

    @Test
    fun `base without slashes is normalised`() {
        assertEquals("http://192.168.100.6:11111/a/tres3/", t.copy(base = "a").url)
        assertEquals("http://192.168.100.6:11111/a/tres3/", t.copy(base = "").url)
    }

    @Test
    fun `unc is the redirector form with DavWWWRoot`() {
        assertEquals("\\\\192.168.100.6@11111\\DavWWWRoot\\a\\tres3", t.unc)
    }

    @Test
    fun `ssl goes before the port in a unc`() {
        assertEquals("\\\\h@SSL@8443\\DavWWWRoot\\a\\x", DavTarget("h", 8443, true, "/a/", "x").unc)
        assertEquals("https://h:8443/a/x/", DavTarget("h", 8443, true, "/a/", "x").url)
    }

    @Test
    fun `ipv6 uses the literal-net spelling in a unc and brackets in a url`() {
        val v6 = DavTarget("fe80::1%3", 8321, false, "/a/", "c")
        assertEquals("\\\\fe80--1s3.ipv6-literal.net@8321\\DavWWWRoot\\a\\c", v6.unc)
        assertEquals("http://[fe80::1%3]:8321/a/c/", v6.url)
    }

    @Test
    fun `parseUnc reads back what unc writes`() {
        assertEquals(t, DavTarget.parseUnc(t.unc))
        val s = DavTarget("h", 8443, true, "/a/", "x")
        assertEquals(s, DavTarget.parseUnc(s.unc))
        val v6 = DavTarget("fe80::1%3", 8321, false, "/a/", "c")
        assertEquals(v6, DavTarget.parseUnc(v6.unc))
    }

    @Test
    fun `parseUnc ignores mappings that are not filet shares`() {
        assertNull(DavTarget.parseUnc("\\\\nas\\media"))
        assertNull(DavTarget.parseUnc("\\\\host@80\\DavWWWRoot\\other\\x"))
        assertNull(DavTarget.parseUnc("\\\\host@80\\DavWWWRoot\\a\\"))
        assertNull(DavTarget.parseUnc("Z:"))
    }

    @Test
    fun `cleanCode drops spaces keeps case and refuses symbols`() {
        assertEquals("ab12", DavTarget.cleanCode(" ab 12 "))
        assertEquals("Ab12", DavTarget.cleanCode("Ab12"))
        assertNull(DavTarget.cleanCode(""))
        assertNull(DavTarget.cleanCode("   "))
        assertNull(DavTarget.cleanCode("ab/12"))
        assertNull(DavTarget.cleanCode("ab-12"))
        assertNull(DavTarget.cleanCode("../x"))
    }
}

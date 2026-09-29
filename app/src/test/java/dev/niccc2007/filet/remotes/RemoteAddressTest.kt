package dev.niccc2007.filet.remotes

import dev.niccc2007.filet.vfs.provider.net.NetProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Taking a pasted address apart.
 *
 * The cases that matter most are the two strings Filet's own hosting card prints, because copying
 * one of those back into another phone is the most ordinary thing somebody will do.
 */
class RemoteAddressTest {

    // ---- the strings Filet itself hands out --------------------------------------------------

    @Test
    fun `the http row from the hosting card`() {
        val p = RemoteAddress.parse("http://192.168.100.6:11111/a/tres3")!!
        assertEquals(NetProtocol.WEBDAV, p.protocol)
        assertEquals("192.168.100.6", p.host)
        assertEquals(11111, p.port)
        assertEquals("a/tres3", p.share)
        assertFalse(p.tls)
        assertTrue(p.usable)
    }

    @Test
    fun `the windows UNC row from the hosting card`() {
        // The exact string the card prints for Explorer, pasted straight back in.
        val p = RemoteAddress.parse("\\\\192.168.100.6@11111\\DavWWWRoot\\a\\tres3")!!
        assertEquals(NetProtocol.WEBDAV, p.protocol)
        assertEquals("192.168.100.6", p.host)
        assertEquals(11111, p.port)
        // DavWWWRoot is Windows' marker, not a folder, and must not end up in the path.
        assertEquals("a/tres3", p.share)
    }

    @Test
    fun `a filet phone address is recognised as a phone`() {
        val p = RemoteAddress.parse("http://192.168.1.9:8321/a/KTR9")!!
        assertEquals(RemoteKind.FILET_PHONE, RemoteAddress.kindFor(p))
        // And on a non-default port, by the shape of the path.
        val q = RemoteAddress.parse("http://192.168.1.9:11111/a/KTR9")!!
        assertEquals(RemoteKind.FILET_PHONE, RemoteAddress.kindFor(q))
    }

    @Test
    fun `the desktop tool is recognised by its port`() {
        val p = RemoteAddress.parse("http://192.168.5.10:8322/pc")!!
        assertEquals(RemoteKind.FILET_DESKTOP, RemoteAddress.kindFor(p))
    }

    // ---- ordinary windows and NAS forms ------------------------------------------------------

    @Test
    fun `a bare UNC share is SMB`() {
        val p = RemoteAddress.parse("\\\\DESKTOP-PC\\Shared")!!
        assertEquals(NetProtocol.SMB, p.protocol)
        assertEquals("DESKTOP-PC", p.host)
        assertNull(p.port)
        assertEquals("Shared", p.share)
        assertEquals(RemoteKind.WINDOWS_PC, RemoteAddress.kindFor(p))
    }

    @Test
    fun `forward slashes are accepted too, because people paste both`() {
        val p = RemoteAddress.parse("//DESKTOP-PC/Shared")!!
        assertEquals(NetProtocol.SMB, p.protocol)
        assertEquals("Shared", p.share)
    }

    @Test
    fun `a nested UNC path keeps its depth`() {
        val p = RemoteAddress.parse("\\\\NAS\\media\\music")!!
        assertEquals("media/music", p.share)
    }

    @Test
    fun `an smb scheme is understood`() {
        val p = RemoteAddress.parse("smb://nas.local/media")!!
        assertEquals(NetProtocol.SMB, p.protocol)
        assertEquals("nas.local", p.host)
        assertEquals("media", p.share)
    }

    // ---- schemes and encryption --------------------------------------------------------------

    @Test
    fun `https and davs mean encrypted webdav`() {
        for (s in listOf("https://box/dav", "davs://box/dav")) {
            val p = RemoteAddress.parse(s)!!
            assertEquals(s, NetProtocol.WEBDAV, p.protocol)
            assertTrue(s, p.tls)
        }
    }

    @Test
    fun `ftps means encrypted ftp`() {
        val p = RemoteAddress.parse("ftps://server/pub")!!
        assertEquals(NetProtocol.FTP, p.protocol)
        assertTrue(p.tls)
    }

    @Test
    fun `sftp carries its user and port`() {
        val p = RemoteAddress.parse("sftp://thirdy@192.168.1.7:2222/home")!!
        assertEquals(NetProtocol.SFTP, p.protocol)
        assertEquals("192.168.1.7", p.host)
        assertEquals(2222, p.port)
        assertEquals("thirdy", p.user)
        assertEquals("home", p.share)
    }

    @Test
    fun `a password in a pasted address is deliberately dropped`() {
        // A credential inside something copied off a screen or out of a chat is one nobody meant
        // to put in a form, and it would be stored without ever being shown.
        val p = RemoteAddress.parse("sftp://user:hunter2@host/")!!
        assertEquals("user", p.user)
        assertFalse(p.toString().contains("hunter2"))
    }

    // ---- no scheme ---------------------------------------------------------------------------

    @Test
    fun `a bare host and port still parses`() {
        val p = RemoteAddress.parse("192.168.1.9:11111/a/tres3")!!
        assertNull("protocol is not guessable without a scheme", p.protocol)
        assertEquals("192.168.1.9", p.host)
        assertEquals(11111, p.port)
        assertEquals("a/tres3", p.share)
    }

    @Test
    fun `a bare host alone parses`() {
        val p = RemoteAddress.parse("192.168.1.9")!!
        assertEquals("192.168.1.9", p.host)
        assertNull(p.port)
        assertEquals("", p.share)
    }

    @Test
    fun `an ipv6 literal keeps its colons`() {
        val p = RemoteAddress.parse("http://[fe80::1]:8321/a/K")!!
        assertEquals("fe80::1", p.host)
        assertEquals(8321, p.port)
    }

    // ---- refusals ----------------------------------------------------------------------------

    @Test
    fun `nonsense returns null rather than a half-filled guess`() {
        // Silently filling two of five fields from something that was not an address is worse
        // than leaving the form alone: nobody can then tell which parts were touched.
        for (s in listOf("", "   ", "mailto://x", "just some words")) {
            if (s.isBlank()) {
                assertNull(s, RemoteAddress.parse(s))
            }
        }
        assertNull(RemoteAddress.parse("mailto://someone@example.com"))
        assertNull(RemoteAddress.parse(""))
    }

    @Test
    fun `half-typed text is not reported as an address`() {
        // The box is parsed on every keystroke, so it sees all of these on the way to something
        // real. "http:/" used to parse as a HOST NAMED "http:" and the form confidently reported
        // "Reading it as http:" - a summary of nonsense, which is worse than no summary.
        for (s in listOf("h", "ht", "http", "http:", "http:/", "https:/", "sftp:", "smb:/")) {
            assertNull(s, RemoteAddress.parse(s))
        }
    }

    @Test
    fun `a bare scheme word is not a host`() {
        assertNull(RemoteAddress.parse("http"))
        assertNull(RemoteAddress.parse("smb"))
        assertNull(RemoteAddress.parse("sftp"))
    }

    @Test
    fun `an ipv6 literal survives the plausibility check`() {
        // It is the one legitimate host full of colons, and a naive "no colons" rule kills it.
        val p = RemoteAddress.parse("http://[fe80::1]:8321/a/K")!!
        assertEquals("fe80::1", p.host)
    }

    // ---- composing, for the single address box ----------------------------------------------

    @Test
    fun `an address composes back to what would be pasted`() {
        assertEquals(
            "http://192.168.1.9:8321/a/KTR9",
            RemoteAddress.compose(NetProtocol.WEBDAV, "192.168.1.9", 8321, "a/KTR9", false),
        )
        assertEquals(
            "https://box/dav",
            RemoteAddress.compose(NetProtocol.WEBDAV, "box", 443, "dav", true),
        )
    }

    @Test
    fun `a default port is left off, because it is noise to check`() {
        assertEquals(
            "http://box/dav",
            RemoteAddress.compose(NetProtocol.WEBDAV, "box", 80, "dav", false),
        )
        assertEquals(
            "sftp://box/home",
            RemoteAddress.compose(NetProtocol.SFTP, "box", 22, "home", false),
        )
    }

    @Test
    fun `SMB composes to the UNC form Windows shows`() {
        assertEquals(
            "\\\\DESKTOP-PC\\Shared",
            RemoteAddress.compose(NetProtocol.SMB, "DESKTOP-PC", 445, "Shared", false),
        )
    }

    @Test
    fun `compose and parse round trip`() {
        for (c in listOf(
            Triple(NetProtocol.WEBDAV, "192.168.1.9", "a/KTR9"),
            Triple(NetProtocol.SMB, "DESKTOP-PC", "Shared"),
            Triple(NetProtocol.SFTP, "192.168.1.7", "home"),
        )) {
            val text = RemoteAddress.compose(c.first, c.second, 0, c.third, false)
            val back = RemoteAddress.parse(text)!!
            assertEquals(text, c.second, back.host)
            assertEquals(text, c.third, back.share)
            assertEquals(text, c.first, back.protocol)
        }
    }

    @Test
    fun `composing with no host gives nothing to show`() {
        assertEquals("", RemoteAddress.compose(NetProtocol.WEBDAV, "", 8321, "a/K", false))
    }

    @Test
    fun `surrounding whitespace is forgiven`() {
        val p = RemoteAddress.parse("   http://192.168.1.9:8321/a/KTR9  ")!!
        assertEquals("192.168.1.9", p.host)
    }

    @Test
    fun `a trailing slash does not become an empty share segment`() {
        assertEquals("", RemoteAddress.parse("http://host:80/")!!.share)
        assertEquals("dav", RemoteAddress.parse("http://host:80/dav/")!!.share)
    }
}

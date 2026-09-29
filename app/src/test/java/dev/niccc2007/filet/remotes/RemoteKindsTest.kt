package dev.niccc2007.filet.remotes

import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.vfs.provider.net.NetProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a kind implies.
 *
 * A mislabelled form is worse than an unlabelled one, so the words are asserted here rather than
 * only the protocol - a kind whose help text talks about the wrong thing is the failure this is
 * meant to prevent.
 */
class RemoteKindsTest {

    @Test
    fun `every kind names a protocol and a sensible port`() {
        for (k in RemoteKind.entries) {
            val s = RemoteKinds.specFor(k)
            assertTrue("$k port", s.defaultPort in 1..65535)
            assertTrue("$k host label", s.hostLabel.isNotBlank())
            assertTrue("$k blurb", k.blurb.isNotBlank())
        }
    }

    @Test
    fun `SFTP kinds do not ask for a share, because SFTP has none`() {
        // The old form showed a "share" box for everything. On SFTP there is no such concept, so
        // the field was asking for something that cannot be answered.
        val s = RemoteKinds.specFor(RemoteKind.MAC_OR_LINUX)
        assertEquals(NetProtocol.SFTP, s.protocol)
        assertNull(s.middleLabel)
        assertFalse(s.usesMiddle)
    }

    @Test
    fun `SMB kinds ask for a share and explain where to find it`() {
        for (k in listOf(RemoteKind.WINDOWS_PC, RemoteKind.NAS)) {
            val s = RemoteKinds.specFor(k)
            assertEquals("$k", NetProtocol.SMB, s.protocol)
            assertEquals("$k", 445, s.defaultPort)
            assertNotNull("$k", s.middleLabel)
            assertTrue("$k help", s.middleHelp.isNotBlank())
        }
    }

    @Test
    fun `a Windows PC and a NAS speak the same protocol but are explained differently`() {
        // The reason a kind is not just a protocol.
        val pc = RemoteKinds.specFor(RemoteKind.WINDOWS_PC)
        val nas = RemoteKinds.specFor(RemoteKind.NAS)
        assertEquals(pc.protocol, nas.protocol)
        assertTrue(pc.middleHelp != nas.middleHelp)
        assertTrue(pc.setupHint != nas.setupHint)
    }

    @Test
    fun `another phone needs no account`() {
        // The code is the credential and it lives in the path, so a user and password box would
        // be two more things to leave blank and wonder about.
        val s = RemoteKinds.specFor(RemoteKind.FILET_PHONE)
        assertFalse(s.needsCredentials)
        assertEquals(NetProtocol.WEBDAV, s.protocol)
        assertEquals(8321, s.defaultPort)
    }

    @Test
    fun `the desktop tool is its own kind on its own port`() {
        val s = RemoteKinds.specFor(RemoteKind.FILET_DESKTOP)
        assertEquals(NetProtocol.WEBDAV, s.protocol)
        assertEquals(8322, s.defaultPort)
        assertTrue(s.needsCredentials)
    }

    // ---- overriding the protocol -----------------------------------------------------------

    @Test
    fun `changing protocol moves the port and the share field with it`() {
        // Picking SFTP must not leave an SMB share box on screen asking for something SFTP has no
        // concept of.
        val sftp = RemoteKinds.specFor(RemoteKind.OTHER, NetProtocol.SFTP)
        assertEquals(22, sftp.defaultPort)
        assertNull(sftp.middleLabel)

        val smb = RemoteKinds.specFor(RemoteKind.OTHER, NetProtocol.SMB)
        assertEquals(445, smb.defaultPort)
        assertEquals("Share name", smb.middleLabel)

        val ftp = RemoteKinds.specFor(RemoteKind.OTHER, NetProtocol.FTP)
        assertEquals(21, ftp.defaultPort)
        assertNull(ftp.middleLabel)
    }

    @Test
    fun `the same protocol returns the kind's own wording untouched`() {
        val base = RemoteKinds.specFor(RemoteKind.WINDOWS_PC)
        assertEquals(base, RemoteKinds.specFor(RemoteKind.WINDOWS_PC, NetProtocol.SMB))
    }

    // ---- recognising an existing connection ------------------------------------------------

    private fun conn(p: NetProtocol, port: Int) =
        NetConnection(id = "1", protocol = p, label = "x", host = "h", port = port, user = "", password = "")

    @Test
    fun `an existing connection is matched to a kind for the edit form`() {
        assertEquals(RemoteKind.WINDOWS_PC, RemoteKinds.kindFor(conn(NetProtocol.SMB, 445)))
        assertEquals(RemoteKind.MAC_OR_LINUX, RemoteKinds.kindFor(conn(NetProtocol.SFTP, 22)))
        assertEquals(RemoteKind.FILET_PHONE, RemoteKinds.kindFor(conn(NetProtocol.WEBDAV, 8321)))
        assertEquals(RemoteKind.FILET_DESKTOP, RemoteKinds.kindFor(conn(NetProtocol.WEBDAV, 8322)))
        assertEquals(RemoteKind.OTHER, RemoteKinds.kindFor(conn(NetProtocol.WEBDAV, 80)))
        assertEquals(RemoteKind.OTHER, RemoteKinds.kindFor(conn(NetProtocol.FTP, 21)))
    }

    // ---- blanks and names ------------------------------------------------------------------

    @Test
    fun `a blank connection arrives with the protocol and port already decided`() {
        val c = RemoteKinds.blank(RemoteKind.WINDOWS_PC, "7")
        assertEquals("7", c.id)
        assertEquals(NetProtocol.SMB, c.protocol)
        assertEquals(445, c.port)
        assertFalse(c.anonymous)
    }

    @Test
    fun `a phone blank is anonymous, because the code is the credential`() {
        assertTrue(RemoteKinds.blank(RemoteKind.FILET_PHONE, "1").anonymous)
    }

    @Test
    fun `a name is never blank`() {
        assertEquals("Work NAS", RemoteKinds.nameFor("  Work NAS  ", "1.2.3.4", RemoteKind.NAS))
        assertEquals("1.2.3.4", RemoteKinds.nameFor("", "1.2.3.4", RemoteKind.NAS))
        assertEquals("  ", "A NAS or home server", RemoteKinds.nameFor("  ", "  ", RemoteKind.NAS))
    }

    // ---- the access code, and the path it really lives at -----------------------------------

    @Test
    fun `a phone code becomes the base path it is served at`() {
        // A hosting phone serves at /a/<code>, and the code is not broadcast - so the form shows
        // the code and this builds the path.
        assertEquals("a/KTR9", RemoteKinds.shareFromCode(RemoteKind.FILET_PHONE, "KTR9"))
        assertEquals("a/KTR9", RemoteKinds.shareFromCode(RemoteKind.FILET_PHONE, "  KTR9  "))
    }

    @Test
    fun `a pasted full path is not doubled up`() {
        // Somebody pasting `a/KTR9` instead of typing `KTR9` must not produce `a/a/KTR9`.
        assertEquals("a/KTR9", RemoteKinds.shareFromCode(RemoteKind.FILET_PHONE, "a/KTR9"))
        assertEquals("a/KTR9", RemoteKinds.shareFromCode(RemoteKind.FILET_PHONE, "/a/KTR9/"))
    }

    @Test
    fun `an empty code produces no path at all`() {
        // Rather than the bare prefix, which would build a URL that resolves to nothing.
        assertEquals("", RemoteKinds.shareFromCode(RemoteKind.FILET_PHONE, ""))
        assertEquals("", RemoteKinds.shareFromCode(RemoteKind.FILET_PHONE, "   "))
    }

    @Test
    fun `other kinds pass their share through untouched`() {
        assertEquals("Users", RemoteKinds.shareFromCode(RemoteKind.WINDOWS_PC, "Users"))
        assertEquals("pc", RemoteKinds.shareFromCode(RemoteKind.FILET_DESKTOP, "pc"))
        assertEquals("media/music", RemoteKinds.shareFromCode(RemoteKind.NAS, "media/music"))
    }

    @Test
    fun `a stored path shows as just the code`() {
        assertEquals("KTR9", RemoteKinds.codeFromShare(RemoteKind.FILET_PHONE, "a/KTR9"))
        assertEquals("KTR9", RemoteKinds.codeFromShare(RemoteKind.FILET_PHONE, "/a/KTR9/"))
    }

    @Test
    fun `the bare prefix from discovery shows as an empty code`() {
        // This was the bug: discovery supplies the prefix `a`, and showing it raw put the letter
        // "a" into a box labelled Access code.
        assertEquals("", RemoteKinds.codeFromShare(RemoteKind.FILET_PHONE, "a"))
        assertEquals("", RemoteKinds.codeFromShare(RemoteKind.FILET_PHONE, "/a/"))
    }

    @Test
    fun `code and path round trip`() {
        for (code in listOf("KTR9", "ZZ4M", "abcd")) {
            val path = RemoteKinds.shareFromCode(RemoteKind.FILET_PHONE, code)
            assertEquals(code, RemoteKinds.codeFromShare(RemoteKind.FILET_PHONE, path))
        }
    }

    // ---- saving ----------------------------------------------------------------------------

    @Test
    fun `a host is always required`() {
        val s = RemoteKinds.specFor(RemoteKind.MAC_OR_LINUX)
        assertFalse(RemoteKinds.canSave("", "", s))
        assertFalse(RemoteKinds.canSave("   ", "", s))
        assertTrue(RemoteKinds.canSave("192.168.1.7", "", s))
    }

    @Test
    fun `an SMB share name is required, because it is part of the address`() {
        // Without it the connection fails with an unhelpful access-denied rather than saying what
        // is missing.
        val s = RemoteKinds.specFor(RemoteKind.WINDOWS_PC)
        assertFalse(RemoteKinds.canSave("192.168.1.5", "", s))
        assertTrue(RemoteKinds.canSave("192.168.1.5", "Users", s))
    }

    @Test
    fun `a WebDAV path is optional`() {
        // A WebDAV server can serve straight from its root, so an empty path is a real answer.
        val s = RemoteKinds.specFor(RemoteKind.FILET_DESKTOP)
        assertTrue(RemoteKinds.canSave("192.168.1.5", "", s))
    }
}

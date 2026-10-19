package dev.niccc2007.filet.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTxtTest {

    /**
     * The literal strings, not the constants. These are what the phone's `DavBeacon` puts on the
     * wire; asserting through the constants would still pass if both ends changed together, and an
     * already-installed phone would stop seeing every PC.
     */
    @Test fun `the wire format is the phone's, character for character`() {
        assertEquals("_filet-dav._tcp.local.", PeerTxt.JMDNS_TYPE)
        val txt = PeerTxt.encode("/a/", "Documents", true, "dev-1")
        assertEquals(listOf("path", "scope", "w", "id"), txt.keys.toList())
        assertEquals(listOf("/a/", "Documents", "1", "dev-1"), txt.values.toList())
        assertEquals("0", PeerTxt.encode("/a/", "x", false, "d")["w"])
    }

    @Test fun `decode reads what encode wrote`() {
        val txt = PeerTxt.encode("/a/", "Whole phone", true, "dev-9")
        val h = PeerTxt.decode("Filet-Pixel-My share", "192.168.1.9", 8321) { txt[it] }
        assertEquals("Pixel-My share", h.name)
        assertEquals("/a/", h.basePath)
        assertEquals("Whole phone", h.scope)
        assertTrue(h.writable)
        assertEquals("dev-9", h.deviceId)
        val p = Peer.of(h)
        assertEquals(Peer("Pixel-My share", "192.168.1.9", 8321, false, "/a/", "Whole phone", true, "dev-9"), p)
        assertEquals("192.168.1.9:8321/Pixel-My share", p.key)
    }

    @Test fun `decode applies the phone's defaults to a sparse record`() {
        val h = PeerTxt.decode("Filet-Old", "10.0.0.2", 8321) { null }
        assertEquals("/", h.basePath)
        assertEquals("", h.scope)
        assertFalse(h.writable)
        assertEquals("", h.deviceId)
    }

    @Test fun `own announcements are recognised by id, and an empty id proves nothing`() {
        val mine = PeerTxt.decode("Filet-PC-Docs", "10.0.0.3", 8322) { mapOf("id" to "me")[it] }
        assertTrue(PeerTxt.isSelf(mine, "me"))
        assertFalse(PeerTxt.isSelf(mine, "other"))
        val anon = PeerTxt.decode("Filet-PC-Docs", "10.0.0.3", 8322) { null }
        assertFalse(PeerTxt.isSelf(anon, ""))
    }
}

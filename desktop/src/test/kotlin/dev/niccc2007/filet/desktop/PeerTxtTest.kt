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

    @Test fun `our own share heard before its TXT record is still us, by address`() {
        // JmDNS resolved this PC's own beacon with no attributes, so no id to compare.
        val local = PeerTxt.localHosts(listOf(java.net.InetAddress.getByName("192.168.5.20"), java.net.InetAddress.getByName("fe80::1%12")))
        val ownEarly = PeerTxt.decode("Filet-DESKTOP-PC-Documents", "192.168.5.20", 8322) { null }
        assertTrue(PeerTxt.isSelf(ownEarly, "me", local))
        assertTrue(PeerTxt.isSelf(PeerTxt.decode("Filet-DESKTOP-PC-D", "fe80::1%eth0", 8322) { null }, "me", local))
        // A phone on the same network is not.
        val phone = PeerTxt.decode("Filet-NCO-LX1-Whole Phone", "192.168.5.15", 11111) { null }
        assertFalse(PeerTxt.isSelf(phone, "me", local))
    }

    // ── one row per share of one device ──

    private val novaOld = Peer("NCO-LX1-Whole Phone", "192.168.5.11", 11111, false, "/a/", "Whole phone", true, "nova")
    private val novaNew = novaOld.copy(host = "192.168.5.15")

    @Test fun `one device heard on two addresses is one row, on the newest address, holding both`() {
        val list = Peers.merge(Peers.merge(emptyList(), novaOld), novaNew)
        val row = list.single()
        assertEquals("192.168.5.15", row.host)
        assertEquals(listOf("192.168.5.15", "192.168.5.11"), row.addresses)
        // Heard on the old one again: that is now the newest sighting.
        assertEquals("192.168.5.11", Peers.merge(list, novaOld).single().host)
    }

    @Test fun `a name re-registered with a suffix is the same share`() {
        val list = Peers.merge(Peers.merge(emptyList(), novaOld), novaNew.copy(name = "NCO-LX1-Whole Phone (2)"))
        assertEquals("NCO-LX1-Whole Phone", list.single().name)
    }

    @Test fun `two shares of one phone stay two rows, and hosts with no id are told apart by address`() {
        val music = novaOld.copy(name = "NCO-LX1-Music", port = 22222)
        assertEquals(2, Peers.merge(Peers.merge(emptyList(), novaOld), music).size)
        val old1 = Peer("Tablet", "10.0.0.5", 8321, false, "/a/")
        assertEquals(2, Peers.merge(Peers.merge(emptyList(), old1), old1.copy(host = "10.0.0.6")).size)
        assertEquals(1, Peers.merge(Peers.merge(emptyList(), old1), old1.copy(scope = "x")).size)
    }
}

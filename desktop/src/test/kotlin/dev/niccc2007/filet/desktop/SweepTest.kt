package dev.niccc2007.filet.desktop

import dev.niccc2007.filet.webdav.DavShare
import dev.niccc2007.filet.webdav.FiletHello
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepTest {

    private val wifi = HostAddress("192.168.5.20", 24, "Wi-Fi")

    @Test
    fun `a slash 24 is every host but the network, the broadcast and this PC`() {
        val t = Sweep.targets(listOf(wifi), setOf(8321))
        assertEquals(253, t.size)
        assertEquals("192.168.5.1" to 8321, t.first())
        assertEquals("192.168.5.254" to 8321, t.last())
        assertFalse(t.any { it.first == "192.168.5.20" || it.first == "192.168.5.0" || it.first == "192.168.5.255" })
    }

    @Test
    fun `a wider subnet is narrowed to this PC's slash 24, and each port is asked`() {
        val t = Sweep.targets(listOf(HostAddress("10.1.7.9", 16, "Ethernet")), setOf(8321, 11111))
        assertEquals(253 * 2, t.size)
        assertTrue(t.all { it.first.startsWith("10.1.7.") })
        assertTrue(("10.1.7.15" to 11111) in t)
    }

    @Test
    fun `two adapters are both swept, and neither of this PC's addresses is`() {
        val eth = HostAddress("192.168.4.13", 24, "Ethernet")
        val t = Sweep.targets(listOf(wifi, eth), setOf(8321)).map { it.first }
        assertEquals(506, t.size)
        assertFalse("192.168.4.13" in t)
        assertFalse("192.168.5.20" in t)
    }

    @Test
    fun `a small subnet stays small, and junk is skipped`() {
        assertEquals(listOf("10.0.0.2" to 8321), Sweep.targets(listOf(HostAddress("10.0.0.1", 30, "x")), setOf(8321)))
        assertTrue(Sweep.targets(listOf(HostAddress("fe80::1", 64, "x")), setOf(8321)).isEmpty())
    }

    @Test
    fun `what the server writes, the sweep reads back as the peers mDNS would list`() {
        val body = FiletHello.json(
            FiletHello.Identity("nova", "NCO-LX1", "phone"), 11111,
            listOf(DavShare(1, "Whole Phone", customRoot = "/storage/emulated/0") to true),
        )
        val hello = Sweep.parse(body)!!
        assertEquals("nova", hello.id)
        val p = Sweep.peersOf(hello, "192.168.5.15", 11111).single()
        // The name the beacon announces for this share, so mDNS and the sweep give one row.
        assertEquals("NCO-LX1-Whole Phone", p.name)
        assertEquals("nova", p.deviceId)
        assertEquals("192.168.5.15", p.host)
        assertTrue(p.writable)
        val heard = Peer("NCO-LX1-Whole Phone", "192.168.5.11", 11111, false, "/a/", "0", true, "nova")
        assertEquals(1, Peers.merge(listOf(heard), p).size)
    }

    @Test
    fun `anything that is not a Filet answer is ignored`() {
        assertNull(Sweep.parse("<html>router login</html>"))
        assertNull(Sweep.parse("{\"v\":1,\"name\":\"x\"}"))
        assertNull(Sweep.parse("{\"id\":\"x\"}"))
    }
}

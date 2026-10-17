package dev.niccc2007.filet.remotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which addresses a subnet sweep will actually touch.
 *
 * This is the one part of the scan worth testing and the one part that must not be wrong in
 * the permissive direction. A sweep that walks a /16 is 65,000 connects: useless on a phone,
 * slow enough to look broken, and indistinguishable from something somebody should be annoyed
 * about. Deciding that from a prefix length is arithmetic, so it is stated here rather than
 * discovered on a corporate network.
 */
class ServerScanTest {

    @Test
    fun `a 24 is swept, and it is this device's own subnet`() {
        val plan = ServerScan.sweepFor(listOf("192.168.1.42" to 24.toShort()))
        assertTrue(plan != null)
        val hosts = plan!!
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("192.168.1.") })
        // Not this device. Connecting to ourselves finds our own WebDAV host and offers to
        // add this phone as a remote of itself.
        assertFalse(hosts.contains("192.168.1.42"))
        // Nor the network and broadcast addresses.
        assertFalse(hosts.contains("192.168.1.0"))
        assertFalse(hosts.contains("192.168.1.255"))
    }

    @Test
    fun `the nearest addresses come first`() {
        // A router is usually 1 and the interesting machine is usually near by, so ordering
        // outward from this device's own number makes the first results arrive sooner. It is
        // also the difference between a list that fills in and a list that appears to hang.
        val hosts = ServerScan.sweepFor(listOf("192.168.1.100" to 24.toShort()))!!
        assertEquals("192.168.1.99", hosts[0])
        assertEquals("192.168.1.101", hosts[1])
    }

    @Test
    fun `anything wider than a 24 is refused`() {
        assertNull(ServerScan.sweepFor(listOf("10.0.0.5" to 16.toShort())))
        assertNull(ServerScan.sweepFor(listOf("10.0.0.5" to 8.toShort())))
    }

    @Test
    fun `a narrower subnet is still allowed`() {
        // A /25 or /26 is smaller than a /24, so it is within the limit. The plan is still
        // built across the last octet, which over-reaches slightly - a few addresses outside
        // the subnet, which simply do not answer. Walking fewer than exist would be the worse
        // error: it would silently miss servers.
        assertTrue(ServerScan.sweepFor(listOf("192.168.1.5" to 25.toShort())) != null)
    }

    @Test
    fun `a link-local address is skipped`() {
        // 169.254.x.x is what a phone has when DHCP never answered. Sweeping it finds nothing
        // and costs the same as sweeping a real network.
        assertNull(ServerScan.sweepFor(listOf("169.254.10.3" to 16.toShort())))
        assertNull(ServerScan.sweepFor(listOf("169.254.10.3" to 24.toShort())))
    }

    @Test
    fun `the first usable interface wins`() {
        // A phone on Wi-Fi with a VPN up has several. The usable one is taken rather than the
        // first one listed, which may be the VPN's /32 or a link-local.
        val plan = ServerScan.sweepFor(
            listOf(
                "169.254.1.1" to 16.toShort(),
                "10.1.2.3" to 8.toShort(),
                "192.168.8.20" to 24.toShort(),
            ),
        )
        assertTrue(plan!!.all { it.startsWith("192.168.8.") })
    }

    @Test
    fun `nothing usable is a null rather than a guess`() {
        assertNull(ServerScan.sweepFor(emptyList()))
        assertNull(ServerScan.sweepFor(listOf("not an address" to 24.toShort())))
    }
}

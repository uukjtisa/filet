package dev.niccc2007.filet.remotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the scan touches, what it keeps, and what it forgets.
 *
 * Two families of rule, both of which fail silently.
 *
 * **The sweep plan** must not be wrong in the permissive direction. A sweep that walks a /16 is
 * 65,000 connects: useless on a phone, slow enough to look broken, and indistinguishable from
 * something somebody should be annoyed about. Deciding that from a prefix length is arithmetic,
 * so it is stated here rather than discovered on a corporate network.
 *
 * **The forgetting rules** fail the other way, and did. This object lives as long as the
 * process, so a list that only ever grew kept a host from a network the phone had left - and a
 * stale row is indistinguishable from discovery working, because something IS in the list. The
 * other half of that bug is a rule one line from "drop everything", which would look like
 * discovery finding nothing. Both directions are wrong and neither announces itself, which is
 * why the decisions are pure functions with a test rather than conditions inside a callback.
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

    // ── what gets kept, merged and forgotten ──────────────────────────────────────────────

    private fun swept(host: String, port: Int = 445) = ServerScan.Found(
        host = host,
        name = host,
        protocol = dev.niccc2007.filet.vfs.provider.net.NetProtocol.SMB,
        port = port,
        byName = false,
    )

    private fun announced(
        host: String,
        name: String,
        port: Int = 445,
        path: String? = null,
    ) = ServerScan.Found(
        host = host,
        name = name,
        protocol = dev.niccc2007.filet.vfs.provider.net.NetProtocol.SMB,
        port = port,
        byName = true,
        path = path,
    )

    @Test
    fun `an announcement replaces the swept row for the same socket`() {
        // The point of discovery is the NAME. A list showing both rows shows one machine twice,
        // once as itself and once as a bare IP address - and the keys never match, because a
        // sweep cannot discover a path, which is why this is matched on the socket.
        val after = ServerScan.merge(listOf(swept("192.168.1.9")), announced("192.168.1.9", "nas"))
        assertEquals(1, after.size)
        assertEquals("nas", after[0].name)
        assertTrue(after[0].byName)
    }

    @Test
    fun `a swept row never displaces an announcement`() {
        // The sweep runs after the listening has already turned something up, so without this
        // the named row is replaced by the anonymous one a second later.
        val existing = listOf(announced("192.168.1.9", "nas"))
        assertEquals(existing, ServerScan.merge(existing, swept("192.168.1.9")))
    }

    @Test
    fun `the same row arriving twice changes nothing`() {
        // mDNS re-announces constantly. Appending is how one tablet becomes four rows.
        val existing = listOf(announced("192.168.1.9", "nas"))
        assertEquals(existing, ServerScan.merge(existing, announced("192.168.1.9", "nas")))
        val sweptOnce = listOf(swept("192.168.1.9"))
        assertEquals(sweptOnce, ServerScan.merge(sweptOnce, swept("192.168.1.9")))
    }

    @Test
    fun `two shares on one phone are two rows`() {
        // A phone hosting several shares rides ONE port, and each share is a different drive
        // with a different root. Keying on host and port alone collapsed them into one.
        var list = ServerScan.merge(emptyList(), announced("192.168.1.5", "phone", path = "a/one"))
        list = ServerScan.merge(list, announced("192.168.1.5", "phone", path = "a/two"))
        assertEquals(2, list.size)
    }

    @Test
    fun `named rows sort above anonymous ones`() {
        var list = ServerScan.merge(emptyList(), swept("192.168.1.2"))
        list = ServerScan.merge(list, announced("192.168.1.9", "nas"))
        assertEquals("nas", list[0].name)
    }

    @Test
    fun `a sweep on a new subnet drops what the last subnet found`() {
        // The bug this exists for: the scan outlives the network. A row from 192.168.5.x sat in
        // the list on a different Wi-Fi with a Test button that could never succeed.
        val stale = listOf(swept("192.168.5.13"), swept("192.168.5.20"))
        assertEquals(emptyList<ServerScan.Found>(), ServerScan.prune(stale, "192.168.1", "192.168.5"))
    }

    @Test
    fun `sweeping the same subnet again keeps everything`() {
        // Pressing the button twice must not empty the list it is filling.
        val rows = listOf(swept("192.168.1.9"))
        assertEquals(rows, ServerScan.prune(rows, "192.168.1", "192.168.1"))
        // And the very first sweep has no previous subnet to differ from.
        assertEquals(rows, ServerScan.prune(rows, "192.168.1", null))
    }

    @Test
    fun `an announcement survives a sweep of another subnet`() {
        // An announcement is live by definition - it is withdrawn through `onServiceLost` - and
        // a host reachable over two interfaces legitimately names an address off this subnet.
        val rows = listOf(announced("10.0.0.4", "nas"), swept("192.168.5.13"))
        val kept = ServerScan.prune(rows, "192.168.1", "192.168.5")
        assertEquals(1, kept.size)
        assertEquals("nas", kept[0].name)
    }

    @Test
    fun `an unreadable subnet is never used as a reason to drop rows`() {
        val rows = listOf(swept("192.168.5.13"))
        assertEquals(rows, ServerScan.prune(rows, null, "192.168.5"))
        assertNull(ServerScan.subnetOf("fe80::1"))
        assertNull(ServerScan.subnetOf("192.168.1"))
        assertNull(ServerScan.subnetOf("192.168.1.x"))
        assertEquals("192.168.1", ServerScan.subnetOf("192.168.1.9"))
    }

    @Test
    fun `this device is recognised by address and not by name`() {
        // What the name test could not do: the announced name is built from `Build.MODEL`, so
        // two phones of the same model each read the other's announcement as their own and
        // discarded the one host somebody was looking for.
        val mine = setOf("192.168.1.4", "10.0.0.2")
        assertTrue(ServerScan.isOwn("192.168.1.4", mine))
        assertTrue(ServerScan.isOwn("10.0.0.2", mine))
        assertFalse(ServerScan.isOwn("192.168.1.5", mine))
        assertFalse(ServerScan.isOwn("192.168.1.4", emptySet()))
    }

    @Test
    fun `a sweep retires the rows it could not reach again`() {
        // The half the subnet rule cannot catch: the server that went off while the phone
        // stayed on the same network. Its row is on the subnet being swept, which is exactly
        // why it looked legitimate and sat there for the life of the process.
        val rows = listOf(swept("192.168.1.9"), swept("192.168.1.20"))
        val kept = ServerScan.settle(rows, setOf("192.168.1.9/SMB/445"))
        assertEquals(1, kept.size)
        assertEquals("192.168.1.9", kept[0].host)
    }

    @Test
    fun `a sweep never retires an announcement`() {
        // A sweep tests four ports and knows nothing about what announced itself on a fifth,
        // so reading its silence as absence would delete the named hosts on every sweep -
        // the ones somebody actually wanted.
        val rows = listOf(announced("192.168.1.5", "phone", port = 8080))
        assertEquals(rows, ServerScan.settle(rows, emptySet()))
    }

    @Test
    fun `a sweep that reached nothing empties only the swept rows`() {
        val rows = listOf(swept("192.168.1.9"), announced("192.168.1.5", "phone"))
        val kept = ServerScan.settle(rows, emptySet())
        assertEquals(1, kept.size)
        assertTrue(kept[0].byName)
    }

    // ── the Filet half, folded in from the beacon ─────────────────────────────────────────

    private fun davHost(
        name: String = "phone",
        host: String = "192.168.1.5",
        port: Int = 5005,
        basePath: String = "/a",
        scope: String = "Whole phone",
        deviceId: String = "abc-123",
    ) = dev.niccc2007.filet.webdav.DavHost(
        name = name,
        host = host,
        port = port,
        basePath = basePath,
        scope = scope,
        writable = false,
        deviceId = deviceId,
    )

    @Test
    fun `a beacon host becomes a named webdav row that carries its path and id`() {
        // The Filet-to-Filet half arrives through the beacon, NOT through a second discovery of
        // Filet's own service type - two resolvers in one process compete for the single resolve
        // slot the platform allows, which broke this outright. See ServerScan.SERVICES.
        val row = ServerScan.fromHost(davHost())
        assertEquals("phone", row.name)
        assertEquals("192.168.1.5", row.host)
        assertEquals(5005, row.port)
        assertTrue(row.byName)
        // Trimmed of slashes, because the form builds the rest of the URL around it.
        assertEquals("a", row.path)
        // The id is what lets a saved entry recognise this device at a new address.
        assertEquals("abc-123", row.deviceId)
        assertEquals(
            dev.niccc2007.filet.vfs.provider.net.NetProtocol.WEBDAV,
            row.protocol,
        )
    }

    @Test
    fun `a host with no name falls back to its address rather than showing blank`() {
        assertEquals("192.168.1.5", ServerScan.fromHost(davHost(name = "")).host)
        assertEquals("192.168.1.5", ServerScan.fromHost(davHost(name = "")).name)
    }

    @Test
    fun `a host at the root carries no path rather than an empty one`() {
        // An empty string in the path would become part of the row's key and of the URL the
        // form builds, so "no path" has to be absent and not blank.
        assertNull(ServerScan.fromHost(davHost(basePath = "/")).path)
    }

    @Test
    fun `a filet phone the sweep also found is ONE row, not two`() {
        // The defect the single list exists to remove. The sweep checks 5005, so a Filet phone
        // answers it - and the screen showed the same device twice, once named from the beacon
        // and once as a bare address, leaving the reader to work out they were one machine.
        val sweptIt = ServerScan.Found(
            host = "192.168.1.5",
            name = "192.168.1.5",
            protocol = dev.niccc2007.filet.vfs.provider.net.NetProtocol.WEBDAV,
            port = 5005,
            byName = false,
        )
        val one = ServerScan.combine(listOf(sweptIt), listOf(davHost()))
        assertEquals(1, one.size)
        assertEquals("phone", one[0].name)
        assertTrue(one[0].byName)
    }

    @Test
    fun `combining keeps the servers that are not filet`() {
        val nas = swept("192.168.1.9")
        val both = ServerScan.combine(listOf(nas), listOf(davHost()))
        assertEquals(2, both.size)
        // Named rows first, so the phone is above the bare address.
        assertEquals("phone", both[0].name)
    }

    @Test
    fun `combining nothing with nothing is empty, not a crash`() {
        assertEquals(emptyList<ServerScan.Found>(), ServerScan.combine(emptyList(), emptyList()))
    }

    @Test
    fun `two shares on one phone stay two rows through the fold`() {
        val hosts = listOf(davHost(basePath = "/a/one"), davHost(basePath = "/a/two"))
        assertEquals(2, ServerScan.combine(emptyList(), hosts).size)
    }
}

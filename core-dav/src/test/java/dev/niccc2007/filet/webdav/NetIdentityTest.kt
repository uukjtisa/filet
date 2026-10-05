package dev.niccc2007.filet.webdav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetIdentityTest {

    private val wifi = NetId(101L, listOf("192.168.100.6", "fe80::1"))
    private val mobile = NetId(102L, listOf("10.114.22.9"))

    @Test
    fun `mobile data to wifi is a change`() {
        // The case reported: a tablet moved from mobile data to Wi-Fi, kept its process and its
        // bound socket, and went on announcing an address that no longer existed.
        assertTrue(NetIdentity.changed(mobile, wifi))
    }

    @Test
    fun `the same network describing itself again is not a change`() {
        // One association fires several callbacks. Acting on each one re-registers every
        // announcement several times, which makes the share flicker in other devices' lists.
        assertTrue(!NetIdentity.changed(wifi, wifi))
        assertFalse(NetIdentity.changed(wifi, wifi.copy(addresses = wifi.addresses.reversed())))
    }

    @Test
    fun `a new lease on the same network is a change`() {
        // Same handle, different address. The announcement carries the address, so this counts
        // even though the platform considers it the same network.
        assertTrue(NetIdentity.changed(wifi, wifi.copy(addresses = listOf("192.168.100.23"))))
    }

    @Test
    fun `going offline is remembered but not acted on`() {
        // Nothing to announce on and no interface to scan from. Tearing down here would mean
        // rebuilding from nothing on the way back instead of re-registering.
        assertFalse(NetIdentity.changed(wifi, NetId.NONE))
    }

    @Test
    fun `coming back is a change even onto the same network`() {
        // The registration made before the drop is bound to an interface that went away, so the
        // same network is still a new one to announce on.
        assertTrue(NetIdentity.changed(NetId.NONE, wifi))
    }

    @Test
    fun `the first network seen is a change`() {
        assertTrue(NetIdentity.changed(null, wifi))
    }

    @Test
    fun `spelling of an address does not make it a different one`() {
        val upper = wifi.copy(addresses = listOf("192.168.100.6", "FE80::1"))
        assertFalse(NetIdentity.changed(wifi, upper))
    }
}

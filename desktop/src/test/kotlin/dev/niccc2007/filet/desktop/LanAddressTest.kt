package dev.niccc2007.filet.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressTest {

    private val ethernet = HostAddress("192.168.5.13", 24, "Ethernet")
    private val wifi = HostAddress("192.168.100.17", 24, "Wi-Fi")
    private val hyperV = HostAddress("172.20.0.1", 20, "vEthernet (Default Switch)")

    @Test fun `the address on a peer's subnet wins over the routed one`() {
        // The case that printed an unreachable address: Ethernet routes, the phone is on Wi-Fi.
        assertEquals(wifi, LanAddress.choose(listOf(ethernet, wifi, hyperV), listOf("192.168.100.6"), null))
    }

    @Test fun `several addresses and nothing to tell them apart is no pick at all`() {
        assertNull(LanAddress.choose(listOf(ethernet, wifi, hyperV), emptyList(), null))
    }

    @Test fun `a hand pick is honoured only while the PC holds it`() {
        assertEquals(ethernet, LanAddress.choose(listOf(ethernet, wifi), emptyList(), "192.168.5.13"))
        assertNull(LanAddress.choose(listOf(ethernet, wifi), emptyList(), "10.0.0.9"))
    }

    @Test fun `a peer's subnet beats a stale hand pick`() {
        assertEquals(wifi, LanAddress.choose(listOf(ethernet, wifi), listOf("192.168.100.6"), "192.168.5.13"))
    }

    @Test fun `a single candidate is the pick`() {
        assertEquals(wifi, LanAddress.choose(listOf(wifi), emptyList(), null))
        assertNull(LanAddress.choose(emptyList(), listOf("192.168.100.6"), null))
    }

    @Test fun `subnet arithmetic honours the prefix`() {
        assertTrue(LanAddress.sameSubnet(wifi, "192.168.100.250"))
        assertFalse(LanAddress.sameSubnet(wifi, "192.168.101.2"))
        assertTrue(LanAddress.sameSubnet(hyperV, "172.20.15.9"))
        assertFalse(LanAddress.sameSubnet(hyperV, "172.20.16.1"))
        assertFalse(LanAddress.sameSubnet(wifi, "fe80::1"))
        assertFalse(LanAddress.sameSubnet(wifi, "300.1.1.1"))
    }
}

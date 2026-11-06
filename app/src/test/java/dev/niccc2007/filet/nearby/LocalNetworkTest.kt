package dev.niccc2007.filet.nearby

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When Filet may use the local network: issue #38's gate. */
class LocalNetworkTest {

    @Test
    fun `below Android 17 the network is simply there`() {
        assertTrue(LocalNetwork.allowed(sdk = 36, granted = false))
        assertTrue(LocalNetwork.allowed(sdk = 26, granted = false))
        assertFalse(LocalNetwork.asks(36))
    }

    @Test
    fun `from Android 17 it is the permission`() {
        assertTrue(LocalNetwork.asks(37))
        assertTrue("granted", LocalNetwork.allowed(sdk = 37, granted = true))
        assertFalse("refused: no listener, so no device picker", LocalNetwork.allowed(sdk = 37, granted = false))
        assertFalse("and on every later version", LocalNetwork.allowed(sdk = 38, granted = false))
    }
}

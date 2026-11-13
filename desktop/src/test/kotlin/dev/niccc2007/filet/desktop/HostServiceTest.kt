package dev.niccc2007.filet.desktop

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HostServiceTest {

    private fun store() = ConfigStore(Files.createTempDirectory("filet-host").resolve("config.json"))

    @Test fun `the shares are announced through the beacon the service built`() {
        // Building an Mdns opens nothing; the responder starts on first use.
        val host = HostService(store(), Mdns(), selfName = "test-pc")
        assertNotNull(host.beacon)
        assertSame(host.beacon, host.announcer)
    }

    @Test fun `without mDNS there is nothing to announce through, and nothing breaks`() {
        val host = HostService(store(), mdns = null, selfName = "test-pc")
        assertNull(host.announcer)
    }

    @Test fun `sharing comes back at the next launch unless Stop was pressed`() {
        val folder = Files.createTempDirectory("filet-host-share")
        val file = Files.createTempDirectory("filet-host").resolve("config.json")
        fun next() = HostService(ConfigStore(file), mdns = null, selfName = "test-pc")
        val host = next()
        assertNull(host.addShare(folder.toString(), writable = false))
        assertFalse(host.wasSharing)

        assertNull(host.start())
        host.close()
        // Closing the window is not a choice to stop sharing.
        assertTrue(next().wasSharing)

        assertNull(host.start())
        host.stop()
        assertFalse(next().wasSharing)
        host.close()
    }

    @Test fun `a launch shares when the last run was sharing, or when the updater reopens it sharing`() {
        assertTrue(sharesAtLaunch(arrayOf("--minimized"), wasSharing = true))
        assertTrue(sharesAtLaunch(arrayOf("--share", "--updated"), wasSharing = false))
        assertFalse(sharesAtLaunch(arrayOf("--minimized"), wasSharing = false))
        assertFalse(sharesAtLaunch(emptyArray(), wasSharing = false))
    }
}

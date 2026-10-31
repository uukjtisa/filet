package dev.niccc2007.filet.desktop

import java.nio.file.Files
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
}

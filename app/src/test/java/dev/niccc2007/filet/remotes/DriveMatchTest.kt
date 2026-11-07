package dev.niccc2007.filet.remotes

import dev.niccc2007.filet.link.DriveLink
import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.vfs.provider.net.NetProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/** A scanned drive is matched to a saved remote by device, never merged with somebody else's. */
class DriveMatchTest {

    private val drive = DriveLink("Pixel", "dev1", listOf("192.168.5.30"), 8080, "a/tres3")

    private fun saved(host: String, device: String = "dev1", share: String = "a/tres3", port: Int = 8080) =
        NetConnection(
            id = "n1", protocol = NetProtocol.WEBDAV, label = "Pixel", host = host, port = port,
            user = "", password = "", share = share, deviceId = device,
        )

    @Test
    fun `nothing saved is new`() {
        assertEquals(DriveMatch.New, DriveMatch.of(drive, emptyList()))
    }

    @Test
    fun `the same address is already saved`() {
        val c = saved("192.168.5.30")
        assertEquals(DriveMatch.Known(c), DriveMatch.of(drive, listOf(c)))
        val alt = saved("10.0.0.2").copy(altHosts = listOf("192.168.5.30"))
        assertEquals(DriveMatch.Known(alt), DriveMatch.of(drive, listOf(alt)))
    }

    @Test
    fun `the same device at another address has moved`() {
        val c = saved("192.168.5.22")
        assertEquals(DriveMatch.Moved(c), DriveMatch.of(drive, listOf(c)))
    }

    @Test
    fun `the same code on another device is a different drive`() {
        assertEquals(DriveMatch.New, DriveMatch.of(drive, listOf(saved("192.168.5.22", device = "dev2"))))
        assertEquals(DriveMatch.New, DriveMatch.of(drive, listOf(saved("192.168.5.30", device = "dev2"))))
    }

    @Test
    fun `without device IDs it is only a maybe`() {
        val old = saved("192.168.5.22", device = "")
        assertEquals(DriveMatch.Maybe(old), DriveMatch.of(drive, listOf(old)))
    }

    @Test
    fun `another share on the same device is new`() {
        assertEquals(DriveMatch.New, DriveMatch.of(drive, listOf(saved("192.168.5.30", share = "a/other"))))
    }

    @Test
    fun `learning an address appends it and keeps the saved one first`() {
        val moved = DriveMatch.withAddresses(saved("192.168.5.22", port = 8000), drive)
        assertEquals("192.168.5.22", moved.host)
        assertEquals(listOf("192.168.5.30"), moved.altHosts)
        assertEquals(8080, moved.port)
    }
}

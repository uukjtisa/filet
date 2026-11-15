package dev.niccc2007.filet.data

import dev.niccc2007.filet.data.DriveRegistry.Kind
import dev.niccc2007.filet.data.DriveRegistry.Known
import dev.niccc2007.filet.data.DriveRegistry.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRegistryTest {

    private val sd = Seen("1A2B-3C4D", Kind.SD, "SanDisk", "/storage/1A2B-3C4D", 64_000_000_000)
    private val usb = Seen("9F00-0001", Kind.USB, "USB drive", "/storage/9F00-0001", 32_000_000_000)

    @Test fun `a drive seen for the first time is remembered`() {
        val k = DriveRegistry.seen(emptyList(), listOf(sd), now = 100)
        assertEquals(1, k.size)
        assertEquals(Known(sd.uuid, Kind.SD, "SanDisk", sd.path, sd.total, 100, 100), k[0])
    }

    @Test fun `plugged back in, it is the same drive with a new last seen`() {
        val first = DriveRegistry.seen(emptyList(), listOf(sd), now = 100)
        val again = DriveRegistry.seen(first, listOf(sd.copy(total = null)), now = 500)
        assertEquals(1, again.size)
        assertEquals(100L, again[0].firstSeen)
        assertEquals(500L, again[0].lastSeen)
        // A size that could not be read this time keeps the last one.
        assertEquals(sd.total, again[0].total)
    }

    @Test fun `pulled out, it is listed as gone, newest first`() {
        var k = DriveRegistry.seen(emptyList(), listOf(sd), now = 100)
        k = DriveRegistry.seen(k, listOf(usb), now = 200)
        val gone = DriveRegistry.gone(k, plugged = emptySet())
        assertEquals(listOf(usb.uuid, sd.uuid), gone.map { it.uuid })
        assertTrue(DriveRegistry.gone(k, plugged = setOf(sd.uuid, usb.uuid)).isEmpty())
    }

    @Test fun `only drives that were not there at the last look have arrived`() {
        val k = DriveRegistry.seen(emptyList(), listOf(sd), now = 1)
        assertTrue(DriveRegistry.arrived(k, setOf(sd.uuid)).isEmpty())
        assertEquals(setOf(usb.uuid), DriveRegistry.arrived(k, setOf(sd.uuid, usb.uuid)))
        // Pulled out at the last look, then plugged in while Filet was closed: arrived.
        val pulled = DriveRegistry.seen(k, emptyList(), now = 2)
        assertEquals(false, pulled.single().present)
        assertEquals(setOf(sd.uuid), DriveRegistry.arrived(pulled, setOf(sd.uuid)))
    }

    @Test fun `a USB drive is left out of indexing until asked, an SD card never is`() {
        var k = DriveRegistry.seen(emptyList(), listOf(sd, usb), now = 1)
        assertEquals(setOf(usb.path), DriveRegistry.unindexedUsb(k))
        k = DriveRegistry.setIndexed(k, usb.uuid, true)
        assertTrue(DriveRegistry.unindexedUsb(k).isEmpty())
    }

    @Test fun `a card is named by what the system calls the drive, never by its ID`() {
        assertEquals("SD · SanDisk", DriveRegistry.displayName(Kind.SD, "SanDisk"))
        assertEquals("SanDisk SD card", DriveRegistry.displayName(Kind.SD, "SanDisk SD card"))
        assertEquals("USB drive", DriveRegistry.displayName(Kind.USB, "USB drive"))
        assertEquals("USB · Kingston", DriveRegistry.displayName(Kind.USB, " Kingston "))
        assertEquals("SD card", DriveRegistry.displayName(Kind.SD, ""))
        assertEquals("USB drive", DriveRegistry.displayName(Kind.USB, ""))
    }

    @Test fun `forget removes one drive and nothing else`() {
        val k = DriveRegistry.seen(emptyList(), listOf(sd, usb), now = 1)
        assertEquals(listOf(usb.uuid), DriveRegistry.forget(k, sd.uuid).map { it.uuid })
    }

    @Test fun `the stored form survives a round trip, awkward names included`() {
        val k = DriveRegistry.setIndexed(
            DriveRegistry.seen(emptyList(), listOf(sd.copy(name = "Tab\there\nand \\ slash"), usb.copy(total = null)), now = 7),
            usb.uuid, true,
        )
        assertEquals(k, DriveRegistry.decode(DriveRegistry.encode(k)))
    }

    @Test fun `a damaged line is dropped and the rest survive`() {
        val good = DriveRegistry.encode(DriveRegistry.seen(emptyList(), listOf(sd), now = 1))
        val k = DriveRegistry.decode("garbage\n$good\nX\tNOPE\ta\tb\t\t1\t2")
        assertEquals(listOf(sd.uuid), k.map { it.uuid })
        assertTrue(DriveRegistry.decode(null).isEmpty())
    }
}

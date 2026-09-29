package dev.niccc2007.filet.vfs.provider.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SightingsTest {

    private val tablet = Sightings.Saved(
        id = "n1",
        deviceId = "dev-tablet",
        host = "192.168.100.14",
        altHosts = emptyList(),
        port = 22222,
    )

    /** An entry typed by hand, or saved before ids existed. The state that blocked learning. */
    private val typedByHand = Sightings.Saved(
        id = "n2",
        deviceId = "",
        host = "192.168.100.6",
        altHosts = emptyList(),
        port = 11111,
    )

    @Test
    fun `a saved device at a new address teaches it`() {
        val seen = Sightings.Seen("dev-tablet", "192.168.43.55", 22222)
        assertEquals(
            listOf(Sightings.Learning.Address("n1", "192.168.43.55")),
            Sightings.learningsFor(listOf(tablet), seen),
        )
    }

    @Test
    fun `the same sighting again teaches nothing`() {
        // Discovery re-announces constantly by design, so this is the common case and it must
        // not produce a write. A write per packet re-encrypts every stored password.
        val seen = Sightings.Seen("dev-tablet", "192.168.100.14", 22222)
        assertTrue(Sightings.learningsFor(listOf(tablet), seen).isEmpty())
    }

    @Test
    fun `an entry with no id, at an address it holds, binds the id`() {
        // Without this step nothing can ever start learning: an entry with no id matches no id,
        // so it never learns an address, so it is stranded the first time the network changes.
        val seen = Sightings.Seen("dev-phone", "192.168.100.6", 11111)
        assertEquals(
            listOf(Sightings.Learning.DeviceId("n2", "dev-phone")),
            Sightings.learningsFor(listOf(typedByHand), seen),
        )
    }

    @Test
    fun `an entry with a different id is not taken over`() {
        // An address reused by another device. Overwriting the id here would hand one device's
        // saved entry, and its credentials, to a different device.
        val seen = Sightings.Seen("dev-somebody-else", "192.168.100.14", 22222)
        assertTrue(Sightings.learningsFor(listOf(tablet), seen).isEmpty())
    }

    @Test
    fun `a host that cannot say which device it is teaches nothing`() {
        // An address is a place credentials get sent. A host too old to advertise an id gets
        // nothing written on its word alone.
        val seen = Sightings.Seen("", "192.168.43.55", 22222)
        assertTrue(Sightings.learningsFor(listOf(tablet), seen).isEmpty())
    }

    @Test
    fun `a port a device is not saved on is a different share, so it is new`() {
        // One HTTP server is one port, so two shares on one phone are two ports and the second
        // one is genuinely something to add.
        val seen = Sightings.Seen("dev-tablet", "192.168.100.14", 33333)
        assertNull(Sightings.savedFor(listOf(tablet), seen))
    }

    @Test
    fun `a share already saved is not offered as new`() {
        val seen = Sightings.Seen("dev-tablet", "192.168.43.55", 22222)
        assertEquals("n1", Sightings.savedFor(listOf(tablet), seen))
    }

    @Test
    fun `an address already saved is not offered as new, id or no id`() {
        val seen = Sightings.Seen("", "192.168.100.6", 11111)
        assertEquals("n2", Sightings.savedFor(listOf(typedByHand), seen))
    }

    @Test
    fun `a learned address counts as known afterwards`() {
        val grown = tablet.copy(altHosts = listOf("192.168.43.55"))
        val seen = Sightings.Seen("dev-tablet", "192.168.43.55", 22222)
        assertTrue(Sightings.learningsFor(listOf(grown), seen).isEmpty())
        assertEquals("n1", Sightings.savedFor(listOf(grown), seen))
    }

    @Test
    fun `spelling and stray whitespace do not make a second address`() {
        val named = tablet.copy(host = "Tablet.local")
        val seen = Sightings.Seen("dev-tablet", " tablet.LOCAL ", 22222)
        assertTrue(Sightings.learningsFor(listOf(named), seen).isEmpty())
    }

    @Test
    fun `one sighting can teach two entries that share a device`() {
        // Two shares on one phone are two entries and one device, and both follow it.
        val second = tablet.copy(id = "n3", port = 33333)
        val seen = Sightings.Seen("dev-tablet", "192.168.43.55", 22222)
        val out = Sightings.learningsFor(listOf(tablet, second), seen)
        assertEquals(listOf("n1", "n3"), out.map { it.connectionId })
    }
}

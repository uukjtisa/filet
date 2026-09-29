package dev.niccc2007.filet.home

import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.vfs.provider.net.NetProtocol
import dev.niccc2007.filet.webdav.DavBeacon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPlacesTest {

    private fun saved(
        id: String,
        label: String = "Saved",
        host: String = "192.168.1.9",
        port: Int = 8321,
        share: String = "a/KTR9",
    ) = NetConnection(
        id = id, protocol = NetProtocol.WEBDAV, label = label,
        host = host, port = port, user = "", password = "", share = share,
    )

    private fun found(
        name: String = "Tablet",
        host: String = "192.168.1.9",
        port: Int = 8321,
    ) = DavBeacon.Host(
        name = name, host = host, port = port, basePath = "/a/", scope = "Whole phone",
    )

    // ---- matching ---------------------------------------------------------------------------

    @Test
    fun `a saved place matches a broadcast on the same host and port`() {
        assertTrue(NetworkPlaces.matches(saved("1"), found()))
    }

    @Test
    fun `the path is deliberately not compared`() {
        // A saved place carries a/<code>; the announcement carries only a/, because the code is
        // never broadcast. Comparing paths would match nothing, ever.
        val c = saved("1", share = "a/KTR9")
        val h = found()
        assertEquals("/a/", h.basePath)
        assertTrue(NetworkPlaces.matches(c, h))
    }

    @Test
    fun `a different port is a different place`() {
        assertFalse(NetworkPlaces.matches(saved("1", port = 8321), found(port = 11111)))
    }

    @Test
    fun `host matching ignores case`() {
        assertTrue(NetworkPlaces.matches(saved("1", host = "Tablet.local"), found(host = "tablet.local")))
    }

    // ---- merging ----------------------------------------------------------------------------

    @Test
    fun `a saved place that is broadcasting reads as live`() {
        val out = NetworkPlaces.merge(listOf(saved("1", label = "My tablet")), listOf(found()))
        assertEquals(1, out.size)
        assertEquals("My tablet", out[0].title)
        assertEquals(PlaceState.Live, out[0].state)
        assertTrue(out[0].openable)
        assertTrue(out[0].detail.contains("on now"))
    }

    @Test
    fun `a saved place with nothing broadcasting is unknown, not down`() {
        // Nothing here polls, so "down" would be a guess dressed as a fact - and a red dot on a
        // working NAS is worse than no dot.
        val out = NetworkPlaces.merge(listOf(saved("1")), emptyList())
        assertEquals(PlaceState.Unknown, out[0].state)
        assertTrue(out[0].openable)
    }

    @Test
    fun `a broadcast that is already saved does not appear twice`() {
        val out = NetworkPlaces.merge(listOf(saved("1", label = "My tablet")), listOf(found()))
        assertEquals(1, out.size)
        assertEquals("My tablet", out[0].title)
    }

    @Test
    fun `a broadcast that is not saved appears, and is not openable yet`() {
        val out = NetworkPlaces.merge(emptyList(), listOf(found(name = "Thirdy phone")))
        assertEquals(1, out.size)
        assertEquals("Thirdy phone", out[0].title)
        assertEquals(PlaceState.Live, out[0].state)
        // It cannot be opened until somebody supplies the code, and the row says so.
        assertFalse(out[0].openable)
        assertNull(out[0].connection)
        assertTrue(out[0].detail.contains("needs a code"))
    }

    @Test
    fun `saved places come before discovered ones`() {
        val out = NetworkPlaces.merge(
            listOf(saved("1", label = "NAS", host = "10.0.0.5", port = 445)),
            listOf(found(name = "Tablet")),
        )
        assertEquals(listOf("NAS", "Tablet"), out.map { it.title })
    }

    @Test
    fun `a saved place with no name falls back to its host`() {
        val out = NetworkPlaces.merge(listOf(saved("1", label = "")), emptyList())
        assertEquals("192.168.1.9", out[0].title)
    }

    @Test
    fun `nothing at all merges to nothing`() {
        assertTrue(NetworkPlaces.merge(emptyList(), emptyList()).isEmpty())
        assertFalse(NetworkPlaces.hasAnything(emptyList(), emptyList()))
        assertTrue(NetworkPlaces.hasAnything(listOf(saved("1")), emptyList()))
        assertTrue(NetworkPlaces.hasAnything(emptyList(), listOf(found())))
    }

    @Test
    fun `two phones broadcasting both appear`() {
        val out = NetworkPlaces.merge(
            emptyList(),
            listOf(found(name = "A", host = "192.168.1.9"), found(name = "B", host = "192.168.1.10")),
        )
        assertEquals(2, out.size)
        assertTrue(out.all { it.state == PlaceState.Live })
    }
}

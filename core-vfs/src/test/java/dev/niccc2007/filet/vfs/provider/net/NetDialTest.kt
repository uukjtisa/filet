package dev.niccc2007.filet.vfs.provider.net

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class NetDialTest {

    private val path = VPath.of("dav", "/n1/file.txt")
    private val hosts = listOf("192.168.100.6", "192.168.100.7", "tablet.local")

    @Test
    fun `the first address that answers is used`() {
        val tried = ArrayList<String>()
        val out = NetDial.over(hosts, path, onGood = {}) { h -> tried += h; "body from $h" }
        assertEquals(listOf("192.168.100.6"), tried)
        assertEquals("body from 192.168.100.6", out)
    }

    @Test
    fun `a dead address moves to the next one`() {
        // The whole point. This is what adding a second address did not do before: the failure
        // arrived somewhere nothing was watching for it, so the second address was never tried.
        val tried = ArrayList<String>()
        val out = NetDial.over(hosts, path, onGood = {}) { h ->
            tried += h
            if (h != "192.168.100.7") throw ConnectException("ECONNREFUSED")
            "reached"
        }
        assertEquals(listOf("192.168.100.6", "192.168.100.7"), tried)
        assertEquals("reached", out)
    }

    @Test
    fun `the address that worked is remembered`() {
        var noted: String? = null
        NetDial.over(hosts, path, onGood = { noted = it }) { h ->
            if (h == "192.168.100.6") throw SocketTimeoutException("failed to connect")
            Unit
        }
        assertEquals("192.168.100.7", noted)
    }

    @Test
    fun `a refusal stops the sweep and is reported as itself`() {
        // Trying the next address here would fail on it too and report THAT, so the reader never
        // finds out the code was wrong.
        val tried = ArrayList<String>()
        assertThrows(VfsException.AccessDenied::class.java) {
            NetDial.over(hosts, path, onGood = {}) { h ->
                tried += h
                throw VfsException.AccessDenied(path, null, unauthenticated = true)
            }
        }
        assertEquals(listOf("192.168.100.6"), tried)
    }

    @Test
    fun `an address that answered no is still a working address`() {
        // It answered. Next time it is tried first, because the alternative is sweeping past a
        // perfectly good address every time a folder is missing.
        var noted: String? = null
        assertThrows(VfsException.NotFound::class.java) {
            NetDial.over(hosts, path, onGood = { noted = it }) { throw VfsException.NotFound(path) }
        }
        assertEquals("192.168.100.6", noted)
    }

    @Test
    fun `a cancel promotes nothing`() {
        var noted: String? = null
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            NetDial.over(hosts, path, onGood = { noted = it }) {
                throw java.util.concurrent.CancellationException()
            }
        }
        assertEquals(null, noted)
    }

    @Test
    fun `every address failing reports a real failure, not a wrong one`() {
        val tried = ArrayList<String>()
        val thrown = assertThrows(ConnectException::class.java) {
            NetDial.over(hosts, path, onGood = {}) { h ->
                tried += h
                throw ConnectException("ECONNREFUSED at $h")
            }
        }
        // One pass, bounded by the list. Cycling would make an unreachable remote hang instead
        // of failing.
        assertEquals(hosts, tried)
        assertTrue(thrown.message!!.contains("tablet.local"))
    }

    @Test
    fun `a remote with no address at all fails rather than looping`() {
        assertThrows(VfsException.NotFound::class.java) {
            NetDial.over(emptyList(), path, onGood = {}) { "never" }
        }
    }
}

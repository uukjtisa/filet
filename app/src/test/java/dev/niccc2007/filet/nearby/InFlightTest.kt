package dev.niccc2007.filet.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same share is never fetched twice at once. See [InFlight] and PushInbox.fetch. */
class InFlightTest {

    @Test
    fun `a share being fetched is not started again`() {
        val f = InFlight<String>()
        assertTrue(f.begin("http://192.168.1.4:8080|k1"))
        // The camera reading the same code ten times a second: one download, not thirty.
        val again = (1..30).count { f.begin("http://192.168.1.4:8080|k1") }
        assertEquals(0, again)
    }

    @Test
    fun `a different share starts alongside it`() {
        val f = InFlight<String>()
        assertTrue(f.begin("http://192.168.1.4:8080|k1"))
        assertTrue(f.begin("http://192.168.1.4:8080|k2"))
        assertTrue(f.begin("http://192.168.1.9:8080|k1"))
    }

    @Test
    fun `once it ends the same share may be fetched again`() {
        val f = InFlight<String>()
        f.begin("a")
        f.end("a")
        assertTrue("asked for again after it finished: a fresh copy", f.begin("a"))
        assertFalse(f.begin("a"))
    }
}

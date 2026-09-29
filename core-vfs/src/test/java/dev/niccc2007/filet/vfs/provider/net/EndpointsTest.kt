package dev.niccc2007.filet.vfs.provider.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointsTest {

    @Test
    fun `the one that worked last is tried first`() {
        // Networks are sticky. The address that answered a minute ago almost always answers
        // now, so the common case should cost one request rather than a sweep.
        val out = Endpoints.ordered("192.168.100.14", listOf("192.168.43.1"), lastGood = "192.168.43.1")
        assertEquals("192.168.43.1", out.first())
    }

    @Test
    fun `with nothing known to work, the typed primary leads`() {
        val out = Endpoints.ordered("192.168.100.14", listOf("192.168.43.1"), lastGood = null)
        assertEquals(listOf("192.168.100.14", "192.168.43.1"), out)
    }

    @Test
    fun `nothing is tried twice, whatever the spelling`() {
        // A host is not case-sensitive, and trying the same box twice under two spellings is
        // two timeouts for one answer.
        val out = Endpoints.ordered("Tablet.local", listOf("tablet.LOCAL", "TABLET.local"), null)
        assertEquals(1, out.size)
    }

    @Test
    fun `a last-good that is not in the list is still tried first`() {
        // It answered. That outranks whether anybody wrote it down.
        val out = Endpoints.ordered("a.host", listOf("b.host"), lastGood = "c.host")
        assertEquals(listOf("c.host", "a.host", "b.host"), out)
    }

    @Test
    fun `a remote with no typed address still resolves`() {
        // One that has only ever been discovered has no primary at all.
        assertEquals(listOf("found.local"), Endpoints.ordered("", listOf("found.local"), null))
        assertEquals(listOf("found.local"), Endpoints.ordered("   ", listOf("found.local"), null))
    }

    @Test
    fun `an empty entry never becomes an address to time out on`() {
        assertEquals(emptyList<String>(), Endpoints.ordered("", emptyList(), null))
        assertEquals(emptyList<String>(), Endpoints.ordered("  ", listOf("", "   "), ""))
    }

    @Test
    fun `the field parses one per line, and tolerates commas`() {
        assertEquals(
            listOf("192.168.100.14", "192.168.43.1", "tablet.local"),
            Endpoints.parse(" 192.168.100.14 \n\n192.168.43.1,tablet.local\n"),
        )
    }

    @Test
    fun `parse and format round trip`() {
        val list = listOf("a.host", "b.host")
        assertEquals(list, Endpoints.parse(Endpoints.format(list)))
    }

    @Test
    fun `learning the same address twice changes nothing`() {
        // The signal a caller uses to avoid writing storage on every discovery packet.
        assertNull(Endpoints.learn(listOf("a.host"), "a.host"))
        assertNull(Endpoints.learn(listOf("a.host"), "A.HOST"))
        assertNull(Endpoints.learn(listOf("a.host"), "   "))
    }

    @Test
    fun `a genuinely new address is appended, not promoted`() {
        // It has not answered a real request yet. It earns the front by working.
        assertEquals(listOf("a.host", "b.host"), Endpoints.learn(listOf("a.host"), "b.host"))
    }

    @Test
    fun `learning stops at the ceiling`() {
        // Every address is a potential timeout when the remote is away, so a list that grows by
        // one per network change is a remote that gets slower to fail forever.
        val full = (1..Endpoints.MAX).map { "h$it" }
        assertNull(Endpoints.learn(full, "one-more"))
        assertTrue(Endpoints.learn(full.dropLast(1), "one-more")!!.size == Endpoints.MAX)
    }

    @Test
    fun `rotation moves to the next address and wraps`() {
        val three = listOf("a.host", "b.host", "c.host")
        assertEquals("b.host", Endpoints.after(three, "a.host"))
        assertEquals("c.host", Endpoints.after(three, "b.host"))
        // Wraps rather than stopping at the end: a remote that comes back on an address already
        // tried has to be findable again.
        assertEquals("a.host", Endpoints.after(three, "c.host"))
    }

    @Test
    fun `there is nowhere to rotate to with one address`() {
        // Null is what makes the real failure survive. Rotating onto the same address and
        // failing again would report a second failure in place of the first.
        assertNull(Endpoints.after(listOf("a.host"), "a.host"))
        assertNull(Endpoints.after(emptyList(), "a.host"))
    }

    @Test
    fun `an address no longer in the list starts again at the front`() {
        // The in-use address is held in memory and the list can be edited underneath it.
        assertEquals("a.host", Endpoints.after(listOf("a.host", "b.host"), "deleted.host"))
        assertEquals("a.host", Endpoints.after(listOf("a.host", "b.host"), null))
    }

    @Test
    fun `rotation is case-insensitive about where it is now`() {
        assertEquals("b.host", Endpoints.after(listOf("A.Host", "b.host"), "a.host"))
    }
}

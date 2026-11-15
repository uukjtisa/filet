package dev.niccc2007.filet.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A push row tells the truth when the push's own reply goes missing. See [PushRow], [NetHost]. */
class PushRowTest {

    @Test
    fun `a lost reply no longer reads as no answer when the other phone took the files`() {
        // The negative control: with nothing seen, a reply that never came still says so.
        assertEquals("Sent, no reply yet", PushRow.detail(PushReply.UNANSWERED, null, paired = false))
        assertEquals("Answered: taking the files", PushRow.detail(PushReply.UNANSWERED, FastSeen.FETCHING, paired = false))
        assertEquals("Answered: taking the files", PushRow.detail("none", FastSeen.FETCHING, paired = false))
        assertTrue(PushRow.answered(PushReply.UNANSWERED, FastSeen.FETCHING))
    }

    @Test
    fun `the other phone asking about the share means it holds the offer`() {
        assertEquals("Asked: waiting for them to accept", PushRow.detail(PushReply.UNANSWERED, FastSeen.ASKED, paired = true))
        assertEquals("Asked: waiting for them to accept", PushRow.detail(PushReply.SENDING, FastSeen.ASKED, paired = true))
        // A specific answer it gave is kept over the general one.
        assertEquals("Waiting: their pop-ups are off", PushRow.detail("held", FastSeen.ASKED, paired = true))
    }

    @Test
    fun `fetching is never undone by a later question`() {
        var s: FastSeen? = null
        s = PushRow.seen(s, fetching = false)
        assertEquals(FastSeen.ASKED, s)
        s = PushRow.seen(s, fetching = true)
        s = PushRow.seen(s, fetching = false)
        assertEquals(FastSeen.FETCHING, s)
    }

    @Test
    fun `untouched rows read as before`() {
        assertEquals("Tap to send", PushRow.detail(null, null, paired = false))
        assertEquals("Paired", PushRow.detail(null, null, paired = true))
        assertEquals("They are not taking pushes from this phone", PushRow.detail("blocked", null, paired = false))
        assertFalse(PushRow.answered(PushReply.UNREACHED, null))
        assertFalse(PushRow.answered(null, null))
    }

    @Test
    fun `an IPv6 address makes a working link`() {
        assertEquals("192.168.1.4:8790", NetHost.authority("192.168.1.4", 8790))
        assertEquals("[2001:db8::7]:8790", NetHost.authority("2001:db8::7", 8790))
        assertEquals("[fe80::1c2]:8790", NetHost.authority("fe80::1c2%wlan0", 8790))
        // Parsed back, the host is what went in - the unbracketed form is not even a URL host.
        assertEquals("[2001:db8::7]", java.net.URI("http://" + NetHost.authority("2001:db8::7", 8790) + "/api").host)
        assertEquals(8790, java.net.URI("http://" + NetHost.authority("2001:db8::7", 8790) + "/api").port)
    }

    @Test
    fun `an address as a dual-stack server sees it is matched to the phone`() {
        assertEquals("192.168.1.4", NetHost.plain("::ffff:192.168.1.4"))
        assertEquals("192.168.1.4", NetHost.plain("/192.168.1.4"))
        assertEquals("2001:db8::7", NetHost.plain("2001:db8::7"))
        assertEquals("fe80::1c2", NetHost.plain("fe80::1c2%wlan0"))
    }
}

package dev.niccc2007.filet.nearby

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareGateTest {
    private val e = SharedEntry("t", VPath.of("local", "/storage/emulated/0/a.jpg"), "a.jpg", 0, linked = true)
    private val now = 1_000_000L

    @Test fun `a file of Filet Shared has no rules of its own`() =
        assertEquals(ShareGate.OPEN, ShareGate.of(null, now))

    @Test fun `expired, paused and locked answer in that order`() {
        assertEquals(ShareGate.GONE, ShareGate.of(e.copy(expires = now - 1, paused = true, locked = true), now))
        assertEquals(ShareGate.PAUSED, ShareGate.of(e.copy(paused = true, locked = true), now))
        assertEquals(ShareGate.LOCKED, ShareGate.of(e.copy(locked = true), now))
        assertEquals(ShareGate.OPEN, ShareGate.of(e.copy(expires = now + 1), now))
    }

    @Test fun `the lock code or a fast share's key opens a locked file, never a paused one`() {
        assertEquals(ShareGate.OPEN, ShareGate.of(e.copy(locked = true), now, lockOk = true))
        assertEquals(ShareGate.OPEN, ShareGate.of(e.copy(locked = true), now, keyOk = true))
        assertEquals(ShareGate.PAUSED, ShareGate.of(e.copy(paused = true), now, keyOk = true))
    }

    @Test fun `a Filet gives its name, anything else is a browser at its address`() {
        assertEquals("nova 10", ShareGate.who(" nova 10 ", "192.168.1.5"))
        assertEquals("A browser · 192.168.1.5", ShareGate.who(null, "192.168.1.5"))
        assertEquals("A browser · 192.168.1.5", ShareGate.who("  ", "192.168.1.5"))
    }
}

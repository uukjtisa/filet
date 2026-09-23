package dev.niccc2007.filet.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locking, which exists so Explorer will write at all, and expires so it cannot trap a file. */
class DavLocksTest {

    private var clock = 1_000_000L
    private val locks = DavLocks { clock }

    @Test
    fun `a first lock succeeds and reports itself held`() {
        assertNotNull(locks.acquire("a.txt", 600))
        assertTrue(locks.isLocked("a.txt"))
    }

    @Test
    fun `a second lock on the same path is refused while the first lives`() {
        locks.acquire("a.txt", 600)
        assertNull(locks.acquire("a.txt", 600))
    }

    @Test
    fun `locks are per path`() {
        locks.acquire("a.txt", 600)
        assertNotNull(locks.acquire("b.txt", 600))
    }

    @Test
    fun `two tokens are never the same`() {
        val one = locks.acquire("a.txt", 600)
        locks.release("a.txt", one)
        assertNotEquals(one, locks.acquire("a.txt", 600))
    }

    @Test
    fun `a token looks like an opaquelocktoken`() {
        assertTrue(locks.acquire("a.txt", 600)!!.startsWith("opaquelocktoken:"))
    }

    // ── expiry, which is the half a naive implementation leaves out ───────────────────

    @Test
    fun `a lock expires and the path can be locked again`() {
        // The failure: a laptop is closed mid-edit and the file is unwritable until the whole
        // share is restarted, with nothing on screen explaining why.
        locks.acquire("a.txt", 600)
        clock += 601_000
        assertFalse(locks.isLocked("a.txt"))
        assertNotNull(locks.acquire("a.txt", 600))
    }

    @Test
    fun `a lock still inside its window holds`() {
        locks.acquire("a.txt", 600)
        clock += 599_000
        assertTrue(locks.isLocked("a.txt"))
        assertNull(locks.acquire("a.txt", 600))
    }

    @Test
    fun `an expired lock is not counted`() {
        locks.acquire("a.txt", 600)
        locks.acquire("b.txt", 600)
        assertEquals(2, locks.count())
        clock += 601_000
        assertEquals(0, locks.count())
    }

    // ── releasing ──────────────────────────────────────────────────────────────────────

    @Test
    fun `releasing without the right token still releases`() {
        // Explorer loses track of its own token across a reconnect. Being strict here leaves a
        // file locked that nobody can unlock and nobody can see.
        locks.acquire("a.txt", 600)
        locks.release("a.txt", null)
        assertFalse(locks.isLocked("a.txt"))
    }

    @Test
    fun `releasing something never locked is harmless`() {
        locks.release("nothing.txt", null)
        assertFalse(locks.isLocked("nothing.txt"))
    }

    @Test
    fun `clearing drops everything`() {
        locks.acquire("a.txt", 600)
        locks.acquire("b.txt", 600)
        locks.clear()
        assertEquals(0, locks.count())
    }

    // ── the Timeout header ─────────────────────────────────────────────────────────────

    @Test
    fun `a missing timeout gets the default`() {
        assertEquals(DavLocks.DEFAULT_TIMEOUT_SECONDS, DavLocks.timeoutSeconds(null))
        assertEquals(DavLocks.DEFAULT_TIMEOUT_SECONDS, DavLocks.timeoutSeconds("  "))
    }

    @Test
    fun `a Second- value is honoured`() {
        assertEquals(120, DavLocks.timeoutSeconds("Second-120"))
        assertEquals(120, DavLocks.timeoutSeconds("second-120"))
    }

    @Test
    fun `Infinite is answered with the maximum, never with forever`() {
        // A lock that never expires is one a vanished client keeps for the life of the share.
        assertEquals(DavLocks.MAX_TIMEOUT_SECONDS, DavLocks.timeoutSeconds("Infinite"))
    }

    @Test
    fun `an over-long request is capped`() {
        assertEquals(DavLocks.MAX_TIMEOUT_SECONDS, DavLocks.timeoutSeconds("Second-999999"))
    }

    @Test
    fun `the first usable value of several is taken`() {
        assertEquals(DavLocks.MAX_TIMEOUT_SECONDS, DavLocks.timeoutSeconds("Infinite, Second-300"))
        assertEquals(300, DavLocks.timeoutSeconds("Second-0, Second-300"))
    }

    @Test
    fun `nonsense falls back to the default rather than to zero`() {
        // A zero-second lock is granted and instantly expired, which reads to Explorer as a
        // server that cannot lock at all.
        assertEquals(DavLocks.DEFAULT_TIMEOUT_SECONDS, DavLocks.timeoutSeconds("Second-abc"))
        assertEquals(DavLocks.DEFAULT_TIMEOUT_SECONDS, DavLocks.timeoutSeconds("whatever"))
    }
}

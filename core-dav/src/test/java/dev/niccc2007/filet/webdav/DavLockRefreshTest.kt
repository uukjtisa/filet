package dev.niccc2007.filet.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Refreshing a lock you already hold.
 *
 * The bug this pins: `acquire` refused ANY re-lock of an unexpired path, including by the
 * client holding it. Explorer refreshes its lock periodically for the whole duration of a
 * transfer (RFC 4918 9.10.2), so every large copy from Windows was answered 423 part way
 * through and surfaced as `0x80070021 - another process has locked a portion of the file`.
 */
class DavLockRefreshTest {

    private var clock = 1_000L
    private val locks = DavLocks { clock }

    @Test
    fun `the holder can refresh and keeps the same token`() {
        val first = locks.acquire("/a.apk", 60)!!
        clock += 30_000
        val again = locks.acquire("/a.apk", 60, presented = first)
        // The same token: a refresh is not a new lock, and a different token would invalidate
        // the one the client is already using.
        assertEquals(first, again)
    }

    @Test
    fun `a refresh extends the expiry rather than leaving it where it was`() {
        val token = locks.acquire("/a.apk", 60)!!
        clock += 50_000
        locks.acquire("/a.apk", 60, presented = token)
        // Past the ORIGINAL expiry, inside the refreshed one.
        clock += 20_000
        assertTrue("the refresh did not extend anything", locks.isLocked("/a.apk"))
    }

    @Test
    fun `somebody else is still refused`() {
        locks.acquire("/a.apk", 60)
        assertNull(locks.acquire("/a.apk", 60, presented = "opaquelocktoken:not-the-one"))
        assertNull(locks.acquire("/a.apk", 60, presented = null))
    }

    @Test
    fun `an expired lock is taken by anyone, with or without a token`() {
        locks.acquire("/a.apk", 1)
        clock += 5_000
        assertNotNull(locks.acquire("/a.apk", 60))
    }

    @Test
    fun `the token is found whichever way the If header is written`() {
        // Explorer, curl, cadaver and the .NET client each write this differently. The token is
        // a value the server minted, so presenting it IS the proof - the syntax around it is
        // not what is being checked.
        val token = "opaquelocktoken:0123456789abcdef0123456789abcdef"
        for (header in listOf(
            "(<$token>)",
            "<http://host/a.apk> (<$token>)",
            "(<$token> [\"etag\"])",
            "( < $token > )",
        )) {
            assertEquals(header, token, DavLocks.tokenIn(header))
        }
    }

    @Test
    fun `no token is not a token`() {
        assertNull(DavLocks.tokenIn(null))
        assertNull(DavLocks.tokenIn(""))
        assertNull(DavLocks.tokenIn("([\"an-etag-only\"])"))
    }

    @Test
    fun `releasing then re-locking still works`() {
        val token = locks.acquire("/a.apk", 60)!!
        locks.release("/a.apk", token)
        assertNotNull(locks.acquire("/a.apk", 60))
    }
}

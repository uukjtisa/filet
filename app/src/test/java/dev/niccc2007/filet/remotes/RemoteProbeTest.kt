package dev.niccc2007.filet.remotes

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling five network failures apart, because each has a different fix.
 *
 * The Remotes screen said nothing about whether a share worked, and its own comment explained
 * why - nothing polled it, so any claim would have been a guess. The consequence was that a
 * wrong password, a sleeping PC, a wrong port, a wrong share name and a firewall all presented
 * as one silent nothing. Which of the five it is, is the only thing a reader needs.
 *
 * The ORDER of the classification is the part that is easy to get wrong: every exception below
 * is also an `IOException`, so a generic branch tried first swallows all of them.
 */
class RemoteProbeTest {

    private val path = VPath.of("smb", "/x")

    @Test
    fun `a refusal is a refusal and not a network problem`() {
        val v = RemoteProbe.verdictFor(VfsException.AccessDenied(path))
        assertFalse(v.ok)
        assertEquals("refused", v.label)
        // And it names the three things to check, because a sign-in has three fields.
        assertTrue(v.detail.contains("password"))
        assertTrue(v.detail.contains("domain"))
    }

    @Test
    fun `a missing share says so rather than blaming the network`() {
        val v = RemoteProbe.verdictFor(VfsException.NotFound(path))
        assertEquals("no such share", v.label)
    }

    @Test
    fun `nothing listening on the port is distinct from nothing at the address`() {
        // The distinction that matters most in practice: the PC is awake and the service is
        // not running, versus the PC is asleep. Same silence, opposite fixes.
        assertEquals("refused the port", RemoteProbe.verdictFor(ConnectException()).label)
        assertEquals("timed out", RemoteProbe.verdictFor(SocketTimeoutException()).label)
        assertEquals("no route", RemoteProbe.verdictFor(NoRouteToHostException()).label)
        assertEquals("unknown name", RemoteProbe.verdictFor(UnknownHostException()).label)
    }

    @Test
    fun `a wrapped cause is still read correctly`() {
        // Every provider wraps: sshj, smbj and the HTTP client each put their own exception
        // around the socket's. Reading only the outermost would classify all of them as
        // "did not answer", which is the state this replaced.
        val wrapped = IOException("smb failed", IOException("inner", ConnectException()))
        assertEquals("refused the port", RemoteProbe.verdictFor(wrapped).label)
    }

    @Test
    fun `a cycle in the cause chain does not hang`() {
        // Not hypothetical: wrappers do set a throwable as its own cause, and walking that
        // costs the whole thread.
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        val v = RemoteProbe.verdictFor(a)
        assertFalse(v.ok)
    }

    @Test
    fun `an unrecognised failure keeps its own message`() {
        // A message nobody wrote for this screen is still more useful than a word that was.
        val v = RemoteProbe.verdictFor(IllegalStateException("the server sent nonsense"))
        assertTrue(v.detail.contains("nonsense"))
    }

    @Test
    fun `no failure is a working share`() {
        assertTrue(RemoteProbe.verdictFor(null).ok)
        assertEquals("", RemoteProbe.verdictFor(null).detail)
    }
}

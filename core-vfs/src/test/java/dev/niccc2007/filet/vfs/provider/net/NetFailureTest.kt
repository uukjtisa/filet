package dev.niccc2007.filet.vfs.provider.net

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NetFailureTest {

    private val path = VPath.of("dav", "/n1/file.txt")

    @Test
    fun `a refused connection is the address`() {
        assertTrue(NetFailure.isAddressFailure(ConnectException("ECONNREFUSED (Connection refused)")))
    }

    @Test
    fun `no route and an unresolvable name are the address`() {
        assertTrue(NetFailure.isAddressFailure(NoRouteToHostException()))
        assertTrue(NetFailure.isAddressFailure(UnknownHostException("tablet.local")))
    }

    @Test
    fun `a connect timeout is the address, not a cancel`() {
        // SocketTimeoutException extends InterruptedIOException, and an interrupted socket read
        // is what a cancelled coroutine looks like from down here. Tested in the wrong order
        // every timeout reads as a cancel and no address is ever rotated - which looks exactly
        // like the feature never having been built.
        assertTrue(NetFailure.isAddressFailure(SocketTimeoutException("failed to connect")))
    }

    @Test
    fun `a refusal from the server is the answer and is kept`() {
        // Rotating here would fail on a second address and report THAT, so the reader never
        // learns the code was wrong.
        assertFalse(
            NetFailure.isAddressFailure(
                VfsException.AccessDenied(path, null, unauthenticated = true),
            ),
        )
        assertFalse(NetFailure.isAddressFailure(VfsException.NotFound(path)))
        assertFalse(NetFailure.isAddressFailure(VfsException.Unsupported("append")))
    }

    @Test
    fun `a TLS failure is a server, so it is not rotated past`() {
        assertFalse(NetFailure.isAddressFailure(javax.net.ssl.SSLHandshakeException("bad cert")))
    }

    @Test
    fun `a cancel is this device's own decision`() {
        assertFalse(NetFailure.isAddressFailure(java.util.concurrent.CancellationException()))
        assertFalse(NetFailure.isAddressFailure(InterruptedIOException("thread interrupted")))
    }

    @Test
    fun `it looks through the wrapper the vfs puts on it`() {
        // Every provider wraps what it catches, so the interesting exception is never the top
        // one by the time a retry has to decide.
        val wrapped = VfsException.Io(path, ConnectException("ECONNREFUSED"))
        assertTrue(NetFailure.isAddressFailure(wrapped))
    }

    @Test
    fun `an errno from android arrives as text and is still read`() {
        // ErrnoException is not on this module's compile classpath, so the name is matched in
        // the message. These four mean there is nothing at that address.
        assertTrue(NetFailure.isAddressFailure(IOException("sendto failed: EHOSTUNREACH (No route to host)")))
        assertTrue(NetFailure.isAddressFailure(IOException("connect failed: ENETUNREACH")))
        assertTrue(NetFailure.isAddressFailure(IOException("recvfrom failed: ETIMEDOUT")))
    }

    @Test
    fun `an ordinary io error is not assumed to be the address`() {
        // Silence rather than a guess: rotating on every IOException would sweep all eight
        // addresses whenever a file read fails for any reason at all.
        assertFalse(NetFailure.isAddressFailure(IOException("unexpected end of stream")))
        assertFalse(NetFailure.isAddressFailure(null))
    }

    @Test
    fun `a cause chain that loops does not hang`() {
        val a = IOException("outer")
        val b = IOException("inner", a)
        a.initCause(b)
        assertFalse(NetFailure.isAddressFailure(a))
    }
}

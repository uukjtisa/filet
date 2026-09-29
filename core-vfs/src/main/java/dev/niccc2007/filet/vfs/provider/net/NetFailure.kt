package dev.niccc2007.filet.vfs.provider.net

import dev.niccc2007.filet.vfs.VfsException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Is this failure the ADDRESS, or is it the answer?
 *
 * The one decision that makes several addresses per remote work, and it is exactly the kind
 * that is wrong in both directions if it is guessed at a call site:
 *
 * - Treat a refusal as an address failure and Filet moves to a different address, fails there
 *   too, and reports the second failure. The real one - wrong code, read-only share - never
 *   reaches the reader.
 * - Treat a dead address as an answer and the other addresses are never tried at all, which is
 *   the whole feature not working.
 *
 * So it is one pure function with a test, and nothing else is allowed an opinion.
 *
 * ## The rule
 *
 * **Something answered means keep the answer.** A status code, a TLS handshake, a refusal from
 * the VFS - all of those prove there is a server at that address, and a different address is
 * not going to improve on it.
 *
 * **Nothing answered means try elsewhere.** Refused connection, no route, timed out while
 * connecting, a name that does not resolve.
 *
 * **A cancel is neither.** It is this device's own decision, and rotating on it would sweep
 * every saved address every time somebody backs out of a folder.
 */
object NetFailure {

    /** How far down a cause chain to look before giving up on it. */
    private const val DEPTH = 8

    fun isAddressFailure(t: Throwable?): Boolean {
        var at: Throwable? = t
        var steps = 0
        while (at != null && steps++ < DEPTH) {
            verdictFor(at)?.let { return it }
            val next = at.cause
            // A throwable whose cause is itself is not a hypothetical: wrappers do it, and
            // walking it costs the whole thread.
            if (next === at) return false
            at = next
        }
        return false
    }

    /**
     * One link of the chain: true, false, or "no opinion, keep looking".
     *
     * Order matters and the order is the interesting part:
     *
     * `SocketTimeoutException` **extends** `InterruptedIOException`, and an interrupted socket
     * read is what a cancelled coroutine looks like from down here. Tested in the wrong order,
     * every connect timeout reads as a cancel and no address is ever rotated - which is a bug
     * that looks exactly like the feature being unimplemented.
     */
    private fun verdictFor(t: Throwable): Boolean? = when {
        // Somebody answered. Keep what they said.
        t is VfsException.AccessDenied -> false
        t is VfsException.NotFound -> false
        t is VfsException.AlreadyExists -> false
        t is VfsException.Unsupported -> false
        // A TLS failure is a server: it spoke, and what it said was refused. Rotating past a
        // bad certificate to a plain timeout somewhere else would hide the one message that
        // tells the reader what is actually wrong.
        t.javaClass.name.startsWith("javax.net.ssl.") -> false

        // Nothing answered.
        t is ConnectException -> true
        t is SocketTimeoutException -> true
        t is NoRouteToHostException -> true
        t is PortUnreachableException -> true
        t is UnknownHostException -> true

        // This device's own decision, not the network's.
        t is java.util.concurrent.CancellationException -> false
        t is InterruptedIOException -> false
        t is InterruptedException -> false

        // Android's socket errors arrive as ErrnoException, which is not on the compile
        // classpath here, so the errno name is read off the message. These are the four that
        // mean "there is nothing at that address".
        else -> {
            val m = t.message.orEmpty()
            if (ERRNOS.any { m.contains(it) }) true else null
        }
    }

    private val ERRNOS = listOf("ECONNREFUSED", "EHOSTUNREACH", "ENETUNREACH", "ETIMEDOUT")

    /**
     * Did something at the far end answer, even though the answer was no?
     *
     * The other half of the same question. A refusal proves the address works, so the address
     * earns being tried first next time - while a cancel or a broken stream proves nothing about
     * it either way and must not promote it.
     */
    fun isAnswer(t: Throwable?): Boolean = when (t) {
        is VfsException.AccessDenied,
        is VfsException.NotFound,
        is VfsException.AlreadyExists,
        is VfsException.NotADirectory,
        is VfsException.IsADirectory,
        -> true
        else -> false
    }
}

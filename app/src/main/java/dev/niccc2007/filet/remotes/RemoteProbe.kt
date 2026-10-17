package dev.niccc2007.filet.remotes

import dev.niccc2007.filet.vfs.VfsException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What happened when a saved remote was actually dialled, in words a person can act on.
 *
 * ## Why this exists
 *
 * The Remotes screen said nothing about whether a share worked, and its own comment admitted
 * why: nothing polled it, so "connected" would have been a guess dressed as a fact. That was
 * the honest choice given no probe - but the consequence is that five completely different
 * failures all presented as one silent nothing, and the one thing a reader needs to know is
 * WHICH of the five it is, because each has a different fix:
 *
 * | What it is | What to do about it |
 * |---|---|
 * | nothing at that address | check the PC is awake and on this network |
 * | something there, wrong port | check the port, or that the service is running |
 * | the password was refused | check the user, password, or domain |
 * | no such share | check the share name |
 * | it answered | nothing |
 *
 * Telling them apart is a classification over a throwable, which is exactly the shape that is
 * wrong at a call site and right as one tested function - the same reasoning as `NetFailure`
 * next door, which answers a different question about the same exceptions.
 */
object RemoteProbe {

    /**
     * @param ok true only when the share answered and could be listed.
     * @param label two or three words, for a pill.
     * @param detail one sentence naming what to change. Empty when there is nothing to do.
     */
    data class Verdict(val ok: Boolean, val label: String, val detail: String)

    val WORKING = Verdict(true, "reachable", "")

    /**
     * Classify a failure from listing a remote's root.
     *
     * Ordered most specific first, and that order is the content: an `AccessDenied` wrapped in
     * an `IOException` has to be read as the refusal it is rather than as the IO error it is
     * also technically true to call. Anything unrecognised keeps its own message rather than
     * being flattened into "failed" - a message nobody wrote for this screen is still more
     * useful than a word that was.
     */
    fun verdictFor(t: Throwable?): Verdict {
        if (t == null) return WORKING
        // Walked, because every provider wraps: sshj, smbj and the HTTP client all put their
        // own exception around the socket's.
        var at: Throwable? = t
        var steps = 0
        while (at != null && steps++ < DEPTH) {
            classify(at)?.let { return it }
            val next = at.cause
            if (next === at) break
            at = next
        }
        val message = t.message?.takeIf { it.isNotBlank() }
        return Verdict(false, "did not answer", message ?: "Filet could not reach this share.")
    }

    private fun classify(t: Throwable): Verdict? = when (t) {
        // ── it answered, and said no ──
        is VfsException.AccessDenied -> Verdict(
            false,
            "refused",
            "The share is there and the sign-in was refused. Check the user, the password, " +
                "and the domain if it is a work machine.",
        )

        is VfsException.NotFound -> Verdict(
            false,
            "no such share",
            "The device answered but has nothing at that name. Check the share name, or the " +
                "folder inside it this remote starts in.",
        )

        // ── nothing answered ──
        is UnknownHostException -> Verdict(
            false,
            "unknown name",
            "That name does not resolve on this network. An IP address works where a name " +
                "does not.",
        )

        is ConnectException -> Verdict(
            false,
            "refused the port",
            "Something is at that address but nothing is listening on that port. Check the " +
                "port, and that the service is actually running on the other device.",
        )

        is NoRouteToHostException -> Verdict(
            false,
            "no route",
            "That address is not reachable from this network. Both devices have to be on the " +
                "same Wi-Fi.",
        )

        is SocketTimeoutException -> Verdict(
            false,
            "timed out",
            "Nothing answered in time. The other device may be asleep, or a firewall is " +
                "dropping it silently.",
        )

        // Generic, and LAST among the IO exceptions, because every one above is also an
        // IOException and matching this first would swallow all of them.
        is IOException -> if (t.message?.contains("ECONNRESET") == true) {
            Verdict(
                false,
                "dropped",
                "The connection was closed partway. On SMB this is usually a server that " +
                    "refuses the protocol version Filet offered.",
            )
        } else {
            null
        }

        else -> null
    }

    private const val DEPTH = 8
}

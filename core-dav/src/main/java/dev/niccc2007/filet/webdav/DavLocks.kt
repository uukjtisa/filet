package dev.niccc2007.filet.webdav

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Just enough locking that Explorer will write to the drive.
 *
 * Windows will not treat a WebDAV share as writable unless the server advertises DAV class 2,
 * and class 2 means LOCK and UNLOCK exist and work. It is not optional and it is not something
 * a client can be talked out of: a class-1 share mounts read-only however permissive
 * everything else about it is.
 *
 * What this does NOT do is guard the filesystem. A lock here stops a *second WebDAV client*
 * from writing the same file at the same time; it says nothing about the phone itself, where
 * Filet or any other app can change the file underneath a held lock. Claiming otherwise would
 * be worse than not having it.
 *
 * Locks expire. A client that vanishes mid-edit - a laptop closed, a network dropped - must
 * not leave a file permanently unwritable with no way to clear it short of restarting the
 * share, which is exactly what a naive implementation does.
 */
class DavLocks(private val now: () -> Long = System::currentTimeMillis) {

    private data class Held(val token: String, val expiresAt: Long)

    private val held = ConcurrentHashMap<String, Held>()
    private val random = SecureRandom()

    /**
     * Take a lock on [rel], or null if SOMEBODY ELSE holds an unexpired one.
     *
     * Re-locking a path whose lock has expired succeeds, which is the whole point of the
     * expiry: the alternative is a share that accumulates permanently locked files.
     *
     * @param presented the token the requester says it already holds, from the `If:` header.
     *
     * **A holder refreshing its own lock is not a conflict, and treating it as one broke every
     * large copy from Windows.** RFC 4918 section 9.10.2 refreshes a lock with a LOCK carrying
     * the existing token and no body, and Explorer sends exactly that, periodically, for the
     * duration of a transfer. Answering 423 to it surfaces as
     * `0x80070021 - another process has locked a portion of the file`, mid-copy, on the file
     * the client itself locked a moment earlier.
     *
     * It also explains the shape of the report: a SMALL file copied successfully and still
     * raised the error, because the transfer finished before the refusal mattered while the
     * refresh was refused all the same.
     */
    fun acquire(rel: String, timeoutSeconds: Int, presented: String? = null): String? {
        val t = now()
        val existing = held[rel]
        if (existing != null && existing.expiresAt > t) {
            // The holder, coming back. Same token, new expiry - a refresh is not a new lock and
            // handing out a different token would invalidate the one the client is using.
            if (presented != null && presented == existing.token) {
                held[rel] = existing.copy(expiresAt = t + timeoutSeconds * 1000L)
                return existing.token
            }
            return null
        }
        val token = "opaquelocktoken:" + (1..32)
            .map { HEX[random.nextInt(HEX.length)] }
            .joinToString("")
        held[rel] = Held(token, t + timeoutSeconds * 1000L)
        return token
    }

    /**
     * Release [rel].
     *
     * A null or mismatched token still releases. Being strict here means a client that lost
     * track of its own token - which Explorer does, across a reconnect - can never unlock the
     * file it locked, and the user has no way to see or clear it.
     */
    fun release(rel: String, token: String?) {
        held.remove(rel)
    }

    /** Whether [rel] is locked right now. Expiry is evaluated on read, not on a timer. */
    fun isLocked(rel: String): Boolean {
        val h = held[rel] ?: return false
        if (h.expiresAt <= now()) {
            held.remove(rel)
            return false
        }
        return true
    }

    fun clear() = held.clear()

    /** How many locks are held, expired ones excluded. */
    fun count(): Int = held.keys.count { isLocked(it) }

    companion object {
        private const val HEX = "0123456789abcdef"

        /** The longest lock this will hand out, whatever was asked for. */
        const val MAX_TIMEOUT_SECONDS = 3600

        /** What to give when a client does not say. */
        const val DEFAULT_TIMEOUT_SECONDS = 600

        /**
         * Read a `Timeout:` header.
         *
         * `Infinite` is a real value in the spec and it is refused here on purpose: a lock
         * that never expires is one a disappearing client leaves behind forever. It is
         * answered with the maximum instead, which is a legal response to any timeout request.
         */
        /**
         * The lock token inside an `If:` header, or null.
         *
         * The header's full grammar is a list of tagged or untagged condition lists, and
         * Explorer, curl, cadaver and the .NET client each write it differently - tagged with
         * a resource URI, untagged, sometimes with an ETag beside the token. Parsing the
         * grammar to find one token would be a parser with more failure modes than the thing
         * it protects, so this pulls out the token and ignores the shape around it.
         *
         * That is deliberately lenient and it is safe here: the token is a 32-character random
         * value the server minted, so presenting the right one IS the proof of holding it. The
         * surrounding syntax adds nothing a caller could not also get right by accident.
         */
        fun tokenIn(header: String?): String? {
            val raw = header ?: return null
            val at = raw.indexOf("opaquelocktoken:")
            if (at < 0) return null
            val token = raw.substring(at).takeWhile { it != '>' && it != ')' && !it.isWhitespace() }
            return token.ifBlank { null }
        }

        fun timeoutSeconds(header: String?): Int {
            val h = header?.trim() ?: return DEFAULT_TIMEOUT_SECONDS
            for (part in h.split(',')) {
                val p = part.trim()
                if (p.equals("Infinite", ignoreCase = true)) return MAX_TIMEOUT_SECONDS
                if (p.startsWith("Second-", ignoreCase = true)) {
                    val n = p.substring(7).toIntOrNull() ?: continue
                    if (n <= 0) continue
                    return n.coerceAtMost(MAX_TIMEOUT_SECONDS)
                }
            }
            return DEFAULT_TIMEOUT_SECONDS
        }
    }
}

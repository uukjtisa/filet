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
     * Take a lock on [rel], or null if somebody else holds an unexpired one.
     *
     * Re-locking a path whose lock has expired succeeds, which is the whole point of the
     * expiry: the alternative is a share that accumulates permanently locked files.
     */
    fun acquire(rel: String, timeoutSeconds: Int): String? {
        val t = now()
        val existing = held[rel]
        if (existing != null && existing.expiresAt > t) return null
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

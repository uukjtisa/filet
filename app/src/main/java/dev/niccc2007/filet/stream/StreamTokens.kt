package dev.niccc2007.filet.stream

import dev.niccc2007.filet.vfs.VPath
import java.security.SecureRandom

/**
 * Which file each stream link is for. A link names a token, never a path: the token is 128
 * random bits that map to one file and nothing else, so another app on the phone that finds the
 * port can still read only what Filet has handed out a link to.
 *
 * One token a file, kept while it is in use, so a player re-opening the same track gets the same
 * link. The table is capped, the least recently used dropped first.
 */
class StreamTokens(
    private val cap: Int = 256,
    private val fresh: () -> String = ::randomToken,
) {
    /** What a token is for: the file, and what the answer needs to say about it. */
    data class Entry(val path: VPath, val size: Long, val mime: String)

    private val byToken = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val byPath = HashMap<VPath, String>()

    /** The token for [entry]'s file, the one it already has if it has one. */
    @Synchronized
    fun mint(entry: Entry): String {
        byPath[entry.path]?.let { t ->
            byToken[t] = entry
            return t
        }
        val t = fresh()
        byToken[t] = entry
        byPath[entry.path] = t
        while (byToken.size > cap) {
            val eldest = byToken.entries.iterator().next()
            byToken.remove(eldest.key)
            byPath.remove(eldest.value.path)
        }
        return t
    }

    /** What [token] is for, or null for one never handed out or long dropped. */
    @Synchronized
    fun resolve(token: String): Entry? = byToken[token]

    companion object {
        private val random = SecureRandom()

        /** 128 bits, as 32 lower-case hex digits. */
        fun randomToken(): String {
            val b = ByteArray(16)
            random.nextBytes(b)
            return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

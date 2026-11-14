package dev.niccc2007.filet.nearby

/**
 * Whether a shared file may be downloaded right now, by what its shared entry says.
 *
 * The access code is checked before any of this: these are the rules for one file on top of it.
 * A fast share's key opens a locked file, because the phone asked "You enabled restricted access
 * for this. Fast share bypasses it." before making the key. It never opens a paused or an expired
 * one.
 */
enum class ShareGate {
    OPEN,

    /** Kept on the list with its link; the download is answered "Paused by the owner" and not counted. */
    PAUSED,

    /** Asks for the lock code. */
    LOCKED,

    /** Its expiry has passed. */
    GONE;

    companion object {
        /**
         * @param entry the shared entry the file is, or is inside; null for a file of Filet/Shared,
         *   which has no rules of its own.
         * @param lockOk the request carried the lock code.
         * @param keyOk the request carried a fast share's key that opens this file.
         */
        fun of(entry: SharedEntry?, now: Long, lockOk: Boolean = false, keyOk: Boolean = false): ShareGate = when {
            entry == null -> OPEN
            entry.isExpired(now) -> GONE
            entry.paused -> PAUSED
            entry.locked && !lockOk && !keyOk -> LOCKED
            else -> OPEN
        }

        /** Who downloaded: a Filet gives its name; anything else is a browser at its address. */
        fun who(filetName: String?, address: String): String =
            filetName?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: "A browser · $address"
    }
}

/**
 * A download from the share that finished, for Activity and the download message.
 *
 * @param name what was downloaded, as the one downloading saw it: the file's shared name, or the
 *   zip's.
 * @param ended the one-time entry this download stopped sharing, if it was one.
 */
data class Served(val name: String, val who: String, val ended: Boolean)

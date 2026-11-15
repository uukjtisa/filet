package dev.niccc2007.filet.locker

import dev.niccc2007.filet.vfs.VPath

/**
 * Which paths are in the Locker, asked in one place by everything that must not remember them.
 *
 * A Locker file is private in every sense the rest of Filet has a store for: recents, the job
 * history, saved tabs, the caches under `Filet/`, the logs. Each of those asks [isPrivate] rather
 * than matching the scheme itself, so a place that holds a path inside the Locker - an entry in a
 * zip that lives there, which has a scheme of its own with the Locker path inside it - is caught
 * the same way as the file itself.
 */
object LockerPaths {

    const val SCHEME = "locker"

    /** The top of the Locker. */
    val ROOT: VPath = VPath(SCHEME, "/")

    private const val MARK = "$SCHEME://"

    fun isPrivate(path: VPath): Boolean = path.scheme == SCHEME || path.path.contains(MARK)

    fun isPrivate(raw: String): Boolean = raw.contains(MARK)

    /**
     * [text] with everything from a Locker path to the end of its line cut off, for a log. To the
     * end of the line rather than the end of the path, because a name with a space in it has no
     * end a pattern can find, and half a name is still a name.
     */
    fun redact(text: String): String {
        if (!text.contains(MARK)) return text
        return text.lines().joinToString("\n") { line ->
            val at = line.indexOf(MARK)
            if (at < 0) line else line.substring(0, at) + "$MARK…"
        }
    }
}

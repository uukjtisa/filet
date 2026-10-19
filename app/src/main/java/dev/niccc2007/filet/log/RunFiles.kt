package dev.niccc2007.filet.log

/**
 * The rules for how much log is kept, as pure functions over names and sizes.
 *
 * The file handling is in [FiletLog], which is the one file in this package allowed to touch
 * storage (see `check-r3.mjs`); the decisions are here so they can be tested without a disk.
 */
object RunFiles {

    /** A run on disk: its file name and size. */
    data class Run(val name: String, val bytes: Long)

    const val PREFIX = "filet_"
    const val SUFFIX = ".log"

    /**
     * A run's name, and nothing else - no separator, no traversal. This is the check that stands
     * between "read run X" from an issue reporter and reading any file the app can see.
     */
    fun isRun(name: String): Boolean =
        name.startsWith(PREFIX) && name.endsWith(SUFFIX) && '/' !in name && '\\' !in name && ".." !in name

    /**
     * Which runs to delete, OLDEST FIRST.
     *
     * Newest first by name - the names are ISO-ordered, so name order is time order (see
     * `FiletLog.nameFor`) - keeping at most [keep] runs and at most [maxBytes] between them.
     * [current] is never deleted, whatever its size: a run deleting its own file would log into
     * nothing.
     */
    fun toDelete(runs: List<Run>, keep: Int, maxBytes: Long, current: String?): List<String> {
        val newestFirst = runs.filter { isRun(it.name) }.sortedByDescending { it.name }
        var kept = 0
        var bytes = 0L
        val gone = ArrayList<String>()
        for (r in newestFirst) {
            if (r.name == current) {
                kept++
                bytes += r.bytes
                continue
            }
            if (kept < keep && bytes + r.bytes <= maxBytes) {
                kept++
                bytes += r.bytes
            } else {
                gone += r.name
            }
        }
        return gone.reversed()
    }
}

/**
 * How much one run may write, and what happens at the limit.
 *
 * Two thresholds. Past [soft], DEBUG and INFO are dropped and warnings, failures and requested
 * dumps still go in - when something is going wrong at length, the failures are the part worth
 * the space. Past [hard], nothing more is written. Each crossing writes one line saying so,
 * because a log that stops without saying why reads as the app having stopped.
 *
 * Not thread-safe by itself: it is only ever asked from the log thread.
 */
class Budget(val soft: Long, val hard: Long) {

    var written = 0L
        private set

    /** Lines refused since the run began. */
    var dropped = 0L
        private set

    private var softNoted = false
    private var hardNoted = false

    /** The decision for one line: whether to write it, and a notice to write first. */
    data class Verdict(val write: Boolean, val notice: String?)

    fun admit(important: Boolean, bytes: Int): Verdict {
        if (written >= hard || written + bytes > hard) {
            dropped++
            if (!hardNoted) {
                hardNoted = true
                val note = "--- log reached ${hard / 1024 / 1024} MB: nothing more is written this run ---\n"
                written += note.length
                return Verdict(false, note)
            }
            return Verdict(false, null)
        }
        if (!important && written >= soft) {
            dropped++
            if (!softNoted) {
                softNoted = true
                val note = "--- log reached ${soft / 1024 / 1024} MB: debug and info lines are dropped from here; " +
                    "warnings, failures and dumps are still written ---\n"
                written += note.length
                return Verdict(false, note)
            }
            return Verdict(false, null)
        }
        written += bytes
        return Verdict(true, null)
    }
}

/** One thread's line of `/proc/self/task/<tid>/stat`, parsed. */
object ProcStat {

    data class Thread(val tid: Int, val name: String, val ticks: Long)

    /**
     * `tid (name) state ... utime stime ...`. The name is in parentheses and may itself contain
     * spaces and parentheses, so the fields are counted from the LAST `)`: after it, field 0 is
     * the state, 11 is user time and 12 is system time, in clock ticks.
     */
    fun parse(line: String): Thread? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 0 || close < open) return null
        val tid = line.substring(0, open).trim().toIntOrNull() ?: return null
        val name = line.substring(open + 1, close)
        val f = line.substring(close + 1).trim().split(' ')
        if (f.size < 13) return null
        val user = f[11].toLongOrNull() ?: return null
        val sys = f[12].toLongOrNull() ?: return null
        return Thread(tid, name, user + sys)
    }
}

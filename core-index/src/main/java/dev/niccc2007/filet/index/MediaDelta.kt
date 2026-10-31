package dev.niccc2007.filet.index

/**
 * Keeping the index current from the media store's own change record. SEARCH.md §14.6.
 *
 * The retired Tier 2 answered a media change with a twenty-second walk of every root, from a
 * background job, and drained a day's battery doing it (see `IndexCoordinator`). What this
 * answers a change with is the folder it happened in: the media store numbers every write with
 * a generation, so "what changed since I last looked" is one query, and the work is one
 * `readdir` per folder in the answer.
 */
object MediaDelta {

    /**
     * More changed folders than this and reading them one by one is no longer the cheap option.
     *
     * A camera burst is one folder; a restored backup is thousands. Past this the bounded pass
     * on open does the job, and it skips every folder whose modification time has not moved.
     */
    const val MAX_FOLDERS = 300

    /** Changes closer together than this are read as one. */
    const val SETTLE_MS = 2_000L

    enum class Plan {
        /** Nothing has been written since the last look. */
        NOTHING,

        /** Never looked before: remember where the media store is, read nothing. */
        BASELINE,

        /** Read the folders of the rows changed since the last look. */
        DELTA,

        /**
         * The generation went backwards: the media store's database was rebuilt, and the stored
         * number means nothing against it. Fall back to the bounded pass and start over.
         */
        RESET,
    }

    /**
     * What to do with a volume whose generation was [stored] at the last look and is [current]
     * now.
     *
     * A first look is a baseline and not a full read: the index's own build - the media store
     * seed and the bounded passes - is what reads everything once. Treating generation zero as
     * "everything changed" would re-read every folder on the phone the first time the app opened
     * after an update.
     */
    fun plan(stored: Long, current: Long): Plan = when {
        stored < 0 -> Plan.BASELINE
        current == stored -> Plan.NOTHING
        current < stored -> Plan.RESET
        else -> Plan.DELTA
    }

    /**
     * The folders holding [paths], deduplicated, or null when there are more than [cap].
     *
     * A path with no folder above it is dropped rather than read as the root: the media store
     * only lists files under a volume, and "/" is not one.
     */
    fun folders(paths: Sequence<String>, cap: Int = MAX_FOLDERS): List<String>? {
        val out = LinkedHashSet<String>()
        for (p in paths) {
            val cut = p.lastIndexOf('/')
            if (cut <= 0) continue
            out += p.substring(0, cut)
            if (out.size > cap) return null
        }
        return out.toList()
    }
}

/**
 * How long the listener outlives Filet being minimised. SEARCH.md §14.6, decision 20.
 *
 * The number the setting shows is the number the code obeys. An early release "after two quiet
 * minutes" was considered and rejected: a setting that silently ends sooner than it says is a
 * setting that lies.
 */
object ListenWindow {
    /** Minutes. Off is zero. */
    val CHOICES = listOf(0, 2, 5, 10)

    const val DEFAULT = 10

    /** A stored value from a build with other choices is read as the default, not as nonsense. */
    fun normalise(minutes: Int): Int = if (minutes in CHOICES) minutes else DEFAULT

    /** When the listener lets go, for a minimise at [minimisedAt]. Equal to it for Off. */
    fun releaseAt(minimisedAt: Long, minutes: Int): Long = minimisedAt + normalise(minutes) * 60_000L

    fun label(minutes: Int): String = normalise(minutes).let { if (it == 0) "Off" else "$it min" }

    /** The sentence under the setting, for the value chosen. */
    fun describe(minutes: Int): String = when (val m = normalise(minutes)) {
        0 -> "Finds new files while Filet is open, and on return."
        else -> "Keeps finding new files for $m minutes after leaving Filet."
    }
}

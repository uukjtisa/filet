package dev.niccc2007.filet.home

import dev.niccc2007.filet.data.Prefs
import org.json.JSONObject

/**
 * When each file was first noticed in a tracked folder.
 *
 * The one piece of new storage the history needed, and it is deliberately as small as it can
 * be: **one timestamp per path, written once**, not an event log.
 *
 * It exists because the filesystem cannot answer the question Nic asked for. A file downloaded
 * today can carry any mtime at all - a PDF written last year, a photo from a camera with a
 * wrong clock - so sorting by mtime does not tell you when it turned up. His words:
 * *"this list only lists when fiels where first seen on the tracked folders.. but this other
 * list activity wise re arranges that lsit to which files was edited created."*
 *
 * ## Two rules that make it mean anything
 *
 * **Written once.** A path already known keeps its original timestamp however many times it is
 * seen again. Overwrite it on every scan and "first seen" silently becomes "last scanned",
 * which sorts identically to nothing at all and looks like it works.
 *
 * **Pruned and bounded.** Download churns; without pruning this grows forever on the one device
 * it is meant to help. Entries for files that no longer exist are dropped on each pass, and the
 * store is capped - past the cap the OLDEST entries go first, because a file whose first-seen
 * date has aged out is one the history no longer reaches anyway.
 */
class FirstSeenStore(
    private val read: () -> String?,
    private val write: (String?) -> Unit,
) {

    /**
     * The ordinary constructor.
     *
     * The primary one takes two lambdas rather than [Prefs] so this class can be proved on the
     * JVM. `SharedPreferences` is an Android API and the rules that make first-seen mean
     * anything - written once, pruned, bounded - are exactly the ones worth a test, so they
     * must not sit behind something a unit test cannot construct.
     */
    constructor(prefs: Prefs) : this({ prefs.getString(KEY) }, { prefs.putString(KEY, it) })

    private val known = LinkedHashMap<String, Long>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = read() ?: return
        runCatching {
            val o = JSONObject(raw)
            for (k in o.keys()) known[k] = o.optLong(k)
        }
    }

    /** When [path] was first seen, or null if it has never been recorded. */
    @Synchronized
    fun of(path: String): Long? {
        ensureLoaded()
        return known[path]
    }

    /**
     * Note that [paths] exist now.
     *
     * Only paths that are new to the store get [now]; the rest keep what they had. Returns how
     * many were genuinely new, which is what a caller needs to decide whether to save.
     */
    @Synchronized
    fun record(paths: Collection<String>, now: Long): Int {
        ensureLoaded()
        var added = 0
        for (p in paths) {
            if (!known.containsKey(p)) {
                known[p] = now
                added++
            }
        }
        if (added > 0) {
            trim()
            save()
        }
        return added
    }

    /**
     * Drop anything not in [stillThere].
     *
     * Called with the paths a scan just saw. Not called with a partial listing: pruning against
     * an incomplete scan would forget files that are merely in a folder that failed to read
     * this time, and their first-seen date would be silently reset the next time they appeared.
     */
    @Synchronized
    fun prune(stillThere: Set<String>) {
        ensureLoaded()
        val before = known.size
        known.keys.retainAll(stillThere)
        if (known.size != before) save()
    }

    /** For the "clear history" control, and for tests. */
    @Synchronized
    fun clear() {
        ensureLoaded()
        known.clear()
        save()
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        return known.size
    }

    private fun trim() {
        if (known.size <= MAX) return
        // Oldest first: a file whose first-seen date has aged past the cap is one the history
        // no longer reaches, so losing it costs nothing a user can see.
        val doomed = known.entries.sortedBy { it.value }.take(known.size - MAX).map { it.key }
        for (k in doomed) known.remove(k)
    }

    private fun save() {
        val o = JSONObject()
        for ((k, v) in known) o.put(k, v)
        write(o.toString())
    }

    companion object {
        const val KEY = "home.firstSeen"

        /**
         * How many paths are remembered.
         *
         * Generous - this is a few tens of bytes per entry - but finite, because a phone whose
         * Download folder turns over thousands of files a month would otherwise grow this
         * without limit, and the feature would become a reason the app got slower.
         */
        const val MAX = 20_000
    }
}

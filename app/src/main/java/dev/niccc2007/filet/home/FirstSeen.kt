package dev.niccc2007.filet.home

import dev.niccc2007.filet.data.Prefs
import org.json.JSONObject

/**
 * When each file was first noticed in a tracked folder.
 *
 * The one piece of new storage the history needed, and it is deliberately as small as it can
 * be: **one timestamp per path, written once**, not an event log.
 *
 * It exists because the filesystem cannot answer the question. A file downloaded
 * today can carry any mtime at all - a PDF written last year, a photo from a camera with a
 * wrong clock - so sorting by mtime does not tell you when it turned up. Put plainly:

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

    /**
     * Whether a complete pass has ever been recorded.
     *
     * Until one has, every path is new because Filet was not watching yet, so it is dated from
     * its own modification time. Afterwards Filet genuinely is watching, and a path appearing
     * for the first time really did turn up now.
     */
    private var seeded = false
    private var dirty = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = read() ?: return
        runCatching {
            val o = JSONObject(raw)
            seeded = o.optBoolean(SEEDED_KEY, false)
            for (k in o.keys()) if (k != SEEDED_KEY) known[k] = o.optLong(k)
        }
    }

    /** When [path] was first seen, or null if it has never been recorded. */
    @Synchronized
    fun of(path: String): Long? {
        ensureLoaded()
        return known[path]
    }

    /**
     * Note that these files exist now.
     *
     * @param mtimes each path with its own modification time, needed while the store is seeding.
     * @return how many were genuinely new, which is what a caller needs to decide whether to
     *   save.
     *
     * Only paths that are new to the store are stamped; the rest keep what they had.
     *
     * ## The seeding window, and why "the store is empty" was not good enough
     *
     * When tracking starts, every file already on the device is new to the store. Stamping them
     * with the clock puts four thousand files under "Today at 11:37pm", which is not a history
     * and is not true - nothing was first seen at that moment, the store simply did not exist.
     * So they are seeded from their own modification times, which is the best evidence there is
     * about when each one arrived.
     *
     * The first attempt used "the store is empty" to detect that, and it was wrong ON THE
     * DEVICE rather than in a test: the feed publishes a fast partial result before its full
     * scan finishes, so the store stopped being empty after six files and the remaining four
     * thousand were stamped with the clock a second later. The symptom was identical to the bug
     * it was meant to fix.
     *
     * The second attempt made it a five-minute WINDOW from the store's first use, and that was
     * wrong ON THE DEVICE too. A window is a timer, and the thing it was standing in for is not
     * a duration - it is *whether a complete pass has happened yet*. The timestamp persists, so
     * a store created five minutes ago in an earlier session is already past its window: the
     * first full scan then lands afterwards and every file on the phone is stamped with the
     * clock. Measured on a real device, screenshots from 12 June all read as first seen at
     * 10:11am today, which was simply when the app had been started.
     *
     * So the condition is now the condition itself. Until a **complete** pass has been
     * recorded, a new path takes its own mtime, however long that takes and however many
     * sessions it spans. Once one has, Filet is genuinely watching and a new path takes the
     * clock - which is the whole point, because a file COPIED in today keeps whatever mtime it
     * was written with and only the clock records that it turned up today.
     *
     * @param complete whether [mtimes] is the whole tracked set rather than a partial listing.
     *   Only a complete pass ends the seeding, for the same reason only a complete pass may
     *   [prune]: a partial one does not prove that anything is genuinely new.
     */
    @Synchronized
    fun record(mtimes: Map<String, Long>, now: Long, complete: Boolean = false): Int {
        ensureLoaded()
        val seeding = !seeded
        var added = 0
        for ((p, mtime) in mtimes) {
            if (!known.containsKey(p)) {
                known[p] = if (seeding && mtime > 0) mtime else now
                added++
            }
        }
        if (seeding && complete && mtimes.isNotEmpty()) {
            seeded = true
            dirty = true
        }
        if (added > 0 || dirty) {
            trim()
            save()
        }
        return added
    }

    /** For callers that have no mtimes to hand - a path is new and this is when it turned up. */
    @Synchronized
    fun record(paths: Collection<String>, now: Long): Int =
        record(paths.associateWith { 0L }, now)

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
        // Seeding starts over too: after a deliberate clear, the next pass is a first run
        // again and should seed rather than stamp everything with the moment the button was
        // pressed.
        seeded = false
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
        if (seeded) o.put(SEEDED_KEY, true)
        for ((k, v) in known) o.put(k, v)
        write(o.toString())
        dirty = false
    }

    companion object {
        /**
         * The storage key, versioned.
         *
         * Two earlier versions were written by versions of [record] that stamped files already
         * on the device with the clock, so every one of them read "Today". There is no way to
         * tell a wrongly-stamped entry from a correct one, so rather than migrate, the key
         * moves: the store reads as empty and seeds itself properly on the next pass. The stale
         * entries are a few kilobytes that will never be read again.
         */
        const val KEY = "home.firstSeen.v4"

        /**
         * How many paths are remembered.
         *
         * Generous - this is a few tens of bytes per entry - but finite, because a phone whose
         * Download folder turns over thousands of files a month would otherwise grow this
         * without limit, and the feature would become a reason the app got slower.
         */
        const val MAX = 20_000

        /** The seeded flag shares the blob; a path can never collide with it. */
        private const val SEEDED_KEY = "__seeded"

    }
}

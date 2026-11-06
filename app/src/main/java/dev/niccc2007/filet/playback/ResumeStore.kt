package dev.niccc2007.filet.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where you left off, per track, across restarts.
 *
 * ## Why this is not the player's job
 *
 * The player already holds a position, and it loses it the moment the process goes - which on
 * a file manager is most of the time, because people open it, do something, and leave. A
 * ninety-minute film and a two-hour mix are both things you come back to, and coming back to
 * the start of one is the difference between a media app and a preview pane.
 *
 * ## What is stored, and what deliberately is not
 *
 * One row per track: where it was, how long it is, and when. No artwork, no waveform, no beat
 * map - those are all derivable from the file and all large, and a store that grows without
 * bound is a store somebody eventually has to clear by hand. [KEEP] rows of a few hundred
 * bytes each, oldest dropped first.
 *
 * Every stop is kept, for the music player and the video screen alike: wherever something was
 * left, whatever its length and however far in, a skip included. Only a stop at 0:00 changes
 * nothing - see [keeps]. A track played to its end is kept too, at its end, and Carry on tags it
 * finished rather than dropping it; opening a finished one starts it again. See `Finished`.
 *
 * ## Who writes
 *
 * Two players, and both must. The shared player (`Playback`) writes while it plays and on
 * pause. The video screen owns its own `MediaPlayer` and never went through that path at all -
 * which is why a video quit halfway was never recorded. It writes through [put] with
 * `settled = true` when it pauses, closes, or the app leaves the foreground, and its end when it
 * plays to the end. The same rule holds for both players: one record, which Carry on lists and
 * the video preview's Play from reads.
 *
 * ## Telling the list
 *
 * Bug identified here: the Carry on list re-read only when a view-model counter was bumped, and
 * nothing that WROTE a position bumped it - only the list's own buttons and a pull-to-refresh
 * did. A position written a moment ago was on disk and not on screen until something unrelated
 * recomposed the pane, which presented as entries taking a while to appear. The store now says
 * when it changed, through [changes], so every writer is covered without knowing the list
 * exists.
 *
 * [changes] moves when the SET of rows changes, or when a write is marked settled - a pause, a
 * stop, a close. It does not move for the periodic write while something plays: that would
 * re-read the list every five seconds for as long as anything played with the pane open, a
 * standing cost for a percentage nobody is watching move.
 *
 * Rows are held in memory after the first read, so neither a read nor a write re-parses the
 * JSON.
 *
 * ## Its own file
 *
 * Not in `Prefs`. This turns over constantly while anything plays, and putting that in the same
 * file as the settings means every settings read contends with it, and a corrupt write takes
 * the settings with it.
 */
class ResumeStore private constructor(context: Context) {

    private val sp = context.getSharedPreferences("filet.resume", Context.MODE_PRIVATE)

    /** The parsed rows, newest first, or null before the first read. Guarded by `this`. */
    private var rows: List<Point>? = null

    private val _changes = MutableStateFlow(0)

    /** Moves when the list should be re-read. See the note on the class. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /**
     * One place to come back to.
     *
     * @param path the track's id, which IS its path - see `tracksOf`.
     * @param title what to show, so the list draws without statting every row.
     * @param video true for a video, which is what the two filters sort on.
     * @param positionMs where it got to.
     * @param durationMs how long the whole thing is, or 0 when it was never known.
     * @param at when this was written, for the ordering and the "yesterday" line.
     */
    data class Point(
        val path: String,
        val title: String,
        val video: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val at: Long,
    )

    private fun current(): List<Point> =
        rows ?: parse(sp.getString(KEY, null)).sortedByDescending { it.at }.also { rows = it }

    /** Newest first, which is the only order this list is ever wanted in. */
    fun all(): List<Point> = synchronized(this) { current() }

    fun pointOf(path: String): Point? = all().firstOrNull { it.path == path }

    fun positionOf(path: String): Long? = pointOf(path)?.positionMs?.takeIf { it > 0L }

    /**
     * Write where a track got to. A stop at 0:00 changes nothing: see [keeps].
     *
     * @param settled true for a write made because playback STOPPED - a pause, a close, the app
     *   going to the background. That is the moment the list should show it, so [changes]
     *   moves. The periodic write while playing passes false.
     */
    fun put(
        path: String,
        title: String,
        video: Boolean,
        positionMs: Long,
        durationMs: Long,
        settled: Boolean = false,
    ) {
        synchronized(this) {
            if (!keeps(positionMs)) return
            val before = current()
            val had = before.any { it.path == path }
            val others = before.filterNot { it.path == path }
            save(others + Point(path, title, video, positionMs, durationMs, System.currentTimeMillis()))
            if (!had || settled) _changes.value++
        }
    }

    fun forget(path: String) {
        synchronized(this) {
            val before = current()
            if (before.none { it.path == path }) return
            save(before.filterNot { it.path == path })
            _changes.value++
        }
    }

    fun clear() {
        synchronized(this) {
            rows = emptyList()
            sp.edit().remove(KEY).apply()
            _changes.value++
        }
    }

    /** Caller holds `this`. */
    private fun save(list: List<Point>) {
        // Newest kept, oldest dropped. Sorted before the cut, or a long sitting adds rows at
        // the end of the array and the cut takes the ones somebody is most likely to want.
        val keep = list.sortedByDescending { it.at }.take(KEEP)
        rows = keep
        val out = JSONArray()
        for (r in keep) {
            out.put(
                JSONObject()
                    .put("p", r.path)
                    .put("t", r.title)
                    .put("v", r.video)
                    .put("at", r.positionMs)
                    .put("d", r.durationMs)
                    .put("w", r.at),
            )
        }
        // apply(), not commit(): the write is handed to the platform's own background writer,
        // so a pause on the main thread costs a map update and nothing on the disk.
        sp.edit().putString(KEY, out.toString()).apply()
    }

    private fun parse(raw: String?): List<Point> {
        if (raw.isNullOrBlank()) return emptyList()
        // Best effort throughout. A resume list that throws has made the app worse than not
        // having one, and the only cost of giving up on a bad row is one forgotten position.
        return runCatching {
            val array = JSONArray(raw)
            val out = ArrayList<Point>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val path = o.optString("p")
                if (path.isEmpty()) continue
                out += Point(
                    path = path,
                    title = o.optString("t").ifEmpty { path.substringAfterLast('/') },
                    video = o.optBoolean("v"),
                    positionMs = o.optLong("at"),
                    durationMs = o.optLong("d"),
                    at = o.optLong("w"),
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY = "points"

        /**
         * How many places to remember.
         *
         * A hundred and twenty, which at a few hundred bytes a row is under fifty kilobytes
         * and is more tracks than anybody has half-finished. The cap exists so the file cannot
         * grow without bound, not because the rows are expensive.
         */
        const val KEEP = 120

        /**
         * Whether [put] writes a position: anything past 0:00, for every kind of media and both
         * players. Leaving is what Carry on exists for - Back, the phone's Back, Filet sent away,
         * a pause, Next - whatever the length and however far in, the end included.
         *
         * Bug identified here: the store used to judge what was worth coming back to - ten
         * seconds in, twenty from the end, fifteen seconds long - so a stop early in a video, a
         * short clip, or a skip to its middle was never listed, and Carry on and the preview's
         * Play from could not agree. There is one rule now, and it has no thresholds.
         *
         * 0:00 alone is no stop, and it changes nothing rather than forgetting: a track opened
         * at its start, with the card offering 12:34, and left without moving must not lose the
         * 12:34 it was offering. The reading spots of the documents follow the same rule.
         */
        fun keeps(positionMs: Long): Boolean = positionMs > 0L

        @Volatile private var instance: ResumeStore? = null

        /**
         * The one store for the process.
         *
         * A second instance would be a second view of the same file with its own idea of
         * what is in it, and two of those writing a few seconds apart is how a position gets
         * lost. Both players write and the list reads, so they have to be the same object.
         */
        fun of(context: Context): ResumeStore =
            instance ?: synchronized(this) {
                instance ?: ResumeStore(context.applicationContext).also { instance = it }
            }
    }
}

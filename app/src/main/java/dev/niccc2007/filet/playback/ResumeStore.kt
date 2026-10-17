package dev.niccc2007.filet.playback

import android.content.Context
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
 * Asked for as issue #6, in those terms: a way to resume audio and video after the app has
 * been closed, with somewhere to see what was playing.
 *
 * ## What is stored, and what deliberately is not
 *
 * One row per track: where it was, how long it is, and when. No artwork, no waveform, no beat
 * map - those are all derivable from the file and all large, and a store that grows without
 * bound is a store somebody eventually has to clear by hand. [KEEP] rows of a few hundred
 * bytes each, oldest dropped first.
 *
 * Positions are NOT kept for a track that reached its end. Having finished something is the
 * one state where "resume" has no meaning, and an entry that offers to resume the last two
 * seconds of a track you have already heard is worse than no entry.
 *
 * ## Its own file
 *
 * Not in `Prefs`. This is a log that turns over constantly - a write every few seconds while
 * anything is playing - and putting that in the same file as the settings means every settings
 * read contends with it, and a corrupt write takes the settings with it.
 */
class ResumeStore private constructor(context: Context) {

    private val sp = context.getSharedPreferences("filet.resume", Context.MODE_PRIVATE)

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

    /** Newest first, which is the only order this list is ever wanted in. */
    fun all(): List<Point> = parse(sp.getString(KEY, null)).sortedByDescending { it.at }

    fun positionOf(path: String): Long? =
        all().firstOrNull { it.path == path }?.positionMs?.takeIf { it > 0L }

    /**
     * Write where a track got to, or forget it if there is nothing worth coming back to.
     *
     * The judgement is [worthKeeping] and it is pure, because "worth coming back to" is the
     * only decision here that can be wrong in a way nobody notices - an entry for the first
     * three seconds of every track anybody ever tapped would fill the list with noise, and a
     * missing entry for the film somebody watched most of is the bug this exists to fix.
     */
    fun put(path: String, title: String, video: Boolean, positionMs: Long, durationMs: Long) {
        val rows = parse(sp.getString(KEY, null)).filterNot { it.path == path }
        if (!worthKeeping(positionMs, durationMs)) {
            // Rewritten WITHOUT it rather than left alone: a track played to its end should
            // lose the stale position it had from the last time round.
            save(rows)
            return
        }
        val now = System.currentTimeMillis()
        save(rows + Point(path, title, video, positionMs, durationMs, now))
    }

    fun forget(path: String) {
        save(parse(sp.getString(KEY, null)).filterNot { it.path == path })
    }

    fun clear() {
        sp.edit().remove(KEY).apply()
    }

    private fun save(rows: List<Point>) {
        // Newest kept, oldest dropped. Sorted before the cut, or a long sitting adds rows at
        // the end of the array and the cut takes the ones somebody is most likely to want.
        val keep = rows.sortedByDescending { it.at }.take(KEEP)
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

        /** Below this, nothing was listened to - it was a tap and a change of mind. */
        const val MIN_IN_MS = 10_000L

        /**
         * And this close to the end, it is finished.
         *
         * Twenty seconds, which covers an album's run-out and a film's credits. Resuming
         * somebody into the last fifteen seconds of a track is technically where they were and
         * is never what they wanted.
         */
        const val MIN_LEFT_MS = 20_000L

        /**
         * Whether a position is worth coming back to.
         *
         * Pure, and the one judgement in this file. A duration of zero means the length was
         * never known - a stream, or a header that would not parse - and those are kept on the
         * strength of the position alone, because the alternative is forgetting the one case
         * where somebody most wants to be remembered.
         */
        fun worthKeeping(positionMs: Long, durationMs: Long): Boolean {
            if (positionMs < MIN_IN_MS) return false
            if (durationMs <= 0L) return true
            return positionMs < durationMs - MIN_LEFT_MS
        }

        @Volatile private var instance: ResumeStore? = null

        /**
         * The one store for the process.
         *
         * A second instance would be a second view of the same file with its own idea of
         * what is in it, and two of those writing a few seconds apart is how a position gets
         * lost. The player writes and the list reads, so they have to be the same object.
         */
        fun of(context: Context): ResumeStore =
            instance ?: synchronized(this) {
                instance ?: ResumeStore(context.applicationContext).also { instance = it }
            }
    }
}

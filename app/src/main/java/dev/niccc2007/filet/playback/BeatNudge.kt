package dev.niccc2007.filet.playback

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One record's own correction to its beat, in 10 ms steps: the light earlier or later for that
 * track alone, and 0 for every other.
 *
 * Not the deleted Timing slider back (D-02). That one was a global calibration, wrong on every
 * phone nobody had tuned it on. This corrects one file's ANALYSIS: a soft attack the onset
 * detector places late, a live recording with no sharp transients. No measurement of the route
 * can fix that, because the error is in what was read off the file, not in the output. A route's
 * delay is still not a control; see [BeatLatency].
 *
 * Positive is later: the light is driven from an earlier part of the track, so it fires after
 * the sound it used to fire with.
 */
object BeatNudge {

    const val STEP_MS = 10

    /** Half a second either way. A map further off than that is a wrong map, to be rebuilt. */
    const val MAX_MS = 500

    /** A held button's first wait, so a tap is one step and not two. */
    const val HOLD_FIRST_MS = 400L

    /** The fastest a held button steps, 25 a second: half a second of nudge in two seconds. */
    const val HOLD_FASTEST_MS = 40L

    /** [ms] on the 10 ms grid and inside half a second. */
    fun clamp(ms: Int): Int = ((ms / STEP_MS.toFloat()).roundToInt() * STEP_MS).coerceIn(-MAX_MS, MAX_MS)

    /** `+30 ms later`, `−20 ms earlier`, or `0 ms`. */
    fun label(ms: Int): String = when {
        ms > 0 -> "+$ms ms later"
        ms < 0 -> "−${-ms} ms earlier"
        else -> "0 ms"
    }

    /**
     * How long a held button waits before its next step, [repeat] steps in: the first wait,
     * then quicker each step until [HOLD_FASTEST_MS]. A held button accelerates; a tap is 10 ms.
     */
    fun holdDelay(repeat: Int): Long =
        if (repeat <= 0) HOLD_FIRST_MS else max(HOLD_FASTEST_MS, 200L - 20L * repeat)
}

/**
 * Every track's nudge, by the track's id (its `VPath`, the key "Remember for this track" uses).
 *
 * Kept in `filet.beatnudge`, beside `filet.remember` and out of a settings export for the same
 * reason: it is what this phone's listener did about these files, not how the app is set up.
 * A track at 0 has no entry.
 */
class BeatNudges(private val store: RememberStore) {

    private val all = MutableStateFlow(load())

    /** [trackId]'s nudge as it changes. */
    fun of(trackId: String?): Flow<Int> = all.map { m -> trackId?.let { m[it] } ?: 0 }.distinctUntilChanged()

    /** [trackId]'s nudge now; 0 for a track that has none, or no track. */
    fun nudgeOf(trackId: String?): Int = trackId?.let { all.value[it] } ?: 0

    /** [deltaMs] more on [trackId]'s nudge, held to the grid and the range. */
    fun add(trackId: String, deltaMs: Int) = set(trackId, nudgeOf(trackId) + deltaMs)

    fun set(trackId: String, ms: Int) {
        val v = BeatNudge.clamp(ms)
        if (v == 0) {
            all.value = all.value - trackId
            store.remove(trackId)
        } else {
            all.value = all.value + (trackId to v)
            store.put(trackId, v.toString())
        }
    }

    private fun load(): Map<String, Int> =
        store.all().mapNotNull { (k, v) -> v.toIntOrNull()?.let { BeatNudge.clamp(it) }?.takeIf { it != 0 }?.let { k to it } }.toMap()

    companion object {
        @Volatile private var instance: BeatNudges? = null

        /** The one instance, so the panel and the light read one set of nudges. */
        fun of(context: Context): BeatNudges =
            instance ?: synchronized(this) {
                instance ?: BeatNudges(PrefsTextStore(context.applicationContext, "filet.beatnudge"))
                    .also { instance = it }
            }
    }
}

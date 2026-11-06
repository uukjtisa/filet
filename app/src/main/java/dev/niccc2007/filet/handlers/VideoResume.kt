package dev.niccc2007.filet.handlers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a video carries on from, between the video screen, its preview and Carry on.
 *
 * Two records, for two questions.
 *
 * **Carry on** is `ResumeStore`, on disk. The video screen plays on its own player, not the
 * music player, so the music player's record of where a track got to never saw a video, and Carry
 * on never listed one. The screen now writes it itself - on a pause, on Back, on leaving, and when
 * the app goes to the background - and forgets the video when it plays to the end. What is worth
 * listing is still `ResumeStore.worthKeeping`'s call: nothing under fifteen seconds long, nothing
 * in the first ten seconds or the last twenty. A list of places to come back to stays that.
 *
 * **The preview's Play from** is [stops], in memory for as long as the app runs: where each video
 * was last stopped, kept or not. A video stopped at 0:09 is not worth listing, but scrolled away
 * from and back to, its preview still says where it was left. Cleared for a video when it plays to
 * the end and when its preview's Play from the start is chosen.
 *
 * The pill reads the saved place first, then the last stop: see [playFrom].
 */
object VideoResume {

    /** The last stop of every video stopped while the app has been running. */
    val stops = LastStops()

    /**
     * Where Play starts, and what the preview offers: the saved place, else where the video was
     * last stopped, else the start.
     *
     * The saved place first because a stop it outranks can only be older, or the same: every stop
     * that is saved is noted too, and the saved place can also have been moved on elsewhere, the
     * video carried on as music and paused there.
     *
     * @param savedMs `ResumeStore.positionOf` for this video, null when there is none.
     * @param lastStopMs [LastStops.of] for this video, null when there is none.
     */
    fun playFrom(savedMs: Long?, lastStopMs: Long?): Long =
        savedMs?.takeIf { it > 0L } ?: lastStopMs?.takeIf { it > 0L } ?: 0L

    /**
     * Where the screen starts when it takes the video over from the music player.
     *
     * The music player's live position when it has one. Carry on opens a video by asking the
     * music player to play it from the saved place and then opening the screen, and the music
     * player's position reads 0 from the moment it is asked until its own decoder has opened
     * the file and sought. A screen whose decoder was ready first took that 0 and started the
     * video from the beginning. Until the music player has a position, the saved place is what
     * it was asked to seek to: Carry on forgets the record before asking for the start.
     *
     * @param liveMs the music player's position now.
     * @param savedMs `ResumeStore.positionOf` for this video, null when there is none.
     */
    fun takeOverAt(liveMs: Long, savedMs: Long?): Long =
        if (liveMs > 0L) liveMs else savedMs?.coerceAtLeast(0L) ?: 0L

    /** The preview's pill: what Play does, said as the time it plays from. */
    fun label(fromMs: Long): String = "Play from ${clockOf(fromMs)}"
}

/**
 * Where each video was last stopped, by path, for as long as the app runs.
 *
 * Observable, so a preview already on screen follows a stop made under it. Capped at the [MAX]
 * most recently stopped: a session spent going through a camera roll should not grow it without
 * end, and a preview a few hundred videos back offering no place is no loss.
 */
class LastStops {

    private val _all = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Every stop held, by path. */
    val all: StateFlow<Map<String, Long>> = _all.asStateFlow()

    /** The last stop of [path], or null. */
    fun of(path: String): Long? = _all.value[path]

    /** [path] was stopped at [atMs]. At the start is no place, so it clears instead. */
    fun note(path: String, atMs: Long) {
        if (atMs <= 0L) return clear(path)
        synchronized(this) {
            val next = LinkedHashMap(_all.value)
            // Out and back in, so the newest stop is last and the cap drops the oldest.
            next.remove(path)
            next[path] = atMs
            while (next.size > MAX) next.remove(next.keys.first())
            _all.value = next
        }
    }

    /** Forget [path]'s stop: it played to the end, or was asked for from the start. */
    fun clear(path: String) {
        synchronized(this) {
            if (path !in _all.value) return
            _all.value = _all.value - path
        }
    }

    companion object {
        const val MAX = 256
    }
}

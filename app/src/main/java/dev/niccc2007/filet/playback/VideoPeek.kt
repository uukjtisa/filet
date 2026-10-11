package dev.niccc2007.filet.playback

/**
 * The short loop of picture a video shows while it is being played as audio.
 *
 * ## Why a video played as audio needs a picture at all
 *
 * A video handed to the background player becomes a track in a queue, and the player screen
 * draws a track as a square of cover art. A video has no embedded cover art, so what you got
 * was the generic audio glyph - the same placeholder a tagless MP3 gets. A lecture, a mix and
 * a clip of somebody's dog all looked identical, which makes the one screen that is supposed
 * to tell you what you are listening to the one screen that does not.
 *
 * A still frame would fix the identification and nothing else. A few seconds of the video,
 * looping, says what it is at a glance AND says that the thing playing is a video rather than
 * a song - which is the distinction the screen was failing to make.
 *
 * ## Why this is a pure function with a test
 *
 * The window is arithmetic with three ways to be wrong and all of them only show up on a
 * particular file. Past the end of a short video is a black loop. Starting at zero is a fade
 * from black on most things anybody films or edits. A fixed length is wrong at both ends: four
 * seconds of a two-second clip cannot be taken, and a quarter of a two-hour film is not a
 * glance.
 */
object VideoPeek {

    /** No loop is shorter than this; below it the eye reads a stutter rather than a clip. */
    const val MIN_MS = 1_000L

    /** And none is longer. Past about this it stops being a glance and becomes watching. */
    const val MAX_MS = 4_000L

    /**
     * Roughly how much of a long video the loop takes. An eighth, so a 30-second clip gives
     * about four seconds and anything longer is capped.
     */
    private const val SHARE = 8

    /**
     * How far in the loop starts, as a fraction of the room available for it.
     *
     * Not zero. The first frames of a video are a title card, a fade from black, or the dark
     * frame a camera writes while its exposure settles - and a loop of black is worse than the
     * placeholder it replaced, because it looks like the player is broken rather than like it
     * has nothing to show.
     */
    private const val LEAD_IN = 10

    /**
     * The slice to loop, as `start until end` in milliseconds.
     *
     * An empty range means "loop the whole file": either the duration is not known yet, or the
     * video is short enough that taking a piece of it would be sillier than playing all of it.
     * The caller treats that as seek-to-zero-and-let-it-run.
     */
    fun windowOf(durationMs: Long): LongRange {
        // Not known yet. MediaPlayer reports 0 or -1 before it has prepared, and a window
        // computed from that would be 0..0 - a single frame, frozen, which reads as a crash.
        if (durationMs <= 0L) return LongRange.EMPTY
        // Short enough to show whole. A two-second clip is already a glance; taking a
        // one-second window out of it throws away half of the thing and gains nothing.
        if (durationMs <= MAX_MS) return LongRange.EMPTY
        val length = (durationMs / SHARE).coerceIn(MIN_MS, MAX_MS)
        // Clamped against the room that is actually left, so the window can never run past the
        // end however the arithmetic above lands.
        val start = ((durationMs - length) / LEAD_IN).coerceIn(0L, durationMs - length)
        return start until (start + length)
    }

    /** Where to seek back to when the loop comes round, for a window that may be empty. */
    fun loopStart(window: LongRange): Long = if (window.isEmpty()) 0L else window.first

    /**
     * When the loop has run far enough, given where it is now.
     *
     * Position is polled rather than timed, so a decoder that stalls or a seek that lands late
     * cannot leave the loop running off the end of its window.
     */
    fun shouldLoop(positionMs: Long, durationMs: Long, window: LongRange): Boolean = when {
        window.isEmpty() -> false
        else -> positionMs >= window.last || (durationMs > 0 && positionMs >= durationMs)
    }
}

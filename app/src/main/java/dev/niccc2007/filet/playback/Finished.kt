package dev.niccc2007.filet.playback

/**
 * When a stop counts as the end of a track or video, and how far through it was.
 *
 * Carry on keeps every stop, the end included, so a finished item stays on the list rather than
 * vanishing from it. It is tagged with how far it got instead, because "I watched this to the
 * end" is worth knowing. The players and the list must all agree on where "finished" starts:
 * the list tags a row, and opening that row has to start at 0:00, or it lands on the last frame
 * and ends straight away. So the decision lives here once and every reader asks it.
 *
 * Decision: 99% of the length, not "the last N seconds". A fixed tail is most of a short clip
 * and nothing of a film. A share scales with both: the credits of a film, a second of a song.
 */
internal object Finished {

    /** At or past 99% of [durationMs]. An unknown length (0 or less) is never finished. */
    fun at(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && positionMs * 100 >= durationMs * 99

    /**
     * Whole percent through, floored and held to 0..100, for the tag. Floored so 99.9% reads
     * 99 rather than claiming the end it never reached.
     */
    fun percent(positionMs: Long, durationMs: Long): Int =
        if (durationMs <= 0L) 0 else (positionMs * 100 / durationMs).coerceIn(0L, 100L).toInt()

    /**
     * Where opening a remembered position starts: 0 for a finished one, since continuing at the
     * end ends at once, otherwise the position itself.
     */
    fun startAt(positionMs: Long, durationMs: Long): Long =
        if (at(positionMs, durationMs)) 0L else positionMs
}

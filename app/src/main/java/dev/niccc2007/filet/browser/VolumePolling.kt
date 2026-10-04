package dev.niccc2007.filet.browser

/**
 * Whether a volume is worth asking again on this pass.
 *
 * ## Why this is a decision and not an `if`
 *
 * The storage cards re-measure on a timer so a drive that was asleep comes back by itself. That
 * poll is cheap for a local disk and expensive for a mounted share that is away: measured on a
 * phone, one sleeping share cost **five seconds** of an eight-second poll, for ever, to be told
 * the same thing it was told last time. Polling it as often as a local disk is what made the
 * answer "switch the poll off", which would have taken the recovery with it.
 *
 * Backing off is the middle answer, and the shape of the backoff is exactly the kind of decision
 * that is wrong in both directions if it is guessed at the call site:
 *
 * - **Too eager** and the cost above stands.
 * - **Too lazy** and a share that came back five seconds ago is still reported offline a minute
 *   later, which reads as the feature not working.
 *
 * So: double the gap each time, and stop doubling at [MAX_DOUBLINGS]. A volume that has been away
 * for a while is checked every sixteenth pass rather than never, which on an eight-second poll is
 * a little over two minutes - slow enough to cost nothing, soon enough that nobody sits looking at
 * a card wondering.
 */
object VolumePolling {

    /**
     * @param pass which measurement pass this is, counting from one.
     * @param misses how many times in a row this volume has failed to report. Zero means it is
     *   answering, and an answering volume is always measured.
     */
    fun shouldProbe(pass: Long, misses: Int): Boolean {
        if (misses <= 0) return true
        val every = 1L shl misses.coerceAtMost(MAX_DOUBLINGS)
        return pass % every == 0L
    }

    /**
     * The ceiling on the doubling.
     *
     * Four, so the longest gap is sixteen passes. Without a ceiling the gap doubles for as long as
     * the device is away - a share off overnight would come back to a gap of hours, and the
     * recovery everybody is relying on would have quietly become a restart.
     */
    const val MAX_DOUBLINGS = 4
}

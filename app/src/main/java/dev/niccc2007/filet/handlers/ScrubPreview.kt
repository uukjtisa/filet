package dev.niccc2007.filet.handlers

/**
 * The thumbnail shown above the progress bar while it is being dragged.
 *
 * What makes this hard is not decoding a frame, it is deciding how often to. A drag across a
 * phone reports a few hundred positions a second, and a frame decode on a 4K file takes tens
 * of milliseconds - so asking for every position means a queue of decodes that arrive long
 * after the finger has moved on, and a preview that lags behind by seconds while the video
 * itself stutters.
 *
 * Two rules, both arithmetic, both here rather than in the screen:
 *
 *  - **Quantise.** Ask for a frame every [stepFor] of video rather than every pixel.
 *    slowly then sits on one already-decoded frame instead of asking for a hundred neighbours
 *    nobody can tell apart.
 *  - **One at a time.** A decode already running is not interrupted, and the newest request
 *    that arrived while it ran is the one taken next. Queueing them all means showing frames
 *    the finger left behind.
 */
object ScrubPreview {

    /**
     * Roughly how many distinct frames a drag across the whole bar should show.
     *
     * A fixed step in milliseconds was the fault: two seconds is barely a step on a two-hour
     * film and an eternity on a thirty-second clip, so the preview moved in visible jumps on
     * anything short. What should be constant is the number of frames across the BAR, because
     * that is what the finger is travelling along.
     *
     * About one frame every few pixels of bar. More is wasted - they cannot be told apart at
     * thumbnail size, and each one is a decode.
     */
    const val FRAMES_ACROSS = 160

    /** Never finer than this, however short the video. Below it the decodes cannot keep up. */
    const val MIN_STEP_MS = 250L

    /** Never coarser than this, however long. Beyond it the preview stops being useful. */
    const val MAX_STEP_MS = 5_000L

    /**
     * How far apart preview frames are for a video of this length.
     *
     * Scaled to the duration so the preview feels the same on a clip and on a film, then
     * clamped at both ends: too fine and the decodes fall behind the finger, too coarse and
     * the thumbnail jumps.
     */
    fun stepFor(durationMs: Long): Long {
        if (durationMs <= 0L) return MIN_STEP_MS
        return (durationMs / FRAMES_ACROSS).coerceIn(MIN_STEP_MS, MAX_STEP_MS)
    }

    /**
     * The frame to decode for a scrub at [positionMs].
     *
     * Snapped to a multiple of [stepMs] so a slow drag reuses what has already been decoded,
     * and clamped inside the video - a request one millisecond past the end returns nothing at
     * all from the platform, which reads as the preview breaking at the right-hand edge.
     */
    fun frameFor(positionMs: Long, durationMs: Long, stepMs: Long = stepFor(durationMs)): Long {
        if (durationMs <= 0L || stepMs <= 0L) return 0L
        val clamped = positionMs.coerceIn(0L, durationMs)
        val snapped = (clamped / stepMs) * stepMs
        return snapped.coerceIn(0L, durationMs)
    }

    /**
     * Whether a decode is worth starting.
     *
     * @param have the frame already decoded and on screen, or null.
     * @param running the frame a decode is currently working on, or null.
     * @param want the frame [frameFor] just asked for.
     */
    fun shouldDecode(have: Long?, running: Long?, want: Long): Boolean =
        want != have && want != running
}

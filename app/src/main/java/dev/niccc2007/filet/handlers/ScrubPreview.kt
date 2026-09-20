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
 *  - **Quantise.** Ask for a frame every [STEP_MS] of video rather than every pixel. Dragging
 *    slowly then sits on one already-decoded frame instead of asking for a hundred neighbours
 *    nobody can tell apart.
 *  - **One at a time.** A decode already running is not interrupted, and the newest request
 *    that arrived while it ran is the one taken next. Queueing them all means showing frames
 *    the finger left behind.
 */
object ScrubPreview {

    /**
     * How far apart preview frames are, in video time.
     *
     * Two seconds is about where neighbouring frames stop being distinguishable on a thumbnail
     * while still being close enough to find a scene by eye.
     */
    const val STEP_MS = 2_000L

    /**
     * The frame to decode for a scrub at [positionMs].
     *
     * Snapped to a multiple of [stepMs] so a slow drag reuses what has already been decoded,
     * and clamped inside the video - a request one millisecond past the end returns nothing at
     * all from the platform, which reads as the preview breaking at the right-hand edge.
     */
    fun frameFor(positionMs: Long, durationMs: Long, stepMs: Long = STEP_MS): Long {
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

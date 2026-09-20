package dev.niccc2007.filet.handlers

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turning a finger into brightness, volume or a seek.
 *
 * What a player is expected to do, and this one did not: drag up and down on the left of the
 * picture for brightness, on the right for volume, and sideways to move through the video.
 *
 * **Left is brightness, right is volume.** Not a preference - MX Player, VLC, mpv, Plex and
 * YouTube on mobile all agree, and a player that swaps them is wrong in the hands of anyone
 * who has used another one.
 *
 * All of it is arithmetic on a touch position, so all of it is here and tested. The handler in
 * `VideoScreen.kt` listens and applies, and decides nothing.
 */
object PlayerGesture {

    /** What a drag turned out to be. */
    enum class Drag {
        /** Sideways: move through the video. */
        SEEK,

        /** Up and down on the left. */
        BRIGHTNESS,

        /** Up and down on the right. */
        VOLUME,

        /** Not yet far enough to tell. */
        NONE,
    }

    /**
     * How much of the screen's height a full sweep of the range takes.
     *
     * Less than the whole height on purpose. A gesture that needs the entire screen to go from
     * silent to loud cannot be finished with a thumb, and the top and bottom of a phone screen
     * are the two places a thumb does not comfortably reach.
     */
    const val SWEEP = 0.7f

    /**
     * What this drag is, given how far it has travelled from where it started.
     *
     * Decided from the TOTAL travel rather than the latest movement, so the caller can ask
     * once and then hold the answer for the rest of the gesture. Deciding again every frame is
     * what makes a slightly diagonal drag flicker between seeking and changing the volume.
     *
     * @param startX where the finger first went down - that is what picks the side, not where
     *   it has wandered to since. A brightness drag that drifts across the middle is still a
     *   brightness drag.
     * @param slop how far it must travel before it counts as a drag at all.
     */
    fun drag(startX: Float, dx: Float, dy: Float, width: Float, slop: Float = 24f): Drag {
        if (abs(dx) < slop && abs(dy) < slop) return Drag.NONE
        // Ties go to seeking: it is the reversible one. A seek by mistake is undone by seeking
        // back, where a volume change by mistake is only noticed once it is too loud.
        if (abs(dx) >= abs(dy)) return Drag.SEEK
        return if (startX < width / 2f) Drag.BRIGHTNESS else Drag.VOLUME
    }

    /**
     * A level from 0 to 1, moved by a vertical drag.
     *
     * Screen coordinates grow downwards and every other player raises the value when the
     * finger goes up, so the sign is inverted here rather than at each call site.
     *
     * @param dyPx travel from where the drag started, not since the last frame. Accumulating
     *   per-frame deltas drifts, and drift in a volume control is the kind of bug that is only
     *   noticed as "it never quite reaches the top".
     */
    fun levelAfter(startLevel: Float, dyPx: Float, heightPx: Float, sweep: Float = SWEEP): Float {
        if (heightPx <= 0f || sweep <= 0f) return startLevel.coerceIn(0f, 1f)
        val moved = -dyPx / (heightPx * sweep)
        return (startLevel + moved).coerceIn(0f, 1f)
    }

    /**
     * A 0..1 level as one of the system's volume steps.
     *
     * Android's volume is a small number of steps, often fifteen, so a level has to land on one
     * of them. Rounding rather than truncating, or the top of the drag never reaches maximum.
     */
    fun volumeSteps(level: Float, maxSteps: Int): Int {
        if (maxSteps <= 0) return 0
        return (level.coerceIn(0f, 1f) * maxSteps).roundToInt().coerceIn(0, maxSteps)
    }

    /** A system volume step back to a 0..1 level, for starting a drag where the volume is. */
    fun volumeLevel(steps: Int, maxSteps: Int): Float {
        if (maxSteps <= 0) return 0f
        return (steps.toFloat() / maxSteps).coerceIn(0f, 1f)
    }

    /**
     * Where a sideways drag seeks to.
     *
     * Relative to where the video was when the drag began, unlike the progress bar, where the
     * position under the finger is the position. Dragging the picture is a nudge from here;
     * dragging the bar is a jump to there.
     *
     * @param fullSweepMs how much of the video a drag across the whole width covers. Capped
     *   against the duration, so a ninety-second clip does not scrub two minutes per screen.
     */
    fun seekAfter(
        startMs: Long,
        dxPx: Float,
        widthPx: Float,
        durationMs: Long,
        fullSweepMs: Long = 90_000L,
    ): Long {
        if (durationMs <= 0L || widthPx <= 0f) return startMs.coerceAtLeast(0L)
        val span = minOf(fullSweepMs, durationMs)
        val moved = (dxPx / widthPx) * span
        return (startMs + moved).toLong().coerceIn(0L, durationMs)
    }
}

package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.playback.Scrub
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
    fun drag(startX: Float, dx: Float, dy: Float, width: Float, slop: Float = COMMIT): Drag {
        // Far enough to be an intention at all.
        if (abs(dx) < slop && abs(dy) < slop) return Drag.NONE
        // And far enough from the diagonal to say WHICH intention. A drag that is still roughly
        // diagonal has not decided yet, so neither does this - the caller keeps asking, and the
        // answer arrives when the finger has actually committed.
        //
        // This is the fix for a sideways drag turning into a brightness drag. The threshold used
        // to be 24px with no ratio, which is a few pixels past Compose's own touch slop: at that
        // distance a horizontal drag carries three or four pixels of vertical jitter, either axis
        // can be the larger, and the answer was then held for the whole gesture. One wobble at the
        // start and the rest of the drag was the wrong gesture.
        val horizontal = abs(dx)
        val vertical = abs(dy)
        // Ties go to seeking: it is the reversible one. A seek by mistake is undone by seeking
        // back, where a volume change by mistake is only noticed once it is too loud.
        if (horizontal >= vertical) return Drag.SEEK
        // Vertical, but it has to be CLEARLY vertical. The asymmetry is deliberate and it is the
        // same reasoning as the tie: the ambiguous middle goes to the harmless gesture. Returning
        // NONE here instead would be defensible and is worse in the hand - the picture would sit
        // there doing nothing while a finger moves on it.
        if (vertical < horizontal * DOMINANCE) return Drag.SEEK
        return if (startX < width / 2f) Drag.BRIGHTNESS else Drag.VOLUME
    }

    /**
     * A drag's travel so far, summed from the platform's per-event movement.
     *
     * ## Why summed, and not "where it is now minus where it started"
     *
     * Bug identified here: a sideways swipe on a video changed the brightness or the volume. The
     * classifier was right; what it was given was not. The positions a drag reports are in the
     * gesture surface's own coordinates, and that surface MOVES during the gesture - a drag
     * brings the chrome back, the top bar comes in at full height in one frame, and the surface
     * under it (a `weight(1f)` box in the same column) is pushed down by the bar's height. A
     * finger that has not moved vertically at all is then 150 px higher in the surface's terms,
     * the travel reads as a clear upward drag, and the drag is held as BRIGHTNESS or VOLUME for
     * the rest of the gesture - raising the level, because up is louder.
     *
     * Each event's movement is computed by the platform from two positions mapped through the
     * SAME, current layout, so a move of the surface is not in it. Summing those is the finger's
     * own travel whatever the layout does underneath. It does not drift: the sum of differences
     * of one coordinate is that coordinate's change, to float precision.
     */
    class Travel {
        var dx = 0f
            private set
        var dy = 0f
            private set

        fun reset() { dx = 0f; dy = 0f }

        /** One event's movement, as the platform reports it. */
        fun add(moveX: Float, moveY: Float) { dx += moveX; dy += moveY }
    }

    /**
     * How far a finger travels before the gesture is allowed to mean anything.
     *
     * Deliberately well past the platform's touch slop. The decision is held for the rest of the
     * gesture, so it is worth waiting for: being slightly late to start seeking costs nothing that
     * anybody can perceive, and being wrong costs the whole drag.
     */
    const val COMMIT = 48f

    /**
     * How much more vertical than horizontal a drag has to be to move a level.
     *
     * Applied to the vertical gestures only. Seeking is the one that is cheap to get wrong, so it
     * takes the benefit of every doubt; brightness and volume have to be asked for clearly.
     *
     * 1.25 is about 51 degrees from horizontal - past the wobble in a real sideways drag, and
     * still comfortably inside what somebody performing a vertical drag actually produces. A ratio
     * rather than an angle because two deltas is the arithmetic the caller already has.
     */
    const val DOMINANCE = 1.25f

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
     * A sideways drag on the picture, event by event: where it seeks to, and in which gear.
     *
     * Relative, unlike the bar, where the position under the finger is the position: dragging the
     * picture is a nudge from here, dragging the bar is a jump to there. Everything else is the
     * bar's. The pace - one bar's width of travel is the whole video - because the picture used
     * to cover at most ninety seconds per sweep, forty times slower than the bar on an hour-long
     * video. And the gears: once the drag is a seek, moving the finger up or down by the bar's own
     * distances drops to a quarter of the pace past `Scrub.QUARTER_DP` and to whole seconds past
     * `Scrub.FINE_DP`, exactly as pulling away from the bar does, and back again on the way back.
     *
     * Every event moves through `Scrub.barMove` itself, so the picture and the bar are one piece
     * of arithmetic rather than two that can drift. The pull is counted from where the seek
     * began, so the slant of the drag that made it a seek does not change gear on its own; up and
     * down both count, as they do on the bar.
     *
     * @param startMs where the video was when the drag began.
     * @param pxPerDp the screen's density, for the gear distances.
     */
    class SeekDrag(startMs: Long, private val durationMs: Long, private val pxPerDp: Float) {
        private var atMs = startMs.coerceAtLeast(0L).toDouble()
        private var pullDp = 0f

        /** The gear the pull has reached, named on screen while it is in use. */
        val mode: Scrub.Mode get() = Scrub.modeFor(pullDp)

        /**
         * One event: [dxPx] along, [dyPx] up or down.
         *
         * @param trackPx the width of the progress bar the pace is taken from. See [seekTrackPx].
         */
        fun move(dxPx: Float, dyPx: Float, trackPx: Float) {
            pullDp += dyPx / pxPerDp.coerceAtLeast(0.1f)
            atMs = Scrub.barMove(atMs, dxPx, trackPx, durationMs, pullDp, pxPerDp) ?: atMs
        }

        /** Where letting go lands: whole seconds once fine, as on the bar. */
        fun target(): Long = when {
            // Unknown length: barMove moved nothing, and nothing is clamped against zero.
            durationMs <= 0L -> atMs.toLong()
            mode == Scrub.Mode.FINE -> Scrub.wholeSecond(atMs, durationMs) ?: atMs.toLong()
            else -> atMs.toLong().coerceIn(0L, durationMs)
        }
    }

    /**
     * The width a drag on the picture takes its pace from: the progress bar's when the measured
     * one can be believed, the picture's otherwise.
     *
     * The bar is narrower than the picture - its padding, and in landscape the navigation bar at
     * the side - so pacing by the picture's width would leave a drag a few percent behind it. But
     * the bar is only measured while the controls are up, and a width kept from before a rotation
     * is the other orientation's: wider than the picture, or under half of it. Neither is this
     * screen's bar, and the picture's width is the nearest answer until the bar is drawn again,
     * which the drag itself brings about by bringing the controls up.
     */
    fun seekTrackPx(pictureWidthPx: Float, barWidthPx: Float): Float =
        if (barWidthPx > 0f && barWidthPx <= pictureWidthPx && barWidthPx >= pictureWidthPx / 2f) {
            barWidthPx
        } else {
            pictureWidthPx
        }
}

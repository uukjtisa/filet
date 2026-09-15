package dev.niccc2007.filet.handlers

/**
 * Where a pinch or a double tap leaves the view.
 *
 * ## Why this is a file and not three lines inside a gesture callback
 *
 * The cause was not the gesture detector. `ZoomableImage` took `scale` as a **parameter** and
 * read it from inside `Modifier.pointerInput(bitmap) { … }`. That block is a coroutine which
 * restarts only when its key changes, and its key was the bitmap - so the coroutine captured
 * `scale` once, at 1f, and kept reading 1f for the life of the image. Every pinch frame then
 * computed `1f * zoom`, where `zoom` is a **per-frame ratio** of about 1.02. The result never
 * left 1.0, so the image never grew and the pinch looked dead.
 *
 * Double tap survived only because it assigns an absolute 2.5f and never reads the old value to
 * get there - but its *toggle back out* read the same stale `scale > 1.01f`, so double-tapping a
 * zoomed image zoomed it in again instead of restoring it. That half was broken too and had not
 * been noticed, which is the useful part: the visible symptom was half of the actual bug.
 *
 * So the arithmetic lives here, as a value that is transformed rather than a variable that is
 * read. A stale [ZoomView] produces a visibly wrong answer in a test; a stale capture inside a
 * pointer callback produces a viewer that quietly stops zooming, and that shipped.
 */
data class ZoomView(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    /** Whether the view is doing anything - below this a pan has nothing to move. */
    val zoomed: Boolean get() = scale > FLAT

    /**
     * Apply one frame of a pinch.
     *
     * @param zoom the **ratio for this frame**, not the total. `detectTransformGestures` reports
     *   roughly 1.0 every frame and the total is the product of them, which is exactly why this
     *   must multiply the current scale rather than a captured one.
     */
    fun pinched(zoom: Float, panX: Float, panY: Float, boxW: Float, boxH: Float): ZoomView {
        val next = (scale * zoom).coerceIn(1f, MAX)
        return ZoomView(
            scale = next,
            offsetX = clampPan(offsetX + panX, next, boxW),
            offsetY = clampPan(offsetY + panY, next, boxH),
        )
    }

    /** Pan with no zoom change - dragging an already-zoomed image around. */
    fun panned(panX: Float, panY: Float, boxW: Float, boxH: Float): ZoomView = ZoomView(
        scale = scale,
        offsetX = clampPan(offsetX + panX, scale, boxW),
        offsetY = clampPan(offsetY + panY, scale, boxH),
    )

    /**
     * Double tap: out if it is zoomed, in on the point touched if it is not.
     *
     * Centring on the tap is the whole value of a double tap over a button - it magnifies the
     * thing under the finger rather than the middle of the picture.
     */
    fun doubleTapped(atX: Float, atY: Float, boxW: Float, boxH: Float): ZoomView {
        if (zoomed) return NONE
        val next = STEP
        return ZoomView(
            scale = next,
            offsetX = clampPan((boxW / 2f - atX) * next, next, boxW),
            offsetY = clampPan((boxH / 2f - atY) * next, next, boxH),
        )
    }

    companion object {
        /** Nothing applied. Also what a double tap on a zoomed view returns to. */
        val NONE = ZoomView()

        /**
         * At or below this, the view counts as unzoomed. Float equality on 1f is not safe.
         *
         * [clampPan] reads this same constant rather than carrying its own. They were two
         * different numbers - 1.01 here and 1.001 there - which meant a view could report
         * itself unzoomed while still holding pan slack, and a scale between the two behaved
         * as zoomed for panning and flat for the double-tap toggle. Caught by a test written
         * to check something else; one threshold, one place.
         */
        const val FLAT = 1.001f

        /** Past this a photo is single pixels and panning it is hopeless. */
        const val MAX = 12f

        /** Where one double tap lands. Enough to read small text, not so far it is lost. */
        const val STEP = 2.5f
    }
}

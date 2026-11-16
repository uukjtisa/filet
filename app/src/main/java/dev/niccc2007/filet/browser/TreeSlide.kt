package dev.niccc2007.filet.browser

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.ui.ScrollEdgeCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * How far one pane's tree has slid sideways. Every row in the pane reads the same one, so the
 * tree moves as one piece: names, icons, chevrons and guide lines together, while each row's
 * own fill, its outline and the column at its end stay where they are.
 *
 * Read in layout and draw only, never in composition, so a drag moves pixels without
 * recomposing a single row. See [treeSlid] and the guides in `RowViews`.
 */
@Stable
class TreeSlide {
    /** Pixels the tree has moved left. */
    var offsetPx by mutableFloatStateOf(0f)
        private set

    /** How far it may go, from the deepest row on screen. See [TreeScale.maxSlideDp]. */
    var maxPx by mutableFloatStateOf(0f)
        private set

    private var settling: Job? = null

    /** Moves by [delta] pixels, inside the limits, and says how far it really went. */
    fun dragBy(delta: Float): Float {
        val before = offsetPx
        offsetPx = TreeScale.slid(before, delta, maxPx)
        return offsetPx - before
    }

    /** A touch catches a moving tree, as it catches a moving list. */
    fun stop() {
        settling?.cancel()
        settling = null
    }

    /** Back to the start at once: another folder's tree begins at its left edge. */
    fun reset() {
        stop()
        offsetPx = 0f
    }

    /**
     * A new limit. A tree slid further than the rows now on screen need eases back rather than
     * jumping, so scrolling up from a deep branch brings the shallow rows home.
     */
    fun limit(max: Float, scope: CoroutineScope) {
        maxPx = max.coerceAtLeast(0f)
        if (offsetPx > maxPx) {
            stop()
            val from = offsetPx
            settling = scope.launch {
                animate(from, maxPx, animationSpec = tween(SETTLE_MS, easing = FastOutSlowInEasing)) { v, _ -> offsetPx = v }
            }
        }
    }

    /** Carries on after the finger lifts, and stops at either end. */
    fun fling(velocity: Float, decay: DecayAnimationSpec<Float>, scope: CoroutineScope) {
        stop()
        if (abs(velocity) < MIN_FLING) return
        settling = scope.launch {
            var last = offsetPx
            AnimationState(offsetPx, velocity).animateDecay(decay) {
                val moved = dragBy(value - last)
                last = value
                if (moved == 0f) cancelAnimation()
            }
        }
    }

    private companion object {
        const val SETTLE_MS = 220
        const val MIN_FLING = 40f
    }
}

/**
 * Lays a row's sliding part out at the width it needs, at least the width it has, and places it
 * [TreeSlide.offsetPx] to the left. The box around it clips, so what slides past its edges is
 * hidden rather than drawn over the column at the row's end.
 *
 * @param needDp what the row needs: its indent, chevron, icon, gap and a name worth reading.
 */
fun Modifier.treeSlid(slide: TreeSlide, needDp: Float): Modifier = layout { measurable, constraints ->
    val width = maxOf(constraints.maxWidth, needDp.dp.roundToPx())
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) {
        // Read here, in placement, so a drag re-places the row and recomposes nothing.
        placeable.placeRelative(-slide.offsetPx.roundToInt(), 0)
    }
}

/**
 * The tree's sideways drag, on the list as a whole rather than per row, so there is one offset
 * and one gesture however many rows are on screen.
 *
 * It decides on the Initial pass, before the rows and the list's own scroll see the event, and
 * only consumes once the touch is plainly sideways and the tree can move that way - see
 * [TreeScale.claim]. Until then everything underneath works as before: a tap opens, a hold
 * selects and drags a file, a vertical drag scrolls. A tree at its end leaves the touch alone,
 * and the tab swipe, which yields to anything that consumes, takes it as usual.
 */
fun Modifier.treeDrag(slide: TreeSlide): Modifier = composed {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    pointerInput(slide, decay) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            slide.stop()
            val slop = viewConfiguration.touchSlop
            val holdMs = viewConfiguration.longPressTimeoutMillis
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var claim = TreeScale.Claim.UNDECIDED
            var fingers = 1
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                fingers = maxOf(fingers, event.changes.count { it.pressed })
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                tracker.addPosition(change.uptimeMillis, change.position)
                when (claim) {
                    TreeScale.Claim.SLIDE -> {
                        slide.dragBy(-change.positionChange().x)
                        change.consume()
                    }
                    TreeScale.Claim.LEAVE -> return@awaitEachGesture
                    TreeScale.Claim.UNDECIDED -> {
                        val travel = change.position - down.position
                        val held = change.uptimeMillis - down.uptimeMillis > holdMs
                        claim = TreeScale.claim(travel.x, travel.y, slop, slide.offsetPx, slide.maxPx, held, fingers)
                        if (claim == TreeScale.Claim.SLIDE) {
                            // What travelled past the slop moves now, so the names start under the finger.
                            slide.dragBy(-(travel.x - sign(travel.x) * slop))
                            change.consume()
                        }
                    }
                }
            }
            if (claim == TreeScale.Claim.SLIDE) slide.fling(-tracker.calculateVelocity().x, decay, scope)
        }
    }
}

/**
 * The cues for a tree that has more to one side: a dissolve and a chevron at the left edge once
 * it has slid, and at the right edge of the sliding part while more is hidden there. The right
 * one stops short of the column pinned at each row's end, so it never fades a size or a date.
 *
 * @param endDp the width pinned at the end of a row. See `treeTrailingDp`.
 */
@Composable
fun BoxScope.TreeSlideCues(slide: TreeSlide, ground: Color, endDp: Float) {
    val left by remember(slide) { derivedStateOf { slide.offsetPx > CUE_EDGE_PX } }
    val right by remember(slide) { derivedStateOf { slide.maxPx - slide.offsetPx > CUE_EDGE_PX } }
    ScrollEdgeCue(visible = left, ground = ground, atStart = true)
    Box(Modifier.matchParentSize().padding(end = endDp.dp)) {
        ScrollEdgeCue(visible = right, ground = ground, atStart = false)
    }
}

/** Within this many pixels of an end, call it the end, as `HScroll` does. */
private const val CUE_EDGE_PX = 2f

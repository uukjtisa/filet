package dev.niccc2007.filet.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.launch

/**
 * Attach "pull past either end to refresh" to a scrollable, with the indicator Chrome draws.
 *
 * A nested-scroll connection rather than a drag handler, because the list has to get first
 * refusal on every delta: only what the list could NOT consume is a pull past its end, and
 * that distinction is the whole gesture. Reading the raw drag instead would fire on an
 * ordinary scroll that happened to begin at the top.
 *
 * The indicator is drawn by this modifier, over the content, so every list that has the
 * gesture has the indicator too - a pull that can fire with nothing on screen to say so is
 * what made the old one feel like it went off by itself. It comes in from whichever edge is
 * being pulled, its arc fills as the pull approaches the line, and it turns accent-coloured
 * and points the other way once letting go will refresh. Letting go short slides it back out
 * and does nothing.
 *
 * The decision of what counts lives in [EdgePull], which has tests. This part is the plumbing.
 */
@Composable
fun Modifier.edgePullRefresh(
    atTop: () -> Boolean,
    atBottom: () -> Boolean,
    onRefresh: () -> Unit,
): Modifier {
    val density = LocalDensity.current
    val threshold = with(density) { EdgePull.THRESHOLD_DP.dp.toPx() }
    val maxTravel = with(density) { EdgePull.MAX_TRAVEL_DP.dp.toPx() }
    val radius = with(density) { 18.dp.toPx() }
    val stroke = with(density) { 2.5.dp.toPx() }
    val colors = Filet.colors
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val scope = rememberCoroutineScope()

    // What is drawn. Separate from the gesture's own state so the slide-out after release can
    // animate without the gesture thinking a pull is still in progress.
    var shownEdge by remember { mutableStateOf<PullEdge?>(null) }
    var shownDistance by remember { mutableStateOf(0f) }
    var fired by remember { mutableStateOf(false) }
    val retract = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }

    val connection = remember(threshold) {
        object : NestedScrollConnection {
            private var state = PullState()

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Only a real finger. A programmatic scroll - the reveal jump, or the scroll
                // to top on entering a folder - lands at an end with leftover delta and would
                // otherwise count as somebody asking for a refresh.
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                state = EdgePull.onScroll(state, available.y, atTop(), atBottom())
                if (state.edge != null) {
                    scope.launch { retract.snapTo(0f) }
                    fired = false
                    shownEdge = state.edge
                    shownDistance = state.distance
                } else if (shownEdge != null && !fired) {
                    shownDistance = 0f
                    shownEdge = null
                }
                // Nothing is consumed: the list keeps its own overscroll animation, so the
                // gesture still looks and feels like the end of a list.
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val (next, fire) = EdgePull.onRelease(state, threshold)
                state = next
                if (shownEdge != null) {
                    fired = fire
                    scope.launch {
                        if (fire) {
                            // One turn while the pane re-lists, then away. The refresh has no
                            // completion signal to wait on, and a spinner that never stops is
                            // worse than one that says "asked" and leaves.
                            spin.snapTo(0f)
                            spin.animateTo(1f, tween(450))
                        }
                        retract.animateTo(1f, tween(if (fire) 180 else 160))
                        shownEdge = null
                        shownDistance = 0f
                        fired = false
                    }
                }
                if (fire) onRefresh()
                return Velocity.Zero
            }
        }
    }

    return this
        .nestedScroll(connection)
        .drawWithContent {
            drawContent()
            val edge = shownEdge ?: return@drawWithContent
            val p = if (fired) 1f else EdgePull.progress(shownDistance, threshold)
            val travel = EdgePull.indicatorOffset(shownDistance.coerceAtLeast(if (fired) threshold else 0f), maxTravel) *
                (1f - retract.value)
            if (travel <= 0.5f) return@drawWithContent
            val cx = size.width / 2f
            val cy = if (edge == PullEdge.TOP) travel - radius else size.height - travel + radius
            val armed = fired || p >= 1f
            val ink = if (armed) colors.accent else colors.fg3
            val alpha = (0.35f + 0.65f * p).coerceIn(0f, 1f)

            drawCircle(surface, radius, Offset(cx, cy), alpha = alpha)
            drawCircle(colors.lineSoft, radius, Offset(cx, cy), alpha = alpha, style = Stroke(1f))

            // The arc grows with the pull and turns with it, which is what reads as winding up.
            val r = radius * 0.5f
            val sweep = 40f + 260f * p
            val turn = p * 220f + spin.value * 360f
            rotate(turn, Offset(cx, cy)) {
                drawArc(
                    ink, -90f, sweep, false,
                    topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
                    alpha = alpha, style = Stroke(stroke, cap = StrokeCap.Round),
                )
                // Arrowhead at the arc's leading end.
                val end = Math.toRadians((-90f + sweep).toDouble())
                val tip = Offset(cx + r * kotlin.math.cos(end).toFloat(), cy + r * kotlin.math.sin(end).toFloat())
                val head = stroke * 1.6f
                val dir = end + Math.PI / 2
                val back = Offset(tip.x - head * kotlin.math.cos(dir).toFloat(), tip.y - head * kotlin.math.sin(dir).toFloat())
                val side = Offset(-(tip.y - back.y), tip.x - back.x)
                val arrow = Path().apply {
                    moveTo(tip.x + (tip.x - back.x), tip.y + (tip.y - back.y))
                    lineTo(back.x + side.x * 0.9f, back.y + side.y * 0.9f)
                    lineTo(back.x - side.x * 0.9f, back.y - side.y * 0.9f)
                    close()
                }
                drawPath(arrow, ink, alpha = alpha)
            }
        }
}

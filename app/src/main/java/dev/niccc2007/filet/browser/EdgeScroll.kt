package dev.niccc2007.filet.browser

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A row that scrolls by itself while something is dragged against its edge.
 *
 * Asked for on the tab strip and the bottom bar picker: a long row can only be rearranged end to
 * end if holding a tab at the edge brings the rest of the row to it. Without this the thing being
 * dragged can only go as far as the screen shows, and moving the first tab last takes a drop, a
 * scroll and a second drag for every screenful.
 */
object EdgeScroll {

    /**
     * How far to scroll this frame for something held at [at] in a row [viewport] wide: nothing
     * in the middle, faster the deeper into the [edge] band it is, [fastest] at the very edge or
     * past it. Negative is towards the start.
     */
    fun step(at: Float, viewport: Float, edge: Float, fastest: Float): Float {
        if (viewport <= 0f || edge <= 0f) return 0f
        val band = minOf(edge, viewport / 3f)
        return when {
            at < band -> -fastest * ((band - at) / band).coerceAtMost(1f)
            at > viewport - band -> fastest * ((at - (viewport - band)) / band).coerceAtMost(1f)
            else -> 0f
        }
    }
}

/**
 * While [active], scroll [scroll] whenever [heldAt] (in the row's content, unscrolled) sits in
 * one of its edge bands, and report how far it went through [moved] so the held item can stay
 * under the finger.
 */
@Composable
fun EdgeAutoScroll(
    active: Boolean,
    scroll: ScrollState,
    heldAt: () -> Float,
    moved: (Float) -> Unit,
    edge: Dp = 44.dp,
    fastest: Dp = 14.dp,
) {
    val density = LocalDensity.current
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val band = with(density) { edge.toPx() }
        val top = with(density) { fastest.toPx() }
        while (true) {
            withFrameNanos { }
            val want = EdgeScroll.step(heldAt() - scroll.value, scroll.viewportSize.toFloat(), band, top)
            if (want != 0f) {
                val went = scroll.scrollBy(want)
                if (went != 0f) moved(went)
            }
        }
    }
}

package dev.niccc2007.filet.ui

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/** A rectangle in window pixels. Plain numbers, so the rule below is tested without Android. */
data class PxBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * How far something must step in from the screen's left and right edges to stay clear of a
 * camera hole.
 *
 * Bug identified here: the browser padded its whole column by the cutout's side inset. In
 * landscape that inset is the depth of the hole - 96 px on a phone whose punch hole is a 72 px
 * circle mid-way down one edge - so a strip the full height of the screen was given up for
 * something the size of a fingertip, and the tab strip, the path bar and every list stopped short
 * of the edge.
 *
 * The rule is per surface: a bar or list moves aside only when its own bounds sit beside a hole,
 * and only by as much as the hole reaches into it. A phone with no hole reports none and nothing
 * moves; a hole that only touches the top edge (portrait) never moves anything sideways, because
 * nothing is drawn beside it under the status bar.
 */
object CutoutRule {

    /** Left and right padding, in px, for something at [bounds] in a window [windowWidth] wide. */
    fun sidePads(bounds: PxBox, holes: List<PxBox>, windowWidth: Int): Pair<Int, Int> {
        var left = 0
        var right = 0
        for (h in holes) {
            // Beside: the hole's rows and the surface's rows overlap.
            if (h.top >= bounds.bottom || h.bottom <= bounds.top) continue
            if (h.left <= 0 && h.right > bounds.left) left = maxOf(left, h.right - bounds.left)
            if (h.right >= windowWidth && h.left < bounds.right) right = maxOf(right, bounds.right - h.left)
        }
        return left to right
    }
}

/**
 * Steps this surface in from a side edge only where a camera hole sits beside it. See [CutoutRule].
 *
 * The bounds are those of the surface before the padding, so the padding never changes what it
 * is measured from.
 */
fun Modifier.clearOfHoles(): Modifier = composed {
    val view = LocalView.current
    val density = LocalDensity.current
    // Read so that turning the screen recomposes with the hole where it is now.
    val inLeft = WindowInsets.displayCutout.getLeft(density, LayoutDirection.Ltr)
    val inRight = WindowInsets.displayCutout.getRight(density, LayoutDirection.Ltr)
    val holes = remember(view, inLeft, inRight) { holesOf(view) }
    var pads by remember { mutableStateOf(0 to 0) }
    if (holes.isEmpty()) {
        Modifier
    } else {
        Modifier
            .onGloballyPositioned { c ->
                val p = c.positionInWindow()
                val x = p.x.roundToInt()
                val y = p.y.roundToInt()
                val now = CutoutRule.sidePads(PxBox(x, y, x + c.size.width, y + c.size.height), holes, view.rootView.width)
                if (now != pads) pads = now
            }
            .absolutePadding(
                left = with(density) { pads.first.toDp() },
                right = with(density) { pads.second.toDp() },
            )
    }
}

private fun holesOf(view: View): List<PxBox> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
    val cut = view.rootWindowInsets?.displayCutout ?: return emptyList()
    return cut.boundingRects.map { PxBox(it.left, it.top, it.right, it.bottom) }
}

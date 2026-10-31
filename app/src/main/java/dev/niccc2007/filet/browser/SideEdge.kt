package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.ui.theme.Filet
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The width the side bar is drawn at right now, for the few things inside it that size
 * themselves by trial rather than by measuring - see `RecentSwitch`. Re-keying on it is what lets
 * them grow back when the bar is widened, not only shrink when it is narrowed.
 */
val LocalSideBarWidth = compositionLocalOf { SideWidth.DEFAULT_DP.toFloat() }

/**
 * The side bar's edge, dragged to set its width. A double tap puts the default back.
 *
 * The width moves live under the finger and is stored once, when the finger lifts: a preference
 * write per frame would be sixty disk writes a second for a value only the last of which matters.
 *
 * One gesture loop for both the drag and the double tap. Two detectors on the same element race
 * for the down event, and whichever wins decides, which is how a double tap ends up resizing by a
 * pixel or a drag ends up resetting.
 *
 * @param widthDp the width as drawn - stored or live, already clamped.
 * @param bounds the clamp for the window this is in. A live width is clamped as it is dragged, so
 *   dragging past the limit and back starts moving again at once rather than after a dead zone.
 * @param onLive the width while dragging, then null once the finger is up.
 * @param line draw the 1dp divider down the middle: the rail sits against the listing and needs
 *   one; the drawer's edge faces the gap before the pushed card and does not.
 */
@Composable
fun SideEdge(
    widthDp: Float,
    bounds: (Float) -> Float,
    onLive: (Float?) -> Unit,
    onCommit: (Int) -> Unit,
    onReset: () -> Unit,
    line: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Filet.colors
    val density = LocalDensity.current
    val config = LocalViewConfiguration.current
    val width by rememberUpdatedState(widthDp)
    val clamp by rememberUpdatedState(bounds)
    val live by rememberUpdatedState(onLive)
    val commit by rememberUpdatedState(onCommit)
    val reset by rememberUpdatedState(onReset)
    var dragging by remember { mutableStateOf(false) }
    var lastTapAt by remember { mutableStateOf(0L) }

    Box(
        modifier
            .width(SideWidth.GAP_DP.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var now = width
                    var travelled = 0f
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp()) break
                        val dx = change.positionChange().x
                        travelled += abs(dx)
                        if (!moved && travelled > config.touchSlop) {
                            moved = true
                            dragging = true
                        }
                        if (moved && dx != 0f) {
                            change.consume()
                            now = clamp(now + with(density) { dx.toDp().value })
                            live(now)
                        }
                    }
                    if (moved) {
                        dragging = false
                        commit(now.roundToInt())
                        live(null)
                    } else {
                        val at = System.currentTimeMillis()
                        if (at - lastTapAt <= config.doubleTapTimeoutMillis) {
                            lastTapAt = 0L
                            reset()
                        } else {
                            lastTapAt = at
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (line) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.lineSoft))
        // Always faintly visible: a drag handle nobody can see is not a drag handle.
        Box(
            Modifier
                .width(if (dragging) 4.dp else 3.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (dragging) colors.accent else colors.fg3.copy(alpha = 0.45f)),
        )
    }
}

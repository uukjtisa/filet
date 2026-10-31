package dev.niccc2007.filet.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.ui.theme.Filet

/**
 * How a file row answers a finger: a tap, a double tap, and a long press, decided quickly.
 *
 * ## The two waits that made selecting feel slow
 *
 * **The long press** waited the platform's full 500ms before anything happened, and in a file
 * list a long press is the most common thing a finger does after scrolling - it starts every
 * selection and every drag. [QuickPress] brings it to [LONG_PRESS_MS] for the rows only; the
 * rest of the app keeps the system timing.
 *
 * **The tap** waited for a double tap. Every row listens for one, so every single tap was held
 * back until the double-tap window closed in case a second one came. While SELECTING that
 * wait is pure cost - a tap there toggles a row, and toggling is cheap to undo - so [rowTaps]
 * acts on the first tap at once and, if a second follows inside the window, takes that second
 * one for itself (it is consumed before the row's own click sees it) and reports a double tap.
 * Outside a selection a tap OPENS something, and a folder cannot be un-opened, so there the
 * platform's wait stays.
 */
object RowTaps {
    /** Long enough that a resting finger is not a press; short enough not to feel like waiting. */
    const val LONG_PRESS_MS = 300L

    /** The rows' view configuration: the platform's, with a shorter long press. */
    class QuickPress(private val base: ViewConfiguration) : ViewConfiguration by base {
        override val longPressTimeoutMillis: Long get() = minOf(LONG_PRESS_MS, base.longPressTimeoutMillis)
    }
}

/**
 * Tap, double tap and long press for a row or a tile.
 *
 * @param selecting whether a selection is live. Changes how taps are timed - see [RowTaps].
 * @param onDoubleTap where the finger was, in root coordinates, so a menu can open under it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.rowTaps(
    selecting: Boolean,
    onTap: () -> Unit,
    onLongClick: () -> Unit,
    onDoubleTap: (Offset) -> Unit,
): Modifier {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var lastDown by remember { mutableStateOf(Offset.Zero) }
    val liveDouble = rememberUpdatedState(onDoubleTap)
    val tracked = this
        .onGloballyPositioned { origin = it.positionInRoot() }
        // Only watches. Where the finger lands, so the menu a double tap opens is under it.
        .pointerInput(Unit) {
            awaitEachGesture {
                lastDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
            }
        }
    if (!selecting) {
        return tracked.combinedClickable(
            onClick = onTap,
            onLongClick = onLongClick,
            onDoubleClick = { liveDouble.value(origin + lastDown) },
        )
    }
    return tracked
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                // The first tap is the row's own click and has already acted. A second inside
                // the window is ours: consumed here, in the Initial pass, so the click below
                // never sees it and never toggles the row back.
                val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                } ?: return@awaitEachGesture
                val at = second.position
                val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                up.consume()
                liveDouble.value(origin + at)
            }
        }
        .combinedClickable(onClick = onTap, onLongClick = onLongClick)
}

/**
 * The mark on a selected row's icon: a filled accent disc with a tick, at the icon's corner.
 * Drawn over the icon rather than replacing it, so a selected photo is still the photo.
 */
@Composable
fun SelectMark(selected: Boolean, modifier: Modifier = Modifier, side: Dp = 16.dp) {
    if (!selected) return
    val colors = Filet.colors
    Box(
        modifier
            .size(side)
            .clip(CircleShape)
            .background(colors.accent)
            .border(1.5.dp, colors.raised, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(FiletIcons.Check, null, tint = Color.White, modifier = Modifier.size(side * 0.62f))
    }
}

/**
 * A grid tile's select circle, top-left: an empty ring, filled with a tick once selected.
 *
 * Shown only while the pane is selecting, as Google Photos and Files do - see `FileTile`. The
 * first pick is a hold; after that the circle is its own target on every tile.
 */
@Composable
fun CornerSelect(selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Filet.colors
    Box(
        modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            SelectMark(true, side = 20.dp)
        } else {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.18f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
            )
        }
    }
}

package dev.niccc2007.filet.handlers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The bits of a player that the audio screen and the video screen both need.
 *
 * Kept together so the scrub bar behaves identically in both. Nic asked for the video one
 * specifically - "if i drag it it intuitively skips or follows my hand as if i was holding the
 * playback bar progress" - and there is no reason the music player should feel different.
 */

/**
 * A playback bar you can grab anywhere.
 *
 * Two things make this different from the stock one. The touch target is [TOUCH_HEIGHT] tall
 * while the drawn bar is a few pixels, so it can be hit with a thumb; and the position is
 * absolute from the first touch rather than a delta applied to a thumb you had to catch first.
 * Put your finger halfway along and it is halfway along.
 *
 * @param onScrub fires continuously while dragging, so the time readout can follow the finger
 *   without the player being asked to seek sixty times a second.
 * @param onScrubEnd fires once, on lift, and is where the real seek happens.
 */
@Composable
fun ScrubBar(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    bufferedFraction: Float = 0f,
    onScrub: (Long) -> Unit = {},
    onScrubEnd: (Long) -> Unit,
) {
    val colors = Filet.colors
    var width by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }

    // Read live: the block is keyed on `enabled` and `durationMs`, but `onScrubEnd` closes
    // over the player state, which changes far more often than either. See
    // tools/check-deadswitch.mjs.
    val liveScrub = rememberUpdatedState(onScrub)
    val liveScrubEnd = rememberUpdatedState(onScrubEnd)

    val shownFraction =
        if (dragging) seekFraction(seekPosition(dragX, width, durationMs), durationMs)
        else seekFraction(positionMs, durationMs)

    Box(
        modifier
            .fillMaxWidth()
            .height(TOUCH_HEIGHT)
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(enabled, durationMs) {
                if (enabled) {
                    detectTapGestures(onTap = { at -> liveScrubEnd.value(seekPosition(at.x, width, durationMs)) })
                }
            }
            // After the tap detector so it wins the main pass: a drag that starts as a press
            // has to become a scrub, not a tap that fires on lift somewhere else entirely.
            .pointerInput(enabled, durationMs) {
                if (enabled) {
                    detectDragGestures(
                        onDragStart = { at ->
                            dragging = true
                            dragX = at.x
                            liveScrub.value(seekPosition(dragX, width, durationMs))
                        },
                        onDragEnd = {
                            dragging = false
                            liveScrubEnd.value(seekPosition(dragX, width, durationMs))
                        },
                        onDragCancel = { dragging = false },
                    ) { change, drag ->
                        change.consume()
                        dragX += drag.x
                        liveScrub.value(seekPosition(dragX, width, durationMs))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(TOUCH_HEIGHT)) {
            val trackHeight = (if (dragging) 6.dp else 4.dp).toPx()
            val y = size.height / 2f
            val radius = trackHeight / 2f
            drawRoundRectLine(colors.lineSoft, 0f, size.width, y, trackHeight, radius)
            if (bufferedFraction > 0f) {
                drawRoundRectLine(
                    colors.fg3.copy(alpha = 0.35f),
                    0f, size.width * bufferedFraction.coerceIn(0f, 1f), y, trackHeight, radius,
                )
            }
            drawRoundRectLine(
                if (enabled) colors.accent else colors.fg3,
                0f, size.width * shownFraction, y, trackHeight, radius,
            )
            if (enabled) {
                // The knob grows under the finger. It is feedback that the bar took the touch,
                // which a bar that only moves does not give on a fast drag.
                drawCircle(
                    colors.accent,
                    (if (dragging) 9.dp else 6.dp).toPx(),
                    Offset(size.width * shownFraction, y),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundRectLine(
    colour: Color,
    fromX: Float,
    toX: Float,
    centreY: Float,
    thickness: Float,
    radius: Float,
) {
    if (toX <= fromX) return
    drawRoundRect(
        color = colour,
        topLeft = Offset(fromX, centreY - thickness / 2f),
        size = Size(toX - fromX, thickness),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
    )
}

/** Big enough to hit with a thumb while the drawn bar stays thin. */
private val TOUCH_HEIGHT = 30.dp

/**
 * A transport control.
 *
 * @param filled the one primary action in the row. Exactly one button in a transport is the
 *   one you reach for without looking, and giving it a solid ground is what makes it findable.
 */
@Composable
fun TransportButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    active: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val tint = when {
        !enabled -> colors.fg3.copy(alpha = 0.35f)
        filled -> androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
        active -> colors.accent
        else -> androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) colors.accent else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

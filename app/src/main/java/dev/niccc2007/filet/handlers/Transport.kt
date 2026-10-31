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
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.playback.Scrub

/**
 * The bits of a player that the audio screen and the video screen both need.
 *
 * Kept together so the scrub bar behaves identically in both. The design calls for the video one
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
    /**
     * The colour of the filled part and the knob, when the caller has one.
     *
     * The music player passes the colour it took off the artwork, so the bar belongs to the
     * track like the rest of its controls do. Null keeps the theme accent, which is what the
     * video player and everything else wants.
     */
    fill: Color? = null,
    onScrub: (Long) -> Unit = {},
    onScrubEnd: (Long) -> Unit,
) {
    val colors = Filet.colors
    val played = fill ?: colors.accent
    var width by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // ── pull away from the bar to seek finely ──────────────────────────────────────────────
    //
    // The bar used to map its own width to the whole track and nothing else, so on a
    // four-minute song one pixel was about a second and on an hour-long one it was fifteen -
    // which makes landing on a particular moment a matter of luck rather than aim. Asked for
    // as a one-second jump.
    //
    // Done by how far the finger is from the bar rather than by a mode button: drag along it
    // for the whole track, pull down or up and the same horizontal movement covers a quarter
    // as much, pull further and it steps in whole seconds. No control to find, nothing to put
    // back afterwards, and the gesture tells you what it is doing while you are doing it.
    //
    // The arithmetic is `Scrub`, which is pure and tested - a seek that is subtly wrong is not
    // something anybody notices as a bug, they just blame their thumb.
    val pxPerDp = LocalDensity.current.density
    // Held in MILLISECONDS, not as an x. Converting back and forth through the bar's width is
    // what made a fine mode impossible: the position has to accumulate at the fine rate rather
    // than being re-derived from where the finger is.
    var scrubMs by remember { mutableStateOf<Double?>(null) }
    var pullDp by remember { mutableFloatStateOf(0f) }
    val mode = Scrub.modeFor(pullDp)

    // Read live: the block is keyed on `enabled` and `durationMs`, but `onScrubEnd` closes
    // over the player state, which changes far more often than either. See
    // tools/check-deadswitch.mjs.
    val liveScrub = rememberUpdatedState(onScrub)
    val liveScrubEnd = rememberUpdatedState(onScrubEnd)

    // What the drag is asking for: whole seconds once it is fine, so the readout stops
    // flickering through values nobody can aim at.
    fun target(): Long {
        val at = scrubMs ?: return positionMs
        return if (mode == Scrub.Mode.FINE) {
            Scrub.wholeSecond(at, durationMs) ?: at.toLong()
        } else {
            at.toLong().coerceIn(0L, durationMs.coerceAtLeast(0L))
        }
    }

    val shownFraction =
        if (dragging) seekFraction(target(), durationMs)
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
                            pullDp = 0f
                            // Unrounded, and from where the finger landed - a drag that begins
                            // by jumping the playhead to the touch is what the bar has always
                            // done and is what makes the first movement feel connected.
                            scrubMs = Scrub.barStart(at.x, width, durationMs)
                            liveScrub.value(target())
                        },
                        onDragEnd = {
                            dragging = false
                            liveScrubEnd.value(target())
                            scrubMs = null
                            pullDp = 0f
                        },
                        onDragCancel = {
                            dragging = false
                            scrubMs = null
                            pullDp = 0f
                        },
                    ) { change, drag ->
                        change.consume()
                        // Vertical travel is the pull, and it accumulates either way: up and
                        // down both mean "finer", because which one there is room for depends
                        // on where the bar sits on the screen.
                        pullDp += drag.y / pxPerDp.coerceAtLeast(0.1f)
                        scrubMs = Scrub.barMove(
                            currentMs = scrubMs ?: Scrub.barStart(change.position.x, width, durationMs) ?: return@detectDragGestures,
                            dxPx = drag.x,
                            widthPx = width,
                            durationMs = durationMs,
                            pullDp = pullDp,
                            pxPerDp = pxPerDp,
                        )
                        liveScrub.value(target())
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
                if (enabled) played else colors.fg3,
                0f, size.width * shownFraction, y, trackHeight, radius,
            )
            if (enabled) {
                // The knob grows under the finger. It is feedback that the bar took the touch,
                // which a bar that only moves does not give on a fast drag.
                drawCircle(
                    played,
                    (if (dragging) 9.dp else 6.dp).toPx(),
                    Offset(size.width * shownFraction, y),
                )
            }
        }
        // What the pull has bought, said while it is being bought. Without this the finer
        // mapping is indistinguishable from the bar having stopped responding - the finger
        // moves the same distance and the playhead moves a quarter as far, which reads as a
        // fault rather than as a gear.
        if (dragging && mode != Scrub.Mode.NORMAL) {
            Text(
                mode.label,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                color = played,
                modifier = Modifier.align(Alignment.TopCenter),
            )
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
    /**
     * The colour for the filled and active states, when the caller has one to offer.
     *
     * The music player hands it the colour it took off the artwork, so the play button
     * belongs to the track rather than to the theme. Null keeps the theme accent, which is
     * what every other transport in the app wants and what the player falls back to when
     * the artwork has no colour worth taking.
     */
    fill: Color? = null,
    onFill: Color? = null,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val ground = fill ?: colors.accent
    val tint = when {
        !enabled -> colors.fg3.copy(alpha = 0.35f)
        filled -> onFill ?: androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
        active -> ground
        else -> androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    filled -> ground
                    // A lit mode gets a wash of its own colour rather than only a tint, so
                    // shuffle and repeat read as on at a glance instead of on inspection.
                    active -> ground.copy(alpha = 0.15f)
                    else -> Color.Transparent
                },
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

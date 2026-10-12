package dev.niccc2007.filet.handlers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.playback.MAX_SPEED
import dev.niccc2007.filet.playback.MIN_SPEED
import dev.niccc2007.filet.playback.PitchMode
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.theme.Filet
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The speed sheet, shared by the music player and the video player.
 *
 * ## Why it is a sheet with a slider
 *
 * The chip used to cycle five presets on tap, on the reasoning that the values anybody uses
 * fit in one button. Asked for directly: *"make the speed a slider instead, a pop-up slider,
 * so I can set the speed"* - and the cycle does make you count taps to go down one step.
 *
 * ## Why the rail is logarithmic
 *
 * The range runs from [dev.niccc2007.filet.playback.Playback.MIN_SPEED] to whatever the
 * device will actually take, and on a linear rail that puts 1.0x a quarter of the way along
 * with everything useful crammed into the left of it: half the travel would be between 2x and
 * 4x, where nobody needs resolution, and the 0.9-1.1 anybody actually cares about would be
 * four pixels wide. A log rail gives every DOUBLING the same distance, which is how speed is
 * heard.
 *
 * ## The hard limit, stated rather than hidden
 *
 * Asked for 0.01x to 100x. Those are not refusals Filet is making: Android resamples through
 * `AudioTrack`, and a rate far outside a quarter to four times throws rather than playing -
 * at 100x a 48 kHz track is 4.8 MHz of samples a second into a sink that runs at 48. The
 * player starts optimistic and walks its ceiling down on the first refusal, so this rail ends
 * where this phone actually ends.
 */
@Composable
fun SpeedSheet(
    speed: Float,
    maxSpeed: Float,
    pitch: PitchMode,
    tint: Color,
    onTint: Color,
    onSpeed: (Float) -> Unit,
    onPitch: (PitchMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Filet.colors
    Dlg(onDismiss = onDismiss) {
        Box(
            Modifier
                .padding(top = 9.dp, bottom = 2.dp)
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.high),
        )
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "PLAYBACK SPEED",
                        fontSize = 9.sp,
                        letterSpacing = 1.4.sp,
                        color = colors.fg3,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        speedLabel(speed),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp,
                        color = tint,
                    )
                }
                if (abs(speed - 1f) > 0.001f) {
                    Text(
                        "Reset",
                        fontSize = 12.sp,
                        color = colors.fg2,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.high)
                            .clickable { onSpeed(1f) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            SpeedRail(speed, maxSpeed, tint, onTint, onSpeed)
            Spacer(Modifier.height(14.dp))
            PitchModeRow(pitch, tint, onTint, onPitch)
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * The rail: a bubble over the thumb, a log track, and tappable stops under it.
 *
 * Hand-drawn rather than Material's `Slider`, for the same reasons `ScrubBar` is: the touch
 * target has to be a thumb tall while the drawn track is a few pixels, the position has to be
 * absolute from the first touch rather than a delta applied to a thumb you had to catch
 * first, and it has to take the artwork's colour. A Material `Slider` gives none of those
 * without fighting a theme, and its own tick marks are evenly spaced, which a log rail's
 * are not.
 */
@Composable
private fun SpeedRail(
    speed: Float,
    maxSpeed: Float,
    tint: Color,
    onTint: Color,
    onSpeed: (Float) -> Unit,
) {
    val colors = Filet.colors
    val density = LocalDensity.current
    val knobPx = with(density) { KNOB.toPx() }
    var width by remember { mutableFloatStateOf(0f) }
    val live = rememberUpdatedState(onSpeed)
    val stops = remember(maxSpeed) { SPEED_STOPS.filter { it <= maxSpeed + 0.001f } }
    val fraction = speedFraction(speed, maxSpeed)

    fun at(x: Float) {
        if (width <= 0f) return
        val span = (width - knobPx * 2f).coerceAtLeast(1f)
        live.value(snapSpeed(speedAt(((x - knobPx) / span).coerceIn(0f, 1f), maxSpeed), maxSpeed))
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val full = maxWidth
        val knobDp = KNOB
        // The travel in dp, so the bubble and the labels can be placed with `offset` instead
        // of a second canvas. Same inset the canvas uses, or the two disagree by 9dp.
        val span = full - knobDp * 2
        Column {
            // The bubble. TikTok's rail carries the value above the thumb rather than only in
            // a readout at the top, and it is right: while you are dragging, the thumb is
            // where your eye is and the readout is 60dp away under your own hand.
            Box(Modifier.fillMaxWidth().height(24.dp)) {
                Box(
                    Modifier
                        .offset(
                            x = (knobDp + span * fraction - BUBBLE / 2)
                                .coerceIn(0.dp, (full - BUBBLE).coerceAtLeast(0.dp)),
                        )
                        .width(BUBBLE)
                        .clip(RoundedCornerShape(7.dp))
                        .background(tint),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        speedLabel(speed),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = onTint,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .onSizeChanged { width = it.width.toFloat() }
                    .pointerInput(maxSpeed) { detectTapGestures { at(it.x) } }
                    // After the tap detector so it wins the main pass: a drag that starts as
                    // a press has to become a drag, not a tap that fires on lift somewhere
                    // else entirely. Same ordering, same reason, as ScrubBar.
                    .pointerInput(maxSpeed) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            at(change.position.x)
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxWidth().height(30.dp)) {
                    val mid = size.height / 2f
                    val track = 4.dp.toPx()
                    // Inset by the knob's radius at both ends, or the thumb is half off the
                    // canvas at each limit - which are the two values a speed rail is dragged
                    // to most, and a control that loses its handle there reads as clipped.
                    val x0 = knobPx
                    val run = (size.width - knobPx * 2f).coerceAtLeast(1f)
                    drawRoundRect(
                        colors.high,
                        topLeft = Offset(x0, mid - track / 2f),
                        size = Size(run, track),
                        cornerRadius = CornerRadius(track, track),
                    )
                    drawRoundRect(
                        tint,
                        topLeft = Offset(x0, mid - track / 2f),
                        size = Size(run * fraction, track),
                        cornerRadius = CornerRadius(track, track),
                    )
                    for (stop in stops) {
                        val f = speedFraction(stop, maxSpeed)
                        drawCircle(
                            if (f <= fraction) onTint.copy(alpha = 0.4f)
                            else colors.fg3.copy(alpha = 0.65f),
                            radius = 1.8.dp.toPx(),
                            center = Offset(x0 + run * f, mid),
                        )
                    }
                    drawCircle(tint, radius = knobPx, center = Offset(x0 + run * fraction, mid))
                }
            }

            // The stops, named and tappable. A rail with unlabelled detents is a rail that
            // sticks for no stated reason; naming them also makes 1x one press, which is the
            // single most-wanted value on the whole control.
            //
            // Only SOME of them are named. All eight are snapped to, and eight labels across
            // a 380dp rail collide at the top end where the log scale bunches them - so the
            // five that are worth reading get the text and the rest keep their tick.
            Box(Modifier.fillMaxWidth().height(18.dp)) {
                for (stop in stops.filter { it in SPEED_LABELLED }) {
                    val on = abs(stop - speed) < 0.005f
                    Text(
                        speedLabel(stop),
                        fontSize = 9.5.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) tint else colors.fg3,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .offset(
                                x = (knobDp + span * speedFraction(stop, maxSpeed) - LABEL / 2)
                                    // Kept inside the rail: the first and last labels are
                                    // centred on points that are themselves at the edge.
                                    .coerceIn(0.dp, (full - LABEL).coerceAtLeast(0.dp)),
                            )
                            .width(LABEL)
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { onSpeed(stop) },
                    )
                }
            }
        }
    }
}

/** The three answers to what the pitch does, as one segmented row. */
@Composable
private fun PitchModeRow(
    pitch: PitchMode,
    tint: Color,
    onTint: Color,
    onPitch: (PitchMode) -> Unit,
) {
    val colors = Filet.colors
    Column {
        Text("PITCH", fontSize = 9.sp, letterSpacing = 1.4.sp, color = colors.fg3)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.sunken)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (mode in PitchMode.entries) {
                val on = mode == pitch
                Text(
                    mode.label,
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) onTint else colors.fg2,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) tint else Color.Transparent)
                        .clickable { onPitch(mode) }
                        .padding(vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // Says what the chosen one costs. Neither of the other two is wrong, which is why
        // there are three of them and why the row below is an explanation and not a warning.
        Text(
            pitch.about,
            fontSize = 10.sp,
            lineHeight = 13.5.sp,
            color = colors.fg3,
        )
    }
}

private val KNOB = 9.dp
private val BUBBLE = 46.dp
private val LABEL = 34.dp

/**
 * Where a speed sits on the rail, 0 to 1, on a log scale.
 *
 * Every doubling gets the same distance, which is how speed is heard and is the only way a
 * 0.1-to-4 range leaves usable resolution around 1.0: linearly, 1.0 would sit at a quarter of
 * the travel with half the rail spent between 2x and 4x.
 */
fun speedFraction(speed: Float, maxSpeed: Float): Float {
    val top = maxSpeed.coerceAtLeast(MIN_SPEED * 2f)
    val v = speed.coerceIn(MIN_SPEED, top)
    return (ln(v / MIN_SPEED) / ln(top / MIN_SPEED)).toFloat().coerceIn(0f, 1f)
}

/** The inverse: what speed a point on the rail means. */
fun speedAt(fraction: Float, maxSpeed: Float): Float {
    val top = maxSpeed.coerceAtLeast(MIN_SPEED * 2f)
    val f = fraction.coerceIn(0f, 1f)
    return (MIN_SPEED * exp(f * ln(top / MIN_SPEED))).toFloat().coerceIn(MIN_SPEED, top)
}

/**
 * Round to something sayable, and to a stop when close enough.
 *
 * 1.03x is nobody's intention and is audible, so the window around a stop is wide enough to
 * land on with a thumb. Away from a stop the step is proportional rather than fixed: a
 * twentieth is the right grain at 1x and far too coarse at 0.2x, where it is a fifth of the
 * whole value.
 */
fun snapSpeed(raw: Float, maxSpeed: Float = MAX_SPEED): Float {
    val top = maxSpeed.coerceAtLeast(MIN_SPEED * 2f)
    val clamped = raw.coerceIn(MIN_SPEED, top)
    val stop = SPEED_STOPS.filter { it <= top + 0.001f }.minByOrNull { abs(it - clamped) }
    if (stop != null && abs(speedFraction(stop, top) - speedFraction(clamped, top)) <= SNAP_F) {
        return stop
    }
    val step = if (clamped < 1f) 0.01f else 0.05f
    return ((clamped / step).roundToInt() * step).coerceIn(MIN_SPEED, top)
}

/** `0.5×`, `0.75×`, `1×` - the shortest true spelling of a speed. */
fun speedLabel(speed: Float): String {
    val text = String.format(java.util.Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    return text + "×"
}

/** The stops the rail snaps to. Doublings, plus the ones anybody actually asks for. */
val SPEED_STOPS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

/** The ones that get a label under the rail. The rest keep their tick and their snap. */
val SPEED_LABELLED = setOf(0.25f, 0.5f, 1f, 2f, 4f)

/** How close to a stop counts as on it, measured in rail travel rather than in speed. */
const val SNAP_F = 0.018f

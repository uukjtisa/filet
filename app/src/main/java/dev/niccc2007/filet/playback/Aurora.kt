package dev.niccc2007.filet.playback

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * The drifting gradient behind the Aurora face.
 *
 * ## What it is
 *
 * Three soft lights in the track's own colours, each going round its own slow ellipse at its
 * own rate, over a ground dark enough to read white text on. Because the three periods do not
 * divide into each other the pattern never visibly repeats, which is the whole difference
 * between an animated background and a looping one - a loop you can spot is a loop you start
 * waiting for.
 *
 * ## Why it is drawn rather than animated with layers
 *
 * One `drawBehind` with three radial brushes is three draw calls into the layer that is
 * already being rasterised for the screen. The obvious alternative - three blurred Boxes
 * moved with `offset` - is three more layers, three more allocations per frame, and a blur
 * render-effect each, for a picture nobody is looking at directly.
 *
 * ## It stops when the system says animations are off
 *
 * `ANIMATOR_DURATION_SCALE` at 0 means the person has turned animations off, at the OS, for
 * a reason - a vestibular one, a battery one, or because a slow phone is being nursed. A
 * background that drifts for ever is exactly the kind of thing that setting is for, so it
 * freezes into a still gradient rather than ignoring it. The face still looks like itself.
 */
@Composable
fun AuroraBackdrop(
    base: Color,
    ground: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val animated = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }

    val clock = rememberInfiniteTransition(label = "aurora")

    @Composable
    fun phase(seconds: Int, from: Float): Float =
        if (!animated) {
            from
        } else {
            val t by clock.animateFloat(
                initialValue = from,
                targetValue = from + 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(seconds * 1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "drift$seconds",
            )
            t
        }

    // Co-prime-ish periods, so the three never line up again inside a sitting.
    val a = phase(23, 0f)
    val b = phase(31, 0.37f)
    val c = phase(41, 0.71f)

    val tints = remember(base) { auroraTints(base) }

    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(ground)
                blob(tints[0], a, 0.62f, 0.34f, size.minDimension * 1.25f)
                blob(tints[1], b, 0.38f, 0.66f, size.minDimension * 1.45f)
                blob(tints[2], c, 0.72f, 0.78f, size.minDimension * 1.1f)
            },
    )
}

/**
 * One light, on an ellipse.
 *
 * An ellipse rather than a circle because a circular path at a constant rate reads as a
 * rotation - the eye finds the centre it is going round and the motion stops being weather.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.blob(
    colour: Color,
    phase: Float,
    cx: Float,
    cy: Float,
    radius: Float,
) {
    val t = phase * 2f * Math.PI.toFloat()
    val centre = Offset(
        size.width * (cx + 0.17f * cos(t)),
        size.height * (cy + 0.11f * sin(t * 1.3f)),
    )
    drawCircle(
        brush = Brush.radialGradient(
            0f to colour,
            0.55f to colour.copy(alpha = colour.alpha * 0.45f),
            1f to Color.Transparent,
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
    )
}

/**
 * Three lights from one sampled colour.
 *
 * The palette gives one hue - the track's - and three lights all in it would be a wash rather
 * than an aurora. So the other two are taken a sixth of the wheel either side, which is the
 * step that reads as "related" rather than as "clashing" or "the same": any further and it
 * stops being the colour of the thing playing, any nearer and the movement is invisible.
 *
 * Pure, and tested, because an alpha or a hue step wrong here is a background that is either
 * a grey smear or a lava lamp.
 */
fun auroraTints(base: Color): List<Color> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    fun at(shift: Float, sat: Float, value: Float, alpha: Float): Color {
        val h = ((hsv[0] + shift) % 360f + 360f) % 360f
        return Color(
            android.graphics.Color.HSVToColor(
                floatArrayOf(h, (hsv[1] * sat).coerceIn(0f, 1f), value.coerceIn(0f, 1f)),
            ),
        ).copy(alpha = alpha)
    }
    return listOf(
        at(0f, 1.0f, 0.62f, 0.50f),
        at(60f, 0.9f, 0.50f, 0.38f),
        at(-60f, 0.8f, 0.44f, 0.30f),
    )
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)

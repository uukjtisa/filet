package dev.niccc2007.filet.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color

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
 * ## It stops when the glow is off, or when the system says animations are off
 *
 * This face's drift IS the living glow, at full strength and with a ground of its own, so the
 * one switch that governs the glow everywhere else governs it here. Off, it freezes into a
 * still gradient rather than disappearing - the face still looks like itself. The OS
 * animations-off setting does the same thing for the same reason. See [rememberGlowPhases].
 *
 * @param running false freezes the lights where they started.
 */
@Composable
fun AuroraBackdrop(
    base: Color,
    ground: Color,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val phases = rememberGlowPhases(running)
    val tints = remember(base) { auroraTints(base) }

    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                // Read in the draw lambda, never in the body: this face fills the screen, and
                // reading the frame up there recomposes all of it sixty times a second. See
                // the note in `livingGlow`.
                val p = phases.value
                drawRect(ground)
                glowBlob(tints[0], p[0], 0.62f, 0.34f, size.minDimension * 1.25f)
                glowBlob(tints[1], p[1], 0.38f, 0.66f, size.minDimension * 1.45f)
                glowBlob(tints[2], p[2], 0.72f, 0.78f, size.minDimension * 1.1f)
            },
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

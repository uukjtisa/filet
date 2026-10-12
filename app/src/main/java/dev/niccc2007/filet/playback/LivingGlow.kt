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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * The living glow: the background colour, drifting, while something is playing.
 *
 * ## What this is, and what it is not
 *
 * It is NOT the Aurora face. That was the mistake worth writing down: asked for *"an animated
 * colour gradient - that glow on the back of the thing - enchanted, like something"*, a fifth
 * face was built whose whole identity is a drifting gradient. The face was kept, because it
 * is good. It was also the wrong answer, because the ask was about **every** look: the glow
 * that is already behind the player should be alive rather than a still photograph, and that
 * should hold on the minimised strip and in the shade as well.
 *
 * So this is one layer, used in five places, governed by one switch:
 *
 * | Surface | What it does |
 * |---|---|
 * | Classic | lights in the theme accent - that face deliberately ignores the artwork |
 * | Ambient, Editorial | lights over the blurred artwork, under the veil |
 * | Immersive | lights over the backdrop |
 * | Aurora | the face's own drift, which this switch now governs too |
 * | The strip | two sweeps across a 56dp band |
 * | The notification | see `PlaybackService` - the shade cannot animate, so the card's own colour drifts between posts |
 *
 * ## Why it is a Modifier and a composable
 *
 * The Modifier is the real thing - one `drawBehind` with three radial brushes, into the layer
 * that is being rasterised anyway. The composable is the same call wrapped in a
 * `Box(fillMaxSize())`, for the faces that need it as a sibling layer over a backdrop they
 * have already drawn rather than as a background on something.
 *
 * ## When it does not run
 *
 * - The switch is off.
 * - Nothing is playing. A paused player is a still picture on purpose; movement with no
 *   sound reads as the app being busy with something.
 * - The system says animations are off. See [rememberSystemAnimations].
 *
 * In all three cases the lights are drawn at their starting positions rather than removed, so
 * the surface keeps its colour and only loses its motion.
 */
@Composable
fun LivingGlow(
    base: Color,
    running: Boolean,
    strength: Float = 1f,
    spread: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().livingGlow(base, running, strength, spread))
}

/**
 * The glow as a background on something that is already laid out.
 *
 * @param spread how big the lights are against the surface. 1 suits something the size of a
 *   screen; a short wide band needs far less - see [GLOW_SPREAD_STRIP].
 * @param clip false lets the light reach past the surface it is drawn on. See below.
 */
@Composable
fun Modifier.livingGlow(
    base: Color,
    running: Boolean,
    strength: Float = 1f,
    spread: Float = 1f,
    clip: Boolean = true,
): Modifier {
    val phases = rememberGlowPhases(running)
    val tints = remember(base, strength) { glowTints(base, strength) }
    val a = phases[0]
    val b = phases[1]
    val c = phases[2]
    return drawBehind {
        // `drawBehind` is not bounded by the node it is on, so a radial brush wider than a
        // 56dp strip reaches up into whatever is above it. That arrived as a bug - the first
        // build painted the bottom third of the file list - and was then liked, so it is a
        // setting rather than a fault. Clipped by default, because it tints the names of
        // files somebody is trying to read.
        if (clip) clipRect { lights(tints, a, b, c, spread) } else lights(tints, a, b, c, spread)
    }
}

private fun DrawScope.lights(tints: List<Color>, a: Float, b: Float, c: Float, spread: Float) {
    glowBlob(tints[0], a, 0.30f, 0.26f, size.maxDimension * 0.80f * spread)
    glowBlob(tints[1], b, 0.74f, 0.44f, size.maxDimension * 0.95f * spread)
    glowBlob(tints[2], c, 0.52f, 0.72f, size.maxDimension * 0.65f * spread)
}

/**
 * How much of the glow is painted, as a thing somebody picks.
 *
 * One strength cannot be right for every cover. The lights are the artwork's own colour, so a
 * cover that samples to a deep violet lands far heavier at the same alpha than one that
 * samples to a dusty orange - the setting that is weather on one album is a coloured panel on
 * the next. Reported in those terms, about the strip, where it shows first.
 *
 * Faint by default: the glow should be noticed on the second look, not the first.
 */
enum class GlowLevel(val id: String, val label: String, val scale: Float) {
    FAINT("faint", "Faint", 0.55f),
    SOFT("soft", "Soft", 1.0f),
    STRONG("strong", "Strong", 1.7f);

    companion object {
        fun of(id: String?): GlowLevel = entries.firstOrNull { it.id == id } ?: FAINT

        /** The next one round, for a control that cycles rather than opening a menu. */
        fun after(level: GlowLevel): GlowLevel =
            entries[(entries.indexOf(level) + 1) % entries.size]
    }
}

/**
 * Three clocks that never line up again.
 *
 * Co-prime-ish periods, so the pattern does not visibly repeat inside a sitting - a loop you
 * can spot is a loop you start waiting for, which is the difference between an animated
 * background and a looping one.
 *
 * Returns the starting phases unchanged when nothing should be moving, so a caller draws a
 * still gradient rather than nothing at all.
 */
@Composable
fun rememberGlowPhases(running: Boolean): List<Float> {
    val animated = rememberSystemAnimations()
    val move = running && animated
    val clock = rememberInfiniteTransition(label = "glow")

    @Composable
    fun phase(seconds: Int, from: Float): Float =
        if (!move) {
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

    return listOf(phase(23, 0f), phase(31, 0.37f), phase(41, 0.71f))
}

/**
 * Whether the system wants anything to move.
 *
 * `ANIMATOR_DURATION_SCALE` at 0 means the person has turned animations off, at the OS, for
 * a reason - a vestibular one, a battery one, or because a slow phone is being nursed. A
 * background that drifts for ever is exactly the kind of thing that setting is for, so it is
 * read once per composition rather than ignored.
 */
@Composable
fun rememberSystemAnimations(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
}

/**
 * One light, on an ellipse.
 *
 * An ellipse rather than a circle because a circular path at a constant rate reads as a
 * rotation - the eye finds the centre it is going round and the motion stops being weather.
 */
fun DrawScope.glowBlob(
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
 * The three lights' colours, at a given strength.
 *
 * [auroraTints] is the full-strength set, which is right for a face whose entire background is
 * these lights. Every other surface already HAS a background - blurred artwork, a video frame,
 * the theme - and the glow goes on top of it, so the same alphas there would bury what is
 * underneath. [strength] scales the alpha and nothing else, so the hues stay the track's.
 */
fun glowTints(base: Color, strength: Float): List<Color> =
    if (strength == 1f) {
        auroraTints(base)
    } else {
        auroraTints(base).map { it.copy(alpha = (it.alpha * strength).coerceIn(0f, 1f)) }
    }

/** How strong the glow is over a surface that already has a background of its own. */
const val GLOW_OVER_ART = 0.42f

/**
 * Over a 56dp band, where the lights are nearly the whole surface.
 *
 * Half what it was, measured on the phone rather than guessed: at 0.34 the strip read as a
 * coloured panel instead of a glow.
 */
const val GLOW_OVER_STRIP = 0.17f

/**
 * And the lights are shrunk to match.
 *
 * The radii are a fraction of the surface's LONGEST side, which on a 1080x168 strip is its
 * width - so each light was six times taller than the band holding it and all that reached
 * the screen was its flat middle. At this spread three separate lights fit across the strip.
 */
const val GLOW_SPREAD_STRIP = 0.38f

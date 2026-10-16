package dev.niccc2007.filet.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.delay

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
 * - Nothing is playing. A paused player is a still picture on purpose; movement with no
 *   sound reads as the app being busy with something.
 * - The system says animations are off. See [rememberSystemAnimations].
 *
 * In both of those the lights are drawn at their starting positions rather than removed, so
 * the surface keeps its colour and only loses its motion.
 *
 * The switch being OFF is a different thing and it is `on`, not `running`. The two were one
 * parameter to begin with, which meant turning the glow off froze it instead of removing it -
 * a static wash nobody had asked for, and no way to tell it from a paused one.
 */
@Composable
fun LivingGlow(
    base: Color,
    on: Boolean,
    running: Boolean,
    strength: Float = 1f,
    spread: Float = 1f,
    /** The beat envelope, where there is one. See [rememberBeatPulse]. */
    pulse: State<Float>? = null,
    modifier: Modifier = Modifier,
) {
    if (!on) return
    Box(modifier.fillMaxSize().livingGlow(base, on, running, strength, spread, pulse = pulse))
}

/**
 * The glow as a background on something that is already laid out.
 *
 * @param spread how big the lights are against the surface. 1 suits something the size of a
 *   screen; a short wide band needs far less - see [GLOW_SPREAD_STRIP].
 * @param on false paints nothing at all - the switch, as opposed to [running], which only
 *   decides whether what is painted moves.
 * @param clip false lets the light reach past the surface it is drawn on. See below.
 */
@Composable
fun Modifier.livingGlow(
    base: Color,
    on: Boolean,
    running: Boolean,
    strength: Float = 1f,
    spread: Float = 1f,
    clip: Boolean = true,
    /** The beat envelope, where there is one. See [rememberBeatPulse]. */
    pulse: State<Float>? = null,
): Modifier {
    if (!on) return this
    val phases = rememberGlowPhases(running)
    val tints = remember(base, strength) { glowTints(base, strength) }
    return drawBehind {
        // READ HERE, not above.
        //
        // This is the difference between a background that drifts and a phone that gets hot,
        // and it does not show up on a fast one. `Modifier.livingGlow` is a composable
        // function that returns a value, so it has no restart scope of its own - a state read
        // in its body belongs to whoever called it. The caller is `NowPlayingStrip`, which is
        // on screen in every folder. Reading the phase up there meant the whole strip - its
        // artwork, its marquee, its five buttons, its BoxWithConstraints subcomposition -
        // recomposed on every animation frame for as long as anything was playing.
        //
        // Read inside the draw lambda, the frame invalidates the draw phase and nothing else.
        val p = phases.value
        val a = p[0]
        val b = p[1]
        val c = p[2]
        // `drawBehind` is not bounded by the node it is on, so a radial brush wider than a
        // 56dp strip reaches up into whatever is above it. That arrived as a bug - the first
        // build painted the bottom third of the file list - and was then liked, so it is a
        // setting rather than a fault. Clipped by default, because it tints the names of
        // files somebody is trying to read.
        // The beat, read here for the same reason and with the same effect: the draw
        // invalidates on every onset and nothing above it recomposes at all.
        val hit = pulse?.value ?: 0f
        if (clip) {
            clipRect { lights(tints, a, b, c, spread, hit) }
        } else {
            lights(tints, a, b, c, spread, hit)
        }
    }
}

private fun DrawScope.lights(
    tints: List<Color>,
    a: Float,
    b: Float,
    c: Float,
    spread: Float,
    hit: Float,
) {
    // Swell AND brighten. See `AuroraBackdrop`: on its own, a change of alpha is a flicker.
    val grow = spread * (1f + hit * BEAT_BLOOM)
    val lift = 1f + hit * BEAT_FLARE
    glowBlob(tints[0].brighter(lift), a, 0.30f, 0.26f, size.maxDimension * 0.80f * grow)
    glowBlob(tints[1].brighter(lift), b, 0.74f, 0.44f, size.maxDimension * 0.95f * grow)
    glowBlob(tints[2].brighter(lift), c, 0.52f, 0.72f, size.maxDimension * 0.65f * grow)
}

/** How much the lights swell on a hard beat, and how much brighter they get with it. */
private const val BEAT_BLOOM = 0.20f
private const val BEAT_FLARE = 1.0f

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
 * ## Why this is a coroutine and not an infinite transition
 *
 * Reported as a phone getting hot and laggy whenever music played, on a mid-range Samsung,
 * and the ticket was right. Two separate costs, and this is both of them:
 *
 *  - an `animateFloat` produces a value every frame, and its value is read in composition.
 *    See the note in [livingGlow] for what that did to the strip.
 *  - a value every frame means a redraw every frame - three radial gradients and their
 *    shaders - for a gradient whose slowest light takes forty-one seconds to go round.
 *    Sixty frames a second of that is about fifty-five more than anyone can see.
 *
 * So the phase is sampled off the wall clock on a slow loop instead. The movement is
 * identical to the eye and the draw happens [GLOW_FPS] times a second. Off the clock rather
 * than counted, so the drift keeps its place across a pause, a screen-off, or anything else
 * that stops the loop for a while.
 *
 * Returns the starting phases unchanged when nothing should be moving, so a caller draws a
 * still gradient rather than nothing at all, and the loop does not run at all.
 */
@Composable
fun rememberGlowPhases(running: Boolean): State<FloatArray> {
    val animated = rememberSystemAnimations()
    val move = running && animated
    val phases = remember { mutableStateOf(START) }
    LaunchedEffect(move) {
        if (!move) {
            phases.value = START
            return@LaunchedEffect
        }
        while (true) {
            val now = android.os.SystemClock.elapsedRealtime()
            phases.value = FloatArray(3) { i ->
                START[i] + (now % PERIODS_MS[i]).toFloat() / PERIODS_MS[i]
            }
            delay(1000L / GLOW_FPS)
        }
    }
    return phases
}

/**
 * One phase, from the wall clock, at a rate somebody picks.
 *
 * The same discipline as [rememberGlowPhases] and for the same reason: a `State` to be read
 * inside a draw lambda, stepped on a slow loop rather than produced every frame. A caller
 * that reads this in composition undoes all of it - see the note in [livingGlow].
 */
@Composable
fun rememberPhase(periodMs: Int, running: Boolean, fps: Long = 20L): State<Float> {
    val animated = rememberSystemAnimations()
    val move = running && animated
    val phase = remember { mutableStateOf(0f) }
    LaunchedEffect(move, periodMs) {
        if (!move) return@LaunchedEffect
        while (true) {
            phase.value = (android.os.SystemClock.elapsedRealtime() % periodMs).toFloat() / periodMs
            delay(1000L / fps)
        }
    }
    return phase
}

/** Where each light sits before anything moves, spread so they never start together. */
private val START = floatArrayOf(0f, 0.37f, 0.71f)

/** How long each light takes to go round. Co-prime-ish, in milliseconds. */
private val PERIODS_MS = intArrayOf(23_000, 31_000, 41_000)

/**
 * How often the glow is redrawn.
 *
 * Fifteen. The fastest light moves a fifth of a degree of its circle in that time, so the
 * difference between this and sixty is not visible on a blurred gradient - and it is four
 * times less drawing, continuously, for as long as music is playing.
 */
private const val GLOW_FPS = 15L

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

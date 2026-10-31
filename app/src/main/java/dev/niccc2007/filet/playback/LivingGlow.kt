package dev.niccc2007.filet.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * The living glow: the background colour, drifting, while something is playing.
 *
 * ## What this is, and what it is not
 *
 * It is NOT the Halo face's backdrop. That was the mistake worth writing down: an animated
 * colour gradient behind the player was first built as a fifth face whose whole identity was
 * the gradient. The face was kept, because it is good, but a gradient on one face was the wrong
 * answer: the glow already behind EVERY look should be alive rather than a still photograph,
 * and that should hold on the minimised strip and in the shade as well.
 *
 * So this is one layer, used in five places, governed by one switch:
 *
 * | Surface | What it does |
 * |---|---|
 * | Classic | lights in the theme accent - that face deliberately ignores the artwork |
 * | Ambient, Editorial | lights over the blurred artwork, under the veil |
 * | Immersive | lights over the backdrop |
 * | Halo | the face's own drift, which this switch now governs too |
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
    /** The beat, SIGNED about the track's own average. See [beatSwing]. */
    pulse: State<Float>? = null,
    /** How far the beat may swing the glow. See `Prefs.pulseDepth`. */
    pulseDepth: Float = 1f,
    modifier: Modifier = Modifier,
) {
    if (!on) return
    Box(
        modifier
            .fillMaxSize()
            .livingGlow(base, on, running, strength, spread, pulse = pulse, pulseDepth = pulseDepth),
    )
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
    /** The beat, SIGNED about the track's own average. See [beatSwing]. */
    pulse: State<Float>? = null,
    /** How far the beat may swing the glow. See `Prefs.pulseDepth`. */
    pulseDepth: Float = 1f,
): Modifier {
    if (!on) return this
    val clock = rememberGlowPhases(running)
    val lamps = remember(base, strength) { GlowLamps(glowTints(base, strength)) }
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
        val a = clock.phase(0)
        val b = clock.phase(1)
        val c = clock.phase(2)
        // `drawBehind` is not bounded by the node it is on, so a radial brush wider than a
        // 56dp strip reaches up into whatever is above it. That arrived as a bug - the first
        // build painted the bottom third of the file list - and was then liked, so it is a
        // setting rather than a fault. Clipped by default, because it tints the names of
        // files somebody is trying to read.
        // The beat, read here for the same reason and with the same effect: the draw
        // invalidates on every onset and nothing above it recomposes at all.
        // Already signed about the track's own recent average when it gets here, so this
        // is a multiply and not a judgement - see `beatSwing` for why the judgement moved.
        val swing = if (pulse == null) 0f else pulse.value * pulseDepth
        if (clip) {
            clipRect { lights(lamps, a, b, c, spread, swing) }
        } else {
            lights(lamps, a, b, c, spread, swing)
        }
    }
}

private fun DrawScope.lights(
    lamps: GlowLamps,
    a: Float,
    b: Float,
    c: Float,
    spread: Float,
    swing: Float,
) {
    // Swell AND brighten. See `HaloBackdrop`: on its own, a change of alpha is a flicker.
    // ONLY the positive half of the swing, and this is not a clamp for tidiness.
    //
    // Bug identified here: the background faded towards black while a track sat playing,
    // and got worse the longer it sat. `swing` is `norm - mean`, and `mean` chases `norm`
    // over a long window from a standing start of zero. `norm` spikes during a hit and
    // rests near zero between them, so its TIME average is low - about a sixth - which
    // means the resting swing starts at zero and sags to roughly -0.15 as the mean
    // converges. Fed through `1f + swing * FLARE` that put the glow BELOW its resting
    // brightness for most of every bar, sagging further over the first half-minute of the
    // track until the coloured light had dropped out and the dark ground showed through.
    //
    // The measurement is untouched - the beat is still read against the track's own
    // running average, which is D-01 and must not be undone. What changes is what the DRAW
    // does with a trough: nothing at all. Rest is the floor, a beat rises from it, and the
    // background is never dimmer than it is with the pulse switched off - which is the
    // same guarantee D-01 was written to protect, now held at the other end.
    val up = swing.coerceAtLeast(0f)
    val grow = spread * (1f + up * BEAT_BLOOM)
    val lift = 1f + up * BEAT_FLARE
    glowBlob(lamps.brushes[0], lamps.alpha(0, lift), a, 0.30f, 0.26f, size.maxDimension * 0.80f * grow)
    glowBlob(lamps.brushes[1], lamps.alpha(1, lift), b, 0.74f, 0.44f, size.maxDimension * 0.95f * grow)
    glowBlob(lamps.brushes[2], lamps.alpha(2, lift), c, 0.52f, 0.72f, size.maxDimension * 0.65f * grow)
}

/**
 * Three lights' brushes, built once per colour rather than once per frame.
 *
 * Bug identified here: every redraw of every light built a fresh `Brush.radialGradient` - a
 * list of stops, a brush and a native shader - because the gradient's centre, radius and alpha
 * were all baked into it and all three move. At the glow's rate and the beat's rate, over three
 * lights on the face and three on the strip, that was hundreds of short-lived objects a second
 * for the collector to chase on a phone that was already warm.
 *
 * So the brush carries only the hue, at full alpha, centred on the origin at a fixed radius.
 * Position and size are a canvas transform (see [glowBlob]) and brightness is the draw's own
 * alpha, which multiplies every stop - so `colour, colour * 0.45, transparent` at alpha `a`
 * is the same gradient the old per-frame brush drew with `a` baked into its stops. A brush
 * whose size never changes also keeps its shader: `ShaderBrush` rebuilds only when the size it
 * is applied at changes, and a canvas transform does not change it.
 */
internal class GlowLamps(private val tints: List<Color>) {
    val brushes: Array<Brush> = Array(tints.size) { i -> unitRadial(tints[i].copy(alpha = 1f), 0.55f, 0.45f) }

    /** The same arithmetic `brighter` did, as a number for the draw rather than a new colour. */
    fun alpha(i: Int, lift: Float): Float = (tints[i].alpha * lift).coerceIn(0f, 1f)
}

/**
 * A radial gradient from [colour] to transparent, centred on the origin at [UNIT_RADIUS].
 *
 * @param at where the middle stop sits, and [keep] how much of the colour's alpha it keeps.
 */
internal fun unitRadial(colour: Color, at: Float, keep: Float): Brush = Brush.radialGradient(
    0f to colour,
    at to colour.copy(alpha = colour.alpha * keep),
    1f to Color.Transparent,
    center = Offset.Zero,
    radius = UNIT_RADIUS,
)

/**
 * The radius every remembered gradient is built at, and scaled from.
 *
 * Large rather than 1, so the shader's own arithmetic is done at screen-like magnitudes and the
 * transform scales it by something near 1 rather than by a thousand.
 */
internal const val UNIT_RADIUS = 1000f

/**
 * How much the lights swell on a hard beat, and how much brighter they get with it.
 *
 * ## The constant that used to live here, and the bug it was
 *
 * The beat used to be measured against a hardcoded resting point of 0.34 at the moment of
 * drawing. The envelope that fed it decays to ZERO between beats (see `rememberBeatSignal`),
 * so every moment that was not a drum measured a third BELOW rest and these two multipliers
 * took the lights down with it. Switching the pulse on therefore made the resting glow about
 * two thirds of its brightness - and against the default Faint strength and the alpha the
 * lights are drawn at over artwork, that is a glow of roughly a seventh. Invisible, reported
 * as the glow being nowhere, and entirely a sign error rather than a missing feature.
 *
 * The resting point is now the track's own recent average and is computed where the clock is.
 * See `beatSwing`.
 */
private const val BEAT_BLOOM = 0.30f
private const val BEAT_FLARE = 1.5f

/**
 * How much of the glow is painted, as a thing somebody picks.
 *
 * One strength cannot be right for every cover. The lights are the artwork's own colour, so a
 * cover that samples to a deep violet lands far heavier at the same alpha than one that
 * samples to a dusty orange - the setting that is weather on one album is a coloured panel on
 * the next. Reported in those terms, about the strip, where it shows first.
 *
 * ## SOFT by default, not FAINT
 *
 * It defaulted to Faint, on the reasoning that the glow should be noticed on the second look
 * rather than the first. That reasoning has been overruled by use three separate times - the
 * glow has been reported as invisible, as missing, and as "where is it" - and at 0.55 of the
 * full strength, multiplied by the alpha the lights are drawn at over artwork, the default
 * really was close to nothing on a bright cover.
 *
 * Faint is still there for anybody who wants it. It is no longer what somebody gets before
 * they have an opinion.
 */
enum class GlowLevel(val id: String, val label: String, val scale: Float) {
    // Faint sits ON the new floor rather than below it. At 0.55 it was under `GLOW_MIN`, so
    // picking it from the strip's pill wrote a value the slider could not represent and the
    // thumb snapped to the bottom of its travel.
    FAINT("faint", "Faint", GLOW_MIN),
    SOFT("soft", "Soft", 1.0f),
    STRONG("strong", "Strong", 1.7f);

    /** Where this step sits on the continuous control. See [glowScale]. */
    val position: Float get() = ((scale - GLOW_MIN) / (GLOW_MAX - GLOW_MIN)).coerceIn(0f, 1f)

    companion object {
        fun of(id: String?): GlowLevel = entries.firstOrNull { it.id == id } ?: SOFT

        /** The next one round, for a control that cycles rather than opening a menu. */
        fun after(level: GlowLevel): GlowLevel =
            entries[(entries.indexOf(level) + 1) % entries.size]

        /** Which step a continuous strength is nearest, for the strip's three-stop pill. */
        fun nearest(position: Float): GlowLevel =
            entries.minByOrNull { kotlin.math.abs(it.position - position) } ?: SOFT
    }
}

/**
 * How much glow is painted, from a 0-to-1 control.
 *
 * ## Why this is separate from the pulse's own Intensity
 *
 * Because they are different questions and conflating them is what made one of them look
 * broken: turning up a control that sat under "Living glow" changed only how hard the BEAT
 * swung it, so the glow itself did not move and the control appeared to do nothing.
 *
 * This one sets how bright the glow is at all - with the pulse off it is the whole of the
 * answer. `Prefs.pulseDepth` sets how far a beat moves it from there. The first is a level,
 * the second is a range about that level, and a player needs both.
 *
 * The three named steps are still there as stops on the same scale (see [GlowLevel]), so
 * the strip's quick pill and this slider are two views of one number rather than two
 * settings that can disagree.
 */
fun glowScale(position: Float): Float =
    GLOW_MIN + (GLOW_MAX - GLOW_MIN) * position.coerceIn(0f, 1f)

/**
 * The floor and the ceiling of the glow strength control.
 *
 * Bug identified here: the floor was 0.20, which multiplied by the alpha the lights are drawn
 * at is close enough to nothing that sliding the control down turned the background BLACK. That
 * made the slider an off switch wearing a different name - the exact thing that was removed
 * when the "Living background" toggle went, and removed because the coloured background is what
 * the player looks like rather than an effect laid over it.
 *
 * 0.70 is the point where the backdrop is unmistakably the track's colour and still quiet
 * enough to read white text over. The control now spans "quiet" to "a wash"; it no longer spans
 * "gone" to "a wash".
 */
/**
 * How vibrant the coloured background is, as a multiplier over its painted strength.
 *
 * The range is deliberately wide at the top and shallow at the bottom: asking for "more
 * vibrant" is the common request and asking for "nearly gone" is rare, and the background is
 * the ground the whole player sits on - taking it to zero would leave the dark rectangle that
 * the glow switch used to produce and that the switch was removed for.
 */
fun vibranceScale(position: Float): Float =
    VIBRANCE_MIN + (VIBRANCE_MAX - VIBRANCE_MIN) * position.coerceIn(0f, 1f)

const val VIBRANCE_MIN = 0.5f
const val VIBRANCE_MAX = 2.0f

/**
 * The position at which [vibranceScale] returns exactly 1.
 *
 * So an install that has never touched the slider looks precisely as it did before the control
 * existed. A new setting whose default changes the thing it controls is a setting that reads as
 * a regression to everybody who did not ask for it.
 */
const val VIBRANCE_DEFAULT = (1f - VIBRANCE_MIN) / (VIBRANCE_MAX - VIBRANCE_MIN)

const val GLOW_MIN = 0.70f
const val GLOW_MAX = 2.20f

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
 *
 * ## Cost model
 *
 * - **Trigger:** [running] (the caller's "playing"), the Activity being on screen, and the OS
 *   animation switch - all three, see [shouldAnimate]. Any one false and the loop is not
 *   running; it is not merely writing the same value.
 * - **Rate:** `PlayerDials.glowFps` - 15 Hz on a capable phone, 12 and 10 below it.
 * - **Allocates:** nothing per tick. It writes one `Long`; the three phases are arithmetic on
 *   it inside the draw. It used to build a `FloatArray(3)` every tick.
 * - **Invalidates:** the draw phase of whoever reads [GlowClock.phase], never a composition.
 */
@Composable
fun rememberGlowPhases(running: Boolean): GlowClock {
    val animated = rememberSystemAnimations()
    val onScreen = rememberOnScreen()
    val fps = rememberPlayerDials().glowFps
    val move = shouldAnimate(onScreen, running, animated)
    val at = remember { mutableLongStateOf(GlowClock.STILL) }
    LaunchedEffect(move, fps) {
        if (!move) {
            at.longValue = GlowClock.STILL
            return@LaunchedEffect
        }
        // Registered, so a diagnostics dump NAMES this loop and says how fast it is going.
        // The cost model above was prose; this is the measurement, and the two disagreeing is
        // the thing worth catching - a loop documented at 15 Hz and running at 60 looks
        // identical in the source and is four times the heat. See `FiletLog.loop`.
        val ticking = dev.niccc2007.filet.log.FiletLog.loop("glow.clock", 1000L / fps)
        try {
            while (true) {
                at.longValue = android.os.SystemClock.elapsedRealtime()
                ticking.tick()
                delay(1000L / fps)
            }
        } finally {
            ticking.close()
        }
    }
    return remember { GlowClock(at) }
}

/**
 * The glow's three phases, computed where they are drawn.
 *
 * One `Long` of state rather than an array of phases, so a tick writes a number and allocates
 * nothing. Reading [phase] inside a draw lambda subscribes that draw and nothing else.
 */
class GlowClock internal constructor(private val at: State<Long>) {
    /** Light [i]'s phase, 0 to 2: its starting offset plus how far round its period it is. */
    fun phase(i: Int): Float {
        val now = at.value
        if (now == STILL) return START[i]
        return START[i] + (now % PERIODS_MS[i]).toFloat() / PERIODS_MS[i]
    }

    internal companion object {
        /** No clock running: every light sits at its start. */
        const val STILL = -1L
    }
}

/**
 * One phase, from the wall clock, at the tier's rate.
 *
 * The same discipline as [rememberGlowPhases] and for the same reason: a `State` to be read
 * inside a draw lambda, stepped on a slow loop rather than produced every frame. A caller
 * that reads this in composition undoes all of it - see the note in [livingGlow].
 *
 * Gated the same way: [running], on screen, and the OS switch. Stopped, it holds wherever it
 * was rather than snapping back, which is what it has always done.
 *
 * Rate: `PlayerDials.phaseFps`, 20 Hz on a capable phone. Allocates nothing; a `Float` state
 * write per tick.
 */
@Composable
fun rememberPhase(periodMs: Int, running: Boolean): State<Float> {
    val animated = rememberSystemAnimations()
    val onScreen = rememberOnScreen()
    val fps = rememberPlayerDials().phaseFps
    val move = shouldAnimate(onScreen, running, animated)
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(move, periodMs, fps) {
        if (!move) return@LaunchedEffect
        // Named by its period, because several of these run at once - the ring's sweep, its
        // breath, the disc - and "one phase loop" in a dump would not say which.
        val ticking = dev.niccc2007.filet.log.FiletLog.loop("phase.${periodMs}ms", 1000L / fps)
        try {
            while (true) {
                phase.floatValue =
                    (android.os.SystemClock.elapsedRealtime() % periodMs).toFloat() / periodMs
                ticking.tick()
                delay(1000L / fps)
            }
        } finally {
            ticking.close()
        }
    }
    return phase
}

/** `rememberPhase`'s rate before tiers existed, kept as the capable tier's value. */
internal const val PHASE_FPS = 20L

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
internal const val GLOW_FPS = 15L

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
    /** A remembered [unitRadial] in the light's hue. See [GlowLamps] for why it is not built here. */
    brush: Brush,
    alpha: Float,
    phase: Float,
    cx: Float,
    cy: Float,
    radius: Float,
) {
    if (radius <= 0f || alpha <= 0f) return
    val t = phase * 2f * Math.PI.toFloat()
    val x = size.width * (cx + 0.17f * cos(t))
    val y = size.height * (cy + 0.11f * sin(t * 1.3f))
    val k = radius / UNIT_RADIUS
    withTransform({
        translate(x, y)
        scale(k, k, pivot = Offset.Zero)
    }) {
        drawCircle(brush = brush, radius = UNIT_RADIUS, center = Offset.Zero, alpha = alpha)
    }
}

/**
 * The three lights' colours, at a given strength.
 *
 * [haloTints] is the full-strength set, which is right for a face whose entire background is
 * these lights. Every other surface already HAS a background - blurred artwork, a video frame,
 * the theme - and the glow goes on top of it, so the same alphas there would bury what is
 * underneath. [strength] scales the alpha and nothing else, so the hues stay the track's.
 */
fun glowTints(base: Color, strength: Float): List<Color> =
    if (strength == 1f) {
        haloTints(base)
    } else {
        haloTints(base).map { it.copy(alpha = (it.alpha * strength).coerceIn(0f, 1f)) }
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

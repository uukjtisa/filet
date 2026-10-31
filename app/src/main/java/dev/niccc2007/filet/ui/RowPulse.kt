package dev.niccc2007.filet.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.cos

/**
 * ONE clock for every pulsing row on the screen.
 *
 * ## Why it is shared and not per row
 *
 * A row that owns its own [rememberInfiniteTransition] is a row with a composition-scoped
 * animation running for as long as it is on screen. A screenful is forty rows and a fling holds
 * several screenfuls alive, so the per-row version is forty-odd independent animations, each
 * waking the frame loop and each invalidating its own subtree - which is the shape of the defect
 * that cost this app most of its frame budget once already (see `EntryFormat`'s note on
 * `SimpleDateFormat`: 13,329 of 13,330 frames missed vsync).
 *
 * It is also wrong to LOOK at. Independent clocks start when their row is composed, so rows
 * scrolled into view pulse out of step with the rows above them, and a list of folders reads as
 * a field of unrelated blinking rather than one thing happening.
 *
 * So the transition is created once, above every list, and handed down. Forty rows reading one
 * clock cost one animation.
 *
 * ## How a row must read it
 *
 * **In the draw phase, never in composition.** The same rule as the row background in
 * `FileRow`: held as the [State] object and read inside a `graphicsLayer`, `drawBehind` or
 * `Canvas` lambda, all of which run after composition. Read with `by` in the composable's own
 * body instead and every row recomposes sixty times a second, which is worse than the forty
 * animations this was built to remove.
 *
 * ```kotlin
 * val pulse = LocalRowPulse.current
 * Text(..., modifier = Modifier.graphicsLayer { alpha = RowPulse.figure(pulse.phase.value) })
 * ```
 *
 * ## Why the shapes are pure functions
 *
 * [dash], [figure] and [sweep] are arithmetic on a phase, so what the three row states actually
 * look like is testable without a device - including the one thing that can be quietly wrong,
 * which is what they return when nothing is moving. See [STILL].
 */
class RowPulse(val phase: State<Float>) {

    companion object {
        /** One breath. Slow enough to read as breathing rather than blinking. */
        const val PULSE_MS = 1400

        /** Turns of the confirming spinner per breath, so one clock drives both. */
        const val SPINS = 2

        /**
         * The clock that is not running, for a preview, a test, and reduce-motion.
         *
         * **Parked at the TOP of the cycle, not the bottom**, and that is the whole reason this
         * is a named constant rather than `mutableFloatStateOf(0f)`. Every shape here is a
         * breath between a dim value and a bright one, so a still clock at phase 0 would leave
         * every row permanently at its DIMMEST - a pending figure greyed almost to nothing, for
         * ever, with no motion to explain why. Under reduce-motion that is the app hiding
         * information from the person who asked for less movement.
         *
         * At the top of the cycle a still row is simply a legible row: full-strength figure,
         * a dash at its brightest, and the spinner a static arc rather than a turning one.
         */
        val STILL = RowPulse(mutableFloatStateOf(0.5f))

        /** 0 at the bottom of the cycle, 1 at the top, smooth through both. */
        fun breath(phase: Float): Float = 0.5f - 0.5f * cos(2.0 * Math.PI * phase).toFloat()

        /**
         * Alpha for a dash standing in for a figure nothing knows yet.
         *
         * Dim at both ends: this is the state where the row has NOTHING, and a bright pulse
         * would draw the eye to the one row on screen with no information on it.
         */
        fun dash(phase: Float): Float = lerp(0.22f, 0.50f, breath(phase))

        /**
         * Alpha for a figure that is being re-confirmed.
         *
         * Never below [FIGURE_FLOOR]: the number is REAL and somebody is reading it. Fading it
         * out to say "this might move" would make the row harder to use in order to describe
         * its own uncertainty, which is backwards - the spinner beside it carries that.
         */
        fun figure(phase: Float): Float = lerp(FIGURE_FLOOR, 1f, breath(phase))

        /** Where the spinner's arc starts, in degrees. */
        fun sweep(phase: Float): Float = (phase * SPINS * 360f) % 360f

        const val FIGURE_FLOOR = 0.62f

        private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t
    }
}

/**
 * The clock the rows read. [RowPulse.STILL] when nobody has provided one, which is the correct
 * state in a preview or a test: no motion, and every shape at its legible end.
 */
val LocalRowPulse = staticCompositionLocalOf { RowPulse.STILL }

/**
 * The one running clock, built once above the lists.
 *
 * Returns [RowPulse.STILL] under the system's reduce-motion setting rather than a slower
 * animation, per TEMPLATE.md rule 2 - and because there is nothing to slow down: the pulse is
 * the whole animation, so a reduced version of it is no version of it.
 */
@Composable
fun rememberRowPulse(): RowPulse {
    if (reduceMotion()) return RowPulse.STILL
    val clock = rememberInfiniteTransition(label = "rowPulse")
    val phase = clock.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        // Restart rather than Reverse. `breath` is already symmetric around the top of the
        // cycle, so reversing would play the same curve backwards at half speed AND run the
        // spinner backwards every other cycle.
        animationSpec = infiniteRepeatable(tween(RowPulse.PULSE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "rowPulsePhase",
    )
    return remember(phase) { RowPulse(phase) }
}

package dev.niccc2007.filet.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings

/**
 * The motion vocabulary, lifted from Trawl's `TrawlSwitcher.kt` so the two apps move the
 * same way (TEMPLATE.md §4). Values are read out of that file, not eyeballed.
 */
object Motion {
    /** Trawl MenuItemEasing - the decelerate used for anything arriving. */
    val Out: Easing = CubicBezierEasing(0.2f, 0.85f, 0.3f, 1f)

    /** Trawl PushEasing - the shove used when one surface displaces another. */
    val Push: Easing = CubicBezierEasing(0.5f, 0.05f, 0.15f, 1f)

    val Soft: Easing = FastOutSlowInEasing

    const val FAST = 140
    const val MID = 220
    const val SLOW = 340

    /** The switcher push: 500 ms, translate 50%, scale .78, radius 26. */
    const val SWITCHER = 500

    fun <T> fast() = tween<T>(FAST, easing = Out)
    fun <T> mid() = tween<T>(MID, easing = Out)
    fun <T> slow() = tween<T>(SLOW, easing = Out)
}

/**
 * TEMPLATE.md rule 2: every animation dies under the system's reduce-motion setting.
 *
 * Compose has no first-class accessor for this, so it is read from Settings.Global. A
 * missing key means "no reduction requested", which is the safe reading.
 */
@Composable
fun reduceMotion(): Boolean {
    val ctx = LocalContext.current
    return runCatching {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
}

/** Stagger helper: the alpha for the [index]-th item at a given progress. */
fun staggerDelay(index: Int, stepMs: Int = 50, firstMs: Int = 60): Int = firstMs + index * stepMs

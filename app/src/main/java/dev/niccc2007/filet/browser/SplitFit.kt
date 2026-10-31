package dev.niccc2007.filet.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/**
 * How a pane copes with being half a screen wide.
 *
 * ## The fault
 *
 * Every surface in this app was sized against a whole phone. Split the screen and each pane is
 * about 180dp across, which is narrower than the narrowest phone anybody ships - so rows clip,
 * the path bar truncates to nothing, the tree's indents eat the names, a playlist row has no
 * room for its duration, and the toolbar drops buttons it should not. Reported as "it messes up
 * everything in the UI", and that is accurate: nothing was designed for this width.
 *
 * ## Why shrinking, rather than a layout per surface
 *
 * There are two honest answers. One is to design every surface again for a narrow column, which
 * is real work per surface and has to be redone for each new one. The other is to shrink the
 * whole pane so the layout it already has fits the room it has got.
 *
 * Shrinking wins for now, and not only because it is cheaper. A dedicated narrow design would
 * have to decide, surface by surface, what to drop - and a pane that shows different information
 * depending on how wide it is makes split view a different app rather than the same app smaller.
 * Shrinking keeps every control present and reachable, which is the property that matters while
 * somebody is dragging a file from one side to the other.
 *
 * It is a trade, so it is worth naming: text gets smaller, and on a small phone in split view it
 * gets *noticeably* smaller. That is why [Mode] exists rather than this being unconditional, and
 * why the dedicated design is still the better answer eventually.
 *
 * ## How
 *
 * By lowering the pane's [Density] rather than scaling pixels. Everything sized in `dp` or `sp` -
 * padding, row heights, icons, text, touch targets - resolves through the density, so one number
 * shrinks the entire pane coherently and the layout does its own arithmetic at the new size.
 * Scaling with a `graphicsLayer` would instead draw a phone-width pane and squash the picture,
 * which blurs text and leaves touch targets where the fingers are not.
 */
object SplitFit {

    /** What to do with a pane that is narrower than the layout expects. */
    enum class Mode(val id: String, val label: String) {
        /**
         * Shrink the pane so its existing layout fits. The default, and the only one built.
         */
        SHRINK("shrink", "Shrink to fit"),

        /**
         * Leave the layout at full size and let it clip, which is what happened before.
         *
         * Kept so the shrink can be turned off by anybody who would rather have big text and
         * lost edges - and so that a bug report about shrinking has something to compare with.
         */
        NONE("none", "Don't shrink"),
    }

    /**
     * The width a shrunk pane is laid out at.
     *
     * 320dp, the floor the responsive pass set for the whole screen: every surface is checked to
     * hold together at it. It was 360, the width a single pane is designed for, and shrinking a
     * half-phone pane all the way to 360 took it to 0.57 on a 411dp phone - every row and icon
     * smaller than it needed to be to fit. Fitting the floor instead is 0.64, the least shrink
     * that still lays the pane out at a width it was built to survive.
     */
    const val DESIGNED_FOR_DP = 320f

    /**
     * How far the density may fall.
     *
     * 0.6, so a 390dp-or-wider phone split down the middle fits whole. Names do not pay for it:
     * they are sized in real, on-glass units and asked for at their real size divided by this
     * scale (see `TileName`), so the floor that matters for reading is theirs, 9sp. Past this
     * the chrome itself gets too small to hit, so a narrower pane clips a little instead.
     */
    const val FLOOR = 0.6f

    /**
     * The density multiplier for a pane [widthDp] wide.
     *
     * Pure, so the arithmetic is tested rather than eyeballed in a split screen.
     */
    fun scaleFor(widthDp: Float, mode: Mode = Mode.SHRINK): Float {
        if (mode == Mode.NONE) return 1f
        if (!widthDp.isFinite() || widthDp <= 0f) return 1f
        if (widthDp >= DESIGNED_FOR_DP) return 1f
        return (widthDp / DESIGNED_FOR_DP).coerceAtLeast(FLOOR)
    }

    /** True when a pane this wide is being shrunk at all, for anything that wants to say so. */
    fun shrinks(widthDp: Float, mode: Mode = Mode.SHRINK): Boolean = scaleFor(widthDp, mode) < 1f
}

/**
 * Run [content] at a density that makes a [width]-wide pane fit.
 *
 * The font scale is carried through unchanged. Somebody who has made their system text larger has
 * asked for that, and overriding it here would quietly undo an accessibility setting to win back
 * a few pixels.
 */
@Composable
fun SplitFitted(width: Dp, mode: SplitFit.Mode, content: @Composable () -> Unit) {
    val here = LocalDensity.current
    val scale = SplitFit.scaleFor(width.value, mode)
    if (scale >= 1f) {
        content()
        return
    }
    CompositionLocalProvider(
        LocalDensity provides Density(here.density * scale, here.fontScale),
        content = content,
    )
}

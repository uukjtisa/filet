package dev.niccc2007.filet.browser

import kotlin.math.roundToInt

/**
 * Where the tab strip scrolls to, and where its + goes. Issue #26.
 *
 * ## The two complaints
 *
 * The active tab could sit out of sight: switching tabs by swiping the list moved the
 * highlight to a chip off the edge of the strip, and the strip stayed where it was. And the +
 * was the last thing in the scrolling row, so it scrolled away with the tabs, and the scroll
 * cue's chevron sat over it whenever there were more tabs to the right - which read as the +
 * turning into an arrow.
 *
 * ## What it does now
 *
 * The strip centres the tab being shown whenever that changes, as far as the ends allow. It
 * only does that when the tab changes, so it never fights a thumb scrolling the strip.
 *
 * The + follows the last tab while the tabs fit, and stays at the right-hand end once they do
 * not, outside the part that scrolls - Chrome's tab strip. It cannot flip back and forth at
 * the boundary: pinned, it takes its width from the scroller, so the tabs overflow only a
 * narrower box, and they fit there only if they also fit beside the + in the wider one.
 */
internal object TabStripScroll {

    /**
     * Whether the + sits outside the scroller. [maxValue] is the scroller's: how far it can
     * move, `Int.MAX_VALUE` before it has been measured. Unmeasured counts as fitting, so the
     * first frame of a strip with a few tabs looks the same as every frame after it.
     */
    fun pinned(maxValue: Int): Boolean = maxValue != Int.MAX_VALUE && maxValue > 0

    /**
     * The scroll that puts [centre] - a chip's centre, in the row's own coordinates - in the
     * middle of a [viewport] that can scroll up to [maxValue]. Clamped, so a tab near either
     * end goes as near the middle as the row allows and no further.
     */
    fun centreOn(centre: Float, viewport: Int, maxValue: Int): Int {
        if (maxValue <= 0 || maxValue == Int.MAX_VALUE) return 0
        return (centre - viewport / 2f).roundToInt().coerceIn(0, maxValue)
    }

    /** The tab the strip keeps in view: the focused pane's, in a split. */
    fun shown(activeA: Int, activeB: Int, split: SplitMode, focused: Side): Int =
        if (split != SplitMode.OFF && focused == Side.B) activeB else activeA
}

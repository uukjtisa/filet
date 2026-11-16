package dev.niccc2007.filet.browser

import dev.niccc2007.filet.data.ViewStep
import kotlin.math.abs
import kotlin.math.floor

/**
 * How far a tree steps in per level, where level zero starts, and how far the tree may slide
 * sideways to show a row that runs deeper than the pane is wide.
 *
 * ## A deep tree slides; it no longer squeezes
 *
 * This used to shrink the step, and then the left margin, until the deepest open row fitted the
 * pane. Twenty folders deep that left every name an ellipsis and the ladder too tight to read,
 * which was the complaint: a deep tree became a cramped list. Now the step is fixed per view
 * size, and a row that needs more room than the pane has is laid out at its full width and the
 * whole tree drags sideways to reach it, with a cue at the edge that hides something.
 *
 * ## The step follows the view size
 *
 * Twelve dp a level in Compact list, sixteen in List, twenty in Comfortable: the bigger the row,
 * the bigger the step, so a level reads as a level at every size.
 *
 * ## Two screen widths, then it stops stepping in
 *
 * The tree may grow sideways to twice the screen's width, no further. Levels past that sit at
 * the last column instead of stepping in again, so an absurdly deep tree costs a long drag at
 * worst, never miles of it. At 360 dp and List that is thirty levels before it bites.
 *
 * ## Connected is structural here, not something to be careful about
 *
 * The guide lines are drawn from the same [Steps] the row's own padding is built from, through
 * [Steps.at], so a spine cannot land in a different column from the chevron it hangs off.
 */
object TreeScale {

    /**
     * @param startDp the left margin before level zero.
     * @param indentDp how much further in each level sits.
     * @param levels how many levels step in before deeper ones stop: see [at].
     */
    data class Steps(val startDp: Float, val indentDp: Float, val levels: Int = Int.MAX_VALUE) {
        /** Where a row at [depth] starts, and where the guide for that level runs. */
        fun at(depth: Int): Float = startDp + minOf(depth, levels).coerceAtLeast(0) * indentDp
    }

    /** The step for one view size. Grid steps never draw a tree; they get the List step. */
    fun indentFor(step: ViewStep): Float = when (step) {
        ViewStep.COMPACT_LIST -> 12f
        ViewStep.COMFORTABLE -> 20f
        else -> 16f
    }

    /**
     * The steps for a tree in [step], on a screen [screenWidthDp] wide, whose rows carry an icon
     * [iconDp] across.
     *
     * The cap is the SCREEN's width, not the pane's: in a split the pane is half a screen, and
     * the rule is about how far anyone should have to drag, which the screen decides.
     */
    fun of(step: ViewStep, screenWidthDp: Int, iconDp: Int): Steps {
        val indent = indentFor(step)
        val room = 2f * screenWidthDp - START - afterIndent(iconDp)
        return Steps(START, indent, floor(room / indent).toInt().coerceAtLeast(0))
    }

    /** What a row needs after its indent: the chevron, the icon, the gap, and a name worth reading. */
    fun afterIndent(iconDp: Int): Float = CHEVRON + iconDp + GAP + NAME_MIN

    /** How wide the sliding part of a row at [depth] has to be. */
    fun needDp(depth: Int, steps: Steps, iconDp: Int): Float = steps.at(depth) + afterIndent(iconDp)

    /**
     * How far the tree may slide: what the deepest row on screen needs, less the width the
     * sliding part of a row has. Zero when everything fits, which switches the drag off and
     * leaves the gesture to the tab swipe.
     */
    fun maxSlideDp(deepest: Int, steps: Steps, iconDp: Int, slidingDp: Float): Float =
        (needDp(deepest, steps, iconDp) - slidingDp).coerceAtLeast(0f)

    /** The offset after moving by [delta], held inside 0..[max]. */
    fun slid(offset: Float, delta: Float, max: Float): Float = (offset + delta).coerceIn(0f, max.coerceAtLeast(0f))

    /** Who a sideways-ish touch belongs to. See [claim]. */
    enum class Claim { UNDECIDED, SLIDE, LEAVE }

    /**
     * Whether a touch that has travelled [dx], [dy] is the tree's to slide.
     *
     * Sideways and past the slop, and only if the tree can move that way: a tree already at its
     * end leaves the touch alone, so the tab swipe can have it. Down or up first, a long press,
     * or a second finger, and it is somebody else's for the rest of the gesture.
     *
     * @param offset where the tree is now, and [max] how far it can go, both in pixels.
     */
    fun claim(dx: Float, dy: Float, slop: Float, offset: Float, max: Float, held: Boolean, fingers: Int): Claim {
        if (held || fingers > 1) return Claim.LEAVE
        val across = abs(dx)
        val down = abs(dy)
        if (down >= slop && down >= across) return Claim.LEAVE
        if (across < slop || across <= down) return Claim.UNDECIDED
        // A finger moving left shows what is to the right, so the offset grows.
        val canMove = if (dx < 0f) offset < max - EDGE_PX else offset > EDGE_PX
        return if (canMove) Claim.SLIDE else Claim.LEAVE
    }

    /** The ordinary left margin, matching every other list in the app. */
    const val START = 10f

    /** The chevron's box, reserved on every tree row whether or not it has an arrow. */
    const val CHEVRON = 26f

    /** Between the icon and the name. */
    const val GAP = 10f

    /** The least name a row is laid out with before the tree slides rather than squeezes. */
    const val NAME_MIN = 160f

    /** Within half a pixel of an end, call it the end. */
    private const val EDGE_PX = 0.5f
}

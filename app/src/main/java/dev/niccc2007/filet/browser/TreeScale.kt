package dev.niccc2007.filet.browser

/**
 * How far a tree steps in per level, and how far from the edge it starts.
 *
 * ## Why this is not a constant
 *
 * It was, and a fixed 26dp step is wrong in both directions. Four levels deep in a split
 * pane the indent had eaten half the width and every name was an ellipsis; one level deep on
 * a tablet it was needlessly timid. So it gives way when the pane is cramped - a smaller
 * step, and then a smaller left margin - under one condition that is not negotiable: the
 * guide lines stay connected the whole way down.
 *
 * ## Connected is structural here, not something to be careful about
 *
 * The guide lines are drawn from the same two numbers the row's own padding is built from -
 * one [Steps] goes to both - so a spine cannot land in a different column from the chevron
 * it hangs off. That is the whole reason this is a value rather than two constants read in
 * two places: two places is how they drift.
 *
 * ## It is computed from the DEEPEST row, not per row
 *
 * Every row in a pane uses the same step. Sizing each row to its own depth would give level
 * three a different column from level two, which is the one thing a tree may not do. So the
 * question is what step lets the deepest row OPEN RIGHT NOW still show a usable name, and
 * the answer applies to all of them. Fold that branch away and the tree opens back out.
 *
 * ## The order things give way in
 *
 * The indent shrinks first, because a narrower step costs nothing but a slightly tighter
 * ladder. Only once it is at its floor does the whole tree slide toward the edge, which
 * costs the left margin the rest of the app has - so it is the last thing spent, not the
 * first.
 */
object TreeScale {

    /**
     * @param startDp the left margin before level zero.
     * @param indentDp how much further in each level sits.
     */
    data class Steps(val startDp: Float, val indentDp: Float)

    /**
     * @param widthDp the pane's own width, not the screen's.
     * @param deepest the depth of the deepest row currently in the list. 0 for a flat list.
     */
    fun of(widthDp: Int, deepest: Int): Steps {
        if (deepest <= 0) return Steps(START, INDENT)
        val room = widthDp - KEEP
        // Enough for the full step: nothing to do, and a wide pane never pays for this.
        if (room >= START + deepest * INDENT) return Steps(START, INDENT)

        val fits = (room - START) / deepest
        if (fits >= INDENT_MIN) return Steps(START, fits.coerceAtMost(INDENT))

        // The step is as small as it may go, so the margin gives way instead. Below the
        // floor on both, nothing more can be recovered and rows simply run out of name -
        // which is honest: a tree twelve deep in a hundred-dp pane has no good answer.
        val start = (room - deepest * INDENT_MIN).coerceIn(START_MIN, START)
        return Steps(start, INDENT_MIN)
    }

    /**
     * What a row needs after the indent: the chevron, the icon, the gap, and enough name to
     * be worth reading. Under this the tree stops taking room for its ladder.
     */
    const val KEEP = 124f

    /** The ordinary left margin, matching every other list in the app. */
    const val START = 10f

    /** And the least it may become. Zero would put the first chevron against the frame. */
    const val START_MIN = 2f

    /**
     * The full step.
     *
     * Twenty, not a chevron's twenty-six. The first version made the step exactly one
     * chevron wide so a child's arrow began where its parent's ended, which is tidy and
     * costs a quarter more width per level than it needs to - X-plore, which is where the
     * tree came from, steps in by well under its own arrow and the levels are still plainly
     * distinct. The elbow still runs arrow to arrow either way; it is simply shorter.
     */
    const val INDENT = 20f

    /**
     * The smallest step that still reads as a step.
     *
     * The elbow runs `indent - 6` dp from the parent's spine to the child's arrow, so at
     * ten there are four dp of horizontal line: thin, but a visible join. Below that the
     * corner and the run are the same pixel and the levels stop being distinguishable from
     * one another, which is worse than a cut-off name.
     */
    const val INDENT_MIN = 10f
}

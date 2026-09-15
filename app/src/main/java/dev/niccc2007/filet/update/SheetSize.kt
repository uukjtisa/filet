package dev.niccc2007.filet.update

/**
 * How tall the release-notes pane is, and what a drag on the handle does to it.
 *
 * The design calls for the changelog viewer to be resizable and bigger by default - there is a lot
 * of content in a release body and it was getting a small window. He was right, and it was
 * worse than it looked: the pane was a flat 260dp while the
 * notes now carry 208dp screenshot strips, so a single strip filled it.
 *
 * Kept as arithmetic rather than as state inside the composable because the three ways this goes
 * wrong are all arithmetic. A drag that inverts, a fraction that creeps past its bounds and
 * strands the buttons off-screen, and a stored value from a previous phone that no longer makes
 * sense. Each is one line here and one test below.
 */

/**
 * The default share of the screen the notes get.
 *
 * More than half, deliberately. This is the screen somebody reads before deciding whether to
 * install an APK, and it is the only place the notes are read at all for anyone who never opens
 * the repo.
 */
const val NOTES_DEFAULT_FRACTION = 0.58f

/** Below this the pane cannot show a screenshot strip and a line of text together. */
const val NOTES_MIN_FRACTION = 0.22f

/**
 * Above this the sheet's own title and buttons start being pushed off the bottom.
 *
 * The cap is on the NOTES, not on the sheet, so whatever the notes take the buttons still fit.
 */
const val NOTES_MAX_FRACTION = 0.78f

/**
 * The fraction after dragging the handle by [dragPx].
 *
 * @param dragPx pointer movement in pixels, positive downwards, which is what Compose reports.
 *   Dragging the handle DOWN makes the pane smaller, so the sign flips here - that inversion is
 *   the single most likely thing to be wrong and is pinned by a test.
 * @param screenPx the height the fraction is a fraction of.
 */
fun nextNotesFraction(
    current: Float,
    dragPx: Float,
    screenPx: Float,
    min: Float = NOTES_MIN_FRACTION,
    max: Float = NOTES_MAX_FRACTION,
): Float {
    if (screenPx <= 0f) return current.coerceIn(min, max)
    return (current - dragPx / screenPx).coerceIn(min, max)
}

/**
 * A stored fraction, made safe to use.
 *
 * Zero means "never set", which is not the same as "set to nothing" - a stored 0 would otherwise
 * clamp to the minimum and the pane would open as a slot. Anything outside the bounds is brought
 * back inside rather than rejected, because a value from an older build is still a preference.
 */
fun usableNotesFraction(stored: Float): Float =
    if (stored <= 0f) NOTES_DEFAULT_FRACTION else stored.coerceIn(NOTES_MIN_FRACTION, NOTES_MAX_FRACTION)

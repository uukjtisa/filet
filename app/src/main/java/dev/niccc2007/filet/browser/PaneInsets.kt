package dev.niccc2007.filet.browser

/**
 * How much room a pane's list keeps clear for the things that float over it.
 *
 * ## Why this is arithmetic and not a guess
 *
 * A list that fills its box and a bar drawn on top of it are both correct on their own, and
 * together they hide the last row. Scrolling does not help: at maximum scroll the last item's
 * bottom edge is the viewport's bottom edge, which is exactly where the floating thing is. The
 * row is on screen and unreadable, which is the worst of the three possible states because it
 * looks like a rendering fault rather than a layout one.
 *
 * So every overlay declares its size here and the list reserves it. The numbers are in dp and
 * plain Ints so the decision can be tested without a composition - the values are converted at
 * the one place they are applied.
 *
 * The base gap is not decoration. Even with nothing floating, a row whose bottom edge is the
 * same pixel as a divider reads as cut off.
 */
object PaneInsets {

    /** Kept clear under the last row at all times, so it never sits flush against a bar. */
    const val ROW_BREATH = 10

    /** The floating paste pill: its own height plus the margin it is drawn with. */
    const val PASTE_PILL = 52

    /** The gutter down the right edge of a pane, and the drop band that sits in it. */
    const val DROP_BAND = 44

    /**
     * @param pastePill whether the clipboard pill is drawn over this pane right now.
     */
    fun bottom(pastePill: Boolean): Int = ROW_BREATH + if (pastePill) PASTE_PILL else 0

    /**
     * The drop band takes no width. It floats.
     *
     * Two versions of this were wrong in opposite directions, and both are worth keeping
     * written down because the reasons are different:
     *
     * - **Reserved only while dragging.** The list's `contentPadding` then went from 0 to 44dp
     *   at the exact moment a long press resolved into a drag, re-measuring every row under
     *   the finger starting the gesture. The drag was cancelled by the widget built to receive
     *   it. Any toggle here has that fault, whatever triggers it.
     * - **Reserved permanently while split.** No layout change and no cancelled drag, but a
     *   split pane on a phone is barely half a screen wide, and taking 44dp of it forever
     *   visibly squashes the rows - to buy nothing at rest, since the band is not drawn then.
     *
     * So it reserves nothing and overlays instead. Rows stay full width, no measurement
     * changes at any point in the gesture, and the band covers the right edge only while a
     * drag is in flight - which is the one moment you are looking straight at it and every row
     * is still reachable beside it.
     */
    fun end(split: Boolean): Int = 0
}

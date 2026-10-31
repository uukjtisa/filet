package dev.niccc2007.filet.browser

/**
 * How wide the side bar may be, and where the app card sits beside it when the drawer opens.
 * Pure, so the arithmetic is tested rather than eyeballed on two screen sizes.
 *
 * ## Two widths, not one
 *
 * The drawer (under [BrowserScreen]'s rail breakpoint) and the rail (at and above it) keep a
 * width each. The rail costs the listing room for as long as the window is that shape; the drawer
 * costs nothing once it is closed. The same number is rarely the right answer for both, and one
 * shared width would make narrowing the rail for a wider listing also cramp the drawer.
 *
 * ## Clamped where it is drawn
 *
 * The stored width is whatever was dragged to. The limit depends on the window, so it is applied
 * on every draw: a width chosen on a wide window and then squeezed by a narrow one comes back when
 * the wide one does, rather than having been overwritten by the squeeze.
 */
object SideWidth {
    const val DEFAULT_DP = 236

    /** Below this a row's name is cut to a few letters and the counts collide with it. */
    const val MIN_DP = 180

    /** Past this the rows are mostly empty space on the right. */
    const val MAX_DP = 420

    /**
     * How much of the app card the open drawer must leave showing. The card is the way back -
     * tapping it closes the drawer - and a drawer dragged across the whole screen would leave
     * only the close button.
     */
    const val DRAWER_KEEPS_DP = 72

    /** The rail never takes more than this share of the window: the listing is the app. */
    const val RAIL_SHARE = 0.45f

    /** Between the drawer's edge and the pushed card. Also where the drawer's drag handle sits. */
    const val GAP_DP = 12

    /** The app card's scale with the drawer fully open. [BrowserScreen]'s push reads this. */
    const val PUSHED_SCALE = 0.78f

    enum class Surface { DRAWER, RAIL }

    /** The widest [surface] may be in a window [windowDp] wide. Never below [MIN_DP]. */
    fun widest(surface: Surface, windowDp: Float): Float = when (surface) {
        Surface.DRAWER -> minOf(MAX_DP.toFloat(), windowDp - DRAWER_KEEPS_DP)
        Surface.RAIL -> minOf(MAX_DP.toFloat(), windowDp * RAIL_SHARE)
    }.coerceAtLeast(MIN_DP.toFloat())

    fun clamp(dp: Float, surface: Surface, windowDp: Float): Float =
        dp.coerceIn(MIN_DP.toFloat(), widest(surface, windowDp))

    /**
     * How far right the app card moves, in dp, at [push] (0 closed, 1 open).
     *
     * Bug identified here: the card moved by half the window's width. Its scale is about its own
     * centre, so at 0.78 its left edge also comes in by 11% of the window, and the two together
     * put that edge at 61% of the width whatever the drawer was. On a phone that happens to land
     * on the drawer's edge; on a landscape window it left a band of empty background between the
     * drawer and the card, and on a narrow phone it overlapped the drawer. The edge is now
     * placed: [GAP_DP] past the drawer at every width, moving with the drawer as it is dragged.
     */
    fun cardShift(push: Float, drawerDp: Float, windowDp: Float): Float {
        val scale = 1f - (1f - PUSHED_SCALE) * push
        val shrinkInset = (1f - scale) / 2f * windowDp
        return push * (drawerDp + GAP_DP) - shrinkInset
    }

    /** The card's scale at [push]; the same value [cardShift] assumes. */
    fun cardScale(push: Float): Float = 1f - (1f - PUSHED_SCALE) * push
}

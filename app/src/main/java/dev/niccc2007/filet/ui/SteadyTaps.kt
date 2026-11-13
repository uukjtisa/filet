package dev.niccc2007.filet.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.unit.dp

/**
 * When a press counts as a tap, and when two taps count as a double tap.
 *
 * Bug identified here: the viewers took their double tap from `detectTapGestures`, which counts a
 * press as a tap however far the finger travelled, provided nothing consumed the movement. The
 * PDF viewer's sideways pan reads its movement without consuming it, so on a zoomed page the end
 * of a slide was taken for a first tap and putting the finger straight back down for the second -
 * and the page zoomed out under a finger that was only panning. Any viewer with a drag that does
 * not consume had the same trap waiting in it.
 *
 * So a tap is decided by where the finger went, and not only by whether something consumed it: a
 * press that never left the touch slop and was let go before a long press. A double tap is two of
 * those, the second starting soon after the first ended and near where it landed - the same three
 * conditions the platform's own gesture detector applies.
 */
object TapRule {
    /** How far apart the two taps of a double tap may land: the platform's DOUBLE_TAP_SLOP. */
    const val PAIR_SLOP_DP = 100f

    /** A press is a tap if it never strayed past [slopPx] and was let go before a long press. */
    fun isTap(farthestPx: Float, slopPx: Float, heldMs: Long, longPressMs: Long): Boolean =
        farthestPx <= slopPx && heldMs < longPressMs

    /**
     * A second press makes a double tap if it came down between [minGapMs] and [maxGapMs] after
     * the first tap ended, within [pairSlopPx] of it. The lower bound is the platform's: a touch
     * screen can report one press as two a few milliseconds apart.
     */
    fun pairs(gapMs: Long, apartPx: Float, minGapMs: Long, maxGapMs: Long, pairSlopPx: Float): Boolean =
        gapMs in minGapMs..maxGapMs && apartPx <= pairSlopPx
}

/**
 * Taps and double taps by [TapRule], in place of `detectTapGestures` where a drag shares the
 * surface.
 *
 * Never looser than the detector it replaces: a press whose down or up something else consumed,
 * or that left the surface, is still not a tap. It is stricter in the two ways the viewers needed -
 * travel past the slop and a held press are not taps either, however the movement was handled.
 *
 * [onTap] fires for a tap that no second tap followed in time, so a viewer can toggle its controls
 * on a single tap without that firing on the way to a double one.
 */
suspend fun PointerInputScope.detectSteadyTaps(
    onTap: ((Offset) -> Unit)? = null,
    onDoubleTap: (Offset) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    val longPressMs = viewConfiguration.longPressTimeoutMillis
    val maxGapMs = viewConfiguration.doubleTapTimeoutMillis
    val minGapMs = viewConfiguration.doubleTapMinTimeMillis
    val pairSlop = TapRule.PAIR_SLOP_DP.dp.toPx()
    awaitEachGesture {
        val firstDown = awaitFirstDown()
        val first = awaitSteadyUp(firstDown, slop, longPressMs) ?: return@awaitEachGesture
        val secondDown = withTimeoutOrNull(maxGapMs) { awaitFirstDown() }
        if (secondDown == null) {
            onTap?.invoke(first.position)
            return@awaitEachGesture
        }
        val gap = secondDown.uptimeMillis - first.uptimeMillis
        val apart = (secondDown.position - first.position).getDistance()
        if (!TapRule.pairs(gap, apart, minGapMs, maxGapMs, pairSlop)) {
            // Too soon or too far to be the same gesture: the first tap stands alone, and the
            // second is only a tap of its own if it turns out to be one.
            onTap?.invoke(first.position)
            awaitSteadyUp(secondDown, slop, longPressMs)?.let { onTap?.invoke(it.position) }
            return@awaitEachGesture
        }
        val second = awaitSteadyUp(secondDown, slop, longPressMs)
        // A second press that became a slide is the start of a pan, not half of a double tap.
        if (second != null) onDoubleTap(second.position) else onTap?.invoke(first.position)
    }
}

/**
 * Follows the press that began with [down] until that finger lifts. The lifting change if the
 * press was a tap by [TapRule]; null if it travelled, was held, was joined by another finger, had
 * its movement or its lift consumed, or left the surface.
 */
private suspend fun AwaitPointerEventScope.awaitSteadyUp(
    down: PointerInputChange,
    slop: Float,
    longPressMs: Long,
): PointerInputChange? {
    var farthest = 0f
    var spoiled = false
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } > 1) spoiled = true
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (change.isConsumed) spoiled = true
        val p = change.position
        if (p.x < 0f || p.y < 0f || p.x > size.width || p.y > size.height) spoiled = true
        farthest = maxOf(farthest, (p - down.position).getDistance())
        if (!change.pressed) {
            val held = change.uptimeMillis - down.uptimeMillis
            return change.takeIf { !spoiled && TapRule.isTap(farthest, slop, held, longPressMs) }
        }
    }
}

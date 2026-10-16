package dev.niccc2007.filet.browser

/**
 * Swiping sideways to change the tab in the pane under your finger.
 *
 * ## What it switches, and why that was wrong the first time
 *
 * It was built first as "swipe to change which pane you are in", copied from X-plore without
 * copying the reason. X-plore's gesture moves between panes because only ONE of its panes is
 * on screen at a time, so the other pane is somewhere to go. Here both panes are visible at
 * once, so that gesture moved a focus ring between two things already in front of you and
 * changed nothing else - a gesture whose whole result is a highlight.
 *
 * The thing with somewhere to go is the TAB STRIP, and in a split each side is on its own
 * tab. So the swipe belongs to the pane it started in: that pane moves along the strip and
 * the other one is left exactly as it was.
 *
 * ## The conflict, which is the whole design problem
 *
 * Filet moves files by dragging them, and a one-finger horizontal swipe is therefore not free
 * the way it is in an app that does not. A gesture that sometimes eats a drag is worse than
 * no gesture at all, because the failure is silent: you drag a folder, nothing picks up, and
 * you cannot tell whether you were slow or the app was wrong.
 *
 * Two things keep them apart:
 *
 *  1. **A drag starts with a LONG PRESS**, and the platform's is 500ms. A swipe has to have
 *     STARTED MOVING within [GRACE_MS] of touching down - less than half of that - so a
 *     finger that is about to pick something up has not begun swiping, and a finger that is
 *     swiping is long gone before the press would have fired.
 *  2. **It has to be mostly sideways.** [RATIO] of horizontal to vertical, or it is a scroll.
 *     A list being flicked up at a slight angle must never change tab.
 *
 * ## Distance OR flick, never distance AND speed
 *
 * The first version required both, and that is why it never fired once: a deliberate 70dp
 * push is slow by definition, so it failed the speed test, and a fast flick is short, so it
 * failed the distance test. Both halves of the gesture people actually make were rejected.
 *
 * It now commits on either: far enough at any speed, or a shorter flick that is quick. That
 * is what every pager on the platform does, and it is the only arrangement where the same
 * threshold does not have to describe two different hand movements.
 *
 * Pure, and tested, because every threshold here is wrong in a way you only find out about
 * with a file halfway into another folder.
 */
object PaneSwipe {

    /** How hard you have to swipe. */
    enum class Sensitivity(
        val id: String,
        val label: String,
        /** Far enough to commit on its own, however slowly it got there. */
        val distanceDp: Float,
        /** A flick: shorter than that, but it has to be quick. */
        val flickDp: Float,
        /** How quick, averaged over the movement rather than sampled at the end of it. */
        val speedDpPerSec: Float,
    ) {
        /** For a thumb that does not travel far. Fires on a short, unhurried push. */
        LIGHT("light", "Light", 52f, 22f, 280f),

        /** The default: about a third of a narrow pane, or a flick nobody makes by accident. */
        NORMAL("normal", "Normal", 84f, 38f, 560f),

        /** A long, deliberate push, for anyone who keeps catching it by mistake. */
        FIRM("firm", "Firm", 132f, 64f, 980f);

        companion object {
            fun of(id: String?): Sensitivity = entries.firstOrNull { it.id == id } ?: NORMAL
        }
    }

    /**
     * Should this gesture change tab?
     *
     * @param dx total horizontal travel in dp, signed.
     * @param dy total vertical travel in dp; the unsigned distance is what matters.
     * @param speedDpPerSec the AVERAGE horizontal speed over the movement.
     * @param sinceDownMs how long the finger had been down when it started moving.
     */
    fun fires(
        dx: Float,
        dy: Float,
        speedDpPerSec: Float,
        sinceDownMs: Long,
        sensitivity: Sensitivity,
    ): Boolean {
        if (sinceDownMs > GRACE_MS) return false
        val across = kotlin.math.abs(dx)
        if (across < kotlin.math.abs(dy) * RATIO) return false
        if (across >= sensitivity.distanceDp) return true
        return across >= sensitivity.flickDp &&
            kotlin.math.abs(speedDpPerSec) >= sensitivity.speedDpPerSec
    }

    /**
     * How far through the gesture this is, for the label that appears while you push.
     *
     * The label is the part that makes the gesture discoverable - a swipe nobody is told
     * about is a swipe nobody finds - so it starts showing at [HINT_AT] of the distance,
     * which is early enough to be a hint and late enough not to flash on every scroll.
     */
    fun progress(dx: Float, dy: Float, sensitivity: Sensitivity): Float {
        val across = kotlin.math.abs(dx)
        if (across < kotlin.math.abs(dy) * RATIO) return 0f
        val at = across / sensitivity.distanceDp
        if (at < HINT_AT) return 0f
        return at.coerceAtMost(1f)
    }

    /** Which way a swipe goes: left moves on through the strip, right moves back. */
    fun stepFor(dx: Float): Int = if (dx < 0f) 1 else -1

    /**
     * The tab a swipe lands on, or null when there is none that way.
     *
     * It does NOT wrap. Wrapping means the last tab is one swipe from the first, so a stray
     * flick at the end of the strip throws you to the other end of it - and with two tabs,
     * every swipe in either direction does the same thing, which makes the gesture feel
     * broken rather than generous. Running out is information: it is why the hint says so.
     */
    fun nextTab(current: Int, step: Int, count: Int): Int? {
        if (count <= 1) return null
        val next = current + step
        return if (next in 0 until count) next else null
    }

    /**
     * How long after touching down a swipe can still begin.
     *
     * The file list's drag needs a long press, and the platform's is 500ms. This is under
     * half of that, so a finger that is about to pick something up has not started swiping
     * and a finger that is swiping is long gone before the press would have fired.
     */
    const val GRACE_MS = 220L

    /** How much more sideways than up-and-down it has to be. */
    const val RATIO = 1.8f

    /** Where the label starts to show, as a fraction of the distance needed. */
    const val HINT_AT = 0.45f
}

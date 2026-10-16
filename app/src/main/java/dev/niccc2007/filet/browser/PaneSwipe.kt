package dev.niccc2007.filet.browser

/**
 * Swiping sideways to change which pane you are in.
 *
 * ## The conflict, which is the whole design problem
 *
 * Filet moves files by dragging them, and X-plore - where this gesture comes from - does not.
 * A one-finger horizontal swipe is therefore not free here the way it is there, and a gesture
 * that sometimes eats a drag is worse than no gesture at all, because the failure is silent:
 * you drag a folder, nothing picks up, and you cannot tell whether you were slow or the app
 * was wrong.
 *
 * Three things keep them apart, and all three are needed:
 *
 *  1. **A drag starts with a LONG PRESS.** The file list has always worked that way. A swipe
 *     that begins within [GRACE_MS] of touching down is a swipe; after that the finger has
 *     already been claimed by the list and nothing here fires.
 *  2. **It only exists in dual pane.** With one pane there is nothing to switch to, so in
 *     ordinary browsing - which is most of the time - this code does not run at all. That is
 *     not a simplification, it is the safety margin: the mode where the gesture is armed is
 *     the mode where the user asked for it.
 *  3. **It has to be mostly sideways.** [RATIO] of horizontal to vertical, or it is a scroll.
 *     A list being flicked up at a slight angle must never change pane.
 *
 * ## Sensitivity is a setting because fingers differ
 *
 * Not a number somebody guessed. The three steps move the distance AND the speed together,
 * because they trade off: a short swipe has to be fast to be deliberate, and a slow one has
 * to be long. Picking them independently is two sliders to answer one question.
 *
 * Pure, and tested, because every threshold here is wrong in a way you only find out about
 * with a file halfway into another folder.
 */
object PaneSwipe {

    /** How hard you have to swipe. */
    enum class Sensitivity(val id: String, val label: String, val distanceDp: Float, val speedDpPerSec: Float) {
        /** For a thumb that does not travel far. Fires on a short, unhurried push. */
        LIGHT("light", "Light", 40f, 240f),

        /** The default: about a third of a narrow pane, at a speed nobody reaches by accident. */
        NORMAL("normal", "Normal", 70f, 520f),

        /** A deliberate flick, for anyone who keeps catching it by mistake. */
        FIRM("firm", "Firm", 110f, 950f);

        companion object {
            fun of(id: String?): Sensitivity = entries.firstOrNull { it.id == id } ?: NORMAL
        }
    }

    /**
     * Should this gesture change pane?
     *
     * @param dx total horizontal travel in dp, signed.
     * @param dy total vertical travel in dp, unsigned distance is what matters.
     * @param speedDpPerSec the horizontal speed at the end of it.
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
        if (across < sensitivity.distanceDp) return false
        if (across < kotlin.math.abs(dy) * RATIO) return false
        return kotlin.math.abs(speedDpPerSec) >= sensitivity.speedDpPerSec
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

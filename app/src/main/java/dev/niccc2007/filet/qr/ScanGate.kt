package dev.niccc2007.filet.qr

/**
 * Which decoded camera frames become an answer. Pure: the clock is passed in.
 *
 * The camera reads the same code ten times a second for as long as it is in view, so "a frame
 * decoded" and "show the dialog" cannot be the same event. Two rules:
 *
 *  - Nothing new opens while an answer is showing. The frames behind the dialog are still being
 *    read and would otherwise replace what somebody is in the middle of reading.
 *  - The code that was just closed stays closed while it is still in front of the lens. Closing
 *    the dialog with the phone still pointed at the code would otherwise reopen it on the next
 *    frame, and the only way out would be to point the phone at the floor. It counts as gone
 *    once [GONE_MS] has passed without a frame reading it; pointing back at it after that is
 *    asking for it again.
 *
 * A different code is always let through - looking at the next sticker is the point.
 */
class ScanGate(private val goneMs: Long = GONE_MS) {
    private var closed: String? = null
    private var closedSeenAt = 0L

    /** Whether a frame that read [text] at [now] should open the dialog. */
    fun admit(text: String, now: Long, showing: Boolean): Boolean {
        if (showing) return false
        // Every frame that still reads it pushes the clock on, so holding the phone still keeps
        // it closed indefinitely and only looking away for [goneMs] lets it back.
        if (text == closed && now - closedSeenAt < goneMs) {
            closedSeenAt = now
            return false
        }
        closed = null
        return true
    }

    /** The answer showing [text] was closed at [now]. */
    fun closed(text: String, now: Long) {
        closed = text
        closedSeenAt = now
    }

    companion object {
        /** About two missed glances: long enough to mean the phone moved away, not a blur. */
        const val GONE_MS = 1500L
    }
}

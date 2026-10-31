package dev.niccc2007.filet.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf

/**
 * How many dialogues and pop-up menus are open right now. The viewers read it so their controls
 * stay up while something is on top of them.
 *
 * ## The fault this exists for
 *
 * Bug identified here: the picture viewer's controls retract after a few idle seconds, and the
 * QR chip in its top bar draws its dialogue from inside that bar. Retracting the bar removed the
 * chip, and the dialogue with it - so the details of a code vanished part way through being
 * read. The viewer already held its controls for its own menu, by name, and the chip was simply
 * not on the list. A list of what holds the controls is the shape that missed it, and the next
 * dialogue added to a viewer would have been missed the same way.
 *
 * So it is declared once, where every dialogue is drawn: [Dlg] and [PopMenu] count themselves in
 * while composed. A viewer does not need to know what was opened, only that something is open,
 * and a dialogue opened from anywhere - the Details sheet, the delete confirmation, a chip -
 * holds it without its author having to remember to.
 *
 * Process-wide rather than per screen on purpose: a viewer is only ever on screen when it is on
 * top, so anything open is open over it.
 */
object OverlayHold {
    private val open = mutableIntStateOf(0)

    /** Something is open. A snapshot read: a composable reading it recomposes when it changes. */
    val held: Boolean get() = open.intValue > 0

    internal fun acquire() {
        open.intValue = open.intValue + 1
    }

    /** Never below zero, so a release without its acquire cannot leave the controls unholdable. */
    internal fun release() {
        open.intValue = (open.intValue - 1).coerceAtLeast(0)
    }

    /** Counts the calling dialogue in for as long as it is composed. */
    @Composable
    fun Hold() {
        DisposableEffect(Unit) {
            acquire()
            onDispose { release() }
        }
    }
}

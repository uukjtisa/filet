package dev.niccc2007.filet.handlers

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat

/**
 * When a viewer's own controls should get out of the way.
 *
 * ## The rule, and why it is not "hide while playing"
 *
 * A viewer is for looking at the thing, and everything drawn over it is in the way of that. The
 * first version hid the controls only while a video was playing, which is the usual rule and is
 * half of the answer: a paused frame somebody is studying, and a photograph somebody is looking
 * at, are exactly the cases where the controls are most in the way and they kept them up for ever.
 *
 * So the state that matters is **being touched**, not being played. The controls come up on a
 * touch, stay for as long as somebody is plausibly still using them, and retract. One rule, both
 * viewers, whatever is on screen.
 *
 * ## What holds them open
 *
 * [interacting] is the exception, and it has to exist: controls that vanish mid-drag take the
 * thing being dragged with them. A finger on the scrubber, a menu open over the picture, an edit
 * in progress - while any of those is true the clock does not run.
 */
object ViewerChrome {

    /**
     * Whether the controls should retract now.
     *
     * @param now the current time in milliseconds.
     * @param lastTouchedAt when the picture was last touched, in the same clock.
     * @param interacting something is holding the controls open and the timer must not run.
     */
    fun shouldRetract(
        now: Long,
        lastTouchedAt: Long,
        interacting: Boolean,
        idleMs: Long = IDLE_MS,
    ): Boolean {
        if (interacting) return false
        // A clock that has gone backwards - a device whose time was corrected, or a caller
        // passing a later touch than the sample - must not be read as "a very long time ago".
        val idle = now - lastTouchedAt
        if (idle < 0L) return false
        return idle >= idleMs
    }

    /**
     * How long the controls stay up after a touch.
     *
     * Long enough to reach for the button you brought them up for, short enough that the picture
     * is uncovered before it gets annoying. Three seconds is what the phone players settle on and
     * there is no reason to be different for the sake of it.
     */
    const val IDLE_MS = 3_000L
}

/**
 * Hide the system bars while a viewer is open, and put them back on the way out.
 *
 * A viewer that leaves the status bar and the navigation bar on screen is not showing the picture,
 * it is showing the picture inside the app. It is the same fault as the title bar being in the way
 * and it has the same fix, which is why both are handled here rather than separately.
 *
 * **Swipe still works.** The behaviour is the transient one: a swipe from the edge brings the bars
 * back for a few seconds and they leave again. Locking them away entirely is how a viewer becomes
 * a thing people have to kill the app to get out of.
 *
 * Restored on dispose rather than on a back press, so it is put right however the screen is left -
 * including by a gesture, a crash in a child composable, or the activity being recreated.
 */
@Composable
fun ImmersiveViewer(active: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(active, context) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (active && controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
}

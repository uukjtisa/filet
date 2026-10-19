package dev.niccc2007.filet.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * Whether a playback surface may run a clock at all. Pure.
 *
 * ## The two rules, and the defect behind each
 *
 * **Nothing animates when nothing is looking.** Every clock on this path was a `LaunchedEffect`
 * stepping on `delay`, and `delay` is not the frame clock: it keeps firing with the Activity
 * stopped, the screen off, or the surface sitting underneath a full-screen viewer. The strip is
 * composed under the full player - the viewer is a layer over the browser, not a destination -
 * so with the player open the strip's beat follower, its glow and its marquee all ran behind an
 * opaque screen, every redraw of them was a redraw of the window, and with the screen off the
 * follower went on stepping sixty times a second for nobody. [onScreen] is the Activity being at
 * least STARTED; [covered] is a viewer being open over the surface.
 *
 * **Nothing animates when nothing is playing.** The beat follower's loop wrote zeros sixty
 * times a second through a pause - correct values, at full cost, forever.
 *
 * [systemAnimations] is the OS animations-off switch, honoured as before.
 *
 * Off is a state, not an absence: every caller draws its resting picture when this is false,
 * so a paused player still shows its glow and its wave at rest. Only the motion stops.
 */
fun shouldAnimate(
    onScreen: Boolean,
    playing: Boolean,
    systemAnimations: Boolean,
    covered: Boolean = false,
): Boolean = onScreen && !covered && playing && systemAnimations

/**
 * True while the Activity is at least STARTED - visible, or partly so behind a dialog.
 *
 * STARTED rather than RESUMED, so a permission prompt or the notification shade pulled half
 * way down does not freeze a player that is plainly still in view.
 */
@Composable
fun rememberOnScreen(): Boolean =
    LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
        .isAtLeast(Lifecycle.State.STARTED)

/**
 * The player's state without the position, for a surface's body. See `Playback.status`.
 *
 * Collected with the lifecycle, so a surface in the background is not woken by it at all.
 */
@Composable
fun rememberStatus(playback: Playback): State<NowPlaying> {
    val flow = remember(playback) { playback.status }
    return flow.collectAsStateWithLifecycle(playback.state.value.withoutPosition())
}

/**
 * Where the track is, for whatever draws the playhead - read it in a draw lambda or a small
 * leaf composable, never in a large body. Collecting it is what keeps the position poll at its
 * fast rate; see `Playback.tickInterval`.
 */
@Composable
fun rememberPosition(playback: Playback): State<Long> {
    val flow = remember(playback) { playback.position }
    return flow.collectAsStateWithLifecycle(playback.state.value.positionMs)
}

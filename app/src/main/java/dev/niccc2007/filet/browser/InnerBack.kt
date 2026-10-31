package dev.niccc2007.filet.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * Claim the back gesture for a level inside a pane, for as long as [enabled] is true.
 *
 * Use this and NOT `BackHandler` for anything drawn inside a pane. A `BackHandler` competes
 * with the browser's single handler and which of them wins is decided by composition order,
 * which is how backing out of an open playlist came to leave the whole tab - see
 * `BrowserViewModel.claimInnerBack`.
 *
 * [undo] is read through `rememberUpdatedState`, so the claim is registered once and still
 * calls the current lambda - a claim re-registered on every state change would reorder itself
 * among its siblings and stop being the innermost one.
 */
@Composable
fun InnerBack(vm: BrowserViewModel, enabled: Boolean, undo: () -> Unit) {
    val live = rememberUpdatedState(undo)
    val on = rememberUpdatedState(enabled)
    DisposableEffect(vm) {
        val token = vm.claimInnerBack {
            if (on.value) {
                live.value()
                true
            } else {
                false
            }
        }
        onDispose { vm.releaseInnerBack(token) }
    }
}

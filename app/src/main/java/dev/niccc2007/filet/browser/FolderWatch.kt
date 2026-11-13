package dev.niccc2007.filet.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.provider.FolderChanges

/**
 * Watches the folder [pane] is showing for as long as the pane is composed and the app started,
 * and re-reads it when something outside Filet changes it - see [FolderChanges] for what is heard
 * and how changes are coalesced, and `BrowserViewModel.relistFromOutside` for when a re-read waits.
 *
 * ON_START/ON_STOP rather than composition alone, as the New files tab does: a pane stays composed
 * behind another app, and a watch left running then is a standing cost for a list nobody is
 * reading. Coming back is covered anyway - `refreshStalePanes` re-reads the visible panes on resume.
 */
@Composable
internal fun WatchFolder(vm: BrowserViewModel, pane: PaneController, cwd: VPath?, folder: Boolean) {
    val os = remember(cwd, folder) { if (folder && cwd != null) vm.osPathOf(cwd) else null }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(os, lifecycle) {
        if (os == null || cwd == null) return@DisposableEffect onDispose { }
        var watch: AutoCloseable? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (watch == null) {
                    watch = runCatching { FolderChanges(os, vm.appContext) { vm.relistFromOutside(pane, cwd) } }.getOrNull()
                }
                Lifecycle.Event.ON_STOP -> {
                    watch?.close()
                    watch = null
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            watch?.close()
            watch = null
        }
    }
}

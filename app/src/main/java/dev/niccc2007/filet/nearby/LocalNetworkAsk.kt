package dev.niccc2007.filet.nearby

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * A screen's use of the local network: whether it may, and how to ask.
 *
 * @property allowed the network may be used now. Always true below Android 17.
 * @property refused the person said no.
 * @property settingsOnly Android will not show its prompt again - it stops after the second no -
 *   so the only way left is this app's page in Settings, and offering Allow would do nothing.
 * @property ask the platform's own prompt again.
 * @property settings an intent for this app's page in Settings, where it can be allowed by hand.
 */
class LocalNetworkAccess(
    val allowed: Boolean,
    val refused: Boolean,
    val settingsOnly: Boolean,
    val ask: () -> Unit,
    val settings: Intent,
)

/**
 * The local network for a screen that is about it - Remotes and Nearby, and nothing else.
 *
 * Opening one of them is asking to look around the network, so the permission is asked for then,
 * once per visit, with the platform's ordinary prompt. Never again on its own: not on a
 * recomposition, not on a return to the app. A refusal is answered by the screen offering it,
 * and then by Settings once Android no longer will. It is READ again on every return, so allowing
 * it in Settings shows here without reopening the screen. See [LocalNetwork] for
 * why it is never asked for anywhere else.
 *
 * @param onGranted called when it goes from refused to allowed while the screen is up: what the
 *   screen could not do without it - a share that was not announced - it does now.
 */
@Composable
fun rememberLocalNetwork(onGranted: () -> Unit = {}): LocalNetworkAccess {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val granted by rememberUpdatedState(onGranted)
    var allowed by remember { mutableStateOf(LocalNetwork.allowed(context)) }
    var refused by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }

    fun settle(now: Boolean) {
        val was = allowed
        allowed = now
        if (now && !was) granted()
    }

    DisposableEffect(owner) {
        val watch = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) settle(LocalNetwork.allowed(context))
        }
        owner.lifecycle.addObserver(watch)
        onDispose { owner.lifecycle.removeObserver(watch) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refused = !ok
        settle(ok || LocalNetwork.allowed(context))
    }
    LaunchedEffect(Unit) {
        if (!allowed && !asked) {
            asked = true
            launcher.launch(LocalNetwork.PERMISSION)
        }
    }
    val settings = remember(context) {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
    val activity = remember(context) { context.activity() }
    val settingsOnly = refused && !allowed && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, LocalNetwork.PERMISSION)
    return LocalNetworkAccess(allowed, refused, settingsOnly, { launcher.launch(LocalNetwork.PERMISSION) }, settings)
}

/** The activity under [this], through any wrappers; null where there is none. */
private fun Context.activity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

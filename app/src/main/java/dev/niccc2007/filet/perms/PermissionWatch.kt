package dev.niccc2007.filet.perms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * What Filet actually holds right now, kept current for free.
 *
 * ## The watcher does not poll, and that is not a shortcut
 *
 * The ask was for a watcher that re-checks "every 1 second or every time a perm is fixed",
 * at no cost. Those two are the same thing here, and the second one is free:
 *
 * **A permission can only change while Filet is not the app you are looking at.** Every
 * grant and every revoke happens either in a system dialogue or on a Settings page, and both
 * of those stop this activity. So the moment that can possibly matter is `ON_RESUME`, and
 * re-reading there catches every change with no timer running at all.
 *
 * A one-second poll would do the same job, a thousand times an hour, and still only ever
 * notice on the tick after a resume. The exception is a change made over adb while the screen
 * is up, which is not a path anybody using the app is on.
 *
 * ## Reading, not remembering
 *
 * Nothing here caches to disk. A stored "granted" that the system has since revoked is worse
 * than no record at all, and every read below is a cheap in-process call.
 */

/** The ids currently granted, re-read whenever the app comes back to the front. */
@Composable
fun rememberGrantedPermissions(): Set<String> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(grantedNow(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = grantedNow(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return granted
}

/**
 * One pass over the catalogue.
 *
 * The three special cases do not go through `checkSelfPermission` at all, and asking it about
 * them returns DENIED however granted they are - which is exactly the bug that had Settings
 * offering "Grant all files" to somebody who already had it.
 */
fun grantedNow(context: Context): Set<String> {
    val sdk = Build.VERSION.SDK_INT
    return Perms.shownOn(sdk).filter { entry ->
        when (entry.id) {
            // Asked of the provider layer, not of `Environment` directly. R3: storage APIs
            // live behind a provider, and `StorageAccess` already owns this question for the
            // request side - two answers to "do we have all-files access" is one too many.
            Perms.ALL_FILES ->
                dev.niccc2007.filet.vfs.provider.StorageAccess.hasAllFiles()

            Perms.INSTALL_APPS -> runCatching {
                context.packageManager.canRequestPackageInstalls()
            }.getOrDefault(false)

            Perms.BATTERY -> runCatching {
                context.getSystemService(PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(context.packageName) == true
            }.getOrDefault(false)

            else -> when (entry.kind) {
                // Install-time permissions are in the manifest and therefore held. Asking the
                // package manager would also answer yes; this skips the call.
                Perms.Kind.INSTALL -> true
                else -> ContextCompat.checkSelfPermission(context, entry.id) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
    }.map { it.id }.toSet()
}

/**
 * The Settings page that grants one special permission, or null when there is none.
 *
 * Each carries a fallback without the package URI. Some OEM builds refuse the per-app form
 * and the generic list is better than a dead button - which is the same rule the all-files
 * request already follows elsewhere in the app.
 */
fun grantIntentFor(context: Context, id: String): List<Intent> {
    val pkg = Uri.parse("package:" + context.packageName)
    return when (id) {
        Perms.ALL_FILES ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) emptyList()
            else listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )

        Perms.INSTALL_APPS ->
            listOf(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg),
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            )

        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS shows a yes/no dialogue and needs a
        // permission in the manifest that Play reviews; the settings LIST needs neither and
        // is one more tap. Worth the tap not to carry a reviewed permission for a setting
        // most people never touch.
        Perms.BATTERY ->
            listOf(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
            )

        else -> emptyList()
    }
}

/** Filet's own page in system Settings: the only place anything can be revoked. */
fun appSettingsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:" + context.packageName),
    )

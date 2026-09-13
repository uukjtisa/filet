package dev.niccc2007.filet.vfs.provider

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Whether this process may actually read storage, and how to ask.
 *
 * Lives beside the provider rather than in the UI layer: a storage permission is a storage
 * concern, and this keeps `Environment` inside the one package PLAN.md R3 allows it in.
 *
 * SEARCH.md §8.2 rule 5 — always ask the OS, never a cached boolean.
 */
object StorageAccess {

    /** Full shared-volume access. The permission that makes a file manager a file manager. */
    fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true

    /** The ordinary runtime permission, which is all that exists below R. */
    fun legacyPermission(): String? = when {
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> "android.permission.WRITE_EXTERNAL_STORAGE"
        Build.VERSION.SDK_INT <= 32 -> "android.permission.READ_EXTERNAL_STORAGE"
        else -> null   // 33+ replaced it with the granular media permissions
    }

    fun hasLegacy(context: Context): Boolean {
        val p = legacyPermission() ?: return true
        return context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Intents to try, in order, for the all-files grant.
     *
     * Three of them, because **the per-app screen does not exist on every OEM build**. EMUI in
     * particular can leave `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` unresolvable, and
     * firing an unresolvable intent throws `ActivityNotFoundException` — which the user
     * experiences as the app crashing when they tap Grant.
     */
    fun requestIntents(context: Context): List<Intent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return listOf(appDetails(context))
        }
        return listOf(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + context.packageName),
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            appDetails(context),
        )
    }

    /** The first intent above that something on this device can actually handle. */
    fun resolvableRequestIntent(context: Context): Intent? =
        requestIntents(context).firstOrNull { it.resolveActivity(context.packageManager) != null }

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
}

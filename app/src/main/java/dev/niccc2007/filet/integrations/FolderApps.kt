package dev.niccc2007.filet.integrations

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * The app a folder added through the system's folder picker comes from - Termux, Google Drive, a
 * sync app, another file manager - found from the folder itself.
 *
 * A folder lent by an app arrives as a tree address whose authority is that app's document
 * provider (`content://com.termux.documents/tree/...`), and the system says which installed app
 * answers for an authority. So the app is found the same way however the folder was added - from
 * Home's Termux card, from Settings › Storage, from any picker - and for any app, not a list of
 * the ones somebody thought of.
 *
 * Bug identified here: the Termux card asked whether Termux's home was already added by parsing a
 * VOLUME PATH as a URI. A volume's path is the tree address percent-encoded with a slash in front
 * (`/content%3A%2F%2Fcom.termux.documents...`), which parses as a relative URI with no authority,
 * so the answer was always no and the card offering to add Termux stayed after Termux had been
 * added. The fallback compared the folder's name to "Termux", and the folder is called "home".
 * Settings › Storage had no detection at all and listed it as a folder called "home".
 */
object FolderApps {

    /** An app that lends folders: what to call it and where it lives. */
    data class App(val packageName: String, val label: String, val authority: String)

    private val apps = ConcurrentHashMap<String, Any>()
    private val icons = ConcurrentHashMap<String, Any>()
    private val NONE = Any()

    /** The installed app answering for [authority], or null when none does any more. */
    fun of(context: Context, authority: String): App? {
        val known = apps[authority]
        if (known != null) return known as? App
        val found = runCatching {
            val pm = context.packageManager
            val info = pm.resolveContentProvider(authority, 0) ?: return@runCatching null
            val label = info.applicationInfo?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: info.packageName
            App(info.packageName, label, authority)
        }.getOrNull()
        apps[authority] = found ?: NONE
        return found
    }

    /**
     * The app's own launcher icon, drawn at [px] square - read from the installed app each time
     * Filet starts, so no other app's artwork is ever stored. Null when it has none to give.
     */
    fun icon(context: Context, packageName: String, px: Int): ImageBitmap? {
        val key = "$packageName@$px"
        val known = icons[key]
        if (known != null) return known as? ImageBitmap
        val drawn = runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(px, px).asImageBitmap()
        }.getOrNull()
        icons[key] = drawn ?: NONE
        return drawn
    }

    /** The app's own screen, for "Open in Termux". Null when it has no screen to open. */
    fun launch(context: Context, app: App): Intent? =
        context.packageManager.getLaunchIntentForPackage(app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** An app installed or removed since: its answer is asked again. */
    fun forget() {
        apps.clear()
        icons.clear()
    }
}

/**
 * What a folder lent by an app is called. Pure.
 *
 * The app's name leads and the folder's follows - "Termux · home", "Google Drive · Photos" -
 * because the folder's own name says nothing about where it lives: every Termux, every Linux
 * container and half the sync apps call theirs "home". A folder whose name IS the app's, or that
 * has none, is called by the app alone rather than "Termux · Termux".
 */
object AppFolderNames {

    /** The name the system gives it, which Rename starts from. */
    fun system(app: String?, folder: String): String {
        val f = folder.trim()
        return when {
            app.isNullOrBlank() -> f.ifEmpty { "Granted folder" }
            f.isEmpty() || f.equals(app.trim(), ignoreCase = true) -> app.trim()
            else -> "${app.trim()} · $f"
        }
    }

    /**
     * The big line on its Home card, under the app's name in the heading: a name it was given,
     * else the folder's own, else the app's.
     */
    fun cardValue(app: String, folder: String, label: String, systemLabel: String): String = when {
        label != systemLabel -> label
        folder.isNotBlank() -> folder.trim()
        else -> app
    }

    /** What the card says under it: what is in it, and whether Filet may change it. */
    fun caption(items: Int?, writable: Boolean): String {
        val access = if (writable) "read and write" else "read only"
        return when (items) {
            null -> access
            1 -> "1 item · $access"
            else -> "$items items · $access"
        }
    }
}

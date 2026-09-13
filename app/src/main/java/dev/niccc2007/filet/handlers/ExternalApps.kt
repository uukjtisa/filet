package dev.niccc2007.filet.handlers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * One app on the device that can open a file, named precisely enough to launch again.
 *
 * The package alone is not enough — an app may expose several viewer activities and the
 * launcher entry is often not one of them — so the activity is part of the identity.
 */
data class ExternalApp(
    val packageName: String,
    val activity: String,
    val label: String,
) {
    val component: ComponentName get() = ComponentName(packageName, activity)

    /** What a settings row shows. The package is included because two apps can share a name. */
    val detail: String get() = packageName
}

/**
 * Which installed app opens what, and how Filet learns the answer.
 *
 * ## Why Filet lists the apps itself
 *
 * `Intent.createChooser` is the obvious thing and it is a dead end for this feature: Android
 * runs that chooser, and **it never tells the caller what the user picked**. The system
 * remembers the choice only if the user taps "Always", and even then only for that exact
 * mime type, in a table Filet cannot read, edit or show. So "hand to another app" could be
 * set as a default and still ask every single time — which is the bug this fixes.
 *
 * Resolving the candidates here and launching the component directly costs one
 * `queryIntentActivities` and buys the whole feature: Filet knows the answer, can store it
 * per extension, can show it in Settings, and can pre-set it before the file is ever opened.
 */
object ExternalApps {

    /**
     * Apps that can open [mime], best first.
     *
     * Filet itself is filtered out. Offering "open with Filet" inside Filet is a loop, and on
     * a device where Filet is the default handler it would otherwise be the top entry.
     */
    fun candidates(context: Context, mime: String): List<ExternalApp> {
        val pm = context.packageManager
        val own = context.packageName

        // An unknown type is not a filter. Querying `*/*` matches every app with a wildcard
        // VIEW filter - Certificate Installer, HTML Viewer, Manage SIM contacts - and putting
        // those under "apps that handle this type" is a list that is both useless and a lie.
        // Empty here means the sheet says nothing claims it and offers the all-apps tier,
        // which is the honest version of the same offer.
        if (mime == ANY_TYPE) return emptyList()

        // Two queries, and the second is not redundant. Asking with a content: URI is the
        // honest question - it is exactly the intent Filet will fire - but an activity whose
        // filter names a scheme Filet does not use drops out of it. The type-only query is
        // the wider net, and merging keeps the URI-matched ones first, which is the order
        // that puts the app most likely to actually work at the top.
        val withUri = runCatching {
            queryAll(pm, Intent(Intent.ACTION_VIEW).setDataAndType(SAMPLE, mime))
        }.getOrDefault(emptyList())
        val typeOnly = runCatching {
            queryAll(pm, Intent(Intent.ACTION_VIEW).setType(mime))
        }.getOrDefault(emptyList())

        return (withUri + typeOnly)
            .asSequence()
            .filter { it.activityInfo != null && it.activityInfo.packageName != own }
            .map {
                ExternalApp(
                    packageName = it.activityInfo.packageName,
                    activity = it.activityInfo.name,
                    label = it.loadLabel(pm).toString(),
                )
            }
            // Distinct by component, then sorted by label: the resolver's own order puts the
            // system default first, which is useful, but duplicates are common when an app
            // registers several aliases.
            .distinctBy { it.packageName to it.activity }
            .toList()
    }

    /**
     * Every app with a launcher entry, minus the ones already offered as declared handlers.
     *
     * The declared list is the *correct* answer and it is also an incomplete one. An app can
     * open a format perfectly and still not appear: it may declare only `file://`, or a
     * vendor mime nobody else uses, or nothing at all and rely on being picked manually. Those
     * look uninstalled, which is the complaint this answers.
     *
     * Launching one of these can fail, and that is the honest trade. It is offered as a second
     * tier with its declared types shown, so the difference between "this will work" and "try
     * it and see" is on screen rather than implied.
     */
    fun allLaunchable(context: Context, exclude: List<ExternalApp>): List<ExternalApp> {
        val pm = context.packageManager
        val own = context.packageName
        val taken = exclude.mapTo(HashSet()) { it.packageName }
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching { queryAll(pm, main) }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { it.activityInfo != null }
            .filter { it.activityInfo.packageName != own && it.activityInfo.packageName !in taken }
            .map {
                ExternalApp(
                    packageName = it.activityInfo.packageName,
                    activity = it.activityInfo.name,
                    label = it.loadLabel(pm).toString(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * What each package says it can open, among the types worth probing.
     *
     * Android does not expose another app's intent filters, so this asks the question from
     * the other side: resolve each type once and record who answered. Fourteen queries for the
     * whole device, rather than one per app - the naive shape is a query per app per type and
     * is slow enough to be felt.
     */
    fun declaredTypes(context: Context): Map<String, List<String>> {
        val pm = context.packageManager
        val out = HashMap<String, MutableList<String>>()
        for ((label, mime) in PROBE) {
            val hits = runCatching {
                queryAll(pm, Intent(Intent.ACTION_VIEW).setType(mime))
            }.getOrDefault(emptyList())
            for (h in hits) {
                val pkg = h.activityInfo?.packageName ?: continue
                val list = out.getOrPut(pkg) { ArrayList() }
                if (label !in list) list += label
            }
        }
        return out
    }

    /**
     * The types a second-tier row is labelled with.
     *
     * Deliberately coarse - "video", not `video/x-matroska`. The point of the line is to tell
     * a media player apart from a calculator at a glance, and a column of full mime strings
     * does that worse than one word does.
     */
    private val PROBE = listOf(
        "images" to "image/*",
        "video" to "video/*",
        "audio" to "audio/*",
        "text" to "text/plain",
        "PDF" to "application/pdf",
        "web" to "text/html",
        "zip" to "application/zip",
        "APKs" to "application/vnd.android.package-archive",
        "docs" to "application/msword",
        "sheets" to "application/vnd.ms-excel",
        "slides" to "application/vnd.ms-powerpoint",
        "ebooks" to "application/epub+zip",
        "JSON" to "application/json",
        "anything" to "*/*",
    )

    /** True when this app is still installed and still handles files. */
    fun resolves(context: Context, app: ExternalApp): Boolean = runCatching {
        context.packageManager.getActivityInfo(app.component, 0) != null
    }.getOrDefault(false)

    /** The app's icon, for the picker and the settings row. Null when it cannot be loaded. */
    fun icon(context: Context, app: ExternalApp, px: Int = 96): ImageBitmap? = runCatching {
        val d = context.packageManager.getActivityIcon(app.component)
        if (d is BitmapDrawable && d.bitmap != null) {
            return@runCatching d.bitmap.asImageBitmap()
        }
        val w = if (d.intrinsicWidth > 0) px else px
        val bmp = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, w)
        d.draw(c)
        bmp.asImageBitmap()
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun queryAll(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(intent, 0)
        }

    /**
     * A stand-in URI for asking "who can open this type".
     *
     * The real content URI is minted per file and is not available when Settings asks the
     * question with no file in hand. Resolution keys on the scheme and the mime type, not on
     * the path, so a `content://` placeholder resolves the same set the real one will.
     */
    private val SAMPLE: Uri = Uri.parse("content://dev.niccc2007.filet.files/probe")
}

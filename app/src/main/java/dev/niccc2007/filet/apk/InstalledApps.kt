package dev.niccc2007.filet.apk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One installed app, and where its parts live on disk.
 *
 * @param splitPaths the extra APKs of a split install. Empty for an ordinary app. They are
 *   carried separately from [apkPath] because a split app's base APK **will not install on its
 *   own** - it is deliberately missing the density and ABI resources that live in the splits,
 *   so extracting only the base produces a file that looks complete and fails at install time.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val apkPath: String,
    val splitPaths: List<String>,
    val system: Boolean,
    val sizeBytes: Long,
) {
    val split: Boolean get() = splitPaths.isNotEmpty()
    val parts: Int get() = 1 + splitPaths.size
}

/**
 * Extracting the APK of something already installed.
 *
 * ## Why this is possible at all
 *
 * An installed app's APK sits in `/data/app/...` and is world-readable. Reading it needs no
 * root; what it needs is knowing the path, which `PackageManager` gives as `sourceDir` — and
 * knowing the app exists at all, which needs package visibility. See the manifest note.
 */
object InstalledApps {

    /** Where extracted packages are written, relative to shared storage. */
    const val FOLDER = "Filet/Extracted-Apks"

    /**
     * Every app the platform will admit to, newest label order.
     *
     * System apps are included but flagged rather than hidden: extracting a system APK is a
     * legitimate thing to want and hiding them would be deciding for somebody. The flag is
     * there so the list can be filtered by a reader who does not want four hundred of them.
     */
    suspend fun list(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrElse { emptyList() }

        packages.mapNotNull { info ->
            val source = info.sourceDir ?: return@mapNotNull null
            val pkg = runCatching { pm.getPackageInfo(info.packageName, 0) }.getOrNull()
            val splits = info.splitSourceDirs?.toList().orEmpty()
            InstalledApp(
                packageName = info.packageName,
                label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(info.packageName),
                versionName = pkg?.versionName ?: "",
                versionCode = pkg?.longVersionCode ?: 0L,
                apkPath = source,
                splitPaths = splits,
                system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                sizeBytes = (listOf(source) + splits).sumOf { runCatching { File(it).length() }.getOrDefault(0L) },
            )
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * The file name an extracted package is written under.
     *
     * Kept pure and tested because a name that collides silently overwrites the previous
     * extraction, and a name with a separator in it writes outside the folder. Both are quiet.
     */
    fun fileNameFor(app: InstalledApp): String {
        val version = app.versionName.ifBlank { app.versionCode.toString() }
        return sanitise("${app.label}-$version") + ".apk"
    }

    /** A split app gets a folder of its own, because its parts only mean anything together. */
    fun folderNameFor(app: InstalledApp): String {
        val version = app.versionName.ifBlank { app.versionCode.toString() }
        return sanitise("${app.label}-$version")
    }

    /**
     * Strip anything that is not a filename.
     *
     * An app's label is arbitrary text chosen by its author: it can hold a slash, a newline, a
     * null, or be entirely emoji. A slash writes outside the destination folder, and an empty
     * result writes a file called `.apk` that the next extraction overwrites.
     */
    fun sanitise(raw: String): String {
        val cleaned = raw.asSequence()
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ' ') it else '_' }
            .joinToString("")
            .trim()
            .trim('.')
            .replace(Regex(" +"), " ")
            .take(80)
        return cleaned.ifBlank { "package" }
    }
}

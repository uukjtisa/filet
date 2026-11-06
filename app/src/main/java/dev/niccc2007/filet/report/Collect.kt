package dev.niccc2007.filet.report

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import dev.niccc2007.filet.BuildConfig
import dev.niccc2007.filet.data.FiletStore
import dev.niccc2007.filet.log.FiletLog
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The parts of a report that come off the phone: its facts, and pictures made small enough.
 *
 * Nothing in here sends anything or decides anything. It reads, and the form shows what was read
 * before any of it can go anywhere.
 */
object Collect {

    fun device(context: Context): ReportForm.Device {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = am?.let { ActivityManager.MemoryInfo().also { info -> it.getMemoryInfo(info) } }
        val pm = context.getSystemService(PowerManager::class.java)
        val dm = context.resources.displayMetrics
        return ReportForm.Device(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            appVersion = BuildConfig.VERSION_NAME,
            appCode = BuildConfig.VERSION_CODE,
            flavour = BuildConfig.FLAVOR,
            ramMb = mem?.totalMem?.let { it / (1024 * 1024) },
            lowRam = am?.isLowRamDevice,
            thermal = if (Build.VERSION.SDK_INT >= 29 && pm != null) thermalName(pm.currentThermalStatus) else null,
            batterySaver = pm?.isPowerSaveMode,
            screen = "${dm.widthPixels}x${dm.heightPixels} at ${dm.densityDpi} dpi",
            locale = context.resources.configuration.locales.get(0)?.toLanguageTag(),
        )
    }

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    /**
     * A picked picture, shrunk to a size a report can carry, and written to this screen's cache.
     *
     * Written to a file rather than held only in memory: a picker's address stops being readable
     * once the process that was given it dies, and the form has to come back intact after Android
     * kills it in the background. The file is the picture; the form keeps the path.
     *
     * A screenshot is a lossless PNG of several megabytes. Re-encoded as JPEG at no more than
     * [EDGE] pixels on its long side it keeps every word legible and is a few hundred kilobytes,
     * which is what lets five of them fit in one report. Metadata does not survive the
     * re-encode, which is also the point: a photo's location is not something a bug report
     * should publish.
     */
    fun image(context: Context, uri: Uri, index: Int): Result<String> = runCatching {
        bind(context)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        require(long > 0) { "That file is not a picture Filet can read." }
        var sample = 1
        while (long / (sample * 2) >= EDGE) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("That picture could not be opened.")
        val scale = EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        val sized = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, 85, out)
        picture(folder(context), index, System.currentTimeMillis()).apply { writeBytes(out.toByteArray()) }.absolutePath
    }

    // ── the cached pictures, by path ──
    //
    // The form holds pictures as paths in its saved state. Everything that touches the files
    // behind them is here, so the one file in this package allowed a `java.io.File` is this one,
    // and every path it is handed was made by [image] inside this screen's own cache.

    /** Size in bytes, or null when the picture is gone - a cache cleared under the form. */
    fun sizeOf(path: String): Long? = File(path).takeIf { it.isFile && owns(bound, it) }?.length()

    fun read(path: String): ByteArray? = File(path).takeIf { it.isFile && owns(bound, it) }?.readBytes()

    fun nameOf(path: String): String = path.substringAfterLast('/')

    fun delete(path: String) {
        File(path).takeIf { owns(bound, it) }?.delete()
    }

    /**
     * The one folder the pictures live in: `report`, in the app's private cache.
     *
     * Private because a screenshot for a report is nobody else's until Send, which is also the
     * premise this file's exemption from R3 rests on. Android may empty it, and the form
     * survives that: a picture gone from under it reads as gone.
     *
     * Bug identified here (issue #22): 0.2.0 moved the pictures into Filet's own Reports folder,
     * which is shared storage whenever Filet has all-files access, and left the check in [owns]
     * testing the old place. Every picture failed it. Each one was left out of the report
     * without a word, its size read as nothing, and removing it removed nothing, so it stayed on
     * shared storage. Writing, reading, deleting and clearing all take the folder from here
     * now, so where a picture is put and where it is looked for cannot part again.
     */
    internal fun folder(cache: File): File = File(cache, FOLDER)

    private fun folder(context: Context): File = folder(context.cacheDir).apply { runCatching { mkdirs() } }

    /** Where picture [index], made at [now], is written. The name is what [owns] recognises. */
    internal fun picture(folder: File, index: Int, now: Long): File = File(folder, "$PREFIX$now-$index.jpg")

    /**
     * Whether [f] is one of the pictures in [folder]: directly inside it, named as [picture]
     * names them.
     *
     * Paths come back from saved state, which is the app's own, but the check costs nothing and
     * means these helpers cannot be pointed at any other file - not a crash report beside it,
     * not anything a `..` reaches.
     */
    internal fun owns(folder: File?, f: File): Boolean {
        val home = folder?.let { runCatching { it.canonicalFile }.getOrNull() } ?: return false
        val real = runCatching { f.canonicalFile }.getOrNull() ?: return false
        return real.name.startsWith(PREFIX) && real.name.endsWith(".jpg") && real.parentFile == home
    }

    /** The pictures in [folder] that no form holds. [keep] is the paths the open form has. */
    internal fun orphans(folder: File, keep: Collection<String>): List<File> {
        val held = keep.mapNotNull { runCatching { File(it).canonicalFile }.getOrNull() }.toSet()
        return folder.listFiles().orEmpty().filter { owns(folder, it) && it.canonicalFile !in held }
    }

    @Volatile
    private var bound: File? = null

    /** Called once by the form, so the helpers above know which cache is theirs. */
    fun bind(context: Context) {
        bound = folder(context)
    }

    /**
     * Pictures no form is holding: what a sent report leaves behind, and an abandoned one.
     *
     * The form calls it with its own pictures as [keep] when it opens, so a report that was
     * given up on does not keep its pictures until Android gets round to the cache, and with
     * nothing once a report is sent.
     */
    fun clearPictures(context: Context, keep: Collection<String> = emptyList()) {
        orphans(folder(context), keep).forEach { it.delete() }
    }

    // ── what 0.2.0 left in Filet/Reports ──

    /**
     * Whether [name] is a report picture 0.2.0 wrote into Filet's Reports folder: `picture-`,
     * the moment, the place on the form, `.jpg`. Exactly [picture]'s naming and nothing looser.
     *
     * Pure, and tested with its neighbours, because what it matches is deleted from a folder the
     * person can open, which holds crash reports and anything they put there themselves.
     */
    internal fun leftBy020(name: String, isFile: Boolean): Boolean = isFile && LEFT_BY_020.matches(name)

    /**
     * Deletes [leftBy020]'s files from [folder]: directly inside it, never below, nothing else.
     *
     * @return the ones that would not go.
     */
    internal fun sweep(folder: File): List<File> =
        folder.listFiles().orEmpty().filter { leftBy020(it.name, it.isFile) }.filterNot { it.delete() }

    /**
     * The pictures 0.2.0 left on shared storage, deleted once (issue #22).
     *
     * 0.2.0 wrote every report picture into Filet/Reports and could delete none of them, so each
     * screenshot from a report that was not sent, or was taken off one, is still there, where any
     * app with storage access can read it. Silent: somebody who never used the form has nothing
     * there, and somebody who did gains nothing from being shown a cleanup.
     *
     * The mark holds the folder that was swept, so a different one - all-files access granted
     * since, putting Filet on shared storage - is swept in its turn. A failure is logged and
     * leaves the mark unset, so the next opening tries again. Running twice is harmless anyway:
     * nothing writes that name into that folder any more.
     */
    fun sweepLeftovers(context: Context) {
        runCatching {
            val folder = FiletStore.dir(context, FiletStore.Area.REPORTS)
            val mark = File(context.noBackupFilesDir, SWEPT)
            if (mark.isFile && mark.readText() == folder.absolutePath) return
            val stuck = sweep(folder)
            if (stuck.isEmpty()) {
                mark.writeText(folder.absolutePath)
            } else {
                FiletLog.w(TAG, "${stuck.size} report picture(s) left by 0.2.0 would not delete")
            }
        }.onFailure { FiletLog.w(TAG, "clearing the report pictures 0.2.0 left failed", it) }
    }

    private val LEFT_BY_020 = Regex("picture-\\d+-\\d+\\.jpg")
    private const val SWEPT = "report-pictures-swept"
    private const val TAG = "Report"
    private const val FOLDER = "report"
    private const val PREFIX = "picture-"
    private const val EDGE = 1600
}

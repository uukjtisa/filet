package dev.niccc2007.filet.report

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import dev.niccc2007.filet.BuildConfig
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
        val dir = File(context.cacheDir, "report").apply { mkdirs() }
        File(dir, "picture-${System.currentTimeMillis()}-$index.jpg").apply { writeBytes(out.toByteArray()) }.absolutePath
    }

    // ── the cached pictures, by path ──
    //
    // The form holds pictures as paths in its saved state. Everything that touches the files
    // behind them is here, so the one file in this package allowed a `java.io.File` is this one,
    // and every path it is handed was made by [image] inside this screen's own cache.

    /** Size in bytes, or null when the picture is gone - a cache cleared under the form. */
    fun sizeOf(path: String): Long? = File(path).takeIf { it.isFile && inCache(it) }?.length()

    fun read(path: String): ByteArray? = File(path).takeIf { it.isFile && inCache(it) }?.readBytes()

    fun nameOf(path: String): String = path.substringAfterLast('/')

    fun delete(path: String) {
        File(path).takeIf { inCache(it) }?.delete()
    }

    /**
     * Whether a path is inside this screen's cache folder.
     *
     * Paths come back from saved state, which is the app's own, but the check costs nothing and
     * means these helpers cannot be pointed at any other file.
     */
    private fun inCache(f: File): Boolean = f.parentFile?.name == "report" &&
        f.parentFile?.parentFile?.canonicalPath == cacheRoot?.canonicalPath

    @Volatile
    private var cacheRoot: File? = null

    /** Called once by the form, so the helpers above know which cache is theirs. */
    fun bind(context: Context) {
        cacheRoot = context.cacheDir
    }

    /** Pictures left behind by a report that was sent or abandoned. */
    fun clearPictures(context: Context) {
        File(context.cacheDir, "report").listFiles()?.forEach { it.delete() }
    }

    private const val EDGE = 1600
}

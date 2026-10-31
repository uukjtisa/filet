package dev.niccc2007.filet.data

import android.content.Context
import dev.niccc2007.filet.log.FiletLog
import java.io.File

/**
 * A log that survives a phone which will not give you logcat, now a branch of [FiletLog].
 *
 * Huawei and Honor devices drop application logging unless it is switched on through a hidden
 * dialer menu, so `adb logcat` on one of them returns nothing at all for this app. This writes
 * to the app's own files directory as well, where `adb shell run-as` can read it back whatever
 * the vendor has decided about logs.
 *
 * Every line also goes to the session log as DEBUG under `diag:<tag>`, so a release build with
 * detailed logging on gets the same lines in the file a user can send - the private file stays
 * debug-only. Two faults fixed on the way:
 *
 * - The private file used to be appended on the CALLER's thread, which for `BeatPulse` and
 *   `TrackPcm` is the playback path - a file open, write and close per line. It is written on
 *   the log thread now.
 * - `log(tag, line)` formats its line before it can decide to drop it. The lambda form,
 *   `log(tag) { "..." }`, builds nothing when neither destination would take it, and is the form
 *   for anything called repeatedly.
 */
object Diag {

    @Volatile
    private var file: File? = null

    fun start(context: Context) {
        try {
            if (!dev.niccc2007.filet.BuildConfig.DEBUG) return
            // Beside the crash reports rather than in app-private storage: the whole point
            // of this file is to be read by somebody who is not the app, on a phone whose
            // vendor drops logcat for this package entirely.
            val f = File(FiletStore.dir(context, FiletStore.Area.REPORTS), "diag.log")
            runCatching { if (f.exists() && f.length() > CAP) f.delete() }
            file = f
        } catch (_: Throwable) {
        }
    }

    /** Whether a line would go anywhere. Public because the inline form reads it. */
    val wanted: Boolean get() = file != null || FiletLog.verbose

    fun log(tag: String, line: String) {
        if (!wanted) return
        write(tag, line)
    }

    inline fun log(tag: String, line: () -> String) {
        if (!wanted) return
        write(tag, try { line() } catch (t: Throwable) { "‹line could not be built: ${t.javaClass.simpleName}›" })
    }

    @PublishedApi
    internal fun write(tag: String, line: String) {
        FiletLog.d("diag:$tag", line)
        val f = file ?: return
        FiletLog.onLogThread {
            f.appendText("${System.currentTimeMillis() % 1_000_000} $tag $line\n")
            if (f.length() > CAP) f.writeText("(trimmed)\n")
        }
    }

    private const val CAP = 400_000L
}

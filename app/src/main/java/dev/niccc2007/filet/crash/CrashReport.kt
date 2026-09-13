package dev.niccc2007.filet.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import dev.niccc2007.filet.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a crash leaves behind.
 *
 * Filet has no analytics and will not get any: a file manager is the app with the most
 * sensitive strings on the device, and "we only send stack traces" stops being true the first
 * time a path ends up in one. So a crash goes to **the screen and a local file**, and the
 * user decides whether to send it anywhere.
 *
 * Reports live in `crash/` under app-private storage, newest first, capped at [KEEP]. They
 * are readable from About, which is also how a bug report gets filed with something useful in
 * it instead of "it crashed".
 */
object CrashReport {

    private const val KEEP = 10
    private const val DIR = "crash"

    /**
     * The visible copy, on the shared volume.
     *
     * App-private storage is the safe place for a crash report and it is also a place nobody
     * can reach without adb. A dotted folder keeps it out of the gallery scanner and out of
     * the way in a listing, while still being somewhere you can open, read and attach to a
     * bug report from the phone itself.
     *
     * Best effort on purpose. Without all-files access this write fails, and a crash reporter
     * that throws while reporting a crash is worse than one that quietly keeps the private
     * copy it already has.
     */
    private const val PUBLIC_DIR = "/storage/emulated/0/.filet_logs"
    private const val PUBLIC_KEEP = 30

    /** A report, as it is shown and as it is stored. */
    data class Report(val file: File, val at: Long, val headline: String, val text: String)

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = compose(context, thread, error)
                write(context, text)
                context.startActivity(
                    Intent(context, CrashActivity::class.java)
                        .setAction(context.packageName + ".CRASH")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(EXTRA_REPORT, text)
                )
            } catch (secondary: Throwable) {
                // The handler itself failing must not replace the original crash with a
                // mystery. Print both and let the previous handler do whatever it did.
                secondary.printStackTrace()
                previous?.uncaughtException(thread, error)
            } finally {
                // Do not hand back to the platform handler as well: it would show the system
                // "app has stopped" dialog on top of ours.
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun compose(context: Context, thread: Thread, error: Throwable): String = buildString {
        appendLine("Filet crash report")
        appendLine(STAMP.format(Date()))
        appendLine()
        appendLine("app:      ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        appendLine("device:   ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android:  ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("abi:      ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("thread:   ${thread.name}")
        appendLine()
        appendLine(error.stackTraceToString())
    }

    private fun write(context: Context, text: String) {
        val stamp = System.currentTimeMillis()
        runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            File(dir, "crash-$stamp.txt").writeText(text)
            prune(dir, KEEP)
        }
        // Second, and independently: one failing must not take the other with it.
        runCatching {
            val dir = File(PUBLIC_DIR, DIR).apply { mkdirs() }
            File(dir, "crash-" + FILE_STAMP.format(Date(stamp)) + ".txt").writeText(text)
            prune(dir, PUBLIC_KEEP)
        }
    }

    /** Where the visible reports go, for the About screen to name. */
    fun publicDir(): File = File(PUBLIC_DIR, DIR)

    /**
     * A line in the running log, for things that are worth recording and are not crashes.
     *
     * Same folder, one file per day. Deliberately thin: this is for the handful of events that
     * explain a later crash report - an update installed, storage access revoked, a script
     * denied - and not a debug firehose. A log nobody reads is a log that costs writes and
     * buys nothing.
     */
    fun note(line: String) {
        runCatching {
            val dir = File(PUBLIC_DIR).apply { mkdirs() }
            File(dir, "filet-" + DAY_STAMP.format(Date()) + ".log")
                .appendText(STAMP.format(Date()) + "  " + line + "\n")
        }
    }

    private fun prune(dir: File, keep: Int) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keep).forEach { runCatching { it.delete() } }
    }

    fun recent(context: Context): List<Report> {
        val dir = File(context.filesDir, DIR)
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return emptyList()
        return files.mapNotNull { f ->
            val text = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
            Report(
                file = f,
                at = f.lastModified(),
                // The first line of the trace is what identifies the crash; everything above
                // it is the same for every report.
                headline = text.lineSequence()
                    .firstOrNull { it.contains("Exception") || it.contains("Error") }
                    ?.trim()
                    ?.take(160)
                    ?: f.name,
                text = text,
            )
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, DIR).listFiles()?.forEach { runCatching { it.delete() } }
    }

    const val EXTRA_REPORT = "filet.crashReport"
    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // Filename stamps, not display ones. Colons are legal on ext4 and not on every volume a
    // report might be copied to, so the filename forms avoid them.
    private val FILE_STAMP = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val DAY_STAMP = SimpleDateFormat("yyyy-MM-dd", Locale.US)
}

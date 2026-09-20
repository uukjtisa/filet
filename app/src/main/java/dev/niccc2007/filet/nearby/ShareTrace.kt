package dev.niccc2007.filet.nearby

import android.content.Context
import dev.niccc2007.filet.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A few lines about what starting and stopping a share actually did, in debug builds only.
 *
 * This exists because `Log` is not readable on every device. Some manufacturer builds drop an
 * app's own logcat output while still carrying the system's, so a fault that reproduces every
 * time can leave no trace anywhere - which is a slow way to debug something that takes one
 * second to provoke.
 *
 * Deliberately small and deliberately not shipped: it writes to the app's own external files
 * directory, holds the last [LIMIT] lines, and compiles to nothing in a release build. It
 * records decisions, never anything about the files being shared.
 */
object ShareTrace {

    private const val LIMIT = 400
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var file: File? = null

    fun attach(context: Context) {
        if (!BuildConfig.DEBUG) return
        file = runCatching {
            File(context.getExternalFilesDir(null), "share-trace.txt")
        }.getOrNull()
    }

    /**
     * @param text built lazily, so a release build does not assemble a message it throws away.
     */
    fun line(text: () -> String) {
        if (!BuildConfig.DEBUG) return
        val f = file ?: return
        runCatching {
            synchronized(this) {
                f.appendText("${stamp.format(Date())}  ${text()}\n")
                // Rewritten rather than rotated: this is a few hundred lines, and a second
                // file to go and find is friction at exactly the wrong moment.
                val lines = f.readLines()
                if (lines.size > LIMIT) f.writeText(lines.takeLast(LIMIT).joinToString("\n") + "\n")
            }
        }
    }
}

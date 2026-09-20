package dev.niccc2007.filet.nearby

import android.content.Context
import dev.niccc2007.filet.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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
 *
 * ## Bug identified here, and it was this file
 *
 * Reported as the start-sharing button feeling frozen, with the spinner barely visible. The
 * cause was the instrument, not the thing it was measuring.
 *
 * The first version did its writing on whichever thread called it, and the caller is a button.
 * Worse, it read the **whole file back on every single line** to decide whether to trim, and
 * rewrote the whole file whenever it was over. So one press ran, on the main thread: a storage
 * lookup, an append, a four-hundred-line read, and sometimes a four-hundred-line write - three
 * or four times over, because start() traces its decision as well as its outcome.
 *
 * That is also exactly why the spinner could not be seen. The flag that shows it was set after
 * the tracing, and the main thread never yielded in between, so the frame that would have drawn
 * the spinner did not run until the slow part was already finished.
 *
 * Two rules now, and they are the general ones rather than anything specific to tracing:
 *
 *  1. **A diagnostic may never be slower than the thing it watches.** Every file operation
 *     happens on this object's own single thread; the caller formats a string and returns.
 *  2. **Never read a file to find out something you already know.** The line count is held in
 *     memory, so trimming is a comparison rather than a read, and it only happens once every
 *     [SLACK] lines instead of on every one.
 */
object ShareTrace {

    private const val LIMIT = 400

    /**
     * How far over [LIMIT] the file is allowed to run before it is trimmed.
     *
     * Trimming rewrites the file, so doing it the moment the limit is passed means a rewrite
     * on every subsequent line for ever. Letting it overshoot turns that into one rewrite per
     * [SLACK] lines, and nobody reading a debug trace cares whether it holds 400 lines or 480.
     */
    private const val SLACK = 80

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * One thread, so writes stay ordered and no caller ever waits for one.
     *
     * A daemon thread: this must never be the reason the process stays alive.
     */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "filet-sharetrace").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    @Volatile private var file: File? = null

    /** Lines believed to be in the file. Only ever touched on [io]. */
    private var count = -1

    /**
     * Whether the file is far enough over the limit to be worth rewriting.
     *
     * Pure, because both ways of getting it wrong are quiet: never trim and the file grows
     * without bound, trim eagerly and every line costs a full rewrite - which is the fault
     * this file exists to document.
     */
    fun shouldTrim(count: Int, limit: Int = LIMIT, slack: Int = SLACK): Boolean =
        count > limit + slack

    /** How many lines survive a trim. Always the newest [limit]. */
    fun keepCount(limit: Int = LIMIT): Int = limit

    /**
     * Point the trace at a file.
     *
     * `getExternalFilesDir` reaches the storage manager and can create a directory, so it is
     * not something to do on a button press. Posted, like everything else here.
     */
    fun attach(context: Context) {
        if (!BuildConfig.DEBUG) return
        if (file != null) return
        val app = context.applicationContext
        io.execute {
            if (file != null) return@execute
            file = runCatching { File(app.getExternalFilesDir(null), "share-trace.txt") }.getOrNull()
            count = runCatching { file?.readLines()?.size ?: 0 }.getOrDefault(0)
        }
    }

    /**
     * @param text built on the calling thread, which is the only work the caller does. It is a
     *   string concatenation of values already in hand, so it is cheap - and it has to happen
     *   here rather than on [io], because the values it reads are the ones true *now*.
     */
    fun line(text: () -> String) {
        if (!BuildConfig.DEBUG) return
        val message = runCatching { text() }.getOrElse { "<trace threw: $it>" }
        val at = System.currentTimeMillis()
        io.execute {
            val f = file ?: return@execute
            runCatching {
                f.appendText("${stamp.format(Date(at))}  $message\n")
                if (count >= 0) count++
                if (shouldTrim(count)) {
                    val lines = f.readLines()
                    f.writeText(lines.takeLast(keepCount()).joinToString("\n") + "\n")
                    count = minOf(lines.size, keepCount())
                }
            }
        }
    }
}

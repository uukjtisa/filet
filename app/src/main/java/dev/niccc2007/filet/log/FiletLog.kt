package dev.niccc2007.filet.log

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One log file per run of the app, written where it can be read back.
 *
 * ## Why it exists
 *
 * Two of the faults that have been reported are ones nobody can diagnose from outside: a cold
 * start that takes twenty seconds, and the drive information on Home going blank as though the
 * app had lost its own device while still drawing. Both are intermittent, both are on a phone,
 * and neither leaves a trace. The answer to "what was it doing for those twenty seconds" has to
 * come from the app itself.
 *
 * ## A session, and what a session means
 *
 * **One session per process life.** [open] is idempotent: calling it again on a process that is
 * already logging returns to the same file rather than starting a second one. That is the point -
 * Android keeps a process alive in the background long after the last activity is gone, and the
 * next launch is then a continuation of that run, not a new one. A second file per return to the
 * app would scatter one session's story across a dozen of them, which is the opposite of what a
 * log is for. A genuinely cold boot gets a genuinely new file, because the old process is dead
 * and so is the object holding the handle.
 *
 * **Never rotated inside a run.** Splitting one session at a size threshold means the tail of a
 * crash lives in a different file from its cause, which makes a log worse than no log. Old
 * sessions are pruned by count instead - retention rather than rotation.
 *
 * ## Where
 *
 * `Filet/Logs` in shared storage, so the file can be opened in Filet itself and sent to somebody
 * without a cable or a debugger. A log kept in app-private storage is a log only a developer with
 * the device in hand can read.
 *
 * ## It must never be the reason something breaks
 *
 * Every public call swallows its own failures. A full disk, a revoked permission, a device that
 * will not give out shared storage - each of those is a reason to lose the log, never a reason to
 * fail the thing being logged. Writes go to a single background thread, so no caller ever waits
 * on the disk, and the file is flushed per line so an abrupt death still leaves what it had.
 */
object FiletLog {

    /** The verbosity switch. False still records warnings, failures and the session's shape. */
    @Volatile
    var verbose: Boolean = true

    private val opened = AtomicBoolean(false)

    @Volatile
    private var sink: File? = null

    @Volatile
    private var writer: java.io.Writer? = null

    @Volatile
    private var post: Handler? = null

    private val startedAt = System.currentTimeMillis()

    /** The file this run is writing to, for a screen that offers to open or share it. */
    val file: File? get() = sink

    /**
     * Begin this process's session, or carry on with the one already open.
     *
     * @return the file being written to, or null when logging could not start at all.
     */
    fun open(context: Context, verbose: Boolean): File? {
        this.verbose = verbose
        // compareAndSet, not a null check: two threads reaching a cold logger must not each make
        // a file. The loser returns the winner's.
        if (!opened.compareAndSet(false, true)) return sink
        return runCatching {
            val dir = logsDir(context)
            dir.mkdirs()
            prune(dir)
            val f = File(dir, nameFor(Date()))
            val thread = HandlerThread("filet-log").apply { priority = Thread.MIN_PRIORITY; start() }
            post = Handler(thread.looper)
            writer = java.io.BufferedWriter(java.io.FileWriter(f, true))
            sink = f
            header(context, f)
            f
        }.getOrElse {
            Log.w(TAG, "could not open a session log", it)
            opened.set(false)
            null
        }
    }

    /**
     * The name of a session file.
     *
     * Sortable first, readable second, and in that order on purpose. The logger this is modelled
     * on puts the month's NAME in the file name, which means its own retention has to sort by
     * modification time because "aug" sorts before "jul" - it says so in its own comment. Leading
     * with an ISO-ordered date and a 24-hour clock makes the name's order the real order, so a
     * directory listing, a sort by name and a sort by time all agree. The weekday still rides
     * along at the end, where it cannot affect ordering.
     */
    fun nameFor(at: Date): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss.SSS", Locale.US).format(at)
        val day = SimpleDateFormat("EEE", Locale.US).format(at).lowercase(Locale.US)
        return "filet_${stamp}_$day.log"
    }

    private fun logsDir(context: Context): File =
        File(Environment.getExternalStorageDirectory(), FOLDER).takeIf { it.parentFile != null }
            ?: File(context.filesDir, "logs")

    /**
     * Keep the newest [KEEP] sessions.
     *
     * Nothing pruned the reference implementation's logs and its folder reached 230 files, so
     * this is here from the start rather than after the same discovery.
     */
    private fun prune(dir: File) {
        runCatching {
            val files = dir.listFiles { f: File -> f.isFile && f.name.startsWith(PREFIX) }
                .orEmpty()
                .sortedByDescending { it.name }
            for (old in files.drop(KEEP)) runCatching { old.delete() }
        }
    }

    private fun header(context: Context, f: File) {
        val pkg = context.packageName
        val version = runCatching {
            context.packageManager.getPackageInfo(pkg, 0).versionName
        }.getOrNull().orEmpty()
        write(
            "=== Filet session log ===\n" +
                "started : " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + "\n" +
                "package : " + pkg + "\n" +
                "version : " + version + "\n" +
                "device  : " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                " (Android " + android.os.Build.VERSION.RELEASE + ", API " + android.os.Build.VERSION.SDK_INT + ")\n" +
                "verbose : " + verbose + "\n" +
                "file    : " + f.absolutePath + "\n" +
                "========================================\n",
        )
    }

    // ── writing ──

    fun d(tag: String, message: String) {
        if (!verbose) return
        line("DEBUG", tag, message)
    }

    fun i(tag: String, message: String) = line("INFO", tag, message)

    fun w(tag: String, message: String, t: Throwable? = null) = line("WARN", tag, message, t)

    fun e(tag: String, message: String, t: Throwable? = null) = line("ERROR", tag, message, t)

    /**
     * How long something took, written when it finishes.
     *
     * The shape the launch work needed: a cold start that is slow is slow somewhere specific, and
     * guessing where is how a morning gets spent. A span is recorded at INFO when it is over
     * [SLOW_MS] even with verbose off, because something taking a second is the kind of thing that
     * has to survive in a log from a user who never turned logging up.
     */
    inline fun <T> span(tag: String, what: String, block: () -> T): T {
        val began = System.nanoTime()
        try {
            return block()
        } finally {
            val ms = (System.nanoTime() - began) / 1_000_000
            mark(tag, what, ms)
        }
    }

    /** Record a measured duration. Public because [span] is inline and calls it. */
    fun mark(tag: String, what: String, ms: Long) {
        if (ms >= SLOW_MS) line("INFO", tag, "$what took ${ms}ms")
        else d(tag, "$what took ${ms}ms")
    }

    /** Milliseconds since this object was loaded, which is as close to process start as it gets. */
    fun sinceStart(): Long = System.currentTimeMillis() - startedAt

    private fun line(level: String, tag: String, message: String, t: Throwable? = null) {
        // Logcat always, so `adb logcat -s Filet` works whether or not a file was opened.
        when (level) {
            "ERROR" -> Log.e(TAG, "$tag: $message", t)
            "WARN" -> Log.w(TAG, "$tag: $message", t)
            "DEBUG" -> Log.d(TAG, "$tag: $message")
            else -> Log.i(TAG, "$tag: $message")
        }
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val at = sinceStart()
        val body = StringBuilder()
            .append(stamp).append(" | +").append(at).append("ms | ")
            .append(level.padEnd(5)).append(" | ").append(tag.padEnd(18)).append(" | ")
            .append(message).append('\n')
        if (t != null) body.append(Log.getStackTraceString(t)).append('\n')
        write(body.toString())
    }

    private fun write(text: String) {
        val h = post ?: return
        // Handed to the log thread rather than written here: a caller logging from the main thread
        // must not wait on storage, and the thing most worth logging is a surface that is already
        // too slow.
        h.post {
            runCatching {
                writer?.apply {
                    write(text)
                    flush()
                }
            }
        }
    }

    const val FOLDER = "Filet/Logs"

    /**
     * Where the verbosity switch is stored.
     *
     * Owned here rather than by the settings store, because the Application reads it before that
     * store exists - the session has to start at process start to be able to time the launch at
     * all - and the store reads it again to show the switch. One constant, one spelling.
     */
    const val PREF_VERBOSE = "logs.verbose"
    private const val PREFIX = "filet_"
    private const val TAG = "Filet"

    /** Sessions kept on disk. */
    const val KEEP = 20

    /** A span at or over this is worth recording even with verbose off. */
    const val SLOW_MS = 250L
}

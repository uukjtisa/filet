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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One log file per run of the app, written where it can be read back.
 *
 * ## Why it exists
 *
 * Some faults cannot be diagnosed from outside: a cold start that takes twenty seconds, drive
 * information vanishing from Home, a phone getting hot during playback, a battery drained by
 * the media scanner on a device nobody here has. All are intermittent and all are on a phone.
 * The answer to "what was it doing" has to come from the app itself, and has to be readable by
 * somebody who only has the file.
 *
 * ## What a run's file holds
 *
 * - **Lines** with a time, the time since process start, a level, a stable tag and the thread.
 *   A failure is followed by its cause chain, one line per link with each link's site, and the
 *   root cause's own frames in this app - see [Failure]. A warning or failure also says where it
 *   was logged from.
 * - **Span trees**: [span] nests, and the outermost span writes its whole tree with a duration
 *   on each node - see [Spans].
 * - **Counters**, every [FLUSH_MS]: how many times each counted thing ran in the window, how long
 *   it took in total, the average and the worst - see [Counters]. This is the always-on part,
 *   and it is what makes heat diagnosable without a line per frame.
 * - **Diagnostic dumps** on request: thermal state, battery, which threads spent the CPU, what
 *   is looping, services and notifications - see [Vitals] and [dump].
 *
 * ## Detailed logging, and what "off" costs
 *
 * [verbose] gates DEBUG lines and frame timing. Off, a DEBUG call written as `d(tag) { "..." }`
 * is one volatile read and a branch - the lambda is inlined and never invoked, so nothing is
 * allocated or formatted. The older `d(tag, "a" + b)` form still builds its string before it is
 * dropped; that is why the lazy form exists, and hot paths must use it. Counters are on in both
 * modes; they cost atomic adds, not lines.
 *
 * [detailForThisRun] turns detail on without saving the choice, for reproducing one problem.
 *
 * ## A session, and what a session means
 *
 * **One session per process life.** [open] is idempotent: calling it again on a process that is
 * already logging returns to the same file rather than starting a second one. Android keeps a
 * process alive in the background long after the last activity is gone, and the next launch is a
 * continuation of that run, not a new one. A genuinely cold boot gets a genuinely new file.
 *
 * **Never rotated inside a run.** Splitting one session at a size threshold puts the tail of a
 * crash in a different file from its cause. Old sessions are pruned instead - retention rather
 * than rotation.
 *
 * ## Bounded, so it cannot become the problem it measures
 *
 * - **Per run:** [SOFT_CAP] then [HARD_CAP]. Past the soft cap DEBUG and INFO are dropped and
 *   failures, counters and dumps still go in; past the hard cap nothing does. Each crossing
 *   writes one line saying so - see [Budget].
 * - **Across runs:** the newest [KEEP] runs, and no more than [MAX_TOTAL] bytes between them,
 *   oldest deleted first - see [RunFiles.toDelete].
 * - **In memory:** at most [MAX_QUEUE] lines waiting for the disk. A burst past that drops
 *   DEBUG and INFO and counts what it dropped, rather than growing the heap.
 * - **On the caller:** a line is queued, never formatted, redacted or written on the calling
 *   thread. All of that happens on one minimum-priority thread, which flushes once per burst
 *   rather than once per line. The periodic counter flush runs on `uptimeMillis`, which stops
 *   in deep sleep, so the logger never wakes a sleeping phone.
 *
 * ## Where
 *
 * `Filet/Logs` in shared storage, so the file can be opened in Filet itself and sent without a
 * cable or a debugger. Every line passes through [Redact] before it reaches the disk, and every
 * run read back through [readRun] passes through it again.
 *
 * ## It must never be the reason something breaks
 *
 * Every public call catches `Throwable` around its own work - a full disk, a revoked permission,
 * a message lambda that throws, a regex that fails - and loses the line rather than passing the
 * failure on. A logger that throws while recording a failure turns a bug report into a crash.
 * The one exception is deliberate: [span] and [time] rethrow what their BLOCK throws, because
 * that is the caller's code, not the logger's.
 */
object FiletLog {

    /** The detail switch. False still records warnings, failures, counters and slow spans. */
    @Volatile
    var verbose: Boolean = true

    private val opened = AtomicBoolean(false)

    @Volatile
    private var sink: File? = null

    @Volatile
    private var dir: File? = null

    /** Guarded by [writeLock], with everything else the drain touches. */
    private var writer: java.io.Writer? = null
    private val writeLock = Any()
    private val budget = Budget(SOFT_CAP, HARD_CAP)
    private val stampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var post: Handler? = null

    private val queue = ConcurrentLinkedQueue<Entry>()
    private val queued = AtomicInteger()
    private val draining = AtomicBoolean(false)
    private val overflowed = AtomicLong()

    private val startedAt = System.currentTimeMillis()

    @Volatile
    private var lastCounterFlush = System.nanoTime()

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
        return try {
            val d = logsDir(context)
            d.mkdirs()
            prune(d)
            val f = File(d, nameFor(Date()))
            val thread = HandlerThread("filet-log").apply { priority = Thread.MIN_PRIORITY; start() }
            synchronized(writeLock) { writer = java.io.BufferedWriter(java.io.FileWriter(f, true)) }
            dir = d
            sink = f
            post = Handler(thread.looper)
            header(context, f)
            post?.postDelayed(counterTick, FLUSH_MS)
            f
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "could not open a session log", t) }
            opened.set(false)
            null
        }
    }

    /**
     * The name of a session file.
     *
     * Sortable first, readable second. Leading with an ISO-ordered date and a 24-hour clock makes
     * the name's order the real order, so a listing, a sort by name and a sort by time all agree
     * - and [RunFiles.toDelete] can decide age from the name alone. The weekday rides along at
     * the end, where it cannot affect ordering.
     */
    fun nameFor(at: Date): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss.SSS", Locale.US).format(at)
        val day = SimpleDateFormat("EEE", Locale.US).format(at).lowercase(Locale.US)
        return "${RunFiles.PREFIX}${stamp}_$day${RunFiles.SUFFIX}"
    }

    private fun logsDir(context: Context): File =
        File(Environment.getExternalStorageDirectory(), FOLDER).takeIf { it.parentFile != null }
            ?: File(context.filesDir, "logs")

    /**
     * Keep the newest [KEEP] sessions and at most [MAX_TOTAL] bytes of them. The decision is
     * [RunFiles.toDelete]; this only lists and deletes.
     */
    private fun prune(dir: File) {
        runCatching {
            val runs = dir.listFiles { f: File -> f.isFile && RunFiles.isRun(f.name) }.orEmpty()
                .map { RunFiles.Run(it.name, it.length()) }
            for (name in RunFiles.toDelete(runs, KEEP, MAX_TOTAL, sink?.name)) runCatching { File(dir, name).delete() }
        }
    }

    private fun header(context: Context, f: File) {
        val pkg = context.packageName
        val version = runCatching {
            context.packageManager.getPackageInfo(pkg, 0).versionName
        }.getOrNull().orEmpty()
        raw(
            "=== Filet session log ===\n" +
                "started : " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + "\n" +
                "package : " + pkg + "\n" +
                "version : " + version + "\n" +
                "device  : " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                " (Android " + android.os.Build.VERSION.RELEASE + ", API " + android.os.Build.VERSION.SDK_INT + ")\n" +
                "verbose : " + verbose + "\n" +
                "limits  : " + SOFT_CAP / MB + " MB then debug/info dropped, " + HARD_CAP / MB + " MB hard; " +
                "newest " + KEEP + " runs, " + MAX_TOTAL / MB + " MB between them\n" +
                "file    : " + f.absolutePath + "\n" +
                "line    : time | +ms since start | level | tag | thread | message\n" +
                "========================================\n",
        )
    }

    // ── lines ──

    fun d(tag: String, message: String) {
        if (!verbose) return
        line(DEBUG, tag, message, null)
    }

    /**
     * DEBUG, built only if it will be written. Off, this is a volatile read and a branch: the
     * lambda is inlined and never called, so nothing is allocated. Use this form anywhere that
     * runs often.
     */
    inline fun d(tag: String, message: () -> String) {
        if (!verbose) return
        line(DEBUG, tag, safely(message), null)
    }

    fun i(tag: String, message: String) = line(INFO, tag, message, null)

    inline fun i(tag: String, message: () -> String) = line(INFO, tag, safely(message), null)

    /** A warning. Says where it was logged from, and with [t], the whole cause chain. */
    fun w(tag: String, message: String, t: Throwable? = null) = line(WARN, tag, message, t, callSite())

    /** A failure. Says where it was logged from, and with [t], the whole cause chain. */
    fun e(tag: String, message: String, t: Throwable? = null) = line(ERROR, tag, message, t, callSite())

    /** A message lambda that throws becomes a line saying so, not a crash. */
    @PublishedApi
    internal inline fun safely(message: () -> String): String = try {
        message()
    } catch (t: Throwable) {
        "‹message could not be built: " + t.javaClass.simpleName + "›"
    }

    // ── spans ──

    /**
     * How long something took, written when it finishes, nested in whatever span is already open
     * on this thread. The outermost span writes the whole tree - at INFO when it took
     * [SLOW_MS] or more, so a slow launch survives in a log from somebody who never turned
     * detail on, and at DEBUG otherwise. Each span also feeds a counter, so a span that runs
     * many times shows as a count and a worst case.
     */
    inline fun <T> span(tag: String, what: String, block: () -> T): T {
        val node = spanBegin(tag, what)
        try {
            return block()
        } finally {
            spanEnd(node)
        }
    }

    @PublishedApi
    internal fun spanBegin(tag: String, what: String): SpanNode? = try {
        Spans.begin(tag, what)
    } catch (_: Throwable) {
        null
    }

    @PublishedApi
    internal fun spanEnd(node: SpanNode?) {
        if (node == null) return
        try {
            val root = Spans.end(node)
            Counters.of("span " + node.tag + "/" + node.what).add(node.ms * 1_000_000)
            if (root == null) return
            if (root.ms >= SLOW_MS) line(INFO, root.tag, "span tree\n" + Spans.render(root).trimEnd(), null)
            else if (verbose) line(DEBUG, root.tag, "span tree\n" + Spans.render(root).trimEnd(), null)
        } catch (_: Throwable) {
        }
    }

    /** Record a measured duration on its own line. Public because callers time things themselves. */
    fun mark(tag: String, what: String, ms: Long) {
        if (ms >= SLOW_MS) line(INFO, tag, "$what took ${ms}ms", null)
        else if (verbose) line(DEBUG, tag, "$what took ${ms}ms", null)
    }

    // ── counters and loops ──

    /** Count one occurrence of [name]. Atomic adds; no line, no allocation. */
    fun count(name: String) {
        try {
            Counters.of(name).hit()
        } catch (_: Throwable) {
        }
    }

    /** Count one occurrence of [name] that took [nanos]. */
    fun record(name: String, nanos: Long) {
        try {
            Counters.of(name).add(nanos)
        } catch (_: Throwable) {
        }
    }

    /** Time [block] into the counter [name]. Two clock reads and the adds; rethrows the block's own failure. */
    inline fun <T> time(name: String, block: () -> T): T {
        val began = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, System.nanoTime() - began)
        }
    }

    /**
     * Register a loop for as long as it runs: `val l = FiletLog.loop("glow.clock", 33)`, then
     * `l.tick()` each pass and `l.close()` in a `finally`. [periodMs] is [Loops.FRAME] for a
     * loop driven by the display. The dump lists every loop still open.
     */
    fun loop(name: String, periodMs: Long): Loop = try {
        Loops.open(name, periodMs)
    } catch (_: Throwable) {
        Loop(name, periodMs)
    }

    // ── redaction ──

    /** Scrub [value] from every line from now on - a share's access code, a password. */
    fun secret(value: String?) = Redact.secret(value)

    // ── switches ──

    /**
     * Detail on (or off) for this process only. The saved preference is untouched, so the next
     * cold start goes back to it: the right shape for "reproduce this once with everything on".
     */
    fun detailForThisRun(on: Boolean) {
        verbose = on
        line(INFO, "log", "detailed logging " + (if (on) "on" else "off") + " for this run", null)
    }

    /** Milliseconds since this object was loaded, which is as close to process start as it gets. */
    fun sinceStart(): Long = System.currentTimeMillis() - startedAt

    // ── runs, for the issue reporter ──

    /** A run on disk. [current] is the one this process is writing. */
    data class RunInfo(val name: String, val bytes: Long, val current: Boolean)

    /** Every run kept, newest first. */
    fun runs(): List<RunInfo> = try {
        val d = dir
        d?.listFiles { f: File -> f.isFile && RunFiles.isRun(f.name) }.orEmpty()
            .map { RunInfo(it.name, it.length(), it.name == sink?.name) }
            .sortedByDescending { it.name }
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * One run as text, redacted again on the way out - a run written before a rule existed is
     * still clean when it is attached. Longer than [maxBytes], the TAIL is returned, since the
     * end of a run is where its failure is. Null for a name that is not a run in the log folder:
     * the name is checked, so this cannot be pointed at any other file.
     */
    fun readRun(name: String, maxBytes: Long = HARD_CAP): String? = try {
        val d = dir
        if (d == null || !RunFiles.isRun(name)) {
            null
        } else {
            val f = File(d, name)
            if (!f.isFile || f.parentFile?.canonicalPath != d.canonicalPath) {
                null
            } else {
                if (name == sink?.name) flushNow()
                val size = f.length()
                val skip = (size - maxBytes).coerceAtLeast(0)
                val bytes = f.inputStream().use { input ->
                    var left = skip
                    while (left > 0) {
                        val n = input.skip(left)
                        if (n <= 0) break
                        left -= n
                    }
                    input.readBytes()
                }
                val text = String(bytes, Charsets.UTF_8).lineSequence().joinToString("\n") { Redact.line(it) }
                if (skip > 0) "(first $skip bytes of this run omitted)\n$text" else text
            }
        }
    } catch (_: Throwable) {
        null
    }

    // ── diagnostics ──

    /**
     * Collect the thermal and lag section, write it into this run's log, and return it.
     *
     * Off the main thread - it reads `/proc` and asks several system services. Also flushes the
     * counters' current window first, so the dump and the lines just above it agree.
     */
    suspend fun dump(context: Context, reason: String): String = withContext(Dispatchers.IO) {
        dumpNow(context, reason)
    }

    /** [dump] without waiting for it: run on the log thread, written into the log. */
    fun requestDump(context: Context, reason: String) {
        try {
            val h = post
            if (h == null) dumpNow(context, reason) else h.post { dumpNow(context, reason) }
        } catch (_: Throwable) {
        }
    }

    private fun dumpNow(context: Context, reason: String): String = try {
        flushCounters()
        val logging = "  verbose " + verbose + ", written " + budget.written / 1024 + " KB of " + HARD_CAP / MB +
            " MB, lines dropped at the cap " + budget.dropped + ", dropped in a burst " + overflowed.get() +
            ", file " + (sink?.name ?: "none")
        val text = Vitals.collect(context, reason, threads(), logging)
            .lineSequence().joinToString("\n") { Redact.line(it) }
        raw(text)
        text
    } catch (t: Throwable) {
        "diagnostics failed: " + Failure.typeName(t)
    }

    /**
     * This process's threads from `/proc/self/task`, empty when the kernel will not say.
     *
     * Internal rather than private so [Heartbeat] can sample it too. Deliberately not copied
     * into that file: reading `/proc` is a storage API, R3 keeps those in the few places that
     * are allowed them, and one reader of this path is easier to reason about than two.
     */
    internal fun threads(): List<ProcStat.Thread> = try {
        File("/proc/self/task").listFiles().orEmpty().mapNotNull { task ->
            runCatching { ProcStat.parse(File(task, "stat").readText()) }.getOrNull()
        }
    } catch (_: Throwable) {
        emptyList()
    }

    // ── the pipe ──

    private class Entry(
        val level: String,
        val tag: String,
        val message: String,
        val t: Throwable?,
        val site: String?,
        val wall: Long,
        val sinceStart: Long,
        val thread: String,
    )

    /** Queue one line. Public because the inline overloads call it; use [d], [i], [w], [e]. */
    @PublishedApi
    internal fun line(level: String, tag: String, message: String, t: Throwable?, site: String? = null) {
        try {
            val now = System.currentTimeMillis()
            val entry = Entry(level, tag, message, t, site, now, now - startedAt, Thread.currentThread().name)
            val h = post
            if (h == null) {
                // No session (a test, the crash process, or open failed): logcat only.
                logcat(entry)
                return
            }
            val important = level == WARN || level == ERROR || level == RAW || level == STATS
            if (queued.incrementAndGet() > MAX_QUEUE && !important) {
                queued.decrementAndGet()
                overflowed.incrementAndGet()
                return
            }
            queue.add(entry)
            if (draining.compareAndSet(false, true)) h.post(drainer)
        } catch (_: Throwable) {
        }
    }

    private fun raw(text: String) = line(RAW, "", text, null)

    private val drainer = Runnable { drain() }

    private fun drain() {
        try {
            synchronized(writeLock) { drainLocked() }
        } catch (_: Throwable) {
        } finally {
            draining.set(false)
            // A line queued between the last poll and the flag dropping would otherwise wait for
            // the next caller.
            if (!queue.isEmpty() && draining.compareAndSet(false, true)) post?.post(drainer)
        }
    }

    /** Caller holds [writeLock]. One flush for the whole burst. */
    private fun drainLocked() {
        val w = writer
        while (true) {
            val e = queue.poll() ?: break
            queued.decrementAndGet()
            try {
                logcat(e)
                if (w == null) continue
                val text = format(e)
                val important = e.level == WARN || e.level == ERROR || e.level == RAW || e.level == STATS
                val verdict = budget.admit(important, text.length)
                verdict.notice?.let { w.write(it) }
                if (verdict.write) w.write(text)
            } catch (_: Throwable) {
            }
        }
        runCatching { w?.flush() }
    }

    /**
     * Write everything queued, now, on this thread. For the crash handler: the process is about
     * to be killed, and the lines just before a crash are the ones that explain it.
     */
    fun flushNow() {
        try {
            synchronized(writeLock) { drainLocked() }
        } catch (_: Throwable) {
        }
    }

    private fun format(e: Entry): String {
        if (e.level == RAW) return e.message.lineSequence().joinToString("\n") { Redact.line(it) }.trimEnd('\n') + "\n"
        val out = StringBuilder(e.message.length + 96)
        out.append(stampFormat.format(Date(e.wall))).append(" | +").append(e.sinceStart).append("ms | ")
            .append(e.level.padEnd(5)).append(" | ").append(e.tag.padEnd(14)).append(" | ")
            .append(e.thread.take(24)).append(" | ")
        val lines = e.message.lines()
        out.append(Redact.line(lines.first())).append('\n')
        for (more in lines.drop(1)) out.append("  ").append(Redact.line(more)).append('\n')
        if (e.site != null) out.append("  logged at ").append(e.site).append('\n')
        if (e.t != null) {
            Failure.render(e.t).lineSequence().filter { it.isNotEmpty() }.forEach {
                out.append(Redact.line(it)).append('\n')
            }
        }
        return out.toString()
    }

    private fun logcat(e: Entry) {
        try {
            val msg = Redact.line(if (e.tag.isEmpty()) e.message else e.tag + ": " + e.message)
            when (e.level) {
                ERROR -> Log.e(TAG, msg, e.t)
                WARN -> Log.w(TAG, msg, e.t)
                DEBUG -> Log.d(TAG, msg)
                RAW -> Unit
                else -> Log.i(TAG, msg)
            }
        } catch (_: Throwable) {
        }
    }

    /** The first frame outside this package: where a warning or failure was logged from. */
    private fun callSite(): String? = try {
        Throwable().stackTrace.firstOrNull { !it.className.startsWith("dev.niccc2007.filet.log.") }
            ?.let { (it.fileName ?: "?") + ":" + it.lineNumber + " " + it.className.substringAfterLast('.') + "." + it.methodName }
    } catch (_: Throwable) {
        null
    }

    // ── counters, written periodically ──

    private val counterTick: Runnable = object : Runnable {
        override fun run() {
            try {
                flushCounters()
            } catch (_: Throwable) {
            } finally {
                post?.postDelayed(this, FLUSH_MS)
            }
        }
    }

    private fun flushCounters() {
        val now = System.nanoTime()
        val spanMs = (now - lastCounterFlush) / 1_000_000
        lastCounterFlush = now
        val lines = Counters.flush(spanMs)
        if (lines.isEmpty()) return
        line(STATS, "counters", "last " + Counters.duration(spanMs * 1_000_000) + "\n" + lines.joinToString("\n"), null)
    }

    /** For `Diag`, which keeps its own file and must not write it on a caller's thread. */
    fun onLogThread(block: () -> Unit) {
        try {
            val h = post
            if (h == null) block() else h.post { runCatching(block) }
        } catch (_: Throwable) {
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
    private const val TAG = "Filet"

    @PublishedApi internal const val DEBUG = "DEBUG"
    @PublishedApi internal const val INFO = "INFO"
    private const val WARN = "WARN"
    private const val ERROR = "ERROR"
    private const val STATS = "STATS"
    private const val RAW = "RAW"

    /** Sessions kept on disk. */
    const val KEEP = 20

    /** A span at or over this is worth recording even with verbose off. */
    const val SLOW_MS = 250L

    private const val MB = 1024L * 1024

    /** Past this, one run's DEBUG and INFO lines are dropped. */
    const val SOFT_CAP = 4 * MB

    /** Past this, one run writes nothing more. */
    const val HARD_CAP = 5 * MB

    /** Bytes kept across all runs. Twenty full runs would be 100 MB; this stops at 40. */
    const val MAX_TOTAL = 40 * MB

    /** Lines waiting for the disk before a burst's DEBUG and INFO start being dropped. */
    const val MAX_QUEUE = 5_000

    /** How often counters are written. Two minutes: a few lines per window, not per event. */
    const val FLUSH_MS = 120_000L
}

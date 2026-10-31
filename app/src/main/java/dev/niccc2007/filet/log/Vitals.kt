package dev.niccc2007.filet.log

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.FrameMetrics
import android.view.Window
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "why is this phone hot" section, collected when asked for and never otherwise.
 *
 * Everything here is something the platform hands a release build without a debugger or a
 * permission: thermal status, battery temperature and current, whether the screen is on, this
 * process's CPU time and which of its threads spent it, its memory, its own services and
 * notifications, what is looping and how often, the run's counters, and frame timing when
 * [FrameWatch] has been measuring.
 *
 * The thread table is the part most likely to name a culprit. Heat is CPU, and CPU is spent by
 * a thread with a name; a thread that used a quarter of a core since the last dump while the
 * screen was off is the answer to a hot pocket.
 *
 * Every section is collected on its own and a failure in one prints as that section being
 * unavailable, so a vendor that has broken one API still gets the rest.
 */
internal object Vitals {

    /** Thread CPU ticks at the previous dump, so the table shows what happened since. */
    private val lastTicks = HashMap<Int, Long>()
    private var lastAt = 0L

    fun collect(context: Context, reason: String, threads: List<ProcStat.Thread>, logging: String): String = buildString {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        append("=== diagnostics: ").append(reason).append(" at ").append(stamp)
            .append(" (+").append(FiletLog.sinceStart()).append("ms) ===\n")
        section("power") { power(context) }
        section("battery") { battery(context) }
        section("process") { process(threads.size) }
        section("busiest threads") { threads(threads) }
        section("loops running now") {
            Loops.running().joinToString("\n") { "  " + it.describe() }.ifEmpty { "  none" }
        }
        section("frames") { FrameWatch.describe() }
        section("counters, whole run") {
            Counters.totals().take(60).joinToString("\n") { "  " + it.line(FiletLog.sinceStart()) }.ifEmpty { "  none yet" }
        }
        section("services") { services(context) }
        section("notifications") { notifications(context) }
        section("logging") { logging }
        append("=== end of diagnostics ===\n")
    }

    private inline fun StringBuilder.section(name: String, body: () -> String) {
        append("-- ").append(name).append('\n')
        val text = try {
            body()
        } catch (t: Throwable) {
            "  unavailable: " + Failure.typeName(t) + (t.message?.let { ": $it" } ?: "")
        }
        append(text).append('\n')
    }

    private fun power(context: Context): String {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val parts = ArrayList<String>()
        parts += "screen " + if (pm.isInteractive) "on" else "off"
        parts += "power saver " + if (pm.isPowerSaveMode) "on" else "off"
        parts += "doze " + if (pm.isDeviceIdleMode) "idle" else "not idle"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            parts += "thermal " + thermalName(pm.currentThermalStatus)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val h = pm.getThermalHeadroom(10)
            // 1.0 is throttling; NaN means the device does not report it.
            if (!h.isNaN()) parts += String.format(Locale.US, "headroom in 10s %.2f (1.0 = throttling)", h)
        }
        return "  " + parts.joinToString(", ")
    }

    private fun thermalName(s: Int): String = when (s) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "status $s"
    }

    private fun battery(context: Context): String {
        // A sticky broadcast read with a null receiver registers nothing.
        val i: Intent = context.registerReceiver(null as BroadcastReceiver?, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "  no battery broadcast"
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "charging (AC)"
            BatteryManager.BATTERY_PLUGGED_USB -> "charging (USB)"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "charging (wireless)"
            0 -> "on battery"
            else -> "plugged"
        }
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        // Microamps on most devices, milliamps on some Samsungs; printed raw with that said.
        val now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val parts = ArrayList<String>()
        if (temp != Int.MIN_VALUE) parts += String.format(Locale.US, "%.1f°C", temp / 10.0)
        if (level >= 0) parts += "${level * 100 / scale.coerceAtLeast(1)}%"
        parts += plugged
        if (now != Int.MIN_VALUE && now != 0) parts += "current now $now (µA on most devices; negative is discharge)"
        return "  " + parts.joinToString(", ")
    }

    private fun process(threadCount: Int): String {
        val upMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        val cpuMs = Process.getElapsedCpuTime()
        val rt = Runtime.getRuntime()
        val heapUsed = (rt.totalMemory() - rt.freeMemory()) / MB
        return "  up " + Counters.duration(upMs * 1_000_000) +
            ", cpu " + Counters.duration(cpuMs * 1_000_000) +
            String.format(Locale.US, " (%.1f%% of one core on average)", if (upMs > 0) cpuMs * 100.0 / upMs else 0.0) +
            ", threads " + threadCount +
            ", heap " + heapUsed + "/" + rt.maxMemory() / MB + " MB" +
            ", native " + Debug.getNativeHeapAllocatedSize() / MB + " MB"
    }

    private fun threads(threads: List<ProcStat.Thread>): String {
        if (threads.isEmpty()) return "  /proc/self/task not readable"
        val hz = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1)
        val now = SystemClock.elapsedRealtime()
        val first = lastAt == 0L
        val windowMs = if (first) SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() else now - lastAt
        val rows = threads.map { t ->
            val before = if (first) 0L else lastTicks[t.tid] ?: 0L
            t to (t.ticks - before).coerceAtLeast(0)
        }.sortedByDescending { it.second }
        lastTicks.clear()
        threads.forEach { lastTicks[it.tid] = it.ticks }
        lastAt = now
        val head = "  " + (if (first) "since process start" else "since the previous dump") +
            " (" + Counters.duration(windowMs * 1_000_000) + ")\n"
        return head + rows.take(12).joinToString("\n") { (t, d) ->
            val ms = d * 1000 / hz
            String.format(
                Locale.US, "  %-22s tid %-6d cpu %-8s %5.1f%% of one core",
                t.name, t.tid, Counters.duration(ms * 1_000_000),
                if (windowMs > 0) ms * 100.0 / windowMs else 0.0,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun services(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // Deprecated for other apps' services; still returns this app's own, which is all that
        // is wanted.
        val mine = am.getRunningServices(100).filter { it.service.packageName == context.packageName }
        if (mine.isEmpty()) return "  none running"
        return mine.joinToString("\n") {
            "  " + it.service.className.substringAfterLast('.') +
                (if (it.foreground) " (foreground)" else "") +
                ", started " + Counters.duration((SystemClock.elapsedRealtime() - it.activeSince) * 1_000_000) + " ago"
        }
    }

    private fun notifications(context: Context): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = nm.activeNotifications
        if (active.isEmpty()) return "  none posted"
        // Channel and flags only. A title can be a track or a file name, and the point of this
        // section is what is posted and how often it is refreshed, not what it says.
        return active.joinToString("\n") {
            val n = it.notification
            val ongoing = (n.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0
            "  id " + it.id + " channel " + (n.channelId ?: "?") + (if (ongoing) " ongoing" else "") +
                ", posted " + Counters.duration((System.currentTimeMillis() - it.postTime).coerceAtLeast(0) * 1_000_000) + " ago"
        }
    }

    private const val MB = 1024L * 1024
}

/**
 * Frame timing from the platform's own frame metrics, in a release build.
 *
 * `Window.addOnFrameMetricsAvailableListener` reports every frame's real duration, so jank is a
 * count rather than an impression. Each frame costs one callback on a background thread and
 * three atomic adds - and nothing at all while it is not attached. It is attached only while
 * detailed logging is on: the measuring is cheap, but a callback per frame is still a wake per
 * frame, and on an ordinary run nobody is going to read the result.
 */
object FrameWatch {

    private var thread: HandlerThread? = null
    private val listeners = HashMap<Window, Window.OnFrameMetricsAvailableListener>()
    @Volatile
    private var budgetNanos = 16_666_667L

    /** From the activity's `onResume`. Does nothing unless detailed logging is on. */
    fun attach(activity: Activity) {
        try {
            if (!FiletLog.verbose) return
            val window = activity.window ?: return
            synchronized(listeners) {
                if (window in listeners) return
                val t = thread ?: HandlerThread("filet-frames", Process.THREAD_PRIORITY_BACKGROUND).also { it.start(); thread = it }
                @Suppress("DEPRECATION")
                val rate = activity.windowManager.defaultDisplay.refreshRate
                if (rate > 1f) budgetNanos = (1_000_000_000L / rate).toLong()
                val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                    val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    FRAMES.add(total)

                    // This was measured wrong, and the wrong number was believed.
                    //
                    // TOTAL_DURATION is the frame's END-TO-END latency: input handling, then
                    // animation, measure, layout, draw, sync, command issue, the GPU's own work
                    // and the buffer swap. Rendering is PIPELINED, so a frame can take 20ms to
                    // travel that pipeline while a finished frame still leaves the end of it
                    // every vsync. Comparing it against one vsync period therefore reports
                    // almost every frame as dropped on a perfectly smooth run - it reported
                    // 13,329 of 13,330 while the same dump recorded 111 frames a second on a
                    // 120Hz panel, which is the contradiction that gave it away.
                    //
                    // So two honest figures instead of one misleading one:
                    //
                    //   missed its deadline - the platform's OWN deadline for that frame, which
                    //   is what "the user saw a stutter" actually means. DEADLINE arrived in
                    //   API 31; below that a vsync period is the best available guess and the
                    //   figure stays comparable only against itself.
                    //
                    //   over budget on our side - the CPU work this app is responsible for,
                    //   with the GPU and the swap excluded because no Compose change can move
                    //   those. THIS is the number to read when asking whether our code is slow.
                    val deadline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        metrics.getMetric(FrameMetrics.DEADLINE)
                    } else {
                        budgetNanos
                    }
                    if (deadline > 0 && total > deadline) JANKY.add(total)

                    val work = metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) +
                        metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
                        metrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                        metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
                        metrics.getMetric(FrameMetrics.DRAW_DURATION) +
                        metrics.getMetric(FrameMetrics.SYNC_DURATION)
                    WORK.add(work)
                    if (work > budgetNanos) SLOW.add(work)

                    // Over 700ms: the app looked frozen.
                    if (total > 700_000_000L) FROZEN.hit()
                }
                window.addOnFrameMetricsAvailableListener(l, Handler(t.looper))
                listeners[window] = l
            }
        } catch (_: Throwable) {
        }
    }

    /** From the activity's `onPause`. */
    fun detach(activity: Activity) {
        try {
            val window = activity.window ?: return
            synchronized(listeners) {
                val l = listeners.remove(window) ?: return
                window.removeOnFrameMetricsAvailableListener(l)
            }
        } catch (_: Throwable) {
        }
    }

    private val FRAMES get() = Counters.of("frame")
    private val JANKY get() = Counters.of("frame.missed its deadline")
    private val WORK get() = Counters.of("frame.our cpu work")
    private val SLOW get() = Counters.of("frame.our cpu work over budget")
    private val FROZEN get() = Counters.of("frame.frozen >700ms")

    /** Frames, deadline misses and the worst CPU frame, as totals for the run so far. */
    internal data class Frames(val frames: Long, val missed: Long, val worstWork: Long)

    /**
     * The running totals, for a caller that wants the change since it last looked.
     *
     * Totals rather than [Counter.takeWindow], which RESETS the window - a second reader of the
     * same counter would then see a gap it caused itself. The counter names live here because
     * this is the file that writes them, and a second copy of the strings elsewhere is a second
     * thing to keep in step.
     */
    internal fun snapshot(): Frames = Frames(
        frames = FRAMES.total().count,
        missed = JANKY.total().count,
        worstWork = WORK.total().worst,
    )

    internal fun describe(): String {
        val all = FRAMES.total()
        if (all.count == 0L) {
            return "  not measured" + if (!FiletLog.verbose) " (detailed logging is off; frames are only timed while it is on)" else " yet"
        }
        val janky = JANKY.total()
        val work = WORK.total()
        val slow = SLOW.total()
        val frozen = FROZEN.total()
        val pct = { n: Long -> String.format(Locale.US, " (%.1f%%)", n * 100.0 / all.count) }
        return buildString {
            appendLine("  " + all.count + " frames at a " + Counters.duration(budgetNanos) + " vsync")
            appendLine(
                "  " + janky.count + pct(janky.count) + " missed the frame's own deadline, " +
                    frozen.count + " frozen over 700ms",
            )
            appendLine(
                "  end to end: average " + Counters.duration(all.nanos / all.count) +
                    ", worst " + Counters.duration(all.worst) +
                    " (pipeline latency - expected to exceed one vsync)",
            )
            append(
                "  our cpu work: average " +
                    Counters.duration(if (work.count > 0) work.nanos / work.count else 0) +
                    ", worst " + Counters.duration(work.worst) + ", " + slow.count + pct(slow.count) +
                    " over budget (this is the figure our own code moves)",
            )
        }
    }
}

/**
 * The adb trigger for a dump:
 * `adb shell am broadcast -a dev.niccc2007.filet.DIAG -n dev.niccc2007.filet/.log.DiagReceiver`
 *
 * Protected by `android.permission.DUMP` in the manifest, which the shell holds and an ordinary
 * app cannot get - so another app on the phone cannot make Filet write its vitals into a file in
 * shared storage on demand.
 */
class DiagReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FiletLog.requestDump(context.applicationContext, "adb")
    }
}

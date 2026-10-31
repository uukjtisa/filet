package dev.niccc2007.filet.log

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One line a minute about what this phone is doing to itself.
 *
 * ## Why a sampler and not a dump
 *
 * [Vitals] answers "what is happening NOW", and it is the right tool when somebody is holding
 * the phone while it misbehaves. It is the wrong tool for the question actually being asked -
 * *what was it doing while it got hot* - because by the time a dump is triggered the interesting
 * part is over: the thermal status has settled, the thread that burned a core has gone idle, and
 * the CPU figures are cumulative averages that have diluted the spike into nothing.
 *
 * A sample a minute, each one a delta since the last, keeps the SHAPE. Thermal climbing over six
 * samples while one thread holds 40% of a core is a different bug from one spike on the sample
 * where a scan started, and neither is visible in a single dump.
 *
 * ## Why it is one line
 *
 * Because it has to be readable in a file somebody scrolls. A section per sample would be the
 * log's whole content, and the thing this replaces - the live telemetry viewer this was going to
 * be a screen for - is unreadable for the same reason: watching a number move tells you nothing
 * unless you are watching at the moment it moves. A line per minute, greppable, is a record that
 * can be read after the fact, which is when anybody actually reads it.
 *
 * ## What it costs when nobody is reading
 *
 * Nothing worth measuring, and it is gated anyway. It samples only while detailed logging is on
 * - the same switch [FrameWatch] uses, for the same reason - and while it is off this wakes on a
 * long timer to notice the switch being flipped and does nothing else. Each sample is a handful
 * of `/proc` reads and a sticky broadcast lookup, off the main thread.
 */
object Heartbeat {

    /**
     * One sample, as deltas where a delta is the honest figure.
     *
     * @param thermal the platform's thermal status, by name.
     * @param headroom 0..1 of the way to throttling, or null when the platform will not forecast.
     * @param batteryC battery temperature in degrees, which is the closest thing to a
     *   thermometer an app can read without a permission.
     * @param charging whether it is taking power, because charging is itself a heat source and a
     *   hot phone on a charger is not the same finding as a hot phone on battery.
     * @param cpuPercent this process's CPU since the last sample, as a share of ONE core.
     * @param busiest the thread that spent the most of it, and its own share.
     * @param frames frames drawn since the last sample, and how many missed their deadline.
     * @param newWorstWorkNanos a worst frame of our own CPU work that this interval SET, or 0
     *   when the run's worst was already worse. The counter only keeps a running worst, and
     *   printing that inside a per-minute section would read as this minute's figure - so what
     *   gets reported is the thing that is actually about this minute: that it produced a new
     *   one. A minute that did not beat the record says nothing, which is correct.
     */
    data class Sample(
        val thermal: String,
        val headroom: Float?,
        val batteryC: Float?,
        val charging: Boolean,
        val cpuPercent: Int,
        val busiest: String?,
        val busiestPercent: Int,
        val frames: Long,
        val missed: Long,
        val newWorstWorkNanos: Long,
    )

    /**
     * How often a sample is taken while detailed logging is on.
     *
     * A minute. Shorter turns the log into the sampler's own output - at ten seconds an hour of
     * use is 360 lines, which buries the lines that say what the app was DOING - and longer
     * misses the thing worth catching, because a thermal climb takes a few minutes end to end
     * and three points do not show a shape.
     */
    const val EVERY_MS = 60_000L

    /** How often the switch is checked while detailed logging is off. */
    private const val QUIET_MS = 300_000L

    /** Ticks per second, for turning clock ticks into a share of a core. */
    private val hz: Long by lazy {
        runCatching {
            android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
        }.getOrDefault(100L).coerceAtLeast(1L)
    }

    private var job: Job? = null

    private var lastAt = 0L
    private var lastTotalTicks = 0L
    private val lastThreadTicks = HashMap<Int, Long>()
    private var lastFrames = FrameWatch.Frames(0, 0, 0)

    /** Start sampling. Safe to call twice; the second call is ignored. */
    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!FiletLog.verbose) {
                    // Nothing is read and nothing is written. The previous sample is dropped on
                    // the way back in rather than kept: a delta measured across a gap where
                    // sampling was off would attribute minutes of CPU to one interval.
                    forget()
                    delay(QUIET_MS)
                    continue
                }
                delay(EVERY_MS)
                if (!FiletLog.verbose) continue
                runCatching { beat(context) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        forget()
    }

    private fun forget() {
        lastAt = 0L
        lastTotalTicks = 0L
        lastThreadTicks.clear()
        lastFrames = FrameWatch.Frames(0, 0, 0)
    }

    private fun beat(context: Context) {
        val now = System.currentTimeMillis()
        val threads = FiletLog.threads()
        val totalTicks = threads.sumOf { it.ticks }
        val frames = FrameWatch.snapshot()

        // The FIRST sample after sampling starts is a baseline, not a measurement. Its deltas
        // would run from process start, so a share-of-a-core figure would be an average over the
        // whole run presented as a figure for this minute - which is the exact dilution this
        // sampler exists to avoid.
        val span = now - lastAt
        val first = lastAt == 0L
        if (!first && span > 0) {
            val busiest = threads
                .map { it to (it.ticks - (lastThreadTicks[it.tid] ?: it.ticks)) }
                .maxByOrNull { it.second }
            FiletLog.i(
                "heat",
                line(
                    Sample(
                        thermal = thermalName(context),
                        headroom = headroom(context),
                        batteryC = batteryC(context),
                        charging = charging(context),
                        cpuPercent = share(totalTicks - lastTotalTicks, span),
                        busiest = busiest?.first?.name,
                        busiestPercent = share(busiest?.second ?: 0L, span),
                        frames = frames.frames - lastFrames.frames,
                        missed = frames.missed - lastFrames.missed,
                        // Only when this interval raised it - see the parameter's note.
                        newWorstWorkNanos = frames.worstWork
                            .takeIf { it > lastFrames.worstWork } ?: 0L,
                    ),
                ),
            )
        }
        lastAt = now
        lastTotalTicks = totalTicks
        lastThreadTicks.clear()
        for (t in threads) lastThreadTicks[t.tid] = t.ticks
        lastFrames = frames
    }

    /** Clock ticks over a span, as a percentage of one core. */
    private fun share(ticks: Long, spanMs: Long): Int {
        if (ticks <= 0 || spanMs <= 0) return 0
        return ((ticks * 1000.0 / hz) / spanMs * 100).toInt().coerceIn(0, 10_000)
    }

    private fun thermalName(context: Context): String = runCatching {
        val pm = context.getSystemService(PowerManager::class.java) ?: return "unknown"
        when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }.getOrDefault("unknown")

    private fun headroom(context: Context): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val pm = context.getSystemService(PowerManager::class.java) ?: return null
            pm.getThermalHeadroom(0).takeIf { !it.isNaN() && it.isFinite() }
        }.getOrNull()
    }

    private fun batteryStatus(context: Context): Intent? = runCatching {
        // A null receiver reads the STICKY broadcast - the last one the system sent - rather
        // than registering for future ones. No listener is left behind by a sample.
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    private fun batteryC(context: Context): Float? {
        val tenths = batteryStatus(context)
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: return null
        if (tenths == Int.MIN_VALUE) return null
        return tenths / 10f
    }

    private fun charging(context: Context): Boolean {
        val plugged = batteryStatus(context)
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            ?: return false
        return plugged != 0
    }

    /**
     * One sample as one line.
     *
     * Pure, and tested, because this is the only part of the sampler that can be wrong in a way
     * nobody notices: a sampler that writes a misleading line is worse than one that writes
     * nothing, since the line gets believed. The four things it must not do are print a figure
     * the platform refused to give, print a frame section when nothing was drawn, claim a
     * busiest thread that spent no time, and present a running total as if it were this
     * minute's - all of which read as findings.
     */
    fun line(s: Sample): String = buildString {
        append("thermal ").append(s.thermal)
        // Only when the platform forecast one. A missing headroom printed as 0 reads as "nowhere
        // near throttling", which is the opposite of "would not say".
        s.headroom?.let { append(", headroom ").append((it * 100).toInt()).append('%') }
        s.batteryC?.let {
            append(", battery ").append(String.format(Locale.US, "%.1f", it)).append("C")
        }
        append(if (s.charging) ", charging" else ", on battery")
        append(", cpu ").append(s.cpuPercent).append("% of a core")
        // A thread named with no time against it is noise: every run has a dozen idle threads
        // and whichever sorted first would look like the culprit.
        if (s.busiest != null && s.busiestPercent > 0) {
            append(", busiest ").append(s.busiest).append(' ').append(s.busiestPercent).append('%')
        }
        // Frames only when some were drawn. Zero frames means the screen was off or detailed
        // logging was switched on without the activity being resumed - "0 frames, 0 missed"
        // reads as a perfectly smooth minute, which is a claim about nothing.
        if (s.frames > 0) {
            append(", frames ").append(s.frames)
            append(" (").append(s.missed).append(" missed")
            if (s.newWorstWorkNanos > 0) {
                append(", a new worst cpu frame of ")
                append(Counters.duration(s.newWorstWorkNanos))
            }
            append(')')
        }
    }
}

package dev.niccc2007.filet.log

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * How often something ran and for how long, kept in memory and written out periodically.
 *
 * This is what answers "why is the phone hot": not a line per step - a loop at sixty frames a
 * second would write three and a half thousand lines a minute and heat the phone itself - but
 * one line per counter per [FiletLog.FLUSH_MS], saying how many times, how long in total, and the
 * worst single time. "beat.step: 54000 in 15m, worst 0.40ms" is an answer; a line per step is
 * the problem it is trying to describe.
 *
 * A hit is two or three atomic adds on a counter found in a concurrent map by a string literal
 * whose hash is cached - no allocation, no lock, no formatting. Formatting happens only at flush,
 * on the log thread.
 */
class Counter internal constructor(val name: String) {
    private val count = AtomicLong()
    private val nanos = AtomicLong()
    private val worst = AtomicLong()
    private val wCount = AtomicLong()
    private val wNanos = AtomicLong()
    private val wWorst = AtomicLong()

    /** One occurrence with no duration. */
    fun hit() {
        count.incrementAndGet()
        wCount.incrementAndGet()
    }

    /** One occurrence that took [n] nanoseconds. */
    fun add(n: Long) {
        hit()
        val d = if (n < 0) 0 else n
        nanos.addAndGet(d)
        wNanos.addAndGet(d)
        raise(worst, d)
        raise(wWorst, d)
    }

    /** The whole run so far. */
    fun total(): Stat = Stat(name, count.get(), nanos.get(), worst.get())

    /**
     * Everything since the last call, and start a new window. The three reads are not one atomic
     * step, so a hit landing mid-read can fall into either window; over a minute of counts that
     * is noise, and the alternative is a lock on the hot path.
     */
    fun takeWindow(): Stat = Stat(name, wCount.getAndSet(0), wNanos.getAndSet(0), wWorst.getAndSet(0))

    private fun raise(slot: AtomicLong, v: Long) {
        var cur = slot.get()
        while (v > cur && !slot.compareAndSet(cur, v)) cur = slot.get()
    }
}

/** A counter's figures. [nanos] and [worst] are zero for a counter that only counts. */
data class Stat(val name: String, val count: Long, val nanos: Long, val worst: Long) {

    /** One line: count, rate over [spanMs] when given, and timing when there is any. */
    fun line(spanMs: Long? = null): String = buildString {
        append(name).append(": ").append(count)
        if (spanMs != null && spanMs > 0) {
            append(" in ").append(Counters.duration(spanMs * 1_000_000))
            append(" (").append(String.format(Locale.US, "%.1f", count * 1000.0 / spanMs)).append("/s)")
        }
        if (nanos > 0) {
            append(", busy ").append(Counters.duration(nanos))
            append(", avg ").append(Counters.duration(if (count > 0) nanos / count else 0))
            append(", worst ").append(Counters.duration(worst))
        }
    }
}

object Counters {

    /**
     * Distinct counter names held. A name built from data - a path, a track title - would
     * otherwise grow the map without limit; past the cap everything shares [OVERFLOW].
     */
    const val MAX = 256
    const val OVERFLOW = "(other counters)"

    private val map = ConcurrentHashMap<String, Counter>()

    fun of(name: String): Counter {
        map[name]?.let { return it }
        val key = if (map.size >= MAX) OVERFLOW else name
        return map.computeIfAbsent(key) { Counter(it) }
    }

    /** Lines for every counter that moved since the last flush, busiest first. */
    fun flush(spanMs: Long): List<String> =
        map.values.map { it.takeWindow() }.filter { it.count > 0 }
            .sortedWith(compareByDescending<Stat> { it.nanos }.thenByDescending { it.count })
            .map { it.line(spanMs) }

    /** Run totals, busiest first. */
    fun totals(): List<Stat> =
        map.values.map { it.total() }.filter { it.count > 0 }
            .sortedWith(compareByDescending<Stat> { it.nanos }.thenByDescending { it.count })

    internal fun clear() = map.clear()

    /** `840ns`, `12.4µs`, `3.21ms`, `4.5s`, `2m05s`. */
    fun duration(n: Long): String = when {
        n < 1_000 -> "${n}ns"
        n < 1_000_000 -> String.format(Locale.US, "%.1fµs", n / 1_000.0)
        n < 1_000_000_000 -> String.format(Locale.US, "%.2fms", n / 1_000_000.0)
        n < 60_000_000_000 -> String.format(Locale.US, "%.1fs", n / 1_000_000_000.0)
        else -> {
            val s = n / 1_000_000_000
            String.format(Locale.US, "%dm%02ds", s / 60, s % 60)
        }
    }
}

/**
 * Something that runs on a period - a poll, an animation clock, a frame loop - registered for as
 * long as it runs, so a diagnostic dump can say what is running right now and how often.
 *
 * A file manager playing music should be running very little. The two heat reports were both a
 * loop nobody knew was still going; this is the list that would have named it. Open one when
 * the loop starts, [tick] it each pass, and close it in a `finally`.
 */
class Loop internal constructor(val name: String, val periodMs: Long) : AutoCloseable {
    val startedAt: Long = System.nanoTime()
    private val counter = Counters.of("loop $name")

    fun tick() = counter.hit()

    override fun close() {
        Loops.active.remove(this)
    }

    /** `glow.clock every 33ms, 1800 ticks, running 1m00s`. */
    fun describe(now: Long = System.nanoTime()): String =
        name + " " + (if (periodMs == Loops.FRAME) "every frame" else "every ${periodMs}ms") +
            ", " + counter.total().count + " ticks, running " + Counters.duration(now - startedAt)
}

object Loops {
    /** The period of a loop driven by the display rather than by a delay. */
    const val FRAME = -1L

    internal val active: MutableSet<Loop> = ConcurrentHashMap.newKeySet()

    fun open(name: String, periodMs: Long): Loop = Loop(name, periodMs).also { active += it }

    fun running(): List<Loop> = active.sortedBy { it.startedAt }
}

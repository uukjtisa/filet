package dev.niccc2007.filet.handlers

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One frame at a time, for the video player's frame bar (see `FrameBar`).
 *
 * ## On the file's own frames
 *
 * A step goes to a frame the file actually has: the list of them is read off the container,
 * one sample time each, into a [FrameList]. Then the step is an exact seek -
 * `VideoHandle.seekExactly` - so it lands on that frame and not on the keyframe before it.
 *
 * Bug identified here: the step used to go by a rate, the frame count over the file's length.
 * That is an average, and a phone recording is variable-rate. On a clip whose picture runs at
 * 60 a second the average came out at 13.9, so a step jumped 72 ms, about four frames, and on a
 * grid that does not line up with the frames some steps landed on the same frame twice. A list
 * of the real frames has no rate to get wrong.
 *
 * ## Until the list is read
 *
 * Reading it takes a moment on a long file. Meanwhile a step goes by the container's rate on
 * its grid ([target]), and the readout says the number is an estimate.
 *
 * ## On the grid, not by a fixed amount
 *
 * The estimate is "the frame after the one nearest here", worked out from the frame's index
 * rather than by adding a frame's length to the position. A 60-frame file's frame is 16.67 ms;
 * adding a rounded 17 each time drifts a third of a millisecond a step, and twenty-five steps
 * in it is half a frame off and lands on the wrong one.
 */
object FrameStep {

    const val FALLBACK_FPS = 30.0

    /** The step sizes the bar offers, in frames. */
    val SIZES = listOf(1, 2, 5, 10)

    /** What ◀◀ and ▶▶ jump. */
    const val JUMP_MS = 1_000L

    /** A held button's first wait: long enough that a tap is one step and not two. */
    const val HOLD_FIRST_MS = 400L

    /**
     * A held button's pace after that: about seven steps a second, slow enough to see each
     * frame. Never faster than the seeks land, see `FrameBar`.
     */
    const val HOLD_EVERY_MS = 140L

    /** The slowest rate believed: under 4 a second it is a slideshow or a broken header. */
    private const val SLOWEST_FPS = 4.0

    /** The fastest: past this the header is wrong, not the video fast. */
    private const val FASTEST_FPS = 1000.0

    /** Frames a second, from what the container says. Only the estimate uses it. */
    fun fpsOf(frameCount: String?, durationMs: String?, captureFps: String?): Double {
        val frames = frameCount?.trim()?.toLongOrNull()
        val length = durationMs?.trim()?.toLongOrNull()
        if (frames != null && length != null && frames > 0 && length > 0) {
            val fps = frames * 1000.0 / length
            if (fps in SLOWEST_FPS..FASTEST_FPS) return fps
        }
        val capture = captureFps?.trim()?.toDoubleOrNull()
        if (capture != null && capture.isFinite() && capture in SLOWEST_FPS..FASTEST_FPS) return capture
        return FALLBACK_FPS
    }

    /** The estimate: [by] frames on from the one nearest [position] on the rate's grid, held inside the video. */
    fun target(position: Long, duration: Long, fps: Double, by: Int): Long {
        val rate = rateOf(fps)
        val frame = (position.coerceAtLeast(0L) * rate / 1000.0).roundToLong() + by
        val at = (frame.coerceAtLeast(0L) * 1000.0 / rate).roundToLong()
        return if (duration > 0) at.coerceIn(0L, duration) else at
    }

    /** The estimate's jump: [deltaMs] from [position], held inside the video. */
    fun jump(position: Long, duration: Long, deltaMs: Long): Long {
        val at = (position + deltaMs).coerceAtLeast(0L)
        return if (duration > 0) at.coerceAtMost(duration) else at
    }

    /** The estimate's frame number at [position], counted from 1. */
    fun numberAt(position: Long, fps: Double): Long =
        (position.coerceAtLeast(0L) * rateOf(fps) / 1000.0).roundToLong() + 1

    /**
     * `#1234 · 0:41.233`: the frame's number, counted from 1, and the time it is shown at to
     * the millisecond. An estimate says so with a `≈`.
     */
    fun readout(number: Long, timeMs: Long, exact: Boolean): String =
        (if (exact) "#" else "≈#") + number + " · " + preciseClock(timeMs)

    /** `0:41.233`, or `1:02:03.004` past an hour. */
    fun preciseClock(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        val h = t / 3_600_000
        val m = (t / 60_000) % 60
        val s = (t / 1000) % 60
        val milli = t % 1000
        return if (h > 0) "%d:%02d:%02d.%03d".format(h, m, s, milli) else "%d:%02d.%03d".format(m, s, milli)
    }

    /** How long a held button waits before its next step: [repeat] is how many it has already repeated. */
    fun holdDelay(repeat: Int): Long = if (repeat <= 0) HOLD_FIRST_MS else HOLD_EVERY_MS

    private fun rateOf(fps: Double): Double = if (fps.isFinite() && fps > 0) fps else FALLBACK_FPS
}

/**
 * A video's frames, as the times they are shown at, in order.
 *
 * From the container's samples, which come in DECODING order: a file with B-frames stores a
 * frame after the ones predicted from it, so the times arrive shuffled and are sorted here.
 * Held to whole milliseconds, which is what a seek takes. Two frames under a millisecond apart
 * cannot be told apart by a seek anyway, and are kept as one.
 */
class FrameList private constructor(private val times: LongArray) {

    val size: Int get() = times.size

    /** When frame [index] is shown, held to the first and the last. */
    fun timeOf(index: Int): Long = times[index.coerceIn(0, times.size - 1)]

    /**
     * The frame on screen at [positionMs]: the last one shown by then, and the first before it.
     *
     * A millisecond of slack, because a seek is to a whole millisecond and the frame it lands
     * on may start a fraction after it. The player then reports the millisecond it was asked
     * for, which is that frame and not the one before.
     */
    fun at(positionMs: Long): Int {
        val key = positionMs + SLACK_MS
        var lo = 0
        var hi = times.size - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= key) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** The frame whose time is nearest [ms]; the earlier of two equally near. */
    fun nearest(ms: Long): Int {
        val i = at(ms - SLACK_MS)
        if (i + 1 < times.size && abs(times[i + 1] - ms) < abs(times[i] - ms)) return i + 1
        return i
    }

    /** [by] frames on from the one at [positionMs], held to the first and the last: the time to seek to. */
    fun step(positionMs: Long, by: Int): Long = timeOf(at(positionMs) + by)

    /**
     * The frame nearest [deltaMs] on from the one at [positionMs]: the time to seek to.
     *
     * A jump always moves while there is somewhere to go. Across a still that is longer than the
     * jump, the nearest frame can be the one already shown, and a ▶▶ that did nothing would read
     * as broken; it goes to the next frame instead.
     */
    fun jump(positionMs: Long, deltaMs: Long): Long {
        val from = at(positionMs)
        var to = nearest(timeOf(from) + deltaMs)
        if (to == from && deltaMs != 0L) to = from + if (deltaMs > 0) 1 else -1
        return timeOf(to)
    }

    companion object {
        private const val SLACK_MS = 1L

        /**
         * The list from the first [count] of [sampleTimesUs], the container's sample times in
         * microseconds. Null when no frame is left: a time before zero is not shown.
         */
        fun of(sampleTimesUs: LongArray, count: Int = sampleTimesUs.size): FrameList? {
            val n = count.coerceIn(0, sampleTimesUs.size)
            val ms = LongArray(n) { Math.floorDiv(sampleTimesUs[it] + 500, 1000L) }
            ms.sort()
            val kept = LongArray(n)
            var k = 0
            for (t in ms) {
                if (t < 0) continue
                if (k > 0 && kept[k - 1] == t) continue
                kept[k++] = t
            }
            return if (k == 0) null else FrameList(kept.copyOf(k))
        }
    }
}

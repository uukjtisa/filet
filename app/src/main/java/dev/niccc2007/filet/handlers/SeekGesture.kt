package dev.niccc2007.filet.handlers

import kotlin.math.roundToLong

/**
 * Turning a finger into a playback position.
 *
 * Nic's words for what was wrong: "add controls where if i drag it it intuitively skips or
 * follows my hand on where to go back or forward as if i was holding the playback bar
 * progress". The stock `MediaController` only seeks when you happen to grab its thumb, which
 * on a phone is a 20dp target you miss more often than you hit.
 *
 * All of it is arithmetic on a touch position and a duration, so all of it is here and tested.
 * The gesture handlers in `VideoScreen.kt` do the listening and nothing else.
 */

/**
 * Where a touch at [x] lands in a bar that is [width] wide.
 *
 * Absolute, not relative: the position under the finger is the position it seeks to, from the
 * first touch, with no need to grab a thumb first. Clamped, because a drag that carries on
 * past the end of the bar still reports coordinates and would otherwise seek past the end of
 * the file.
 */
fun seekPosition(x: Float, width: Float, durationMs: Long): Long {
    if (durationMs <= 0L || width <= 0f) return 0L
    val fraction = (x / width).coerceIn(0f, 1f)
    return (fraction * durationMs).roundToLong().coerceIn(0L, durationMs)
}

/** The reverse: where the playhead sits along a bar, for drawing it. Always inside 0..1. */
fun seekFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * Which third of the picture was tapped.
 *
 * Halves would be simpler, but then a double tap anywhere near the middle jumps the video by
 * ten seconds in a direction the user did not choose, and the middle is exactly where a
 * thumb rests. The middle band gets play/pause instead, which is the thing a tap there
 * usually meant.
 */
enum class TapZone { BACK, MIDDLE, FORWARD }

fun tapZone(x: Float, width: Float, middleBand: Float = 0.3f): TapZone {
    if (width <= 0f) return TapZone.MIDDLE
    val edge = ((1f - middleBand.coerceIn(0f, 1f)) / 2f)
    val f = x / width
    return when {
        f < edge -> TapZone.BACK
        f > 1f - edge -> TapZone.FORWARD
        else -> TapZone.MIDDLE
    }
}

/**
 * A run of double taps on the same side, so a second and third tap keep adding.
 *
 * This is the part that makes the gesture feel like a control rather than a shortcut: tap
 * twice for ten seconds, keep tapping for twenty, thirty. A tap on the other side, or after
 * the window has lapsed, starts again from one.
 *
 * @param taps how many jumps have accumulated, so the overlay can say "-30s".
 */
data class SeekRun(val zone: TapZone, val taps: Int, val atMs: Long)

fun advanceRun(previous: SeekRun?, zone: TapZone, nowMs: Long, windowMs: Long = 650L): SeekRun {
    val continues = previous != null && previous.zone == zone && nowMs - previous.atMs <= windowMs
    return SeekRun(zone, if (continues) previous.taps + 1 else 1, nowMs)
}

/**
 * Where a double tap should land the playhead.
 *
 * @param step how far one jump moves; ten seconds is what every other player uses and muscle
 *   memory is worth more here than a novel number.
 *
 * Clamped rather than wrapped: jumping back from three seconds in should sit at the start, not
 * at the end of the file. A forward jump lands one step short of the duration instead of
 * exactly on it, because seeking to the last millisecond just ends playback, which reads as
 * the gesture having skipped the track.
 */
fun doubleTapTarget(
    positionMs: Long,
    durationMs: Long,
    zone: TapZone,
    taps: Int = 1,
    stepMs: Long = 10_000L,
): Long {
    if (durationMs <= 0L) return positionMs
    val jumps = taps.coerceAtLeast(1)
    val delta = when (zone) {
        TapZone.BACK -> -stepMs * jumps
        TapZone.FORWARD -> stepMs * jumps
        TapZone.MIDDLE -> 0L
    }
    if (delta == 0L) return positionMs.coerceIn(0L, durationMs)
    val ceiling = (durationMs - TAIL_MS).coerceAtLeast(0L)
    return (positionMs + delta).coerceIn(0L, maxOf(ceiling, 0L))
}

/** How far short of the end a forward jump stops. */
private const val TAIL_MS = 1_000L

/**
 * `0:07`, `4:03`, `1:02:59`.
 *
 * Hours only appear once there are any: padding every track to `0:04:03` to accommodate the
 * one podcast in the folder makes every other row harder to read.
 */
fun clockOf(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** `-1:23`. Shown beside the elapsed time so the length left is readable without arithmetic. */
fun remainingOf(positionMs: Long, durationMs: Long): String {
    if (durationMs <= 0L) return "0:00"
    return "-" + clockOf((durationMs - positionMs).coerceAtLeast(0L))
}

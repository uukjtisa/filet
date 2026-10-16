package dev.niccc2007.filet.playback

import kotlin.math.atan2

/**
 * Turning the record to move through the track.
 *
 * ## What this is for
 *
 * The scrub bar moves a handle and the sound jumps when you let go. A record does the other
 * thing: it plays while you move it, so you hear where you are going and stop when you hear
 * the right place. That is the whole difference, and it is the one a disc on screen earns -
 * Aurora already draws a turning platter, so giving it the gesture it looks like it has is
 * free in the way that matters.
 *
 * ## Why the geometry is here
 *
 * A drag reports positions, not angles, and turning two points into a signed turn is where
 * this goes wrong in ways nobody sees until they cross the top of the disc: the wrap from
 * 179 degrees to -179 is a two-degree move that naive arithmetic reads as a 358-degree one,
 * which on a scrub means the track leaps most of a minute. Pure and tested for that reason.
 */
object Scratch {

    /**
     * The signed turn, in degrees, between two points around a centre.
     *
     * Positive is clockwise, which is the direction the disc turns and therefore the
     * direction that moves forward through the track.
     */
    fun turn(fromX: Float, fromY: Float, toX: Float, toY: Float, cx: Float, cy: Float): Float {
        val a = Math.toDegrees(atan2((fromY - cy).toDouble(), (fromX - cx).toDouble())).toFloat()
        val b = Math.toDegrees(atan2((toY - cy).toDouble(), (toX - cx).toDouble())).toFloat()
        return normalise(b - a)
    }

    /** Fold any angle into -180..180, so a wrap across the top is the short way round. */
    fun normalise(degrees: Float): Float {
        var d = degrees % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /**
     * How far a turn moves the track.
     *
     * [MS_PER_TURN] is the only number here with taste in it. A real 33rpm record is 1.8
     * seconds a turn, which on a phone-sized disc is far too fine to find anything with -
     * your finger runs out of disc before the track has moved through a chorus. Twenty
     * seconds a revolution puts a three-minute song inside nine turns, so a flick moves
     * through it and a slow drag still lands on a word.
     */
    fun msFor(degrees: Float): Long = (degrees / 360f * MS_PER_TURN).toLong()

    /** Where a scratch lands, kept inside the track. */
    fun seekTo(fromMs: Long, degrees: Float, durationMs: Long): Long =
        seekTo(fromMs, degrees, durationMs, MS_PER_TURN)

    /** As above, with the turn length given. */
    fun seekTo(fromMs: Long, degrees: Float, durationMs: Long, perTurnMs: Float): Long {
        if (durationMs <= 0L) return 0L
        return (fromMs + msFor(degrees, perTurnMs)).coerceIn(0L, durationMs)
    }

    /** As above, with the turn length given. */
    fun msFor(degrees: Float, perTurnMs: Float): Long = (degrees / 360f * perTurnMs).toLong()

    /**
     * How fast to play, and which way, for a finger moving at [degPerSec].
     *
     * It is the same number the position is moving at, expressed as a multiple of the track's
     * own speed: travel one turn's worth of track in one turn's worth of time and that is 1x,
     * by construction. Negative is backwards, which the engine plays as readily as forwards.
     *
     * This is why the disc turns at two speeds. See [MS_PER_TURN_DJ].
     */
    fun speedFor(degPerSec: Float, perTurnMs: Float): Float =
        degPerSec * perTurnMs / 360_000f

    /** How long a turn is worth, given whether the sound is following the hand. */
    fun perTurnMs(dj: Boolean): Float = if (dj) MS_PER_TURN_DJ else MS_PER_TURN

    /** A full revolution of the disc is this much of the track, when seeking silently. */
    const val MS_PER_TURN = 20_000f

    /**
     * And this much when the sound is following the hand.
     *
     * Far shorter, and it has to be. Twenty seconds a revolution is right for FINDING a place
     * - a three-minute song is nine turns - but an ordinary drag covers a couple of hundred
     * degrees a second, which against twenty seconds a turn is eleven times speed and a
     * chirp rather than a sound. At two and a half seconds a turn the same hand movement
     * lands near 1x, which is what a record does and what makes the effect recognisable.
     *
     * The honest cost, stated here because it is a real one: a turn now covers two and a half
     * seconds of track instead of twenty, so crossing a whole song takes many turns. That is
     * also what a record does.
     */
    const val MS_PER_TURN_DJ = 2_500f

    /**
     * How often the decoder is actually told, while a finger is moving.
     *
     * Every frame would be sixty seeks a second at a header each; the sound would be a
     * stutter rather than a scrub. Eighty milliseconds is about twelve a second, which is
     * fast enough that the audio tracks the finger and slow enough that each seek completes.
     */
    const val SEEK_EVERY_MS = 80L
}

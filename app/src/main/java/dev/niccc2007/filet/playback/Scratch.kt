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
     * [MS_PER_TURN] is the only number here with taste in it, and there is now exactly one
     * of it. See the constant.
     */
    fun msFor(degrees: Float): Long = (degrees / 360f * MS_PER_TURN).toLong()

    /** Where a scratch lands, kept inside the track. */
    fun seekTo(fromMs: Long, degrees: Float, durationMs: Long): Long =
        seekTo(fromMs, degrees, durationMs, MS_PER_TURN)

    /** As above, with the turn length given. Kept for the tests that pin the arithmetic. */
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
     * Negative is backwards, which the engine plays as readily as forwards.
     *
     * Only the tests use this now: the engine is driven by WHERE the finger is rather than
     * by how fast it is going, which is the difference between a record and a tape deck with
     * a speed knob. See `ScratchEngine.seekToward`.
     */
    fun speedFor(degPerSec: Float, perTurnMs: Float): Float =
        degPerSec * perTurnMs / 360_000f

    /**
     * A full revolution of the disc is this much of the track.
     *
     * ## There used to be two of these, and that was the whole fault
     *
     * Twenty seconds a turn for silent seeking, two and a half when the sound was following
     * the hand. Two problems, and they compounded:
     *
     *  1. The disc behaved differently depending on a setting buried in a menu, so the same
     *     drag moved the track eight times further on one day than the next. Reported as the
     *     rotation being inconsistent - sometimes barely moving the bar, sometimes fast -
     *     and it was, by a factor of eight.
     *  2. Which of the two was in force was captured ONCE, when the gesture modifier was
     *     built, and the modifier was keyed on the track. So turning the sound on did not
     *     change the number until the next song: the engine ran with the SEEKING constant,
     *     every drag computed a speed eight times too high, and that is clamped to six times
     *     and heard as a chirp. That is "the mixer still doesn't properly play the audio".
     *
     * ONE POINT EIGHT seconds a turn, always, because that is what a 33rpm record is. A
     * hand covering two hundred degrees a second - which is what an ordinary drag across a
     * disc this size measures - then plays at exactly 1x, by construction.
     *
     * It was two and a half, and before that twenty. At 2.5 the same drag is 1.4x and a
     * brisk one is three or four times speed, which is a chipmunk: reported as the sound
     * being so high pitched it was unintelligible. The number was not a matter of taste
     * after all - there is a right answer and it is the speed of the thing being imitated.
     *
     * The honest cost: a turn covers two and a half seconds of track, so crossing a whole
     * song takes many turns. That is also what a record does, and the scrub bar is directly
     * underneath for when crossing a whole song is what you want.
     */
    const val MS_PER_TURN = 1_800f

    /** The range the disc-travel control runs between, in milliseconds per full turn. */
    const val TURN_MIN_MS = 900f
    const val TURN_MAX_MS = 30_000f

    /**
     * The control's 0-to-1 position as a turn length, and back.
     *
     * Geometric. The useful range spans a factor of thirty, and on a linear track everything
     * under five seconds - which is the whole of the record-like half of it - would live in
     * the first sixth of the travel.
     */
    fun turnMsFor(position: Float): Float {
        val p = position.coerceIn(0f, 1f)
        val lo = kotlin.math.ln(TURN_MIN_MS)
        val hi = kotlin.math.ln(TURN_MAX_MS)
        return kotlin.math.exp(lo + (hi - lo) * p)
    }

    /** The inverse of [turnMsFor]. */
    fun turnPosition(ms: Float): Float {
        val lo = kotlin.math.ln(TURN_MIN_MS)
        val hi = kotlin.math.ln(TURN_MAX_MS)
        return ((kotlin.math.ln(ms.coerceIn(TURN_MIN_MS, TURN_MAX_MS)) - lo) / (hi - lo))
            .coerceIn(0f, 1f)
    }

    /**
     * What a thirty-degree move is worth, as words.
     *
     * Thirty degrees because that is about the smallest deliberate nudge a thumb makes on a
     * disc this size, so it is the unit the control is actually being set in.
     */
    fun travelLabel(perTurnMs: Float): String {
        val ms = (perTurnMs / 12f).toLong()
        val much = if (ms < 1000) "${ms}ms" else String.format("%.1f", ms / 1000f) + "s"
        // And what that sounds like, which is the part somebody is actually setting.
        //
        // A 33rpm record turns at two hundred degrees a second, so an ordinary drag across
        // a disc this size plays at exactly 1x when a turn is RECORD_MS. Anything above
        // that is the pitch going up, and saying so is the difference between a number and
        // a control: "so high pitched it was unintelligible" is 2x and over.
        val rate = perTurnMs / RECORD_MS
        return "30° ≈ $much · " + String.format("%.1f", rate) + "×"
    }

    /**
     * A 33rpm record: 1.8 seconds a turn.
     *
     * The reference the speed readout is against, and the default for [MS_PER_TURN], because
     * imitating a record is the whole idea and a record has a speed.
     */
    const val RECORD_MS = 1_800f

    /**
     * How often the PLAYER is told, while a finger is moving and the sound is not following.
     *
     * Every frame would be sixty seeks a second at a header each; the sound would be a
     * stutter rather than a scrub. Eighty milliseconds is about twelve a second, which is
     * fast enough that the audio tracks the finger and slow enough that each seek completes.
     *
     * It does not apply to the scratch engine, which is told where the finger is on every
     * frame - telling it is writing one number.
     */
    const val SEEK_EVERY_MS = 80L
}

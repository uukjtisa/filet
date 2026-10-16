package dev.niccc2007.filet.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import kotlin.math.abs

/**
 * Playing a track at the speed a hand is moving it, forwards or backwards, smoothly.
 *
 * ## Why this exists at all
 *
 * Turning Aurora's disc moved through the track by seeking, which is silent and lands in
 * jumps. What was wanted is the other thing: the sound playing CONTINUOUSLY at whatever
 * speed the finger is going, including backwards, so you hear your way to a place instead of
 * guessing at a bar.
 *
 * `MediaPlayer` cannot do it. It seeks, and it plays between 0.1x and 4x forwards. There is
 * no API on it for backwards at all. So the samples have to be ours: the track as PCM, and
 * an `AudioTrack` fed from it at a fractional read index the finger drives. A negative step
 * is the whole reverse effect and falls out of the same arithmetic.
 *
 * ## The whole track, not a window - which is the second version of this
 *
 * The first held a decoded window around wherever the finger was and swapped in a new one as
 * the hand moved, and every failure it had came out of that: the hand outran the decode and
 * the sound cut out, a fresh window landed centred ahead of where the audio was reading, the
 * refill margin had to be tuned against how fast a thumb moves. The diagnostics ended up
 * showing three decodes inside four hundred milliseconds with the finger nearly still.
 *
 * Every desktop implementation of this - xwax, Mixxx's vinyl control, digitalscratch - keeps
 * the track in RAM, and that is the actual difference between them and what was here. See
 * [TrackPcm] for what that costs and how it is kept small. With the whole track there is no
 * window, no edge and no race: a position is inside the track or it is not.
 *
 * ## Position, not speed
 *
 * A real record has no speed input. It has a position - where your hand is holding the
 * groove - and the speed is whatever that position is doing. So the finger writes a position
 * and the audio converges on it over [CATCH_MS]; speed, direction and silence-when-held all
 * fall out of that one number. Differentiating the finger instead gives you the derivative
 * of a noisy signal, and smoothing that back out puts the sound behind the hand.
 *
 * ## What it costs when nobody is scratching
 *
 * No thread, no `AudioTrack`, no buffer: [close] frees all three, and the ordinary playing
 * path is the same `MediaPlayer` it always was, untouched. The decoded PCM outlives a
 * gesture on purpose - scratching the same track twice is the thing people do - and
 * `TrackPcm.forget()` drops it when nothing on screen can scratch any more.
 */
class ScratchEngine(private val context: Context, private val uri: Uri) {

    @Volatile private var pcm: TrackPcm? = null

    /** WHERE the finger is holding the record, in milliseconds into the track. */
    @Volatile private var targetMs = 0.0

    /** And where the sound actually is, which follows it. */
    @Volatile private var atMs = 0.0

    @Volatile private var running = false
    @Volatile private var spokeAt = 0L
    @Volatile private var lastStep = 0f

    private var track: AudioTrack? = null
    private var pump: Thread? = null

    /** Where the sound is now, for handing back to the player on release. */
    val positionMs: Long get() = atMs.toLong().coerceAtLeast(0L)

    /** Where the sound is now, as a fraction of real time. Negative is backwards. */
    val speedNow: Float get() = lastStep

    /**
     * Read the track. False means it cannot be scratched, and the caller goes back to
     * seeking - an effect that takes the sound away when it cannot work is worse than no
     * effect.
     */
    suspend fun open(aroundMs: Long): Boolean {
        val loaded = TrackPcm.of(context, uri) ?: return false
        pcm = loaded
        atMs = aroundMs.toDouble()
        targetMs = aroundMs.toDouble()
        return true
    }

    /** Begin feeding the speaker. Silent until the record is moved. */
    fun start(): Boolean {
        val p = pcm ?: return false
        val out = AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(p.rate, out, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return false
        val made = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(p.rate)
                        .setChannelMask(out)
                        .build(),
                )
                .setBufferSizeInBytes(minimum * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return false
        track = made
        runCatching { made.play() }.onFailure { return false }
        running = true
        pump = Thread({ pumpLoop(made, p) }, "filet-scratch").also {
            // Audio deserves to win over a list being flung at the same moment.
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        return true
    }

    /**
     * Where the hand is holding the record, in milliseconds into the track.
     *
     * Called on every drag frame. It writes one number, so there is no reason to throttle it
     * and every reason not to: the more often the target arrives, the more exactly the sound
     * is the hand.
     */
    fun seekToward(ms: Long) {
        targetMs = ms.toDouble()
        spokeAt = android.os.SystemClock.elapsedRealtime()
    }

    /** Stop and leave nothing running. The decoded track stays; see [TrackPcm]. */
    fun close() {
        running = false
        runCatching { pump?.join(200) }
        pump = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
        pcm = null
    }

    private fun pumpLoop(out: AudioTrack, p: TrackPcm) {
        val chunk = ShortArray(CHUNK_FRAMES * 2)
        val perFrameMs = 1000.0 / p.rate
        val lastMs = (p.samples.size - 3).toDouble() * 1000.0 / p.rate
        // Both of these live across chunks, because a filter that restarts every 512 frames
        // is a discontinuity every 23 milliseconds.
        var filtered = 0f
        var gain = 0f
        while (running) {
            // A gesture that ended without saying so. The ordinary stop needs no help: a
            // target that is not moving is reached, and a reached target is silence.
            val stale = android.os.SystemClock.elapsedRealtime() - spokeAt > QUIET_MS
            var pos = atMs.coerceIn(0.0, lastMs)
            for (f in 0 until CHUNK_FRAMES) {
                // Per frame, not per chunk: the gap is re-read as the audio closes it, so
                // the approach is exponential rather than twelve steps a second of constant
                // speed, which is audible as a chop on a long drag.
                //
                // A steady hand settles at a steady gap and therefore a steady speed equal
                // to the hand's. A hand that stops is caught and the sound stops with it. A
                // hand that reverses makes the gap negative and the step negative, which is
                // the reverse play - falling out of this line rather than being a mode.
                // READ PER FRAME, not per chunk.
                //
                // Read once a chunk, the target jumps by however far the hand moved in 23
                // milliseconds and the playback rate jumps with it - a sawtooth in pitch at
                // forty-three hertz, which is the other half of the laser noise. It is one
                // volatile read, and the convergence below is what smooths the finger's own
                // sampling rate out of it.
                val target = (if (stale) atMs else targetMs).coerceIn(0.0, lastMs)
                val step = ((target - pos) / CATCH_MS)
                    .coerceIn(-MAX_SPEED.toDouble(), MAX_SPEED.toDouble())
                    .toFloat()
                // ── everything below here changes SMOOTHLY with the speed ──
                //
                // The chirping over a steady drag was two switches, both of them flipping
                // many times a second as a hand wobbles:
                //
                //  1. A box filter that came on above 1.1x with a whole number of taps, so
                //     a hand hovering near a boundary turned a low-pass on and off and
                //     stepped its width - heard as a warble.
                //  2. A hard mute below CREEP, which cuts the waveform at whatever point it
                //     had reached. A mute at a non-zero sample is a click, and a train of
                //     them at the rate a finger wobbles is the "choo choo".
                //
                // Neither needed to be a switch. A one-pole whose coefficient is a
                // continuous function of the speed has no boundary to cross, and a gain
                // that slews to silence has no edge to click on.
                val speed = abs(step)
                val raw = p.at(pos * p.rate / 1000.0)
                // Cutoff at half the new Nyquist, as a one-pole. Above 1x this is the
                // anti-aliasing; at or below it the coefficient clamps to 1 and the filter
                // is not in circuit at all.
                val k = (PI_F / speed.coerceAtLeast(0.001f)).coerceIn(0.10f, 1f)
                filtered += (raw - filtered) * k
                // And a record being held still makes no sound - arrived at rather than
                // jumped to.
                val wanted = ((speed - CREEP) / CREEP_FADE).coerceIn(0f, 1f)
                gain += (wanted - gain) * GAIN_SLEW
                val out16 = (filtered * gain).toInt().coerceIn(-32768, 32767).toShort()
                chunk[f * 2] = out16
                chunk[f * 2 + 1] = out16
                pos += step * perFrameMs
                if (pos < 0.0) pos = 0.0
                if (pos > lastMs) pos = lastMs
                lastStep = step
            }
            atMs = pos
            runCatching { out.write(chunk, 0, chunk.size) }.onFailure { running = false }
        }
    }

    private companion object {
        /** No word from the hand for this long means the gesture ended without saying so. */
        const val QUIET_MS = 120L

        /** Frames written per pass. About 23ms at 22kHz, so the finger's speed arrives promptly. */
        const val CHUNK_FRAMES = 512

        /**
         * Faster than this is not a sound, it is a chirp.
         *
         * Four, down from six. Past about four times speed there is nothing left to hear
         * whatever is done to it, and clamping sooner means a hard flick reads as fast
         * rather than as broken.
         */
        const val MAX_SPEED = 4f

        /** Pi, as a float, for the filter coefficient. */
        const val PI_F = 3.1415927f

        /**
         * How quickly the output reaches full once the record starts moving.
         *
         * About five milliseconds at this rate, which is below the ear's resolution for a
         * level change and far above the one sample a hard switch takes.
         */
        const val GAIN_SLEW = 0.01f

        /** The speed over which it fades in, starting at [CREEP]. */
        const val CREEP_FADE = 0.06f

        /**
         * How long the sound takes to arrive where the finger is, in milliseconds.
         *
         * The time constant of the whole thing, and the only tuning left. Short enough that
         * the sound is the hand rather than an echo of it, long enough that the jitter
         * between two finger samples is absorbed instead of played.
         *
         * It also sets the steady-state lag: a hand moving at 1x sits forty-five
         * milliseconds of track ahead of the sound, which is below what anybody hears as a
         * delay and is, as it happens, roughly the compliance of a real stylus.
         */
        const val CATCH_MS = 45.0

        /** Below this there is no sound to make, and resampling it only produces a hum. */
        const val CREEP = 0.02f
    }
}

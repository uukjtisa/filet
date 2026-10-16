package dev.niccc2007.filet.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor

/**
 * Playing a track at the speed a hand is moving it, forwards or backwards, smoothly.
 *
 * ## Why this exists at all
 *
 * Turning Aurora's disc moved through the track by seeking, which is silent and lands in
 * jumps. What was wanted is the other thing: the sound playing CONTINUOUSLY at whatever speed
 * the finger is going, including backwards, so you hear your way to a place instead of
 * guessing at a bar. Scratching is a thing this makes possible; it is not the point, and
 * nothing here tries to produce it.
 *
 * `MediaPlayer` cannot do it. It seeks, and it plays between 0.1x and 4x forwards. There is
 * no API on it for backwards at all.
 *
 * So the samples have to be ours: decode a window of the track to PCM, and feed an
 * `AudioTrack` from it at a fractional read index that the finger drives. A negative step is
 * the whole reverse effect and falls out of the same arithmetic.
 *
 * ## What it costs when nobody is scratching: nothing
 *
 * That was the condition this was built under and it is the reason for every shape here.
 * Nothing is decoded, allocated, or started until a finger is actually on the disc. There is
 * no background thread, no warm buffer and no pre-render while a track plays normally - the
 * ordinary path is the same `MediaPlayer` it always was, untouched. [close] frees the window
 * and stops the thread; between scratches this object holds a few hundred bytes.
 *
 * It is also off unless somebody turns it on, so the cost is accepted before it is paid
 * rather than discovered. Without it the disc still works and still scrubs - silently, by
 * seeking, exactly as before.
 *
 * ## The window, and running off the end of it
 *
 * A whole song as PCM is tens of megabytes, which is not a thing to hold for a gesture. So a
 * window of [WINDOW_MS] is decoded around wherever the finger started, and the position is
 * tracked in MILLISECONDS rather than as an index into the buffer - which means a new window
 * can be swapped in underneath the audio thread without it noticing. Spin far enough, fast
 * enough, and the edge arrives before the next window does; the sound holds there instead of
 * glitching, and catches up when the decode lands.
 *
 * ## Failure is ordinary here
 *
 * A codec the device will not open, a URI that has gone, a format nothing can decode. Every
 * one of those returns false from [open] and the caller goes back to seeking. An effect that
 * takes the sound away when it cannot work is worse than no effect.
 */
class ScratchEngine(private val context: Context, private val uri: Uri) {

    /** One decoded stretch of the track, immutable so it can be swapped in mid-flight. */
    private class Window(
        val pcm: ShortArray,
        val startMs: Long,
        val rate: Int,
        val channels: Int,
    ) {
        val frames: Int get() = pcm.size / channels
        val endMs: Long get() = startMs + frames * 1000L / rate
    }

    @Volatile private var window: Window? = null

    /**
     * WHERE the finger is, in milliseconds into the track.
     *
     * ## Position, not speed, and that is the whole repair
     *
     * This used to be a speed, differentiated from two drag samples a few milliseconds
     * apart and then smoothed back out with a chase constant. Every part of that was a
     * problem. The derivative of a noisy signal is noise; the smoothing that fixed the noise
     * made the sound lag the hand; and the engine integrating a speed while the picture
     * integrated an angle gave one record two clocks, which drift, which is why the disc,
     * the sound and the scrub bar all said different things.
     *
     * A real record has no speed input. It has a position - where your hand is holding the
     * groove - and the speed is whatever that position is doing. So the finger writes a
     * position here, every frame, and the audio converges on it. Speed, direction and
     * silence-when-held all fall out of that one number, and nothing can drift from it
     * because there is nothing else to drift from.
     */
    @Volatile private var targetMs = 0.0

    /**
     * When the finger was last heard from, and whether a decode is already in flight.
     *
     * The first is a safety net rather than the mechanism it used to be: a target that stops
     * moving already stops the sound, because the gap closes. This only catches a gesture
     * that ended without anyone saying so.
     */
    @Volatile private var spokeAt = 0L
    @Volatile private var recentring = false

    @Volatile private var running = false
    @Volatile private var atMs = 0.0
    private var track: AudioTrack? = null
    private var pump: Thread? = null

    /** Where the sound is now, for handing back to the player on release. */
    val positionMs: Long get() = atMs.toLong().coerceAtLeast(0L)

    @Volatile private var lastStep = 0f

    /**
     * Decode a window around [aroundMs]. False means this track cannot be scratched.
     *
     * Suspending and on IO: it reads and decodes, which is not a main-thread job even for the
     * second or so it takes.
     */
    suspend fun open(aroundMs: Long): Boolean = withContext(Dispatchers.IO) {
        // A SMALL window to begin with.
        //
        // The sound has to start the moment the finger does, and decoding twenty-four
        // seconds of audio takes long enough to be heard as the record not responding at
        // the start of every spin. Eight seconds is a few hundred milliseconds of decode
        // and is immediately replaced: `needsMore` is true against the full edge margin as
        // soon as this lands, so the first drag frame asks for the big one.
        val w = decode(aroundMs, OPEN_MS) ?: return@withContext false
        window = w
        atMs = aroundMs.toDouble()
        targetMs = aroundMs.toDouble()
        true
    }

    /** Begin feeding the speaker. Silent until [setSpeed] is given something non-zero. */
    fun start(): Boolean {
        val w = window ?: return false
        val out = AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(w.rate, out, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return false
        val size = minimum * 2
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
                        .setSampleRate(w.rate)
                        .setChannelMask(out)
                        .build(),
                )
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return false
        track = made
        runCatching { made.play() }.onFailure { return false }
        running = true
        pump = Thread({ pumpLoop(made) }, "filet-scratch").also {
            // Audio deserves to win over a list being flung at the same moment.
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        return true
    }

    /**
     * Where the hand is holding the record, in milliseconds into the track.
     *
     * Called on every drag frame. Writing one number, so there is no reason to throttle it
     * and every reason not to: the more often the target arrives, the more exactly the
     * sound is the hand.
     */
    fun seekToward(ms: Long) {
        targetMs = ms.toDouble()
        spokeAt = android.os.SystemClock.elapsedRealtime()
    }

    /** Where the sound is now, as a fraction of real time. Negative is backwards. */
    val speedNow: Float get() = lastStep

    /** Stop, free the window, and leave nothing running. */
    fun close() {
        running = false
        runCatching { pump?.join(200) }
        pump = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
        window = null
    }

    /**
     * True when the FINGER is near the edge of what has been decoded.
     *
     * The finger, not the sound. The sound is behind the finger by design, so asking where
     * the sound is means the decode is not asked for until the finger is already most of the
     * way to the edge - and on a fast spin it arrives after the hand has left the window,
     * which is heard as the record cutting out. Asking where the HAND is buys the whole lag
     * plus the whole decode.
     */
    fun needsMore(): Boolean {
        val w = window ?: return false
        val at = targetMs
        return at - w.startMs < EDGE_MS || w.endMs - at < EDGE_MS
    }

    /**
     * Decode a fresh window around where the sound is now, and swap it in.
     *
     * Guarded, because this is called from drag events: without it a finger near the edge
     * starts a decode on every frame, and a dozen overlapping reads of the same file is both
     * the stutter and the silence - they finish out of order and the last one to land wins.
     */
    suspend fun recentre() {
        if (recentring) return
        recentring = true
        try {
            // Around the FINGER, and biased in the direction it is going. Centring on the
            // sound decoded a window half of which was already behind, so a hand moving one
            // way reached the far edge again almost immediately and the record cut out
            // every few seconds instead of once.
            val ahead = (lastStep * LOOK_AHEAD_MS).toLong().coerceIn(-WINDOW_MS / 3, WINDOW_MS / 3)
            val around = (targetMs.toLong() + ahead).coerceAtLeast(0L)
            val fresh = withContext(Dispatchers.IO) { decode(around, WINDOW_MS) } ?: return
            // Position is in milliseconds, so the audio thread reads the new window on its
            // next frame and carries on from the same instant. No index to translate.
            window = fresh
            dev.niccc2007.filet.data.Diag.log(TAG, "recentred at ${positionMs}ms")
        } finally {
            recentring = false
        }
    }

    private fun pumpLoop(out: AudioTrack) {
        val chunk = ShortArray(CHUNK_FRAMES * 2)
        while (running) {
            val w = window
            if (w == null) {
                java.util.Arrays.fill(chunk, 0)
                runCatching { out.write(chunk, 0, chunk.size) }
                continue
            }
            // A gesture that ended without saying so. The ordinary stop needs no help:
            // a target that is not moving is reached, and a reached target is silence.
            val stale = android.os.SystemClock.elapsedRealtime() - spokeAt > QUIET_MS
            // CLAMPED to what has actually been decoded.
            //
            // Without this a hand that outran the decode left the target outside the window,
            // the read index went off the end, and the engine wrote silence - which is the
            // record cutting out mid-spin. Clamped, the sound runs to the edge of what is
            // loaded and waits there, still playing the moment the next window lands. A
            // record that briefly stops catching up is a record; one that goes silent is a
            // bug.
            val asked = if (stale) atMs else targetMs
            val target = asked.coerceIn(
                (w.startMs + EDGE_GUARD_MS).toDouble(),
                (w.endMs - EDGE_GUARD_MS).toDouble(),
            )
            var pos = atMs
            val perFrameMs = 1000.0 / w.rate
            for (f in 0 until CHUNK_FRAMES) {
                // Per frame, not per chunk: the gap is re-read as the audio closes it, so
                // the approach is exponential rather than twelve steps a second of constant
                // speed - which is audible as a chop on a long drag.
                //
                // CATCH_MS is the time constant. A steady hand settles at a steady gap and
                // therefore a steady speed equal to the hand's; a hand that stops is caught
                // and the sound stops with it; a hand that reverses makes the gap negative
                // and the step negative, which is the reverse play, falling out of the same
                // line rather than being a mode.
                val step = ((target - pos) / CATCH_MS)
                    .coerceIn(-MAX_SPEED.toDouble(), MAX_SPEED.toDouble())
                    .toFloat()
                val offset = (pos - w.startMs) * w.rate / 1000.0
                if (abs(step) < CREEP || offset < 1.0 || offset > w.frames - 2.0) {
                    // Off the edge of the window, or a record somebody is holding still.
                    chunk[f * 2] = 0
                    chunk[f * 2 + 1] = 0
                } else {
                    val i = floor(offset).toInt()
                    val frac = (offset - i).toFloat()
                    // Linear interpolation between neighbouring frames. At a fractional rate
                    // the read lands between samples, and taking the nearest instead is
                    // audible as a rasp on anything slower than about half speed.
                    chunk[f * 2] = lerp(w, i, i + 1, 0, frac)
                    chunk[f * 2 + 1] = if (w.channels > 1) lerp(w, i, i + 1, 1, frac) else chunk[f * 2]
                }
                pos += step * perFrameMs
                lastStep = step
            }
            atMs = pos.coerceIn(0.0, Double.MAX_VALUE)
            runCatching { out.write(chunk, 0, chunk.size) }.onFailure { running = false }
        }
    }

    private fun lerp(w: Window, a: Int, b: Int, channel: Int, frac: Float): Short {
        val first = w.pcm[a * w.channels + channel].toFloat()
        val second = w.pcm[b * w.channels + channel].toFloat()
        return (first + (second - first) * frac).toInt().coerceIn(-32768, 32767).toShort()
    }

    /** Decode [spanMs] of PCM centred on [aroundMs]. Null when the track cannot be read. */
    private fun decode(aroundMs: Long, spanMs: Long): Window? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audio = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audio = i
                    format = f
                    break
                }
            }
            val fmt = format ?: return null
            if (audio < 0) return null
            extractor.selectTrack(audio)

            val startMs = (aroundMs - spanMs / 2).coerceAtLeast(0L)
            extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // Where the decoder ACTUALLY starts, which is the sync point before the request
            // and can be seconds earlier.
            val actualStartUs = extractor.sampleTime.coerceAtLeast(0L)
            val lead = ((startMs * 1000L - actualStartUs) / 1000L).coerceAtLeast(0L)

            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            // Enough to cover the lead-in AND the window, or the position the finger is
            // on can sit outside what was decoded - which is silence, intermittently,
            // depending entirely on where the nearest sync point happened to be. That was
            // most of "sometimes it is just silent".
            val span = spanMs + lead
            val wanted = (span * rate / 1000).toInt() * channels
            val out = ShortArray(wanted)
            var written = 0
            val info = MediaCodec.BufferInfo()
            var fed = false

            while (written < wanted) {
                if (!fed) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val read = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            fed = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        // TWO things, and without either of them this is noise.
                        //
                        // A decoder's output buffer is a window into a larger one: the audio
                        // for this frame starts at `info.offset` and runs for `info.size`,
                        // and everything else in it is whatever was there before. Reading the
                        // whole capacity reads stale bytes as audio.
                        //
                        // And a ByteBuffer is BIG-endian until told otherwise, while PCM16
                        // out of MediaCodec is in the machine's own order, which on every
                        // Android device is little. Taking shorts out of it without saying so
                        // swaps the two bytes of every sample - a quiet passage becomes full
                        // scale, a waveform becomes white noise, and an onset detector reading
                        // it finds beats everywhere and nowhere.
                        //
                        // This is why the record made no sound worth hearing and the ring
                        // pulsed on nothing.
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val shorts = buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                        val take = minOf(shorts.remaining(), wanted - written)
                        shorts.get(out, written, take)
                        written += take
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && fed) {
                    break
                }
            }
            if (written < channels * 2) return null
            android.util.Log.i(
                TAG,
                "window ${actualStartUs / 1000}ms +${written / channels * 1000L / rate}ms " +
                    "rate=$rate ch=$channels asked=${aroundMs}ms lead=${lead}ms",
            )
            return Window(
                pcm = if (written == out.size) out else out.copyOf(written),
                startMs = actualStartUs / 1000L,
                rate = rate,
                channels = channels,
            )
        } catch (e: Throwable) {
            dev.niccc2007.filet.data.Diag.log(TAG, "decode failed: $e")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val TAG = "FiletScratch"

        /** No word from the hand for this long means the hand has stopped. */
        const val QUIET_MS = 90L

        /**
         * How much of the track is held as PCM at once.
         *
         * Twenty-four seconds. At 44.1kHz stereo that is about four megabytes - a gesture's
         * worth of memory, freed the moment the finger lifts. It was twelve, which at the
         * speeds a hand actually reaches is about two seconds of spinning before the edge,
         * and the edge was silence.
         */
        const val WINDOW_MS = 24_000L

        /**
         * How close to an edge counts as needing a fresh window.
         *
         * Generous, because the decode is not instant and the thing it is racing is a hand.
         * At six times speed this is a second and a half of warning, which is about what a
         * decode of this size takes.
         */
        const val EDGE_MS = 9_000L

        /** How far outside the decoded window the sound may be asked to go. Not at all. */
        const val EDGE_GUARD_MS = 120L

        /** How far ahead of the hand a fresh window is centred, per unit of speed. */
        const val LOOK_AHEAD_MS = 3_000f

        /** The first window, decoded on touch-down, sized to be quick rather than large. */
        const val OPEN_MS = 8_000L

        /** Frames written per pass. About 12ms, so the finger's speed arrives promptly. */
        const val CHUNK_FRAMES = 512

        /** Faster than this is not a sound, it is a chirp. */
        const val MAX_SPEED = 6f

        /**
         * How long the sound takes to arrive where the finger is, in milliseconds.
         *
         * The time constant of the whole thing, and the only tuning left. Forty-five is
         * short enough that the sound is the hand rather than an echo of it, and long enough
         * that the jitter between two finger samples is absorbed instead of played - which
         * is what the old chase constant was for, done where it cannot cause drift.
         *
         * It also sets the steady-state lag: a hand moving at 1x sits forty-five
         * milliseconds of track ahead of the sound, which is below what anybody hears as a
         * delay and is, as it happens, roughly the compliance of a real stylus.
         */
        const val CATCH_MS = 45.0

        /** Below this there is no sound to make, and resampling it only produces a hum. */
        const val CREEP = 0.02f

        const val TIMEOUT_US = 5_000L
    }
}

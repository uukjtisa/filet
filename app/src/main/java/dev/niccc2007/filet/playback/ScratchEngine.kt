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
 * Playing a track the way a hand moves a record: at any speed, in either direction.
 *
 * ## Why this exists at all
 *
 * Turning Aurora's disc already moved through the track, by seeking. Seeking is not what a
 * record does - a record PLAYS while it moves, which is where the sound everybody recognises
 * comes from - and `MediaPlayer` cannot produce it. It seeks, and it plays between 0.1x and
 * 4x forwards. There is no API on it for backwards at all.
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
    @Volatile private var speed = 0f
    @Volatile private var running = false
    @Volatile private var atMs = 0.0
    private var track: AudioTrack? = null
    private var pump: Thread? = null

    /** Where the sound is now, for handing back to the player on release. */
    val positionMs: Long get() = atMs.toLong().coerceAtLeast(0L)

    /**
     * Decode a window around [aroundMs]. False means this track cannot be scratched.
     *
     * Suspending and on IO: it reads and decodes, which is not a main-thread job even for the
     * second or so it takes.
     */
    suspend fun open(aroundMs: Long): Boolean = withContext(Dispatchers.IO) {
        val w = decode(aroundMs) ?: return@withContext false
        window = w
        atMs = aroundMs.toDouble()
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
     * How fast, and which way.
     *
     * 1 is forward at the track's own speed, -1 is backward at the same speed, 0 is a stopped
     * record - which is silence, not a pause, because a stopped record makes no sound.
     */
    fun setSpeed(value: Float) {
        speed = value.coerceIn(-MAX_SPEED, MAX_SPEED)
    }

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

    /** True when the finger is near the edge of what has been decoded. */
    fun needsMore(): Boolean {
        val w = window ?: return false
        val at = atMs
        return at - w.startMs < EDGE_MS || w.endMs - at < EDGE_MS
    }

    /** Decode a fresh window around where the sound is now, and swap it in. */
    suspend fun recentre() {
        val fresh = withContext(Dispatchers.IO) { decode(positionMs) } ?: return
        // Position is in milliseconds, so the audio thread reads the new window on its next
        // frame and carries on from the same instant of the track. No index to translate.
        window = fresh
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
            val step = speed
            var pos = atMs
            val perFrameMs = 1000.0 / w.rate
            for (f in 0 until CHUNK_FRAMES) {
                val offset = (pos - w.startMs) * w.rate / 1000.0
                if (step == 0f || offset < 1.0 || offset > w.frames - 2.0) {
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

    /** Decode [WINDOW_MS] of PCM centred on [aroundMs]. Null when the track cannot be read. */
    private fun decode(aroundMs: Long): Window? {
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

            val startMs = (aroundMs - WINDOW_MS / 2).coerceAtLeast(0L)
            extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val actualStartUs = extractor.sampleTime.coerceAtLeast(0L)

            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val wanted = (WINDOW_MS * rate / 1000).toInt() * channels
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
            return Window(
                pcm = if (written == out.size) out else out.copyOf(written),
                startMs = actualStartUs / 1000L,
                rate = rate,
                channels = channels,
            )
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        /**
         * How much of the track is held as PCM at once.
         *
         * Twelve seconds. At 44.1kHz stereo that is about two megabytes - a gesture's worth of
         * memory, freed the moment the finger lifts - and it is far enough either way that an
         * ordinary scratch never reaches the edge.
         */
        const val WINDOW_MS = 12_000L

        /** How close to an edge counts as needing a fresh window. */
        const val EDGE_MS = 2_500L

        /** Frames written per pass. About 12ms, so the finger's speed arrives promptly. */
        const val CHUNK_FRAMES = 512

        /** Faster than this is not a sound, it is a chirp. */
        const val MAX_SPEED = 6f

        const val TIMEOUT_US = 5_000L
    }
}

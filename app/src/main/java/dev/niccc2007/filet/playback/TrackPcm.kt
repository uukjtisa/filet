package dev.niccc2007.filet.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A whole track in memory as PCM, for anything that has to read it at an arbitrary speed.
 *
 * ## Why the whole track, and why this replaced a sliding window
 *
 * The scratch engine used to decode a window around wherever the finger was and swap in a
 * new one as the hand moved. Every failure it had came from that one decision: the hand
 * outran the decode and the sound cut out; a window landed centred ahead of where the audio
 * was reading and the read index fell outside it; the refill margin and the window size had
 * to be tuned against each other and against how fast a thumb moves, which is not a thing
 * that has a right answer.
 *
 * Every desktop implementation of this - xwax, Mixxx's vinyl control, digitalscratch - holds
 * the track in RAM instead, and that is the actual difference between them and what was
 * here. With the whole track there is no window, no edge, no refill, no race: a position is
 * either inside the track or it is not.
 *
 * ## What it costs, and how that is kept to something reasonable
 *
 * **Mono, and downsampled.** Scratching is a lo-fi effect by construction - playing samples
 * back at a fractional rate is what it IS - so the second channel and the top octave are
 * paying for nothing. Four minutes at [TARGET_RATE] mono is about ten megabytes, where the
 * same track at 44.1kHz stereo would be forty.
 *
 * A box filter rather than nearest-neighbour on the way down. Decimating by taking every
 * second sample folds everything above the new Nyquist back into the audible range as a
 * metallic hiss, and averaging the samples that are being dropped costs one add each.
 *
 * **Capped.** A track that would need more than [MAX_BYTES] is decoded at a lower rate
 * rather than refused: a long mix scratches slightly duller instead of not at all.
 *
 * ## Nothing is held between gestures that nobody asked for
 *
 * It is read on demand and cached by URI, two deep, because the thing people actually do is
 * scratch the same track repeatedly. [forget] empties it; the player calls that when the
 * face changes away from the one with a record on it.
 */
class TrackPcm(
    /** Mono samples, one per frame, at [rate]. */
    val samples: ShortArray,
    val rate: Int,
) {
    val durationMs: Long get() = samples.size * 1000L / rate

    /**
     * One sample at a fractional index, interpolated.
     *
     * Catmull-Rom across four neighbours rather than linear across two. At a fractional
     * read rate - which is every moment of this - linear interpolation is a low-pass filter
     * whose corner moves with the speed, heard as the sound going dull as the hand slows.
     * Four-point cubic costs a handful of multiplies and does not.
     */
    fun at(x: Double): Float {
        val i = kotlin.math.floor(x).toInt()
        if (i < 1 || i + 2 >= samples.size) return 0f
        val f = (x - i).toFloat()
        val p0 = samples[i - 1].toFloat()
        val p1 = samples[i].toFloat()
        val p2 = samples[i + 1].toFloat()
        val p3 = samples[i + 2].toFloat()
        return p1 + 0.5f * f * (
            (p2 - p0) + f * (
                (2f * p0 - 5f * p1 + 4f * p2 - p3) + f * (3f * (p1 - p2) + p3 - p0)
                )
            )
    }

    companion object {
        private const val TAG = "FiletScratch"

        /**
         * What the track is resampled to.
         *
         * 22.05kHz: half of CD, so the box filter is an exact pair average on the common
         * case, and eleven kilohertz of bandwidth, which is more than a record gives you
         * once you are moving it by hand.
         */
        const val TARGET_RATE = 22_050

        /** The least it will drop to for a long track rather than give up. */
        const val FLOOR_RATE = 11_025

        /** About ten megabytes of shorts. A gesture's worth of memory, not a session's. */
        const val MAX_BYTES = 11L * 1024 * 1024

        private val cache = LinkedHashMap<String, TrackPcm>(4, 0.75f, true)

        /** Drop everything held. Called when nothing on screen can scratch any more. */
        fun forget() {
            synchronized(cache) { cache.clear() }
        }

        /**
         * The track, decoded, from memory when it has been read before.
         *
         * Suspending and on IO. It reads and decodes a whole file, which on a modern phone
         * is a fraction of a second and is never a main-thread job whatever it is.
         */
        suspend fun of(context: Context, uri: Uri): TrackPcm? {
            val key = uri.toString()
            synchronized(cache) { cache[key] }?.let { return it }
            val made = withContext(Dispatchers.IO) { decode(context, uri) } ?: return null
            synchronized(cache) {
                cache[key] = made
                while (cache.size > KEEP) {
                    val oldest = cache.keys.firstOrNull() ?: break
                    cache.remove(oldest)
                }
            }
            return made
        }

        private const val KEEP = 2

        private fun decode(context: Context, uri: Uri): TrackPcm? {
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
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
                val srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                val durationUs = runCatching { fmt.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)

                // Pick the rate from how long the track is, so a long mix is duller rather
                // than refused. Two bytes a sample.
                val seconds = (durationUs / 1_000_000.0).coerceAtLeast(1.0)
                var rate = TARGET_RATE
                while (rate > FLOOR_RATE && seconds * rate * 2 > MAX_BYTES) rate /= 2
                if (rate < FLOOR_RATE) rate = FLOOR_RATE

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, null, null, 0)
                codec.start()

                val step = srcRate.toDouble() / rate
                val out = ShortArrayBuilder(((seconds + 2) * rate).toInt().coerceAtLeast(1024))
                var sum = 0.0
                var n = 0
                var sinceEmit = 0.0
                val info = MediaCodec.BufferInfo()
                var fed = false

                while (true) {
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
                            // The frame is the slice at info.offset, and PCM16 out of
                            // MediaCodec is in the machine's byte order rather than a
                            // ByteBuffer's big-endian default. Both, or this is noise -
                            // see the same note in `BeatMap`.
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts =
                                buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                            while (shorts.remaining() >= channels) {
                                var frame = 0
                                for (c in 0 until channels) frame += shorts.get().toInt()
                                sum += frame.toDouble() / channels
                                n++
                                sinceEmit += 1.0
                                if (sinceEmit >= step) {
                                    sinceEmit -= step
                                    out.add((sum / n).toInt().coerceIn(-32768, 32767).toShort())
                                    sum = 0.0
                                    n = 0
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && fed) {
                        break
                    }
                }
                if (out.size < rate / 4) {
                    dev.niccc2007.filet.data.Diag.log(TAG, "pcm too short: ${out.size}")
                    return null
                }
                val made = TrackPcm(out.toArray(), rate)
                dev.niccc2007.filet.data.Diag.log(
                    TAG,
                    "pcm ${made.durationMs}ms at ${rate}Hz, ${out.size * 2 / 1024}KB",
                )
                return made
            } catch (e: Throwable) {
                dev.niccc2007.filet.data.Diag.log(TAG, "pcm failed: $e")
                return null
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
            }
        }

        private const val TIMEOUT_US = 5_000L
    }
}

/**
 * A growable ShortArray.
 *
 * `ArrayList<Short>` boxes every sample, which for ten million of them is ten million
 * objects and a minute of garbage collection. This is the same thing without that.
 */
private class ShortArrayBuilder(initial: Int) {
    private var data = ShortArray(initial)
    var size = 0
        private set

    fun add(v: Short) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): ShortArray = if (size == data.size) data else data.copyOf(size)
}

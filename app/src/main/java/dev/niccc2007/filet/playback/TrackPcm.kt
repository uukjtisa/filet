package dev.niccc2007.filet.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * A whole track in memory as PCM, for anything that has to read it at an arbitrary speed.
 *
 * ## Why the whole track, not a sliding window
 *
 * The scratch engine once decoded a window around the finger and swapped in new ones as the hand
 * moved; every failure it had came from that (the hand outran the decode, a window landed ahead
 * of the read index, refill margins had no right answer). xwax, Mixxx and digitalscratch all hold
 * the track in RAM. So does this: a position is inside the track or it is not.
 *
 * ## Filled in place, from the playhead outward
 *
 * Bug identified here: the disc did nothing until the WHOLE file had decoded. `ScratchEngine.open`
 * awaited `of()`, which ran the codec from the first frame to the last before returning, so the
 * first touch waited a second or two on a four-minute track - longer when the playhead was near
 * the end, because that region was decoded last. A real deck answers the first touch.
 *
 * Now the buffer is allocated at full length the moment the header is read (milliseconds), and
 * the decode writes into it by presentation time: first from just behind the playhead to the end,
 * then from the start up to where it began. Anything not yet written reads as silence. So the
 * engine starts on touch with whatever exists, the region under the finger arrives within tens of
 * milliseconds, and the rest improves behind it. [complete] says when it is done; only then is it
 * cached and written to disk.
 *
 * One decode per URI, shared: the beat map's [of] and the engine's [live] join whichever is
 * running, so the two never read the same file twice into two buffers.
 *
 * ## Memory bound
 *
 * Mono, downsampled with a box filter (decimating without one folds the top octave back in as
 * hiss), at [TARGET_RATE], halved toward [FLOOR_RATE] until the track fits [MAX_BYTES]. One track
 * is held ([KEEP]), so the steady state is at most 11 MiB of shorts whoever asked for it. When
 * the container states no duration the buffer is sized to [MAX_BYTES] and trimmed once at the
 * end, which is the one moment two copies exist.
 */
class TrackPcm(
    /** Mono samples, one per frame, at [rate]. Unwritten frames are zero while [complete] is false. */
    val samples: ShortArray,
    val rate: Int,
) {
    val durationMs: Long get() = samples.size * 1000L / rate

    /** True once every frame the decoder produced has been written. */
    @Volatile var complete: Boolean = true
        internal set

    /**
     * One sample at a fractional index, interpolated.
     *
     * Catmull-Rom across four neighbours rather than linear across two: at a fractional read
     * rate, linear interpolation is a low-pass whose corner moves with the speed, heard as the
     * sound going dull as the hand slows.
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
         * The rate the samples are at, from how many there are and how long the track is.
         *
         * A long track once played an octave high: samples at one rate wearing a label for
         * another. Placement by presentation time and the decoder's own output format now keep
         * the label right by construction; this stays as the check run on completion, and is
         * logged rather than applied when it disagrees.
         */
        fun trueRate(count: Int, declared: Int, durationMs: Long): Int {
            if (count <= 0 || durationMs <= 0L) return declared
            val measured = (count * 1000L / durationMs).toInt()
            if (measured !in FLOOR_RATE / 4..192_000) return declared
            val drift = kotlin.math.abs(measured - declared).toFloat() / declared
            return if (drift > RATE_SLACK) measured else declared
        }

        /** How far the measured rate may sit from the declared one before it is believed. */
        const val RATE_SLACK = 0.08f

        /** 22.05 kHz: half of CD, so the box filter is a pair average on the common case. */
        const val TARGET_RATE = 22_050

        /** The least it will drop to for a long track rather than give up. */
        const val FLOOR_RATE = 11_025

        /** About eleven megabytes of shorts. A gesture's worth of memory, not a session's. */
        const val MAX_BYTES = 11L * 1024 * 1024

        /**
         * The rate a track of [durationMs] is held at. Pure.
         *
         * Halved from [TARGET_RATE] until it fits [MAX_BYTES], never below [FLOOR_RATE]: a long
         * mix scratches slightly duller instead of not at all.
         */
        fun rateFor(durationMs: Long): Int {
            val seconds = (durationMs / 1000.0).coerceAtLeast(1.0)
            var rate = TARGET_RATE
            while (rate > FLOOR_RATE && seconds * rate * 2 > MAX_BYTES) rate /= 2
            // Past about eight and a half minutes even the floor overflows the budget. The rate
            // then drops to whatever fits, down to [LOWEST_RATE], so a long mix is held whole
            // and duller rather than cut off; only beyond that is the tail left silent.
            if (seconds * rate * 2 > MAX_BYTES) rate = (MAX_BYTES / 2 / seconds).toInt()
            return rate.coerceIn(LOWEST_RATE, TARGET_RATE)
        }

        /** The least any track is held at; below this it is no longer recognisably the track. */
        const val LOWEST_RATE = 4_000

        /** Frames to allocate. Unknown duration gets the whole budget, trimmed at the end. Pure. */
        fun framesFor(durationMs: Long, rate: Int): Int =
            if (durationMs <= 0L) (MAX_BYTES / 2).toInt()
            else ((durationMs * rate / 1000L) + rate / 2).coerceAtMost(MAX_BYTES / 2).toInt()

        /**
         * Where the decode starts: a little behind [aroundMs], so a scratch backwards from the
         * playhead has audio as soon as a scratch forwards does. Pure.
         */
        fun startFor(aroundMs: Long, durationMs: Long): Long {
            val back = (aroundMs - BEHIND_MS).coerceAtLeast(0L)
            return if (durationMs > 0L) back.coerceAtMost(durationMs) else back
        }

        /** The frame a presentation time lands on. Pure. */
        fun frameAt(ptsUs: Long, rate: Int): Int = (ptsUs * rate / 1_000_000L).toInt()

        /** How far behind the playhead the first pass begins. */
        const val BEHIND_MS = 4_000L

        private val cache = LinkedHashMap<String, TrackPcm>(4, 0.75f, true)

        /**
         * A decode in progress: the buffer it is filling, the finished result, and the job.
         *
         * [pinned] marks a decode the engine is reading. Those are not cancelled when the beat
         * map's waiter leaves, only by [forget] or by a scratch on another track.
         */
        private class Shared {
            val buffer = kotlinx.coroutines.CompletableDeferred<TrackPcm?>()
            val done = kotlinx.coroutines.CompletableDeferred<TrackPcm?>()
            var job: kotlinx.coroutines.Job? = null
            var waiters = 0
            var pinned = false
        }

        private val inFlight = HashMap<String, Shared>()

        /** Owns every decode, so none is an orphan of a scope that no longer exists. */
        private val decodes = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
        )

        /** What has already been read in full, without reading anything. */
        fun cached(uri: Uri): TrackPcm? = synchronized(cache) { cache[uri.toString()] }

        /** Drop everything held, and stop any decode in progress. */
        fun forget() {
            synchronized(cache) { cache.clear() }
            synchronized(inFlight) {
                inFlight.values.forEach { it.job?.cancel() }
                inFlight.clear()
            }
        }

        /**
         * The track as a buffer that may still be filling, returned as soon as it exists.
         *
         * For the scratch engine. Waits only for the header - never for the decode - and a
         * decode it starts begins just behind [aroundMs]. Null when the file has no audio track
         * or cannot be opened.
         */
        suspend fun live(context: Context, uri: Uri, aroundMs: Long): TrackPcm? {
            val key = uri.toString()
            synchronized(cache) { cache[key] }?.let { return it }
            val shared = synchronized(inFlight) {
                // One engine-driven decode at a time: a scratch on another track stops the old one.
                val stale = inFlight.entries.filter { (k, s) -> k != key && s.pinned }
                stale.forEach { (k, s) -> s.job?.cancel(); inFlight.remove(k) }
                (inFlight[key] ?: start(context, uri, key, aroundMs, null).also { inFlight[key] = it })
                    .also { it.pinned = true }
            }
            return shared.buffer.await()
        }

        /**
         * The whole track, decoded, from memory or disk when it has been read before.
         *
         * For the beat map, which needs it complete. Joins a decode already running - including
         * one the engine started - rather than starting a second. [onEarly] is told about the
         * opening seconds, and only when this call is the one that starts the decode.
         */
        suspend fun of(
            context: Context,
            uri: Uri,
            onEarly: ((TrackPcm) -> Unit)? = null,
        ): TrackPcm? {
            val key = uri.toString()
            synchronized(cache) { cache[key] }?.let { return it }
            val stamp = withContext(Dispatchers.IO) { AudioCache.keyFor(context, uri) }
            if (stamp != null) {
                val saved = withContext(Dispatchers.IO) { AudioCache.readPcm(context, stamp) }
                if (saved != null) {
                    synchronized(cache) { cache[key] = saved }
                    return saved
                }
            }
            val shared = synchronized(inFlight) {
                (inFlight[key] ?: start(context, uri, key, 0L, onEarly).also { inFlight[key] = it })
                    .also { it.waiters++ }
            }
            try {
                return shared.done.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // THIS caller went away. The last unpinned waiter stops the decode; rethrown so
                // the caller's own cancellation is never turned into a null.
                synchronized(inFlight) {
                    if (shared.waiters <= 1 && !shared.pinned) {
                        shared.job?.cancel()
                        if (inFlight[key] === shared) inFlight.remove(key)
                    }
                }
                throw e
            } finally {
                synchronized(inFlight) { shared.waiters-- }
            }
        }

        private fun start(context: Context, uri: Uri, key: String, aroundMs: Long, onEarly: ((TrackPcm) -> Unit)?): Shared {
            val shared = Shared()
            val job = decodes.launch {
                val made = decode(context, uri, aroundMs, onEarly, { shared.buffer.complete(it) }) { isActive }
                // A decode that failed before the buffer existed still has to release `live`.
                shared.buffer.complete(made)
                synchronized(inFlight) { if (inFlight[key] === shared) inFlight.remove(key) }
                if (made != null) {
                    synchronized(cache) {
                        cache[key] = made
                        while (cache.size > KEEP) cache.remove(cache.keys.firstOrNull() ?: break)
                    }
                    AudioCache.keyFor(context, uri)?.let { AudioCache.writePcm(context, it, made) }
                }
                shared.done.complete(made)
            }
            job.invokeOnCompletion { cause ->
                if (cause != null) {
                    shared.buffer.complete(null)
                    shared.done.complete(null)
                }
            }
            shared.job = job
            return shared
        }

        /** One track, which is eleven megabytes at most. The one being played. */
        private const val KEEP = 1

        /**
         * Decode [uri] into a buffer allocated up front, playhead region first.
         *
         * [onAllocated] receives the buffer the moment the header has been read. Returns the
         * finished track - trimmed if its length had to be guessed - or null on failure.
         */
        private fun decode(
            context: Context,
            uri: Uri,
            aroundMs: Long,
            onEarly: ((TrackPcm) -> Unit)?,
            onAllocated: (TrackPcm) -> Unit,
            alive: () -> Boolean,
        ): TrackPcm? {
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
                extractor.selectTrack(audio)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
                var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                val durationMs = runCatching { fmt.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L) / 1000L

                val rate = rateFor(durationMs)
                val pcm = TrackPcm(ShortArray(framesFor(durationMs, rate)), rate).apply { complete = false }
                onAllocated(pcm)
                val buf = pcm.samples

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, null, null, 0)
                codec.start()

                val begin = startFor(aroundMs, durationMs)
                // Two passes: [begin, end], then [0, begin). Each is a seek and a flush.
                val passes = if (begin > 0L) listOf(begin to Long.MAX_VALUE, 0L to begin) else listOf(0L to Long.MAX_VALUE)
                var highest = 0
                var earlyAt = if (onEarly != null && begin == 0L) rate * EARLY_SECONDS else Int.MAX_VALUE
                val info = MediaCodec.BufferInfo()

                for ((from, until) in passes) {
                    extractor.seekTo(from * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    codec.flush()
                    var fed = false
                    // Synced from the presentation time of the first buffer after each seek, then
                    // advanced sample by sample, so buffer boundaries cannot leave a seam.
                    var write = -1
                    var sum = 0.0
                    var n = 0
                    var sinceEmit = 0.0
                    passLoop@ while (true) {
                        if (!alive()) return null
                        if (!fed) {
                            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                            if (inIndex >= 0) {
                                val input = codec.getInputBuffer(inIndex)
                                val read = if (input == null) -1 else extractor.readSampleData(input, 0)
                                val past = until != Long.MAX_VALUE && extractor.sampleTime >= until * 1000L
                                if (read < 0 || past) {
                                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    fed = true
                                } else {
                                    codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // The decoder's word, not the container's. HE-AAC declares half the
                            // rate it decodes to, which is the octave-high fault in one line.
                            val o = codec.outputFormat
                            runCatching { srcRate = o.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                            runCatching { channels = o.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) }
                        } else if (outIndex >= 0) {
                            val output = codec.getOutputBuffer(outIndex)
                            if (output != null && info.size > 0) {
                                if (write < 0) write = frameAt(info.presentationTimeUs, rate).coerceAtLeast(0)
                                val step = srcRate.toDouble() / rate
                                // The slice at info.offset, in native byte order - see `BeatMap`.
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                val shorts = output.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                                while (shorts.remaining() >= channels) {
                                    var frame = 0
                                    for (c in 0 until channels) frame += shorts.get().toInt()
                                    sum += frame.toDouble() / channels
                                    n++
                                    sinceEmit += 1.0
                                    if (sinceEmit >= step) {
                                        sinceEmit -= step
                                        if (write in buf.indices) {
                                            buf[write] = (sum / n).toInt().coerceIn(-32768, 32767).toShort()
                                            if (write > highest) highest = write
                                        }
                                        write++
                                        sum = 0.0
                                        n = 0
                                        if (write >= earlyAt) {
                                            earlyAt = Int.MAX_VALUE
                                            onEarly?.invoke(TrackPcm(buf.copyOf(write.coerceAtMost(buf.size)), rate))
                                        }
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            // Feeds the beat-map panel's "reading the file" phase. One volatile
                            // read unless this URI is the one the screen is waiting on, so it
                            // costs nothing on the decode path it reports.
                            // `durationMs` is the container's length, read from the track
                            // format above; it is 0 when the container does not declare one,
                            // and then there is no fraction to report.
                            if (durationMs > 0L && info.presentationTimeUs > 0) {
                                dev.niccc2007.filet.playback.BeatWork.reportDecode(
                                    uri.toString(),
                                    (info.presentationTimeUs / 1000.0 / durationMs).toFloat(),
                                )
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@passLoop
                        } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && fed) {
                            break@passLoop
                        }
                    }
                }

                if (highest < rate / 4) {
                    dev.niccc2007.filet.data.Diag.log(TAG, "pcm too short: $highest")
                    return null
                }
                // Trimmed only when the length was a guess; a stated duration is trusted, and an
                // eleven-megabyte copy to drop a fraction of a second of zeros is not worth it.
                val made = if (durationMs <= 0L) TrackPcm(buf.copyOf(highest + 1), rate) else pcm
                made.complete = true
                pcm.complete = true
                if (trueRate(highest + 1, rate, durationMs) != rate) {
                    dev.niccc2007.filet.data.Diag.log(TAG, "pcm extent disagrees: ${highest + 1} frames at ${rate}Hz for ${durationMs}ms")
                }
                dev.niccc2007.filet.data.Diag.log(TAG, "pcm ${made.durationMs}ms at ${rate}Hz, ${made.samples.size * 2 / 1024}KB, from ${begin}ms")
                return made
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                dev.niccc2007.filet.data.Diag.log(TAG, "pcm failed: $e")
                return null
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
            }
        }

        /** How much of the opening is handed to the beat map early. */
        private const val EARLY_SECONDS = 4

        private const val TIMEOUT_US = 5_000L
    }
}

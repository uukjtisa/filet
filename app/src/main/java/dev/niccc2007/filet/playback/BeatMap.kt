package dev.niccc2007.filet.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where the beats are, so a light can pulse on them instead of on a timer.
 *
 * ## Why there is no other way to do this
 *
 * The obvious route is `android.media.audiofx.Visualizer`, which hands you the samples that
 * are playing. It requires the RECORD_AUDIO permission - a microphone prompt, in a file
 * manager, for a decoration. That trade is not worth making and people are right to refuse
 * it. So the samples have to come from the file, and the only honest way to know where the
 * beats are is to read it.
 *
 * ## What it actually costs
 *
 * One pass over the track, once, when the pulse is switched on and Aurora is on screen.
 * Decoding three minutes of audio is a second or two of CPU on a modern phone and nothing
 * afterwards: what is kept is one float per [BUCKET_MS], which for a three-minute song is
 * about nine thousand of them - thirty-six kilobytes, and no further reading, no thread, and
 * nothing running while the track plays.
 *
 * It is behind a switch for that reason, and the switch is off. A second of work nobody asked
 * for, on a phone that may be doing something else, is not a thing to do by default.
 *
 * ## How a beat is found
 *
 * Not by detecting tempo. Tempo detection is a research problem and a wrong answer is worse
 * than none - a light pulsing confidently on the off-beat reads as broken. What is measured
 * is **onset strength**: energy per bucket, and then how much each bucket RISES above the
 * recent average. A drum hit is a sharp rise; a sustained note is not. That is enough for a
 * light, it is cheap, and it degrades gracefully - on music with no percussion the ring
 * simply breathes instead of punching, which is the right answer for that music anyway.
 */
class BeatMap(private val onsets: FloatArray) {

    /**
     * How hard the music hits at [ms], from 0 to 1.
     *
     * ## It reads FORWARD, which is why it stopped feeling late
     *
     * The first version read the bucket under the position and the two BEFORE it, so a hit
     * lit the light up to forty milliseconds after it had been heard - and that was on top
     * of the sampling interval, the frame, and whatever the device adds between the decoder
     * and the speaker. Every one of those is lateness, and they add up to something plainly
     * behind the music.
     *
     * So it reads the bucket under the position and the one AFTER it. A beat about to land
     * starts the light now; the envelope's own attack then peaks about where the sound
     * arrives. Looking ahead is also what makes a pulse impossible to step over at any
     * frame rate, which is what the backward window was there for in the first place.
     */
    fun strengthAt(ms: Long): Float {
        if (onsets.isEmpty()) return 0f
        val i = (ms / BUCKET_MS).toInt()
        if (i < 0 || i >= onsets.size) return 0f
        var best = onsets[i]
        if (i + 1 < onsets.size) best = max(best, onsets[i + 1] * FADE)
        return best
    }

    val empty: Boolean get() = onsets.isEmpty()

    companion object {
        /**
         * How finely the track is measured.
         *
         * Twenty milliseconds. Fine enough that two hits at 150bpm are four buckets apart and
         * never share one; coarse enough that a whole song is a few thousand floats.
         */
        const val BUCKET_MS = 20L

        /** How much of a bucket's hit the next one inherits, so a pulse has a tail. */
        const val FADE = 0.62f

        /** How many buckets the running average looks back over. About a third of a second. */
        private const val MEMORY = 16

        /** And the least history a bucket needs before it may be called a beat at all. */
        private const val WARM_UP = 4

        /**
         * Below this a rise is dither rather than music.
         *
         * A FRACTION of the recent average, not an absolute level - see [onsetsOf]. Two
         * percent, which is a noise gate and nothing more. It used to be six, as a
         * judgement about what counted as a beat, and that judgement does not belong here:
         * it threw away every soft hit before anything downstream could decide what to do
         * with it, so the light could only ever answer the loud ones. Weak beats now
         * survive as small numbers and pulse weakly, and how weak is a setting.
         */
        private const val FLOOR = 0.02f

        /**
         * Which onset counts as "as hard as this track hits".
         *
         * Everything is scaled so that this one reaches 1. Not the maximum, which was the
         * fault: one cymbal crash in a three-minute song set the scale, and every drum in it
         * then measured a fifth of that and moved the light by almost nothing. At the ninth
         * decile a tenth of the hits saturate and an ordinary beat lands near the top of the
         * range, which is what makes the face answer the music rather than acknowledge it.
         */
        private const val REFERENCE = 0.90f

        /**
         * Read a track and find its onsets. Null when it cannot be decoded.
         *
         * Suspending and on IO. It reads and decodes a whole file, which is never a
         * main-thread job however fast the phone is.
         *
         * ## It hands back partial maps as it goes, and that is not an optimisation
         *
         * Reported as the pulse starting late, and it was - by a second or two, every time
         * a track opened. Nothing was wrong with the beat detection; there was simply no
         * map yet. The whole file was decoded before anything was returned, so the opening
         * of every song played with the pulse switched on and doing nothing.
         *
         * Decoding runs at something like a hundred times real time, so after a few tens
         * of milliseconds the reader is already further into the track than the player is.
         * [onPartial] therefore gets a usable map almost at once and a better one every
         * [EMIT_EVERY] buckets after that, each covering more than playback has reached.
         *
         * The scale shifts slightly as more of the track is seen - the normalisation is
         * against what has been read so far - which is a far smaller fault than a pulse
         * that is not there.
         */
        suspend fun build(
            context: Context,
            uri: Uri,
            onPartial: ((BeatMap) -> Unit)? = null,
        ): BeatMap? = withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, uri, null)
                var index = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        index = i
                        format = f
                        break
                    }
                }
                val fmt = format ?: return@withContext null
                if (index < 0) return@withContext null
                extractor.selectTrack(index)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return@withContext null
                val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, null, null, 0)
                codec.start()

                val perBucket = (rate * BUCKET_MS / 1000).toInt().coerceAtLeast(1) * channels
                val energy = ArrayList<Float>(4096)
                var emitAt = EMIT_FIRST
                var sum = 0.0
                var count = 0
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
                            // See the note in `ScratchEngine.decode`: the frame is the slice
                            // at info.offset, and the samples are in the machine's own byte
                            // order rather than a ByteBuffer's default. Without both, this
                            // measures noise and the ring pulses on nothing.
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts =
                                buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                            while (shorts.hasRemaining()) {
                                sum += abs(shorts.get().toInt()) / 32768.0
                                count++
                                if (count >= perBucket) {
                                    energy.add((sum / count).toFloat())
                                    sum = 0.0
                                    count = 0
                                    if (onPartial != null && energy.size >= emitAt) {
                                        emitAt = energy.size + EMIT_EVERY
                                        onPartial(BeatMap(onsetsOf(energy)))
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && fed) {
                        break
                    }
                }
                if (energy.size < WARM_UP * 2) {
                    dev.niccc2007.filet.data.Diag.log("beat", "too short: ${energy.size} buckets")
                    return@withContext null
                }
                val onsets = onsetsOf(energy)
                android.util.Log.i(
                    "FiletBeat",
                    "${onsets.size} buckets, ${onsets.count { it > 0.3f }} beats, " +
                        "peak=${onsets.maxOrNull()} meanEnergy=${energy.average()}",
                )
                return@withContext BeatMap(onsets)
            } catch (e: Throwable) {
                dev.niccc2007.filet.data.Diag.log("beat", "failed: $e")
                return@withContext null
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
            }
        }

        /**
         * Energy per bucket becomes "how much louder than it has been lately".
         *
         * Pure, and separate from the decoding, so the part with the judgement in it can be
         * reasoned about and tested without a codec.
         */
        fun onsetsOf(energy: List<Float>): FloatArray {
            val out = FloatArray(energy.size)
            for (i in energy.indices) {
                var recent = 0f
                var n = 0
                for (j in max(0, i - MEMORY) until i) {
                    recent += energy[j]
                    n++
                }
                // Nothing is called a beat until there is something to compare it to.
                //
                // Caught by a test: with no history the average is zero, so the FIRST bucket
                // of any track was a rise above nothing and fired - every track pulsed once
                // at its start, and a flat loud recording pulsed at the start and nowhere
                // else, which is the exact opposite of what this measures.
                //
                // WARM_UP buckets, not MEMORY. Waiting for the full window blanked the first
                // third of a second of every track, which with a song that opens on a hit is
                // exactly the beat somebody is listening for. Four buckets is enough history
                // for the comparison to mean something, and the window grows from there.
                if (n < WARM_UP) {
                    out[i] = 0f
                    continue
                }
                val average = recent / n
                if (average <= 1e-5f) {
                    // A rise out of true silence. There is no ratio to take, and it is
                    // certainly an onset, so it is given the top of the scale directly.
                    out[i] = if (energy[i] > 1e-5f) 1f else 0f
                    continue
                }
                // RELATIVE rise, not absolute. A rise of 0.04 is a slam in a quiet passage
                // and inaudible in a loud one, and the absolute version measured the first
                // as nothing and the second as a beat. Dividing by what the music has been
                // doing lately is what makes this follow the track instead of its mastering.
                //
                // And only rises. A long loud note is not a beat; the moment it starts is.
                val rel = (energy[i] - average) / average
                out[i] = if (rel <= FLOOR) 0f else rel
            }
            val hits = out.filter { it > 0f }.sorted()
            if (hits.isEmpty()) return FloatArray(energy.size)
            // Scaled so an ORDINARY hard beat reaches the top, not so the single loudest
            // moment of the track does. See [REFERENCE].
            val reference = hits[((hits.size - 1) * REFERENCE).toInt()]
            if (reference <= 0f) return FloatArray(energy.size)
            for (i in out.indices) out[i] = min(1f, out[i] / reference)
            return out
        }

        /**
         * How much audio is read before the first usable map is handed over.
         *
         * Sixty buckets is 1.2 seconds of the track, which the decoder reaches in a few
         * tens of milliseconds - so the pulse is running before anybody has noticed the
         * track started, rather than a second or two into it.
         */
        private const val EMIT_FIRST = 60

        /** And how much more between each better map after that. Three seconds of audio. */
        private const val EMIT_EVERY = 150

        private const val TIMEOUT_US = 5_000L

        /**
         * The last few tracks' maps, so switching the pulse off and on again is instant.
         *
         * Reported as the pulse barely starting after a toggle, and that is exactly what it
         * was: turning it off dropped the map, turning it back on decoded the whole file
         * again, and for the second or two that took the face did nothing. The reading is
         * the expensive part and its answer does not change, so it is kept.
         *
         * Four, by path and modification time. One is not enough - stepping to the next
         * track and back is the other way this happens - and the cost is a few tens of
         * kilobytes each, not megabytes: one float per twenty milliseconds of audio.
         */
        private val cache = object : LinkedHashMap<String, BeatMap>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BeatMap>) =
                size > KEEP

            // LinkedHashMap is not thread-safe and this is read from the main thread and
            // written from IO. Every access goes through the two synchronized helpers.
        }

        private const val KEEP = 4

        /**
         * The map for [uri], from memory if it has been read before.
         *
         * A cache hit returns the finished map and never calls [onPartial]: there is
         * nothing partial about it, and a caller that drew the partial states on the way
         * past would flicker through them for no reason.
         */
        suspend fun of(
            context: Context,
            uri: Uri,
            onPartial: ((BeatMap) -> Unit)? = null,
        ): BeatMap? {
            val key = uri.toString()
            synchronized(cache) { cache[key] }?.let { return it }
            val built = build(context, uri, onPartial) ?: return null
            synchronized(cache) { cache[key] = built }
            return built
        }
    }
}

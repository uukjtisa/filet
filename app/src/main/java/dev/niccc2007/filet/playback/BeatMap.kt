package dev.niccc2007.filet.playback

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
 * One pass over the samples [TrackPcm] has already decoded, once, when the pulse is switched
 * on and Aurora is on screen. What is KEPT is two floats per [BUCKET_MS], which for a
 * three-minute song is seventy kilobytes - and then no thread, no reading and nothing
 * running while the track plays.
 *
 * It is behind a switch for that reason, and the switch is off. A second of work nobody asked
 * for, on a phone that may be doing something else, is not a thing to do by default.
 *
 * ## How a beat is found
 *
 * Not by detecting tempo. Tempo detection is a research problem and a wrong answer is worse
 * than none - a light pulsing confidently on the off-beat reads as broken.
 *
 * What is measured is **spectral flux**, which is what aubio, librosa and Essentia all use
 * for this: the spectrum every [BUCKET_MS], and the sum of how much each bin went UP since
 * the last one. The first version of this measured broadband energy instead, and that is
 * wrong in a way you can hear - a snare under a sustained pad barely moves the total energy
 * and was missed, while the pad swelling moved it a lot and fired. A snare is energy
 * arriving across many bins at once, which flux sees and loudness does not.
 *
 * It degrades gracefully: on music with no percussion the ring breathes instead of punching,
 * which is the right answer for that music anyway.
 */
class BeatMap(
    private val onsets: FloatArray,
    private val levels: FloatArray = FloatArray(0),
    /** The sample rate the measurement ran at, which is what [biasMs] is derived from. */
    val rate: Int = TrackPcm.TARGET_RATE,
) {

    /**
     * How much later than its bucket's index an onset actually sounds, in milliseconds.
     *
     * ## This is the lateness that used to be a slider
     *
     * A bucket is indexed by where its window STARTS, and [strengthAt] reads
     * `ms / BUCKET_MS` - so bucket i is read when the player says it is at `i * 20ms`. But
     * the flux at frame i is the rise between window i-1 and window i, and the only content
     * in window i that was not already in window i-1 is its last hop's worth. So whatever
     * caused the rise is near the END of a [WINDOW]-sample window, not at its start.
     *
     * At 22050Hz that is 1024 samples less half a hop: about 36 milliseconds. It is
     * arithmetic over the window and the rate, needs nobody's ear, and - because it scales
     * with the rate - it was also silently DOUBLING on a long track, which used to be decoded
     * at half rate. A constant compiled in here would have been wrong for exactly the tracks
     * that were hardest to look at.
     */
    val biasMs: Long
        get() {
            val hop = (rate * BUCKET_MS / 1000).coerceAtLeast(1)
            return ((WINDOW - hop / 2) * 1000L / rate.coerceAtLeast(1))
        }

    /**
     * How LOUD the music is at [ms], 0 to 1 - which is a different question from how hard
     * it hit.
     *
     * The onsets are a derivative: almost every bucket is zero and a few are spikes, which
     * is exactly right for deciding when to pulse and exactly wrong for drawing a shape.
     * A ring drawn from them is a circle with occasional dents in it. The loudness envelope
     * is continuous, so a ring drawn from it is a waveform - it has the track's shape in it
     * at every angle rather than only where a drum was.
     *
     * Both are kept because the face wants both: this for the figure, the onsets for the
     * punch on top of it.
     */
    fun levelAt(ms: Long): Float {
        if (levels.isEmpty()) return 0f
        val i = (ms / BUCKET_MS).toInt()
        if (i < 0 || i >= levels.size) return 0f
        return levels[i]
    }

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

    /**
     * How much of the track this map actually describes, in milliseconds.
     *
     * ## Why a consumer has to know this
     *
     * A map arrives in pieces - the opening of the track is measured within a frame or two and
     * the rest follows (see [from] and `TrackPcm`'s early callback). But [strengthAt] answered
     * a position past the end of a PARTIAL map with 0, which is the same answer it gives for
     * "there is no beat here". Those are completely different facts, and conflating them means
     * a pulse that works for the first few seconds and then goes dead with nothing to say why -
     * which is indistinguishable from the feature being broken.
     *
     * With the coverage known, a reader past the end can hold what it had instead of being told
     * the music stopped. See `rememberBeatSignal`.
     */
    val coveredMs: Long get() = onsets.size * BUCKET_MS

    /** Where the measurement is fine enough to be worth drawing, see `AudioCache`. */
    internal val onsetArray: FloatArray get() = onsets
    internal val levelArray: FloatArray get() = levels

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
         * The map for a track that has already been decoded.
         *
         * ## One decode, two views
         *
         * This used to read and decode the file itself, which meant that playing a track
         * with the pulse on and the record armed ran two `MediaCodec` instances over the
         * same file at once. They competed, the slower one finished late, and on a device
         * that will not hand out a second decoder it did not finish at all - which is the
         * pulse sometimes never coming back. [TrackPcm] is now the single reader and this
         * is arithmetic over what it produced.
         *
         * Pure and synchronous. A four-minute track is a couple of seconds of maths, which
         * is why [onPartial] exists: the caller gets a usable map almost at once and a
         * better one as more of the track is measured.
         */
        fun from(
            pcm: TrackPcm,
            onPartial: ((BeatMap) -> Unit)? = null,
            /** Frames between partial maps. See `PlayerDials.emitEvery`. */
            emitEvery: Int = EMIT_EVERY,
            /**
             * Polled every [CHECK_EVERY] frames; false abandons the measurement by throwing
             * `CancellationException`, so nothing half-measured is cached. See [CHECK_EVERY].
             */
            alive: () -> Boolean = { true },
            /**
             * Frames measured and frames in total, reported every [CHECK_EVERY] frames and once
             * at the end. Real work done over real work to do - the figure the progress bar
             * draws, so nothing on screen moves unless this loop has moved.
             */
            onProgress: ((done: Int, total: Int) -> Unit)? = null,
        ): BeatMap {
            val hop = (pcm.rate * BUCKET_MS / 1000).toInt().coerceAtLeast(1)
            val frames = (pcm.samples.size - WINDOW) / hop
            if (frames < WARM_UP * 2) {
                onProgress?.invoke(1, 1)
                return BeatMap(FloatArray(0), FloatArray(0), pcm.rate)
            }

            val window = Fft.hann(WINDOW)
            val re = FloatArray(WINDOW)
            val im = FloatArray(WINDOW)
            val bins = WINDOW / 2
            var previous = FloatArray(bins)
            val flux = ArrayList<Float>(frames)
            val energy = ArrayList<Float>(frames)
            var emitAt = EMIT_FIRST

            for (f in 0 until frames) {
                if (f % CHECK_EVERY == 0) {
                    if (!alive()) throw java.util.concurrent.CancellationException("track changed")
                    onProgress?.invoke(f, frames)
                }
                val at = f * hop
                var sum = 0.0
                for (i in 0 until WINDOW) {
                    val v = pcm.samples[at + i] / 32768f
                    sum += kotlin.math.abs(v).toDouble()
                    re[i] = v * window[i]
                    im[i] = 0f
                }
                energy.add((sum / WINDOW).toFloat())
                Fft.transform(re, im)
                // SPECTRAL FLUX: how much each bin went UP since the last frame, summed.
                //
                // Only the rises, which is the half-wave rectification every published
                // version of this does - a bin going quiet is the end of a note, not the
                // start of one, and counting it makes every offset look like an onset.
                var rise = 0.0
                for (k in 0 until bins) {
                    val mag = kotlin.math.hypot(re[k], im[k])
                    val d = mag - previous[k]
                    if (d > 0f) rise += d.toDouble()
                    previous[k] = mag
                }
                flux.add(rise.toFloat())

                if (onPartial != null && f >= emitAt) {
                    emitAt = f + emitEvery
                    onPartial(BeatMap(onsetsOf(flux), levelsOf(energy), pcm.rate))
                }
            }
            onProgress?.invoke(frames, frames)
            return BeatMap(onsetsOf(flux), levelsOf(energy), pcm.rate)
        }

        /**
         * Whether [next] may replace [current] on screen.
         *
         * Bug identified here: the wave ran for a few seconds after a track change and then
         * froze. The early map, measured from the decoder's first four seconds, was replaced by
         * the full measurement's FIRST partial - which covers half a second. Past the end of a
         * map the pulse holds its last bucket (see `rememberBeatSignal`), so from that moment
         * the wave read one frozen value until a later partial overtook the playhead: three
         * seconds of audio away on a capable phone, twelve on a slow one. Each partial is
         * better measured than the last, but a map that describes LESS of the track is not an
         * improvement to the person watching it, so coverage may only grow.
         */
        fun keeps(current: BeatMap?, next: BeatMap): Boolean =
            current == null || next.coveredMs >= current.coveredMs

        /**
         * The analysis window.
         *
         * 1024 samples, which at `TrackPcm.TARGET_RATE` is 46 milliseconds - long enough to
         * resolve a bass note's pitch and short enough that a snare does not smear across
         * several frames. The hop is [BUCKET_MS], so consecutive windows overlap by more
         * than half and no attack can fall between two of them.
         */
        const val WINDOW = 1024

        /** How far in the first usable map is handed over, in frames. Half a second. */
        private const val EMIT_FIRST = 25

        /**
         * And how much more between each better one, on a capable phone.
         *
         * Each partial map re-normalises everything measured so far - two sorts over the
         * whole of it - so over a track the partials cost O(n^2 / EMIT_EVERY). On a ten-minute
         * track at 150 that is two hundred sorts of up to thirty thousand values. Below the
         * capable tier the gap widens; see `PlayerDials.emitEvery`.
         */
        internal const val EMIT_EVERY = 150

        /**
         * How often the measuring loop asks whether it is still wanted. Pure arithmetic between
         * checks; 64 frames is about a millisecond of work.
         *
         * Bug identified here: the loop ran inside `withContext(Default)` with no cancellation
         * point, so skipping a track left the previous one being measured to the end - a
         * second or two of a full core per skip, stacking on a run of skips.
         */
        private const val CHECK_EVERY = 64

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
         * The loudness envelope, normalised and gently compressed.
         *
         * Against a high percentile rather than the maximum, for the same reason the onsets
         * are: one loud moment otherwise flattens the rest of the track to nothing. The
         * square root is a crude compressor - it lifts the quiet half of the range, which
         * is where most music actually lives, so the figure has shape in a verse and not
         * only in a chorus.
         */
        fun levelsOf(energy: List<Float>): FloatArray {
            if (energy.isEmpty()) return FloatArray(0)
            val sorted = energy.sorted()
            val reference = sorted[((sorted.size - 1) * 0.95f).toInt()]
            if (reference <= 1e-6f) return FloatArray(energy.size)
            return FloatArray(energy.size) {
                kotlin.math.sqrt((energy[it] / reference).coerceIn(0f, 1f))
            }
        }

        private const val TIMEOUT_US = 5_000L

        /**
         * The maps already measured, by track.
         *
         * Switching the pulse off and on used to drop the map and read the file again, and
         * for the second or two that took the face did nothing. The measuring is the
         * expensive part and its answer does not change, so it is kept. Four of them, at a
         * few tens of kilobytes each - two floats per twenty milliseconds of audio.
         */
        private val cache = object : LinkedHashMap<String, BeatMap>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BeatMap>) =
                size > KEEP

            // LinkedHashMap is not thread-safe and this is read from the main thread and
            // written from a worker. Every access goes through a synchronized block.
        }

        private const val KEEP = 4

        /**
         * The map for [key] if it has ALREADY been measured, and null otherwise.
         *
         * ## Why a surface would want this
         *
         * The minimised strip is on screen in every folder. Giving it the beat the way the
         * player gets it would mean decoding and measuring every track that plays, whether or
         * not anybody ever opened the player - which is exactly the cost the pulse switch
         * exists to make optional, paid in the one place it was never agreed to.
         *
         * So the strip asks whether the answer happens to exist and goes without when it does
         * not. In practice it almost always does, because the map is measured the first time
         * the player is opened on that track and kept (here, and on disk in `AudioCache`), so
         * the hairline beats from the second play onward and costs nothing at all to do so.
         */
        fun cached(key: String): BeatMap? = synchronized(cache) { cache[key] }

        /**
         * The finished map from memory or disk, or null. Asked BEFORE the track is decoded.
         *
         * Bug identified here: the lookup used to happen only after a full decode, so a track
         * measured long ago still waited a second or more of decoding for an answer that was
         * already on disk - and in that wait the wave ran on the four-second early map and then
         * froze. A map that exists needs no samples at all.
         */
        fun stored(context: android.content.Context, stamp: String?, key: String): BeatMap? {
            synchronized(cache) { cache[key] }?.let { return it }
            if (stamp == null) return null
            return AudioCache.readBeats(context, stamp)?.also { synchronized(cache) { cache[key] = it } }
        }

        /**
         * Forget a finished map in memory, for the manual rebuild in the player's panel. The
         * copy on disk is not deleted - the rebuild passes `fresh` to [of], which skips it, and
         * the new measurement is written over it when it finishes. A rebuild that is cancelled
         * part-way therefore leaves the old map in place rather than none.
         */
        fun forget(key: String) {
            synchronized(cache) { cache.remove(key) }
        }

        /**
         * The one key a map is stored and asked for under: the URI the track resolved to.
         *
         * One function so the two sides cannot drift again. They had: the player stored under
         * the resolved URI and the strip asked with the track's VFS path, which never matched.
         * The URI rather than the path because the URI is what was actually decoded - a staged
         * copy of a remote file has a different path and the same audio.
         */
        fun keyFor(resolvedUri: String): String = resolvedUri

        /**
         * The map for [key], measuring it from [pcm] if it has not been measured yet.
         *
         * A cache hit never calls [onPartial]: there is nothing partial about a finished
         * map, and a caller that drew the partial states on the way past would flicker
         * through them for no reason.
         */
        fun of(key: String, pcm: TrackPcm, onPartial: ((BeatMap) -> Unit)? = null): BeatMap {
            synchronized(cache) { cache[key] }?.let { return it }
            val built = from(pcm, onPartial)
            synchronized(cache) { cache[key] = built }
            return built
        }

        /**
         * As [of], but looking on disk first and writing the answer back.
         *
         * Measuring a four-minute track is a second or two of spectrum, and the answer is
         * the same every time it is asked. Seventy kilobytes to never ask again.
         */
        fun of(
            context: android.content.Context,
            stamp: String?,
            key: String,
            pcm: TrackPcm,
            onPartial: ((BeatMap) -> Unit)? = null,
            emitEvery: Int = EMIT_EVERY,
            alive: () -> Boolean = { true },
            onProgress: ((done: Int, total: Int) -> Unit)? = null,
            /** Measure even when a finished map is stored. The manual rebuild. */
            fresh: Boolean = false,
        ): BeatMap {
            if (!fresh) stored(context, stamp, key)?.let { return it }
            val built = from(pcm, onPartial, emitEvery, alive, onProgress)
            synchronized(cache) { cache[key] = built }
            if (stamp != null) {
                AudioCache.writeBeats(
                    context, stamp, built.onsetArray, built.levelArray, built.rate,
                )
            }
            return built
        }
    }
}

package dev.niccc2007.filet.playback

import be.tarsos.dsp.beatroot.Event
import be.tarsos.dsp.beatroot.EventList
import be.tarsos.dsp.beatroot.Induction
import be.tarsos.dsp.beatroot.Peaks
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where the BEATS are, as opposed to where something merely got louder.
 *
 * ## The fault this replaces
 *
 * The pulse used to fire on every onset - every bucket whose spectral flux rose clearly above
 * what the track had been doing lately. That is the right input and the wrong output. A hi-hat
 * between beats, a vocal consonant, a pick on a guitar string: all are onsets, none are the beat,
 * and a light that answers all of them reads as firing at random. It also cannot be tuned out,
 * because an off-beat hat and an on-beat kick can measure the same.
 *
 * ## What decides it now
 *
 * BeatRoot (Simon Dixon; carried in TarsosDSP and vendored under `be.tarsos.dsp.beatroot`). It
 * takes the onsets, clusters the gaps between them into tempo hypotheses, and runs a set of
 * agents that each follow one hypothesis through the track, predicting the next beat and
 * scoring how well the onsets agree. The best agent's beats are the grid. It is offline - it
 * wants the whole track - which is how this app measures anyway: once, then stored.
 *
 * The onsets it is given are the ones its own audio front end would have found: Filet's
 * spectral flux, normalised, through BeatRoot's own peak picker with the parameters of its own
 * onset detector. Only the beat placement is BeatRoot's; the flux, and everything about how
 * hard a beat hits, stay Filet's.
 *
 * ## Strength on the grid
 *
 * Each beat takes the strongest onset within [NEAR] buckets of it, so a beat with a drum on it
 * lands as hard as the drum did and an onset that is not near any beat lands not at all. A beat
 * the agent filled in where it heard nothing - a sustained chord across the bar line - takes a
 * [GHOST] share of how loud the music is there, so a loud held passage keeps a faint pulse and a
 * quiet one stays still.
 *
 * When there is no grid - too few onsets, or music with no pulse - the onsets are returned as
 * they were. Breathing with an ambient track is the right answer for it; inventing a beat is not.
 */
object BeatGrid {

    /**
     * BeatRoot keeps its tuning and its working state in static fields (`Agent`, `AgentList`,
     * `Induction`), so two measurements at once would rewrite each other's margins half way
     * through. One at a time. A track's grid is a fraction of a second, against the tens of
     * seconds the decode took, so the queue costs nothing anybody could see.
     */
    private val lock = Any()

    /** Buckets either side of a beat that its strength is taken from: 40ms at 20ms buckets. */
    const val NEAR = 2

    /** How much of the local loudness a beat with no onset under it gets. */
    const val GHOST = 0.3f

    /** Fewer beats than this is not a grid; the onsets are kept. */
    const val MIN_BEATS = 8

    /** BeatRoot's onset detector: peak height above the local mean, and the decay of its average. */
    private const val PEAK_THRESHOLD = 0.35
    private const val PEAK_DECAY = 0.84

    /** BeatRoot's minimum gap between two onsets, in seconds. */
    private const val PEAK_GAP_S = 0.06

    /**
     * The beat times in seconds for a track whose raw spectral flux is [flux], one value per
     * bucket of [bucketSec]. Empty when no agent could follow the track.
     */
    fun beats(flux: List<Float>, bucketSec: Double): DoubleArray = synchronized(lock) {
        if (flux.size < 4 || bucketSec <= 0.0) return DoubleArray(0)
        val data = DoubleArray(flux.size) { flux[it].toDouble() }
        Peaks.normalise(data)
        val gap = max(1, (PEAK_GAP_S / bucketSec).roundToInt())
        val peaks = Peaks.findPeaks(data, gap, PEAK_THRESHOLD, PEAK_DECAY, true)
        if (peaks.size < MIN_BEATS) return DoubleArray(0)

        val onsets = EventList()
        for ((n, p) in peaks.withIndex()) {
            val t = p * bucketSec
            // The constructor BeatRoot's own onset list uses: an onset is a note-on with no
            // duration, and its salience is how far the flux stood out.
            onsets.add(Event(t, t, t, 56, 64, n.toDouble(), 0.0, 1).apply { salience = data[p] })
        }
        val agents = Induction.beatInduction(onsets)
        agents.beatTrack(onsets, -1.0)
        val best = agents.bestAgent() ?: return DoubleArray(0)
        best.fillBeats(-1.0)
        DoubleArray(best.events.size()).also { out ->
            var i = 0
            for (e in best.events.iterator()) out[i++] = e.keyDown
        }
    }

    /**
     * [onsets] moved onto the beat grid: one hit per beat, at the beat's bucket, as strong as the
     * strongest onset near it. See the class notes for the ghost beats and for the fallback.
     *
     * Pure. [beats] is in seconds, as [BeatGrid.beats] returns it.
     */
    fun place(onsets: FloatArray, levels: FloatArray, beats: DoubleArray, bucketSec: Double): FloatArray {
        if (beats.size < MIN_BEATS || onsets.isEmpty() || bucketSec <= 0.0) return onsets
        val out = FloatArray(onsets.size)
        for (t in beats) {
            val i = (t / bucketSec).roundToInt()
            if (i < 0 || i >= out.size) continue
            var hit = 0f
            for (j in max(0, i - NEAR)..minOf(out.size - 1, i + NEAR)) hit = max(hit, onsets[j])
            if (hit <= 0f && i < levels.size) hit = GHOST * levels[i]
            out[i] = max(out[i], hit)
        }
        return out
    }
}

package dev.niccc2007.filet.playback

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * How far along the reading of a track is, so the screen can say so instead of looking broken.
 *
 * ## Why a state and not a boolean
 *
 * Both of the things that need the samples take a moment to get them, and both of them were
 * silent about it. The wave simply was not there yet; the record made no sound when a finger
 * turned it. Neither is a failure and both look exactly like one, so each got reported as
 * broken rather than as slow - and a progress state is the whole fix, because "reading the
 * beat" is an answer and an inert control is not.
 *
 * [PARTIAL] is its own state because a map arrives in pieces (see [BeatMap.from]): the opening
 * of the track is measured within a frame or two and the rest follows. The wave is already
 * running at that point, so saying "reading" would be a lie, but a beat near the end of the
 * track has not been found yet either.
 */
enum class TrackReady {
    /** Nothing is wanted, so nothing is being read. */
    IDLE,

    /** The file is being decoded, or measured, and there is nothing usable yet. */
    READING,

    /** Usable and still improving. */
    PARTIAL,

    /** Measured to the end. */
    READY,
}

/**
 * Everything one track's samples are needed for, read ONCE.
 *
 * @param map the onsets and the loudness, or null when there is nothing to show yet.
 * @param ready how far along the measuring is.
 * @param record whether there are enough samples in memory for the disc to make a sound.
 */
class TrackBeat(
    val map: BeatMap?,
    val ready: TrackReady,
    val record: Boolean,
)

/**
 * Read the track once, for the beat and for the record both.
 *
 * ## One decode, two consumers - and the bug that made this a function
 *
 * The map was loaded inside the Aurora face and the record was preloaded a few lines below it,
 * each with its own `LaunchedEffect` over the same URI. [TrackPcm] does deduplicate concurrent
 * decodes, so this was no longer two codecs on one file - but it was still two places that had
 * to agree about when to read, what to key on, and what to do with a partial answer, and only
 * one of them ever reported progress.
 *
 * It also meant the beat existed only on the face that happened to load it. The glow on the
 * other four faces was wired for a pulse it was never given, which is why turning the pulse on
 * did nothing anywhere except Aurora. Hoisting the read to the screen is what lets every face
 * answer the music.
 *
 * @param pulse whether a beat map is wanted at all.
 * @param record whether the samples are wanted for the disc as well. The map is the expensive
 *   half; this only asks for the decode, which the map needs anyway.
 */
@Composable
/*
 * Cost model. Trigger: the player screen composed with a source and the pulse or the record
 * wanted, or a manual rebuild asked for - never the strip, never in the background screen
 * alone. Per track, at most: one decode (`TrackPcm.of`, shared and cached) and one measurement
 * (`BeatMap.of`, cached in memory and on disk), both off the main thread and both abandoned when
 * the source changes. A map already stored costs no decode at all unless the record wants the
 * samples. Partial maps arrive every `PlayerDials.emitEvery` frames, each a recomposition.
 */
fun rememberTrackBeat(source: Uri?, pulse: Boolean, record: Boolean): TrackBeat {
    val context = LocalContext.current
    val emitEvery = rememberPlayerDials().emitEvery
    var map by remember(source) { mutableStateOf<BeatMap?>(null) }
    var ready by remember(source) { mutableStateOf(TrackReady.IDLE) }
    var samples by remember(source) { mutableStateOf(false) }
    val rebuild by BeatWork.rebuild.collectAsState()
    // The rebuild count when this track arrived. A request made since is a request about THIS
    // track; one made on the previous track must not re-measure the next one too.
    val askedAt = remember(source) { rebuild }

    LaunchedEffect(source, pulse, record, rebuild) {
        val fresh = rebuild != askedAt
        val wantMap = pulse || fresh
        val attempt = BeatWork.nextRun()
        if (source == null || (!wantMap && !record)) {
            ready = TrackReady.IDLE
            samples = false
            BeatWork.publish(BeatProgress(BeatPhase.IDLE, null, attempt))
            return@LaunchedEffect
        }
        val track = source.toString()
        val key = BeatMap.keyFor(track)
        try {
            val stamp = withContext(Dispatchers.IO) { AudioCache.keyFor(context, source) }

            // A finished map first, before any decoding. See `BeatMap.stored`.
            if (wantMap && !fresh) {
                val stored = withContext(Dispatchers.IO) { BeatMap.stored(context, stamp, key) }
                if (stored != null) {
                    map = stored
                    ready = TrackReady.READY
                    BeatWork.publish(BeatProgress(BeatPhase.DONE, 1f, attempt, cached = true))
                }
            }
            if (fresh) BeatMap.forget(key)
            val measuring = wantMap && ready != TrackReady.READY
            if (!measuring && !record) return@LaunchedEffect

            if (measuring) {
                if (map == null) ready = TrackReady.READING
                BeatWork.publish(BeatProgress(BeatPhase.DECODING, null, attempt), track)
            }
            // ONE read of the file, shared by the wave and the record. Partial maps are taken
            // as they come, so the wave runs within a frame or two of the samples arriving.
            val pcm = TrackPcm.of(context, source) { early ->
                if (measuring && map == null) {
                    map = BeatMap.from(early)
                    ready = TrackReady.PARTIAL
                }
            } ?: run {
                if (ready != TrackReady.READY) ready = TrackReady.IDLE
                return@LaunchedEffect
            }
            // The decode is done, so the disc can make a sound whether or not the measuring
            // has finished - they are independent and used to be reported as one.
            samples = true
            if (!measuring) return@LaunchedEffect

            BeatWork.publish(BeatProgress(BeatPhase.MEASURING, 0f, attempt))
            // Cancellable: a track change cancels this effect, and the measuring loop polls for
            // it rather than finishing a track nobody is listening to. See `BeatMap.CHECK_EVERY`.
            val built = withContext(Dispatchers.Default) {
                val job = coroutineContext
                BeatMap.of(
                    context, stamp, key, pcm,
                    onPartial = { partial ->
                        // Only a map that covers at least as much - see `BeatMap.keeps`.
                        if (BeatMap.keeps(map, partial)) {
                            map = partial
                            ready = TrackReady.PARTIAL
                        }
                    },
                    emitEvery = emitEvery,
                    alive = { job.isActive },
                    onProgress = { done, total ->
                        BeatWork.publish(
                            BeatProgress(BeatPhase.MEASURING, done.toFloat() / total.coerceAtLeast(1), attempt),
                        )
                    },
                    fresh = fresh,
                )
            }
            map = built
            ready = TrackReady.READY
            BeatWork.publish(BeatProgress(BeatPhase.DONE, 1f, attempt))
        } finally {
            // A cancelled run - the track changed, the screen closed - must not leave the
            // panel saying "measuring" or the dot lit. Nothing half-measured is cached: the
            // measuring loop throws on cancellation before `BeatMap.of` stores anything.
            BeatWork.ended(attempt)

            // Handed over rather than abandoned.
            //
            // This is the reported flaw. Everything above belongs to this composition, so
            // closing the player cancelled it - and because nothing half-measured is written,
            // tens of seconds of spectrum analysis were discarded whole. Opening the track
            // again started from nothing, which is exactly the case where somebody HAS waited
            // for it once already.
            //
            // So on the way out, if a map was wanted and is not finished, the same measurement
            // is asked for again from `BeatMaker`, whose scope outlives every screen. It
            // deduplicates and checks the store first, so a track that did finish - or that
            // another sweep is already working on - costs a map lookup here and nothing more.
            // The result lands in the same cache under the same key, so the player finds it
            // waiting the next time it opens.
            if (source != null && ready != TrackReady.READY && (pulse || rebuild != askedAt)) {
                runCatching {
                    dev.niccc2007.filet.FiletApp.graphOf(context).beatMaker
                        .make(source, source.lastPathSegment ?: "Track")
                }
            }
        }
    }

    return TrackBeat(map, ready, samples)
}

// ── what the beat map is doing, for the panel ─────────────────────────────────────────

/** Which half of the work a beat map is in. */
enum class BeatPhase {
    /** Nothing is running for the track on screen. */
    IDLE,

    /** The file is being decoded. Fraction known only when the decoder reports one. */
    DECODING,

    /** The samples are being measured. Fraction is frames measured over frames in the track. */
    MEASURING,

    /** A finished map is in hand, measured now or found in the cache. */
    DONE,
}

/**
 * One reading of the beat map's progress.
 *
 * @param fraction 0 to 1 within [phase], or null where no real figure exists. Never estimated:
 *   a bar that moves without measuring anything is the reason this was asked for in the first
 *   place, so the panel draws a bar only when this is non-null.
 * @param run which attempt this belongs to. A track change starts a new run; a report from an
 *   older one is ignored rather than drawn over the new one.
 * @param cached true when DONE came straight from the cache, so the panel can say so.
 */
data class BeatProgress(
    val phase: BeatPhase = BeatPhase.IDLE,
    val fraction: Float? = null,
    val run: Long = 0,
    val cached: Boolean = false,
) {
    val working: Boolean get() = phase == BeatPhase.DECODING || phase == BeatPhase.MEASURING

    companion object {
        /**
         * Fold a new report into the current one.
         *
         * Within one run the phase only moves forward and the fraction within a phase never
         * goes back - progress reported out of order by two threads must not make the bar
         * jump backwards. Moves smaller than one percent are not published; the measuring loop
         * reports every 64 frames and redrawing the panel that often buys nothing. A newer run
         * always replaces an older one; an older run's report is dropped.
         */
        fun advance(current: BeatProgress, next: BeatProgress): BeatProgress {
            if (next.run < current.run) return current
            if (next.run > current.run) return next
            if (next.phase.ordinal < current.phase.ordinal) {
                // Stopping is the one backward move allowed: a run that ends without
                // finishing goes to IDLE rather than staying "measuring" forever.
                return if (next.phase == BeatPhase.IDLE) next else current
            }
            if (next.phase.ordinal > current.phase.ordinal) return next
            val was = current.fraction
            val now = next.fraction ?: return current
            if (was != null && now < was + 0.01f && now < 1f) return current
            return next
        }
    }
}

/**
 * A dot that says "something is working", for one button. Pure; one per surface.
 *
 * Each activity is a KIND ("beat", and whatever comes later) with a run number. A run shows the
 * dot from when it starts until it finishes, or until the panel it belongs to is opened while
 * it runs - opening the panel is having seen it. A new run of the same kind is new news and
 * shows the dot again. One mechanism for every kind, so the next activity that wants a dot
 * adds a kind rather than a second dot.
 */
class ActivityDots {
    private val running = HashMap<String, Long>()
    private val seen = HashSet<Pair<String, Long>>()

    fun started(kind: String, run: Long) {
        running[kind] = run
    }

    /** Only the run that is current for [kind] can end it; a cancelled older run cannot. */
    fun finished(kind: String, run: Long) {
        if (running[kind] == run) running.remove(kind)
        seen.removeAll { it.first == kind && it.second <= run }
    }

    /** The panel was opened: everything running now counts as seen. */
    fun seen() {
        for ((k, r) in running) seen += k to r
    }

    val showing: Boolean get() = running.any { (k, r) -> (k to r) !in seen }
}

/**
 * The beat map's progress and the dot on the player's panel button, app-wide.
 *
 * App-wide rather than threaded through the screen because three places need it - the reader
 * in [rememberTrackBeat], the panel that shows it, and the decoder that knows how far through
 * the file it is - and they live in three files. Only the track on screen ever reports here.
 */
object BeatWork {
    private const val KIND = "beat"

    private val dots = ActivityDots()
    private var runs = 0L

    private val _progress = MutableStateFlow(BeatProgress())
    val progress: StateFlow<BeatProgress> = _progress.asStateFlow()

    private val _dot = MutableStateFlow(false)

    /** Whether the panel button should carry the activity dot. */
    val dot: StateFlow<Boolean> = _dot.asStateFlow()

    private val _rebuild = MutableStateFlow(0)

    /** Bumped by [requestRebuild]; [rememberTrackBeat] measures again when it changes. */
    val rebuild: StateFlow<Int> = _rebuild.asStateFlow()

    /** The track whose decode is being reported, so a decoder for another one is ignored. */
    @Volatile private var decodingTrack: String? = null

    @Synchronized
    fun nextRun(): Long = ++runs

    /** Measure the track on screen again, ignoring any stored map. Refused while one runs. */
    fun requestRebuild(): Boolean {
        if (_progress.value.working) return false
        _rebuild.value = _rebuild.value + 1
        return true
    }

    /** The panel was opened. */
    @Synchronized
    fun panelSeen() {
        dots.seen()
        _dot.value = dots.showing
    }

    @Synchronized
    internal fun publish(next: BeatProgress, track: String? = null) {
        if (next.phase == BeatPhase.DECODING) decodingTrack = track
        else if (next.run >= _progress.value.run) decodingTrack = null
        val folded = BeatProgress.advance(_progress.value, next)
        _progress.value = folded
        if (folded.working) dots.started(KIND, folded.run) else dots.finished(KIND, folded.run)
        _dot.value = dots.showing
    }

    /** A run ended, finished or not. Clears it if nothing newer has replaced it. */
    @Synchronized
    internal fun ended(run: Long) {
        if (_progress.value.run == run && _progress.value.working) {
            publish(BeatProgress(BeatPhase.IDLE, null, run))
        }
        dots.finished(KIND, run)
        _dot.value = dots.showing
    }

    /**
     * How far the decoder is through [track], 0 to 1. For `TrackPcm` to call from its decode
     * loop; ignored unless that track is the one being decoded for the screen.
     */
    fun reportDecode(track: String, fraction: Float) {
        if (track != decodingTrack) return
        val p = _progress.value
        if (p.phase != BeatPhase.DECODING) return
        publish(p.copy(fraction = fraction.coerceIn(0f, 1f)), track)
    }
}

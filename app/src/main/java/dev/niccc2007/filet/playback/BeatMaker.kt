package dev.niccc2007.filet.playback

import android.content.Context
import android.net.Uri
import dev.niccc2007.filet.jobs.JobLedger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Measuring a track's beats as a real job, away from any screen.
 *
 * ## The flaw this exists to fix
 *
 * The measurement lived inside a `LaunchedEffect` in the player - see [rememberTrackBeat] -
 * which means it belonged to the composition that started it. Leaving the player cancelled it.
 * So the one operation in this app that takes tens of seconds, and whose whole value is that it
 * never has to happen again, was the one operation guaranteed not to survive being left alone:
 * open a track, see the bar move, go back to the file list, and everything measured so far was
 * thrown away. Nothing half-measured is written, so it was thrown away completely.
 *
 * Here the work runs in the application's own scope and is registered in the [JobLedger], which
 * gives it the three things it was missing: it keeps going when the player closes, it is listed
 * with everything else the phone is doing, and it can be stopped on purpose rather than by
 * accident.
 *
 * ## Why one job per request and not one per track
 *
 * A whole playlist is ONE row in the activity list, not two hundred. Two hundred rows is not a
 * list of what the phone is doing, it is a denial of service against every other row in it -
 * and the question somebody actually has is "how far through is it", which a single row with a
 * fraction answers and two hundred rows do not.
 *
 * ## Why work is deduplicated rather than queued
 *
 * The player asks for the track it is on, and a playlist sweep asks for every track in it;
 * those overlap constantly. Measuring the same file twice at once wastes the most expensive
 * thing here and would race two writers onto one cache entry. [running] keeps one measurement
 * per track, and a second asker joins it by doing nothing.
 */
class BeatMaker(
    private val context: Context,
    private val ledger: JobLedger,
    private val scope: CoroutineScope,
    /**
     * The beat-map folder, as a VFS path string.
     *
     * A lambda because the folder depends on a setting that can change while the app runs, and
     * a String because R3 keeps `java.io.File` out of everything but the providers and a short
     * allow-list. Resolved when a job finishes, which is the only moment it is used.
     */
    private val folder: () -> String,
) {

    /** Tracks being measured right now, by cache stamp. */
    private val running = ConcurrentHashMap<String, Job>()

    /** How many measurements are in flight, for the player's panel. */
    val busy: Int get() = running.size

    /**
     * Measure one track, unless it is already measured or already being measured.
     *
     * Returns at once. The work outlives whatever asked for it.
     *
     * @param fresh measure even when a finished map is stored - the manual rebuild.
     */
    fun make(uri: Uri, title: String, fresh: Boolean = false) {
        val stamp = AudioCache.keyFor(context, uri) ?: return
        if (running.containsKey(stamp)) return
        if (!fresh && isStored(uri)) return

        val id = ledger.start(
            title = "Measuring the beat",
            subtitle = title,
            cancel = null,
            opened = folder(),
            kind = dev.niccc2007.filet.jobs.JobKind.BEAT,
            runAgain = { make(uri, title, fresh = true) },
        )
        val job = scope.launch(Dispatchers.Default) {
            try {
                ledger.cancelWith(id, currentCoroutineContext()[Job])
                val ok = measure(uri, fresh) { f -> ledger.progress(id, f) }
                if (ok) ledger.finish(id, "Beat map ready") else ledger.fail(id, "Could not read that track")
            } catch (t: Throwable) {
                // A cancellation is the Stop button, which the ledger has already recorded.
                if (currentCoroutineContext().isActive) ledger.fail(id, t.message ?: "Failed")
                throw t
            } finally {
                running.remove(stamp)
            }
        }
        running[stamp] = job
    }

    /**
     * Measure every track in a list, as one job.
     *
     * Sequential on purpose. Each measurement already saturates a core with spectrum analysis,
     * and running four at once on the phone this was reported on would turn a background job
     * into the reason the rest of the app stutters - which is the complaint that started all
     * of this. Slower and invisible beats faster and felt.
     */
    fun makeAll(tracks: List<Pair<Uri, String>>, listName: String) {
        if (tracks.isEmpty()) return
        val id = ledger.start(
            title = "Measuring beats",
            subtitle = listName + "  ·  " + tracks.size + " tracks",
            cancel = null,
            opened = folder(),
            kind = dev.niccc2007.filet.jobs.JobKind.BEAT,
            totalItems = tracks.size,
            // Between tracks: one measurement saturates a core for seconds, and holding the
            // rest of a long list is what a pause is for here.
            pausable = true,
            runAgain = { makeAll(tracks, listName) },
        )
        scope.launch(Dispatchers.Default) {
            ledger.cancelWith(id, currentCoroutineContext()[Job])
            var done = 0
            var made = 0
            var skipped = 0
            try {
                for ((uri, title) in tracks) {
                    if (!currentCoroutineContext().isActive) break
                    ledger.checkpoint(id)
                    val stamp = AudioCache.keyFor(context, uri)
                    if (stamp == null) {
                        done++
                        continue
                    }
                    if (isStored(uri)) {
                        skipped++
                        done++
                        ledger.progress(id, done.toFloat() / tracks.size, "$done of ${tracks.size}", doneItems = done)
                        continue
                    }
                    // Marked in flight so the player does not start the same track again
                    // while this sweep is working through it.
                    //
                    // Recorded only if there is a job to record. It used to be
                    // `?: return@launch`, which would have abandoned the ledger row as
                    // RUNNING for ever - the early return skips the `finish` below and the
                    // catch never sees it, so the row sits in Activity claiming to be working
                    // with nothing behind it. The map is a de-duplication optimisation, so
                    // not recording is a wasted re-read at worst; a stranded row is a lie.
                    currentCoroutineContext()[Job]?.let { running[stamp] = it }
                    try {
                        // The per-track fraction folded into the whole, so the bar moves
                        // continuously rather than in two-hundredths.
                        val ok = measure(uri, fresh = false) { f ->
                            ledger.progress(
                                id, (done + f) / tracks.size, title,
                            )
                        }
                        if (ok) made++
                    } finally {
                        running.remove(stamp)
                    }
                    done++
                    ledger.progress(id, done.toFloat() / tracks.size, "$done of ${tracks.size}", doneItems = done)
                }
                ledger.finish(
                    id,
                    buildString {
                        append(made).append(" measured")
                        if (skipped > 0) append(", ").append(skipped).append(" already done")
                    },
                )
            } catch (t: Throwable) {
                if (currentCoroutineContext().isActive) ledger.fail(id, t.message ?: "Failed")
                throw t
            }
        }
    }

    /** Whether a finished map is already on disk, so nothing need be done. */
    fun isStored(uri: Uri): Boolean {
        val stamp = AudioCache.keyFor(context, uri) ?: return false
        return BeatMap.stored(context, stamp, BeatMap.keyFor(uri.toString())) != null
    }

    /**
     * The measurement itself: decode, then analyse.
     *
     * Exactly the same two calls the player makes, against exactly the same cache, so a map
     * produced here IS the map the player uses when the pulse is on - there is no second
     * format and no second store to drift out of step. That was the other half of the report:
     * a beat map made in advance has to be the one that actually gets played against.
     */
    private suspend fun measure(
        uri: Uri,
        fresh: Boolean,
        onProgress: (Float) -> Unit,
    ): Boolean = coroutineScope {
        val stamp = withContext(Dispatchers.IO) { AudioCache.keyFor(context, uri) } ?: return@coroutineScope false
        val key = BeatMap.keyFor(uri.toString())
        if (fresh) BeatMap.forget(key)
        val pcm = TrackPcm.of(context, uri) ?: return@coroutineScope false
        val job = currentCoroutineContext()
        withContext(Dispatchers.Default) {
            BeatMap.of(
                context, stamp, key, pcm,
                alive = { job.isActive },
                onProgress = { d, t -> onProgress(d.toFloat() / t.coerceAtLeast(1)) },
                fresh = fresh,
            )
        }
        true
    }
}

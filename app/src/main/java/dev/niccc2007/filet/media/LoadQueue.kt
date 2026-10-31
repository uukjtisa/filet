package dev.niccc2007.filet.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The queue that fills in a list row's picture: newest first, work never wasted, two tiers.
 *
 * ## The three faults it exists to fix
 *
 * **Work was thrown away.** Each row staged its own read and the read was cancelled when the row
 * left the screen. A `MediaMetadataRetriever` parse has no suspension point in it, so cancelling
 * stopped the RESULT being used without stopping the work - and because the cache was only
 * written on completion, a fast scroll through a folder cached almost nothing. Scrolling back up
 * re-read everything, which is exactly what it looked like. Here the work runs in this class's
 * own scope: a caller that goes away stops waiting, the task finishes, and the answer is kept.
 *
 * **The wrong row was served first.** A screenful of requests is served in arrival order by any
 * ordinary pool, so after a fling the rows under the finger sat behind several hundred rows that
 * had already scrolled past. The queue is therefore **LIFO** - the newest request is the row
 * somebody is looking at now, and the stale ones are the ones worth making wait. This is the
 * whole of "render what I scrolled to, then carry on with the others".
 *
 * **Everything ran at once.** `Dispatchers.IO` will run sixty-four of these, and a handful of
 * concurrent header parses plus bitmap decodes on a mid-range phone takes the cores the main
 * thread needs in order to draw. [workers] is a hard ceiling.
 *
 * ## Two tiers, and why
 *
 * [join] is for a row that is on screen. [ahead] is for one that is not yet - the procedural
 * pre-render - and it is drained **only when nothing urgent is waiting**. Without the split,
 * prefetching would enqueue newer requests than the visible rows and LIFO would faithfully serve
 * the rows nobody is looking at first, which is worse than not prefetching at all.
 *
 * ## Why the queues are capped
 *
 * A fling can ask for thousands. The cap drops from the OLDEST end, which under LIFO is the
 * furthest from the viewport and the least likely to be wanted. A dropped task completes rather
 * than hanging, so anybody still waiting on it simply sees no answer and asks again if the row
 * is still there.
 */
class LoadQueue(
    private val workers: Int = 2,
    private val urgentCap: Int = 48,
    private val aheadCap: Int = 24,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private class Task(val key: String, val body: () -> Unit) {
        val done = CompletableDeferred<Unit>()
    }

    private val gate = Any()

    /** On screen. Newest at the END, because a worker takes from the end. */
    private val urgent = ArrayList<Task>()

    /** Not yet on screen. Drained only when [urgent] is empty, oldest first. */
    private val ahead = ArrayList<Task>()

    /** Queued or running, by key, so the same file is never read twice at once. */
    private val known = HashMap<String, Task>()

    private var running = 0

    /**
     * Run [body] for [key] at viewport priority, joining an identical request already queued or
     * running, and suspend until it is done.
     *
     * Cancelling the caller stops the waiting. It does **not** stop the work - that is the point.
     */
    suspend fun join(key: String, body: () -> Unit) = submit(key, body, urgentTier = true)

    /**
     * The same, for a row that is not on screen yet. Yields to everything urgent.
     */
    suspend fun ahead(key: String, body: () -> Unit) = submit(key, body, urgentTier = false)

    private suspend fun submit(key: String, body: () -> Unit, urgentTier: Boolean) {
        val task = synchronized(gate) {
            val held = known[key]
            if (held != null) {
                // A row that has arrived on screen while its pre-fetch was still waiting is now
                // urgent. Moving it rather than queueing a second task is what keeps the promise
                // that one file is read once.
                if (urgentTier && ahead.remove(held)) urgent.add(held)
                held
            } else {
                val fresh = Task(key, body)
                known[key] = fresh
                if (urgentTier) {
                    urgent.add(fresh)
                    trim(urgent, urgentCap)
                } else {
                    ahead.add(fresh)
                    trim(ahead, aheadCap)
                }
                fresh
            }
        }
        // Unconditionally, and never behind a test on `known`: that map is a plain HashMap and
        // reading it outside the lock is a race. There is nothing to gain by guarding this
        // anyway - `pump` takes the lock and returns at once when there is no worker free.
        pump()
        task.done.await()
    }

    /** Drops from the oldest end - furthest from the viewport under LIFO. */
    private fun trim(queue: ArrayList<Task>, cap: Int) {
        while (queue.size > cap) {
            val dropped = queue.removeAt(0)
            known.remove(dropped.key)
            // Completed, not abandoned: a caller awaiting a task that will never run would wait
            // for the life of the screen.
            dropped.done.complete(Unit)
        }
    }

    private fun pump() {
        while (true) {
            val next = synchronized(gate) {
                if (running >= workers) return
                // Urgent from the END (newest), pre-fetch from the FRONT (in reading order, so a
                // pre-render walks down the list rather than bouncing around it).
                val t = when {
                    urgent.isNotEmpty() -> urgent.removeAt(urgent.lastIndex)
                    ahead.isNotEmpty() -> ahead.removeAt(0)
                    else -> return
                }
                running++
                t
            }
            scope.launch {
                try {
                    next.body()
                } catch (_: Throwable) {
                    // A file that has gone, or one nothing can parse. The caller's own fallback
                    // covers it, and one bad file must not stop the queue.
                } finally {
                    synchronized(gate) {
                        known.remove(next.key)
                        running--
                    }
                    next.done.complete(Unit)
                    pump()
                }
            }
        }
    }

    /** For a test, and for the diagnostics dump: what is outstanding right now. */
    fun depth(): Triple<Int, Int, Int> = synchronized(gate) { Triple(urgent.size, ahead.size, running) }
}

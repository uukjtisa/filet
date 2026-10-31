package dev.niccc2007.filet.jobs

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Where a job is. WAITING is queued behind other work and has not started; it is still active,
 * so [Job.running] is true for it - "can I put the phone down yet" counts it.
 */
enum class JobState { WAITING, RUNNING, DONE, FAILED, CANCELLED }

/**
 * What kind of work a job is, for its icon and for the words its detail is written in.
 *
 * Declared by the operation that starts the job. It used to be guessed from the first word of
 * the title, which put a generic icon on every job whose title did not start with one of six
 * verbs - "Saving", "Running script", "Measuring" - and silently changed an icon whenever a
 * title was reworded.
 */
enum class JobKind {
    COPY, MOVE, DELETE, COMPRESS, EXTRACT, SAVE, INDEX, MEASURE, BEAT, SCRIPT, APK, FETCH, DOWNLOAD, OTHER;

    companion object {
        /** For a job started without a kind: the old title rule, kept only as the fallback. */
        fun guess(title: String, source: String = "Filet"): JobKind = when {
            title.startsWith("Copying") -> COPY
            title.startsWith("Moving") -> MOVE
            title.startsWith("Deleting") -> DELETE
            title.startsWith("Compressing") -> COMPRESS
            title.startsWith("Extracting") -> EXTRACT
            title.startsWith("Saving") -> SAVE
            title.startsWith("Indexing") -> INDEX
            title.startsWith("Measuring the beat") || title.startsWith("Measuring beats") -> BEAT
            title.startsWith("Measuring") -> MEASURE
            title.startsWith("Running script") -> SCRIPT
            title.startsWith("Signing") || title.startsWith("Decompiling") || title.startsWith("Rebuilding") -> APK
            title.startsWith("Fetching") -> FETCH
            source != "Filet" -> DOWNLOAD
            else -> OTHER
        }
    }
}

data class Job(
    val id: Long,
    val title: String,
    val subtitle: String = "",
    val state: JobState = JobState.RUNNING,
    /** 0..1, or null when the total is not known up front. */
    val progress: Float? = null,
    val detail: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long = 0L,
    /** Which app published it. Filet's own work says "Filet"; the bridge fills in others. */
    val source: String = "Filet",
    val error: String? = null,
    /**
     * True when stopping this is possible, which is not the same as it being worth offering.
     *
     * An operation registers the coroutine it is running on; a job published from elsewhere -
     * Trawl, over the bridge - has none, and a Stop that could not stop anything is worse than
     * no Stop at all.
     */
    val cancellable: Boolean = false,
    /**
     * Where tapping a finished job should go, as a VFS path string, or empty.
     *
     * The destination the work produced: the folder an archive was extracted into, the file it
     * was compressed to. A finished row that says what it did and cannot show you is a dead
     * end.
     */
    val opened: String = "",
    val kind: JobKind = JobKind.guess(title, source),
    /** Where the work reads from and writes to, in words, for the expanded row. Either may be empty. */
    val from: String = "",
    val to: String = "",
    val doneItems: Int? = null,
    val totalItems: Int? = null,
    val doneBytes: Long? = null,
    val totalBytes: Long? = null,
    /** Smoothed. See [JobRate]. */
    val bytesPerSec: Double? = null,
    /** True when the work checks for a pause between files - see [JobLedger.checkpoint]. */
    val pausable: Boolean = false,
    val paused: Boolean = false,
    /**
     * True when the work left a way to do it again: Retry for a failed job, Run again for a
     * finished one. Not carried across a restart - what to run is code, not a value.
     */
    val canRunAgain: Boolean = false,
) {
    /** Not finished: running, paused, or waiting its turn. */
    val running: Boolean get() = state == JobState.RUNNING || state == JobState.WAITING
}

/**
 * Where the ledger keeps its history between launches. An interface so the ledger stays plain
 * Kotlin and is tested without a device.
 */
interface JobStore {
    fun load(): String?
    fun save(text: String)
}

/**
 * "What is this phone doing, and what did it do while I was not looking."
 *
 * One ledger for every long operation - copies, indexing, hashing, signing - so the answer
 * is a single surface rather than a notification stack with no history. PLAN.md §5.2 has
 * Trawl publishing into the same ledger through the bridge; that is why [Job.source] exists
 * from the start instead of being retrofitted.
 *
 * ## History survives a restart
 *
 * It was in memory only, on the argument that a job which did not survive a process death did
 * not finish and a stale "62%" would be a lie. Both halves hold, and neither needs the history
 * thrown away: a finished job is a fact whenever it is read, and a job that was running when the
 * process died is restored as FAILED, "Filet closed before it finished" - which is the truth,
 * and the one thing somebody coming back to a half-copied folder most needs to be told. How long
 * finished jobs are kept is a setting; see [JobHistory.prune].
 */
class JobLedger(
    private val store: JobStore? = null,
    /** Days to keep finished jobs, read at each save. 0 is forever. */
    private val keepDays: () -> Int = { JobHistory.DEFAULT_KEEP_DAYS },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _jobs = MutableStateFlow(loadHistory())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    private val ids = AtomicLong((_jobs.value.maxOfOrNull { it.id } ?: 0L) + 1)

    /**
     * The coroutine behind each running job, so a Stop can actually stop one.
     *
     * Kept beside the list rather than inside [Job] because a `Job` is a value that is copied
     * on every progress tick, and a cancellation handle is not a value - two copies of a row
     * holding the same handle would be two rows claiming to own one piece of work.
     */
    private val handles = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()

    /** What Retry and Run again do, per job. Beside the list for the same reason as [handles]. */
    private val again = ConcurrentHashMap<Long, () -> Unit>()

    /** The last byte count and when it was seen, per job, for the speed. */
    private val samples = ConcurrentHashMap<Long, Pair<Long, Long>>()

    private val _paused = MutableStateFlow<Set<Long>>(emptySet())
    private val pauseLock = Object()

    val activeCount: Int get() = _jobs.value.count { it.running }

    /**
     * @param cancel the coroutine doing the work. Supplying it is what puts a Stop on the row.
     * @param opened where tapping the finished row should go, as a VFS path string.
     * @param pausable true when the work calls [checkpoint] or [holdIfPaused] between files.
     * @param runAgain how to do it again, for Retry and Run again.
     */
    fun start(
        title: String,
        subtitle: String = "",
        source: String = "Filet",
        cancel: kotlinx.coroutines.Job? = null,
        opened: String = "",
        kind: JobKind = JobKind.guess(title, source),
        from: String = "",
        to: String = "",
        totalItems: Int? = null,
        pausable: Boolean = false,
        runAgain: (() -> Unit)? = null,
        waiting: Boolean = false,
    ): Long {
        val id = ids.getAndIncrement()
        if (cancel != null) handles[id] = cancel
        if (runAgain != null) again[id] = runAgain
        val job = Job(
            id, title, subtitle,
            state = if (waiting) JobState.WAITING else JobState.RUNNING,
            startedAt = now(),
            source = source,
            cancellable = cancel != null,
            opened = opened,
            kind = kind,
            from = from,
            to = to,
            totalItems = totalItems,
            doneItems = totalItems?.let { 0 },
            pausable = pausable,
            canRunAgain = runAgain != null,
        )
        update(save = true) { listOf(job) + it }
        return id
    }

    /** Where a finished job leads, filled in once the work knows. */
    fun leadsTo(id: Long, path: String) = patch(id, save = true) { it.copy(opened = path) }

    /** How to do the work again, given once the work knows what it would be. */
    fun runAgainWith(id: Long, block: () -> Unit) {
        again[id] = block
        patch(id) { it.copy(canRunAgain = true) }
    }

    /**
     * Attach the coroutine doing the work AFTER the row exists, which puts a Stop on it.
     *
     * [start] takes the coroutine up front, which suits an operation running inside one that
     * already exists. Work that LAUNCHES its own cannot: the row has to be listed before the
     * launch, or a job that fails immediately is never seen at all, and the coroutine does not
     * exist until after that. So the handle arrives a moment later, from inside.
     *
     * Ignored once the job has ended, so a late attach cannot resurrect a Stop on a finished
     * row - the three terminal paths drop handles deliberately.
     */
    fun cancelWith(id: Long, job: kotlinx.coroutines.Job?) {
        if (job == null) return
        if (_jobs.value.firstOrNull { it.id == id }?.running != true) return
        handles[id] = job
        patch(id) { if (it.running) it.copy(cancellable = true) else it }
    }

    /**
     * Stop a running job.
     *
     * Cancels the coroutine and marks the row at once rather than waiting for the work to
     * notice: an operation checks for cancellation between entries, so a large file can be
     * seconds from the next check, and a Stop that leaves the row saying "running" for those
     * seconds reads as a button that did nothing. The op's own `finally` is what reports what
     * it managed before it stopped - and cannot turn the row back into "done", see [terminal].
     */
    fun requestCancel(id: Long) {
        handles.remove(id)?.cancel()
        unpause(id)
        terminal(id) { it.copy(state = JobState.CANCELLED, endedAt = now(), paused = false) }
    }

    /**
     * Hold the work at its next checkpoint. Only for a job that has one: a Pause that could not
     * pause anything is the same lie as a Stop that could not stop.
     */
    fun pause(id: Long) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (!job.running || !job.pausable) return
        _paused.value = _paused.value + id
        patch(id) { it.copy(paused = true) }
    }

    fun resume(id: Long) {
        unpause(id)
        patch(id) { it.copy(paused = false) }
    }

    private fun unpause(id: Long) {
        _paused.value = _paused.value - id
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    /**
     * Between two files: wait here while the job is paused, and throw if it was stopped.
     *
     * For work running in a coroutine. See [holdIfPaused] for the same thing from inside a
     * progress callback, which cannot suspend.
     */
    suspend fun checkpoint(id: Long) {
        if (id in _paused.value) _paused.first { id !in it }
        currentCoroutineContext().ensureActive()
    }

    /**
     * [checkpoint] for a callback that cannot suspend: blocks the calling thread while paused.
     *
     * Only ever reached from a copy's IO thread, between one file and the next, and never on the
     * main thread - a held main thread would be the app freezing rather than the work pausing.
     * A Stop wakes it, and the work's next cancellation check ends it.
     */
    fun holdIfPaused(id: Long) {
        if (id !in _paused.value) return
        if (Thread.currentThread().name == "main") return
        synchronized(pauseLock) {
            while (id in _paused.value && handles[id]?.isCancelled != true) pauseLock.wait(500)
        }
    }

    /** Waiting its turn behind other work, with a line saying what for. */
    fun waiting(id: Long, detail: String = "") =
        patch(id) { if (it.running) it.copy(state = JobState.WAITING, detail = detail) else it }

    fun progress(
        id: Long,
        fraction: Float?,
        detail: String = "",
        doneItems: Int? = null,
        doneBytes: Long? = null,
        totalBytes: Long? = null,
    ) {
        val at = now()
        val rate: Double? = doneBytes?.let { bytes ->
            val prev = samples.put(id, at to bytes)
            val old = _jobs.value.firstOrNull { it.id == id }?.bytesPerSec
            if (prev == null) old else JobRate.update(old, prev.second, prev.first, bytes, at)
        }
        patch(id) {
            if (!it.running) {
                it
            } else {
                it.copy(
                    state = JobState.RUNNING,
                    progress = fraction?.coerceIn(0f, 1f),
                    detail = detail,
                    doneItems = doneItems ?: it.doneItems,
                    doneBytes = doneBytes ?: it.doneBytes,
                    totalBytes = totalBytes ?: it.totalBytes,
                    bytesPerSec = rate ?: it.bytesPerSec,
                )
            }
        }
    }

    // All three drop the handle: a finished job cannot be stopped, and holding its coroutine
    // would keep the whole operation's captured state alive for as long as the row is listed.
    fun finish(id: Long, detail: String = "") {
        handles.remove(id)
        terminal(id) {
            it.copy(
                state = JobState.DONE, progress = 1f, detail = detail, endedAt = now(), paused = false,
                doneItems = it.totalItems ?: it.doneItems,
            )
        }
    }

    fun fail(id: Long, error: String) {
        handles.remove(id)
        terminal(id) { it.copy(state = JobState.FAILED, error = error, endedAt = now(), paused = false) }
    }

    fun cancel(id: Long) {
        handles.remove(id)
        terminal(id) { it.copy(state = JobState.CANCELLED, endedAt = now(), paused = false) }
    }

    /**
     * Retry a failed job, or run a finished one again. The work starts a new row of its own; this
     * one stays as the record of the attempt it was.
     */
    fun runAgain(id: Long): Boolean {
        val block = again[id] ?: return false
        block()
        return true
    }

    /** Take one finished job off the list. A running one is not removable; Stop it first. */
    fun remove(id: Long) {
        if (_jobs.value.firstOrNull { it.id == id }?.running == true) return
        again.remove(id)
        update(save = true) { list -> list.filterNot { it.id == id } }
    }

    /** Publish a job Filet did not run - a bridge message from Trawl, for example. */
    fun publishExternal(job: Job) = update(save = !job.running) { listOf(job) + it.filterNot { j -> j.id == job.id } }

    fun clearFinished() {
        again.keys.retainAll(_jobs.value.filter { it.running }.map { it.id }.toSet())
        update(save = true) { list -> list.filter { it.running } }
    }

    /**
     * End a job, once. The first ending wins: an operation that was stopped still runs its own
     * `finally` and reports what it managed, and that report must not turn a row the person
     * stopped back into "done".
     */
    private fun terminal(id: Long, f: (Job) -> Job) {
        samples.remove(id)
        _paused.value = _paused.value - id
        patch(id, save = true) { if (it.running) f(it) else it }
    }

    private fun patch(id: Long, save: Boolean = false, f: (Job) -> Job) =
        update(save) { list -> list.map { if (it.id == id) f(it) else it } }

    private fun update(save: Boolean = false, f: (List<Job>) -> List<Job>) {
        val next: List<Job>
        synchronized(this) {
            next = f(_jobs.value).take(CAP)
            _jobs.value = next
        }
        if (save) persist(next)
    }

    private fun persist(list: List<Job>) {
        val s = store ?: return
        runCatching {
            s.save(JobCodec.encode(JobHistory.prune(list.filter { it.source == "Filet" || !it.running }, now(), keepDays())))
        }
    }

    private fun loadHistory(): List<Job> {
        val text = store?.let { runCatching { it.load() }.getOrNull() } ?: return emptyList()
        return JobHistory.prune(JobHistory.afterRestart(JobCodec.decode(text)), now(), keepDays())
    }

    private companion object { const val CAP = JobHistory.CAP }
}

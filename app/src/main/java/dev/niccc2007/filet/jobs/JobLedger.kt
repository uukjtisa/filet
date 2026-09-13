package dev.niccc2007.filet.jobs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class JobState { RUNNING, DONE, FAILED, CANCELLED }

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
) {
    val running: Boolean get() = state == JobState.RUNNING
}

/**
 * "What is this phone doing, and what did it do while I was not looking."
 *
 * One ledger for every long operation - copies, indexing, hashing, signing - so the answer
 * is a single surface rather than a notification stack with no history. PLAN.md §5.2 has
 * Trawl publishing into the same ledger through the bridge; that is why [Job.source] exists
 * from the start instead of being retrofitted.
 *
 * In-memory and deliberately so: a job that did not survive a process death did not finish,
 * and showing a stale "62%" from last week would be a lie. Completed entries are kept for
 * the session and capped.
 */
class JobLedger {

    private val ids = AtomicLong(1)
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    val activeCount: Int get() = _jobs.value.count { it.running }

    fun start(title: String, subtitle: String = "", source: String = "Filet"): Long {
        val id = ids.getAndIncrement()
        update { listOf(Job(id, title, subtitle, source = source)) + it }
        return id
    }

    fun progress(id: Long, fraction: Float?, detail: String = "") =
        patch(id) { it.copy(progress = fraction?.coerceIn(0f, 1f), detail = detail) }

    fun finish(id: Long, detail: String = "") =
        patch(id) { it.copy(state = JobState.DONE, progress = 1f, detail = detail, endedAt = System.currentTimeMillis()) }

    fun fail(id: Long, error: String) =
        patch(id) { it.copy(state = JobState.FAILED, error = error, endedAt = System.currentTimeMillis()) }

    fun cancel(id: Long) =
        patch(id) { it.copy(state = JobState.CANCELLED, endedAt = System.currentTimeMillis()) }

    /** Publish a job Filet did not run - a bridge message from Trawl, for example. */
    fun publishExternal(job: Job) = update { listOf(job) + it.filterNot { j -> j.id == job.id } }

    fun clearFinished() = update { list -> list.filter { it.running } }

    private fun patch(id: Long, f: (Job) -> Job) =
        update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun update(f: (List<Job>) -> List<Job>) {
        _jobs.value = f(_jobs.value).take(CAP)
    }

    private companion object { const val CAP = 60 }
}

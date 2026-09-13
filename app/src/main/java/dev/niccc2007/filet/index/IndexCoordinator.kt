package dev.niccc2007.filet.index

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * When the index runs, and why it never has to.
 *
 * > **Never run a service to stay correct. Run work to stay *fast*.**
 *
 * Correctness comes from the freshness stack in SEARCH.md §4, which is stateless and works
 * after any kill, any reboot, any three-week gap. Everything scheduled here is a performance
 * optimisation: it can be denied, throttled, deferred for days, killed mid-run, or never
 * scheduled at all, and the app is exactly as correct - just colder.
 *
 * The tiers, cheapest first:
 *
 * 1. **inotify** on hot directories ([HotWatcher]) - instant, narrow, free while alive.
 * 2. **Wake-on-change** via `JobInfo.TriggerContentUri` on MediaStore - the OS wakes us when
 *    media changes, at no standing cost. Almost nobody uses this and it is the best-value
 *    tier on the list.
 * 3. **WorkManager** maintenance - a deferrable periodic sweep, charging and idle.
 * 4. A **short foreground service**, only for a user-initiated full rebuild.
 * 5. Nothing. Cold reads still answer; they just walk.
 */
class IndexCoordinator(
    private val context: Context,
    private val index: FileIndex,
    private val prefs: Prefs,
    private val ledger: JobLedger,
    private val scope: CoroutineScope,
) {
    private var running: Job? = null
    private var watcher: HotWatcher? = null

    private val _run = MutableStateFlow(IndexRun())

    /** The current user-initiated run, for the Stop button and the readout beside it. */
    val run: StateFlow<IndexRun> = _run.asStateFlow()

    fun attachWatcher(w: HotWatcher) { watcher = w }

    /**
     * Called at startup. Cheap: it schedules, it does not crawl.
     *
     * A first crawl is deliberately deferred rather than run here - the first frame after
     * onboarding must not compete with a full-device scan for IO.
     */
    fun onStart(roots: List<VPath>) {
        if (!prefs.indexEnabled.value) return
        scheduleMaintenance()
        scheduleWakeOnMediaChange()
        scope.launch {
            // Let the UI settle before touching the disk at all.
            delay(2_500)
            val last = index.status.value.lastRunAt
            val stale = System.currentTimeMillis() - last > STALE_AFTER_MS
            if (index.status.value.files == 0L || stale) crawl(roots, budgetMs = 25_000)
        }
    }

    /**
     * A bounded background pass. Safe to truncate: the generation sweep only fires after a
     * complete run, so a half-finished one never deletes rows it simply did not reach.
     *
     * Safe is not the same as *resumable*. The next pass walks from the roots again, so on a
     * tree bigger than the budget this tier keeps the hot end of the disk fresh and never
     * reaches the cold end. Finishing the job is [crawlFully]'s, and the user presses it.
     */
    fun crawl(roots: List<VPath>, budgetMs: Long) {
        if (running?.isActive == true) return
        running = scope.launch {
            val jobId = ledger.start("Indexing storage", "${roots.size} volume(s)")
            val result = runCatching {
                index.crawl(roots, budgetMs) { seen -> ledger.progress(jobId, null, "$seen files") }
            }
            result.onSuccess { r ->
                if (r.complete) ledger.finish(jobId, "${r.seenFiles} files, ${r.changedDirs} folders changed")
                else ledger.finish(jobId, "background pass: ${r.seenFiles} files in the time it had")
            }.onFailure { e ->
                ledger.fail(jobId, e.message ?: "index failed")
            }
        }
    }

    /**
     * Index everything, and keep going until it is done.
     *
     * This is what "Index now" means and what the background tiers deliberately are not. The
     * background passes are bounded because an unbounded background crawl is a battery
     * complaint; **this one is not bounded at all**, because the user asked for it, is
     * watching it, and can stop it.
     *
     * The bound that used to be here was worse than useless. A truncated crawl does not
     * resume — [FileIndex.crawl] walks from the roots every time — so a 25-second budget on a
     * tree that takes three minutes indexed the same first slice forever and reported
     * "paused, will resume". It never resumed anywhere.
     *
     * Two things can end it besides finishing: the user, and a flat battery. Both are named
     * in [IndexRun.endedBecause] rather than left as a mystery.
     */
    fun crawlFully(roots: List<VPath>) {
        if (running?.isActive == true) return
        if (roots.isEmpty()) return
        endedBy = null
        IndexService.start(context)
        running = scope.launch {
            _run.value = IndexRun(active = true, startedAt = System.currentTimeMillis())
            val jobId = ledger.start("Indexing storage", "${roots.size} volume(s)")

            // The battery limit is a *limit*, not a schedule: checked while the crawl runs so
            // it can stop one that started at 80% and is still going at 14%.
            val watchdog = launch {
                while (isActive) {
                    delay(30_000)
                    if (batteryTooLow()) {
                        endedBy = CrawlEnd.BATTERY
                        running?.cancel()
                        break
                    }
                }
            }

            val result = runCatching {
                if (batteryTooLow()) {
                    endedBy = CrawlEnd.BATTERY
                    null
                } else {
                    index.crawl(roots, budgetMs = 0) { seen ->
                        _run.value = _run.value.copy(seen = seen)
                        ledger.progress(jobId, null, "$seen files")
                    }
                }
            }
            watchdog.cancel()

            val end = endedBy ?: when {
                result.isFailure -> CrawlEnd.FAILED
                result.getOrNull()?.complete == true -> CrawlEnd.DONE
                // Not complete and nothing claimed it: the coroutine was cancelled, and the
                // only thing that cancels it without setting a reason is stopCrawl().
                else -> CrawlEnd.STOPPED
            }
            val note = if (end == CrawlEnd.FAILED) result.exceptionOrNull()?.message.orEmpty() else ""

            _run.value = _run.value.copy(active = false, endedBecause = end, note = note)
            when (end) {
                CrawlEnd.DONE -> {
                    val r = result.getOrNull()
                    ledger.finish(jobId, "${r?.seenFiles ?: 0} files, ${r?.changedDirs ?: 0} folders changed")
                }
                CrawlEnd.FAILED -> ledger.fail(jobId, note.ifEmpty { "index failed" })
                else -> ledger.finish(jobId, "${end.label} at ${_run.value.seen} files")
            }
            IndexService.stop(context)
        }
    }

    /** The user's Stop. Named, so the readout can say the user did it rather than guessing. */
    fun stopCrawl() {
        endedBy = CrawlEnd.STOPPED
        running?.cancel()
    }

    fun isCrawling(): Boolean = running?.isActive == true

    /**
     * Set by whoever cancels the job, read once the job unwinds.
     *
     * Cancellation carries no reason of its own, so without this every stop would present as
     * the same anonymous "it stopped" the user complained about.
     */
    @Volatile private var endedBy: CrawlEnd? = null

    /** Below 15% and not charging. The one limit that stops a run the user asked for. */
    private fun batteryTooLow(): Boolean = runCatching {
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
            ?: return@runCatching false
        // 0 or -1 means the property is unreadable, not that the phone is empty. Treating an
        // unknown level as "too low" would refuse to index on any device that does not report
        // it, which is the worst possible way to fail this check.
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        level in 1..14 && !bm.isCharging
    }.getOrDefault(false)

    fun stop() {
        endedBy = CrawlEnd.STOPPED
        running?.cancel()
        watcher?.stopAll()
    }

    fun setEnabled(enabled: Boolean, roots: List<VPath>) {
        if (enabled) {
            scheduleMaintenance()
            scheduleWakeOnMediaChange()
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            cancelWakeOnMediaChange(context)
            stop()
        }
    }

    /** Tier 3: a deferrable sweep. Constraints say "when it is free", never "now". */
    private fun scheduleMaintenance() {
        val req = PeriodicWorkRequestBuilder<IndexWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresCharging(false)
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /**
     * Tier 2: let the OS tell us when media changed.
     *
     * `TriggerContentUri` costs nothing while nothing happens - no process, no wakelock, no
     * alarm. It only covers what MediaStore knows about, which is why it is a *hint* that
     * schedules a crawl rather than a source of truth.
     */
    fun rearmMediaTrigger() = scheduleWakeOnMediaChange()

    private fun scheduleWakeOnMediaChange() {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        val info = JobInfo.Builder(JOB_MEDIA_CHANGE, ComponentName(context, MediaChangeJobService::class.java))
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    dev.niccc2007.filet.vfs.provider.AndroidStorage.mediaChangeUri,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                )
            )
            .setTriggerContentUpdateDelay(30_000)
            .setTriggerContentMaxDelay(5 * 60_000)
            .build()
        runCatching { js.schedule(info) }
    }

    companion object {
        const val WORK_NAME = "filet.index.maintenance"
        const val JOB_MEDIA_CHANGE = 4101
        private const val STALE_AFTER_MS = 6L * 60 * 60 * 1000

        fun cancelWakeOnMediaChange(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_MEDIA_CHANGE)
        }
    }
}

/**
 * Tier 3's actual work.
 *
 * Bounded to two minutes. An unbounded worker is one the OS eventually stops trusting, and a
 * partial pass is fine here: nothing depends on the crawl having finished.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = FiletApp.graphOf(applicationContext)
        if (!graph.prefs.indexEnabled.value) return Result.success()
        val roots = runCatching { graph.vfs.roots().map { it.path } }.getOrElse { emptyList() }
        if (roots.isEmpty()) return Result.success()
        runCatching { graph.index.crawl(roots, budgetMs = 120_000) }
        return Result.success()
    }
}

/**
 * Tier 2's callback. The OS woke us because media changed; do a *short* pass and go away.
 *
 * `jobFinished(params, false)` rather than rescheduling: the trigger re-arms itself, and a
 * self-rescheduling job is how a background indexer turns into a battery complaint.
 */
class MediaChangeJobService : JobService() {

    private var work: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val graph = FiletApp.graphOf(applicationContext)
        if (!graph.prefs.indexEnabled.value) return false
        work = graph.scope.launch {
            val roots = runCatching { graph.vfs.roots().map { it.path } }.getOrElse { emptyList() }
            if (roots.isNotEmpty()) runCatching { graph.index.crawl(roots, budgetMs = 20_000) }
            jobFinished(params, false)
        }
        // Re-arm: a TriggerContentUri job fires once and must be scheduled again to keep
        // listening. Forgetting this is why most implementations "stop working after a day".
        graph.indexCoordinator.rearmMediaTrigger()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        work?.cancel()
        return false
    }
}

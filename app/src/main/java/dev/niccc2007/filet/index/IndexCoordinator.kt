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
import dev.niccc2007.filet.index.CrawlKind
import dev.niccc2007.filet.index.crawlKind
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
 * 2. **The media store's change record** ([MediaWatch]) - an observer inside the running
 *    process, for as long as Filet is open and the minutes after set in Settings, answering
 *    each change with one folder read. It replaced a JobScheduler trigger that woke the app for
 *    every media change on the phone; see [rearmMediaTrigger] for what that cost.
 * 3. **WorkManager** maintenance - a deferrable periodic sweep, charging and idle. Shares that
 *    asked for a complete index get their own, on charge and on Wi-Fi ([ShareIndexWorker]).
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
     * The `volume.uuid` of every share that has been opted in to indexing by hand.
     *
     * Read on every crawl rather than captured, so turning a share on or off takes effect at the
     * next pass instead of the next process. Empty until somebody opts a share in - see
     * [Indexable], where a share not in this set is never walked.
     */
    var indexedShares: () -> Set<String> = { emptySet() }

    /**
     * A first build from the media store, for an index holding nothing - see [MediaWatch.seed].
     * Set by the graph; a no-op until then.
     */
    var seedFromMedia: suspend (List<VPath>) -> Unit = {}

    /** [roots] reduced to what a crawl may walk. The single place every entry point narrows. */
    private fun crawlable(roots: List<VPath>): List<VPath> = Indexable.volumes(roots, indexedShares())

    /**
     * What an UNASKED pass may walk: the phone's own drives, never a share.
     *
     * SEARCH.md §14.7. A share that asked for a complete index is walked on charge and on Wi-Fi
     * by [ShareIndexWorker], and nowhere else unasked. Before this the bounded pass on open
     * crawled opted-in shares too, in the same queue as internal storage - a NAS read over
     * mobile data, on battery, on every launch.
     */
    private fun unaskedLocal(roots: List<VPath>): List<VPath> = Indexable.volumes(roots)

    /**
     * Called at startup. Cheap: it schedules, it does not crawl.
     *
     * A first crawl is deliberately deferred rather than run here - the first frame after
     * onboarding must not compete with a full-device scan for IO.
     */
    fun onStart(roots: List<VPath>) {
        if (!prefs.indexEnabled.value) return
        cancelWakeOnMediaChange(context)
        // Manual: nothing scheduled, nothing on launch. Cancelled rather than merely skipped, so
        // a sweep armed while the setting was Smart does not keep firing after it was changed.
        if (!prefs.indexAuto.value) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(SHARE_WORK_NAME)
            return
        }
        scheduleMaintenance()
        decideAndCrawl(roots)
    }

    /**
     * Called when the browser opens - every time, from any activity hosting it.
     *
     * Bug identified here, and it was the largest battery cost the app has had: this used to be
     * `crawlFully(roots)`, unconditionally, from `BrowserViewModel.start()`. Unbounded, over every
     * volume `vfs.roots()` returns - two network shares totalling 340 GB and the root mounts on
     * the phone it was measured on - and it raised the foreground service with its persistent
     * notification. `start()` runs in the file picker too, so every app that asked Filet for a
     * photo started a full crawl of two NAS boxes. It showed as 46% of a day's battery.
     *
     * The careful decision already existed - [onStart] has always asked [crawlKind] - and the
     * open path simply never used it. Now both paths ask the same question, of the same local
     * volumes, and neither can raise the service: that is reserved for Index now.
     */
    fun onOpen(roots: List<VPath>) {
        if (!prefs.indexEnabled.value) return
        if (!prefs.indexAuto.value) return
        decideAndCrawl(roots)
    }

    /**
     * The one decision about whether a pass runs unasked, and how big it is.
     *
     * Bounded in every case. A BUILD used to be handed to [crawlFully], which is unbounded and
     * starts the service, so a fresh install's first open put a persistent notification up and
     * walked everything. It is the same bounded tier as an update now: the first passes build
     * the hot end of the disk quietly, and Index now is there for whoever wants the rest at once.
     */
    private fun decideAndCrawl(roots: List<VPath>) {
        val local = unaskedLocal(roots)
        if (local.isEmpty()) return
        scope.launch {
            // Let the UI settle before touching the disk at all.
            delay(2_500)
            val st = index.status.value
            // Bug identified, reported as the indexing never stopping. This used to read
            // `files == 0 || now - lastRunAt > STALE`, and lastRunAt is only written after a
            // COMPLETE pass - correctly, because that is the only thing "last indexed" can
            // honestly mean. On a tree bigger than one budget no pass ever completes, so that
            // time stayed at zero, the index read as permanently overdue, and every launch
            // started another pass that would also not complete. A loop with no exit.
            //
            // The attempt time is what decides whether to start one; the complete time is
            // what the user is told. See crawlKind, where the two are separated.
            when (
                crawlKind(
                    filesHeld = st.files,
                    lastCompleteAt = st.lastRunAt,
                    lastAttemptAt = st.lastAttemptAt,
                    now = System.currentTimeMillis(),
                    staleAfterMs = STALE_AFTER_MS,
                    retryAfterMs = RETRY_AFTER_MS,
                )
            ) {
                // Nothing held. The media store's table first - one query, seconds, and search
                // answers from it at once - then bounded passes like any other unasked work,
                // never with the service, which is the person's to start.
                CrawlKind.BUILD -> {
                    runCatching { seedFromMedia(local) }
                    crawl(local, budgetMs = BACKGROUND_BUDGET_MS)
                }
                // Rows are held. Visit what may have changed and leave the rest alone.
                CrawlKind.UPDATE -> crawl(local, budgetMs = BACKGROUND_BUDGET_MS)
                CrawlKind.NONE -> Unit
            }
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
    /**
     * Index a folder because somebody has just walked into it.
     *
     * Bug identified: search under a folder the crawl had not reached returned nothing, and
     * nothing about the app said why. Navigating somewhere is the strongest possible signal
     * that its contents are about to be asked about, so that is when it gets read.
     *
     * Bounded and polite: it does nothing if a crawl is already running, so it can never
     * displace the full pass or start two at once. Local paths only - walking a remote on
     * every navigation would cost a round trip per folder for a search that may never happen.
     */
    fun indexFolderNow(path: VPath) {
        if (!index.status.value.enabled || !index.status.value.available) return
        // Automatic, so Manual turns it off along with the rest.
        if (!prefs.indexAuto.value) return
        if (running?.isActive == true) return
        if (path.scheme != "local") return
        running = scope.launch {
            runCatching { index.crawl(listOf(path), budgetMs = FOLDER_BUDGET_MS) { _, _ -> } }
        }
    }

    fun crawl(roots: List<VPath>, budgetMs: Long) {
        if (running?.isActive == true) return
        running = scope.launch {
            val jobId = ledger.start(
                "Indexing storage", "${roots.size} volume(s)",
                cancel = coroutineContext[kotlinx.coroutines.Job],
                kind = dev.niccc2007.filet.jobs.JobKind.INDEX,
                pausable = true,
                runAgain = { crawl(roots, budgetMs) },
            )
            val result = runCatching {
                // The path as well as the count: a number moving in thousands looks exactly
                // like a number that has stopped, and that is what made a long crawl read as
                // a hung app.
                index.crawl(roots, budgetMs) { seen, at ->
                    // Reported between folders and outside any transaction, so a pause held here
                    // holds no lock anything else is waiting on.
                    ledger.holdIfPaused(jobId)
                    ledger.progress(jobId, null, IndexRun.readingLine(seen, at?.path))
                }
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
    /** @param runAgain what Run again on the activity row does: Index now, from the person who asked. */
    fun crawlFully(rootsIn: List<VPath>, runAgain: (() -> Unit)? = null) {
        if (running?.isActive == true) return
        // Narrowed here as well as by the caller. Index now is the one crawl the person asked
        // for, and "everything" still means local drives and opted-in shares - not `/data`.
        val roots = crawlable(rootsIn)
        if (roots.isEmpty()) return
        endedBy = null
        IndexService.start(context)
        running = scope.launch {
            // What the index already holds decides which of the two things this pass is, and
            // it is read before the pass starts so the number on screen is the one that was
            // genuinely there. See IndexRun: nothing was ever rebuilt, but the old line said
            // "0 files scanned so far" under a 30k index, which read as a wipe.
            val known = index.status.value.files
            _run.value = IndexRun(
                active = true,
                startedAt = System.currentTimeMillis(),
                phase = if (known > 0) IndexPhase.UPDATE else IndexPhase.BUILD,
                known = known,
            )
            // Stop on the activity row is the same as Stop in Settings: a cancel with no other
            // reason set reads as STOPPED below.
            val jobId = ledger.start(
                "Indexing storage", "${roots.size} volume(s)",
                cancel = coroutineContext[kotlinx.coroutines.Job],
                kind = dev.niccc2007.filet.jobs.JobKind.INDEX,
                pausable = true,
                runAgain = runAgain,
            )

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
                    // The phone in one pass, then each share in a pass of its own: a share's pass
                    // keeps its frontier only when it is alone (SEARCH.md §14.7), and a share
                    // that is asleep must not stop the phone's pass from completing.
                    val local = roots.filter { it.scheme !in VolumeKey.REMOTE_SCHEMES }
                    val shares = roots.filter { it.scheme in VolumeKey.REMOTE_SCHEMES }
                    val passes = buildList {
                        if (local.isNotEmpty()) add(local)
                        for (s in shares) add(listOf(s))
                    }
                    var seenBefore = 0L
                    passes.map { pass ->
                        index.crawl(pass, budgetMs = 0) { seen, at ->
                            ledger.holdIfPaused(jobId)
                            _run.value = _run.value.copy(seen = seenBefore + seen, reading = at?.path ?: "")
                            ledger.progress(jobId, null, IndexRun.readingLine(seenBefore + seen, at?.path))
                        }.also { seenBefore += it.seenFiles }
                    }.reduceOrNull(::combine)
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

            val r = result.getOrNull()
            _run.value = _run.value.copy(
                active = false,
                endedBecause = end,
                note = note,
                added = r?.addedFiles ?: 0,
                removed = r?.removedFiles ?: 0,
            )
            when (end) {
                CrawlEnd.DONE ->
                    ledger.finish(jobId, "${r?.seenFiles ?: 0} files, ${r?.changedDirs ?: 0} folders changed")
                CrawlEnd.FAILED -> ledger.fail(jobId, note.ifEmpty { "index failed" })
                else -> ledger.finish(jobId, "${end.label} at ${_run.value.seen} files")
            }
            IndexService.stop(context)
        }
    }

    /** Several passes reported as one run. Complete only if every one of them completed. */
    private fun combine(a: CrawlResult, b: CrawlResult) = CrawlResult(
        a.visitedDirs + b.visitedDirs,
        a.seenFiles + b.seenFiles,
        a.changedDirs + b.changedDirs,
        a.complete && b.complete,
        a.elapsedMs + b.elapsedMs,
        a.movedFiles + b.movedFiles,
        a.factsWritten + b.factsWritten,
        removedFiles = a.removedFiles + b.removedFiles,
        addedFiles = a.addedFiles + b.addedFiles,
    )

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

    /** Smart or Manual. See `Prefs.indexAuto`. */
    fun setAuto(auto: Boolean) {
        if (!prefs.indexEnabled.value) return
        if (auto) {
            scheduleMaintenance()
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(SHARE_WORK_NAME)
            // A pass already running unasked is stopped too: Manual has to mean that nothing is
            // happening on its own, from the moment it is chosen.
            if (_run.value.active.not()) running?.cancel()
        }
    }

    fun setEnabled(enabled: Boolean, roots: List<VPath>) {
        if (enabled) {
            if (prefs.indexAuto.value) scheduleMaintenance()
            // Tier 2 is retired, and an install that armed it still has it armed - so
            // turning the index on is also a chance to cancel it. See MediaChangeJobService.
            cancelWakeOnMediaChange(context)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(SHARE_WORK_NAME)
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
                    // On charge only. The pass is two minutes of walking every root, which
                    // on Android 11+ is media-provider CPU because shared storage is a FUSE
                    // mount it serves - and on charge that costs the battery nothing. The
                    // cost of this choice: on a phone never charged long enough for
                    // WorkManager to run it, this tier never runs, and the index is kept
                    // fresh by the launch pass and the read-path checks alone.
                    .setRequiresCharging(true)
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        // Scheduled whether or not a share has asked yet, and a no-op until one does: the
        // worker reads the opted-in set when it runs, so turning a share on needs no rescheduling.
        val shares = PeriodicWorkRequestBuilder<ShareIndexWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresCharging(true)
                    // Wi-Fi, or anything else unmetered. A complete index of a NAS is hundreds of
                    // listings, and on mobile data each one is somebody's allowance.
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(SHARE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, shares)
    }

    /**
     * RETIRED. Tier 2 - wake on a MediaStore change - and why it must not come back.
     *
     * On Android 11 and later shared storage is a FUSE mount served by the media provider, so
     * every listdir and stat performed under `/storage/emulated` is media-provider CPU. This job
     * was armed on `MediaStore.Files` with `NOTIFY_FOR_DESCENDANTS`, so ANY media change
     * anywhere on the phone - another app's download, a messenger receiving a photo - woke a
     * twenty-second crawl of every root, in the background, which re-armed itself, with the
     * index on by default. Not a leak and not a loop: a standing cost proportional to
     * everything else the phone does, billed to a system process. It showed as "Media picker"
     * taking most of a day's battery.
     *
     * **What retiring it loses**, stated so the trade is not rediscovered as a bug: the index is
     * not refreshed while the app is closed, so the first search after a long absence can miss
     * files that arrived meanwhile. The freshness stack covers it at no standing cost - a
     * directory whose mtime has not moved is skipped and one whose mtime HAS moved is re-read
     * (`SqliteIndex`'s `dirMtime` check), navigating into a folder indexes it
     * (`indexFolderNow`), search asks `generationsFor` before trusting a hit, and the launch
     * pass updates what changed. A stale result after a long absence is the visible symptom of
     * this decision, NOT a bug to fix by re-arming a background trigger.
     *
     * `rearmMediaTrigger` is kept as a CANCEL so a build that armed the job unschedules it
     * rather than leaving it live, and `MediaChangeJobService` stays declared so an
     * already-armed job resolves and cancels itself instead of failing to bind.
     */
    fun rearmMediaTrigger() = cancelWakeOnMediaChange(context)

    @Suppress("UnusedPrivateMember")
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
        /**
         * How long a navigate-triggered pass may take.
         *
         * Short on purpose. It is meant to cover the folder you are standing in and a little
         * of what is under it, not to become a full crawl every time a tab moves.
         */
        private const val FOLDER_BUDGET_MS = 6_000L

        /**
         * How long an unasked pass may run, on launch or on open.
         *
         * The same for a build and an update, because the distinction that matters is who
         * asked, not how much is held. Anything longer is Index now's job.
         */
        private const val BACKGROUND_BUDGET_MS = 25_000L

        const val WORK_NAME = "filet.index.maintenance"
        const val SHARE_WORK_NAME = "filet.index.shares"

        /**
         * How long one run of the share worker may walk, across every share.
         *
         * Under WorkManager's ten minutes with room to write the frontier. A share bigger than
         * this resumes at the next run from where it stopped - the reason it has a frontier.
         */
        const val SHARE_BUDGET_MS = 8L * 60 * 1000
        const val JOB_MEDIA_CHANGE = 4101
        private const val STALE_AFTER_MS = 6L * 60 * 60 * 1000

        /**
         * How long to leave a pass that did not complete before trying again.
         *
         * Thirty minutes. A tree too big for one budget is the case this exists for, and on it
         * every launch would otherwise start another doomed pass - which is what "the indexing
         * never stops" was. Long enough that opening the app repeatedly costs nothing, short
         * enough that a device left alone for an afternoon still makes progress.
         */
        private const val RETRY_AFTER_MS = 30L * 60 * 1000

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
        // A sweep that was armed while Smart and fires after Manual was chosen does nothing.
        if (!graph.prefs.indexAuto.value) return Result.success()
        val all = runCatching { graph.vfs.roots().map { it.path } }.getOrElse { emptyList() }
        // Local drives and opted-in shares only. This tier walked every root, shares included,
        // for two minutes every twelve hours - on charge, but over Wi-Fi to a NAS that may be
        // asleep, which is a cost the charger does not pay.
        // Not even opted-in shares any more: those are ShareIndexWorker's, on Wi-Fi.
        val roots = Indexable.volumes(all)
        if (roots.isEmpty()) return Result.success()
        runCatching { graph.index.crawl(roots, budgetMs = 120_000) }
        return Result.success()
    }
}

/**
 * The complete index a share asked for. SEARCH.md §14.7.
 *
 * On charge and on an unmetered network, one pass per share so each keeps its own frontier: a
 * share that is not finished when the budget runs out continues from that point next time,
 * rather than from its root. A share that is asleep or off the network ends its pass at the
 * first listing it refuses and costs nothing more.
 */
class ShareIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = FiletApp.graphOf(applicationContext)
        if (!graph.prefs.indexEnabled.value) return Result.success()
        if (!graph.prefs.indexAuto.value) return Result.success()
        val asked = graph.indexCoordinator.indexedShares()
        if (asked.isEmpty()) return Result.success()
        val all = runCatching { graph.vfs.roots().map { it.path } }.getOrElse { emptyList() }
        val shares = Indexable.volumes(all, asked).filter { it.scheme in VolumeKey.REMOTE_SCHEMES }
        val deadline = System.currentTimeMillis() + IndexCoordinator.SHARE_BUDGET_MS
        for (share in shares) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            runCatching { graph.index.crawl(listOf(share), budgetMs = left) }
        }
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
        // Retired - see IndexCoordinator.rearmMediaTrigger for the whole reasoning. Every
        // MediaStore change on the phone became a twenty-second walk of every root, in the
        // background, served by the media provider. This cancels itself, so an install that
        // armed the job stops being woken by it.
        IndexCoordinator.cancelWakeOnMediaChange(applicationContext)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        work?.cancel()
        return false
    }
}

package dev.niccc2007.filet.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.niccc2007.filet.FiletGraph
import dev.niccc2007.filet.ops.Clipboard
import dev.niccc2007.filet.handlers.ExternalApp
import dev.niccc2007.filet.handlers.ExternalApps
import dev.niccc2007.filet.handlers.HandlerId
import dev.niccc2007.filet.handlers.editedName
import dev.niccc2007.filet.vfs.provider.ArchiveFormat
import dev.niccc2007.filet.vfs.provider.ArchiveEdits
import dev.niccc2007.filet.vfs.provider.ArchiveOptions
import dev.niccc2007.filet.vfs.provider.EditCosts
import dev.niccc2007.filet.vfs.provider.ExtractOptions
import dev.niccc2007.filet.vfs.provider.ExtractPlan
import dev.niccc2007.filet.vfs.provider.Archives
import dev.niccc2007.filet.vfs.provider.archiveName
import dev.niccc2007.filet.ops.PendingOp
import dev.niccc2007.filet.update.Download
import dev.niccc2007.filet.update.Release
import dev.niccc2007.filet.update.RemindChoice
import dev.niccc2007.filet.update.ReminderState
import dev.niccc2007.filet.update.UpdateNotifier
import dev.niccc2007.filet.update.Updater
import dev.niccc2007.filet.update.applyChoice
import dev.niccc2007.filet.update.label
import dev.niccc2007.filet.update.reenable
import dev.niccc2007.filet.update.shouldCheck
import dev.niccc2007.filet.update.shouldPrompt
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Side-by-side is the default because it is the MT Manager layout the mock settled on. */
enum class SplitMode { OFF, SIDE, STACK }

enum class Side { A, B }

/** What a drag is currently hovering, resolved by the UI from its own geometry. */
sealed interface DropTarget {
    data class Folder(val path: VPath) : DropTarget
    data class Pane(val paneId: Int) : DropTarget
}

data class DragState(
    val items: List<VNode>,
    val fromTab: Int,
    val over: DropTarget? = null,
    /** Where [over] resolves to, and what dropping there would actually do. Null when nowhere. */
    val drop: DropPlan? = null,
)

/**
 * What releasing the finger right now would do.
 *
 * Resolved while the drag is still moving so the ghost can say it *before* the drop rather
 * than a toast saying it afterwards - which is the difference between a file manager and a
 * guessing game.
 */
/** A drop that has landed, held while the prompt is open. */
data class PendingDrop(
    val paths: List<VPath>,
    val dest: VPath,
    val destLabel: String,
    val count: Int,
    /** What the same-volume rule would have picked, used only to preselect a button. */
    val suggested: DropAction,
)

/** A drop onto the folder the items already live in, waiting to be confirmed. */
data class PendingDuplicate(
    val paths: List<VPath>,
    val label: String,
    val count: Int,
)

data class DropPlan(
    val dest: VPath,
    val destLabel: String,
    val move: Boolean,
    /** Non-null when the drop is refused, and why. */
    val refusal: String? = null,
    /**
     * The items are already in [dest], so dropping means making another copy beside them.
     *
     * Not a refusal, which is what it used to be: "Already in X" is true and useless, naming
     * something you can see and offering nothing.
     */
    val duplicate: Boolean = false,
) {
    val label: String
        get() = when {
            refusal != null -> refusal
            duplicate -> "Make a copy here"
            move -> "Move to $destLabel"
            else -> "Copy to $destLabel"
        }
}

/**
 * A storage card's data.
 *
 * @param remote whether this volume is reached over the network. Carried on the row rather than
 *   re-derived in the UI from the path's scheme: the scheme test that was there read `local` as
 *   the only on-device scheme, and `root:` and `saf:` are not it - so a card on this very phone
 *   could be treated as an unreachable network drive and taken off Home.
 */
data class VolumeInfo(
    val node: VNode,
    val label: String,
    val free: Long?,
    val total: Long?,
    val remote: Boolean = false,
)

/** The APK toolchain's actions, for the one function that says whether each can run. */
enum class ApkAction { DECOMPILE, REBUILD, SIGN, MANIFEST, INSTALL }

/** Where an update check has got to. One type, so the sheet is a single `when`. */
sealed interface UpdateState {
    data object Checking : UpdateState
    data class Available(val release: Release) : UpdateState
    data class UpToDate(val release: Release) : UpdateState
    data class Downloading(
        val release: Release,
        val percent: Int,
        val bytes: Long,
        val total: Long,
    ) : UpdateState
    /**
     * Downloaded and waiting.
     *
     * Carries no file handle on purpose. R3 keeps storage types below the VFS, and a view
     * model passing `java.io.File` around is exactly the leak that rule is for - so the
     * updater remembers where it put the APK and this only says that it did.
     */
    data class Ready(val release: Release, val bytes: Long) : UpdateState
    data class Failed(val reason: String) : UpdateState

    /** GitHub answered and there is nothing published. Not an error, just not an update. */
    data object NoReleases : UpdateState
}

/**
 * A file, and the apps on this device that could open it.
 *
 * @param forceRemember null to let the sheet ask; non-null when the answer is already given.
 */
data class AppPick(
    val node: VNode,
    val apps: List<ExternalApp>,
    val forceRemember: Boolean? = null,
)

/** A file plus the handler that should show it. */
data class OpenRequest(val node: VNode, val handler: HandlerId)

data class AppState(
    val tabIds: List<Int> = emptyList(),
    val activeA: Int = 0,
    val activeB: Int = 0,
    val split: SplitMode = SplitMode.OFF,
    val focused: Side = Side.A,
    val clipboard: Clipboard? = null,
    val drag: DragState? = null,
    /**
     * A drop that has landed and is waiting to be told what it is.
     *
     * Separate from [drag], which is the gesture in flight. The gesture ends when the finger
     * lifts; the question outlives it.
     */
    val dropAsk: PendingDrop? = null,
    /** A drop onto the source folder, waiting for a yes. */
    val duplicateAsk: PendingDuplicate? = null,
    /**
     * An action that was refused rather than broken.
     *
     * Its own field rather than a toast, because a refusal has two things to say - what was
     * refused and what would have to change - and a toast has room for neither.
     */
    val denied: dev.niccc2007.filet.vfs.Denial? = null,
    val volumes: List<VolumeInfo> = emptyList(),
    val switcherOpen: Boolean = false,
    val activityOpen: Boolean = false,
    val toast: String? = null,
    /** Bumped whenever a job finishes, so the current panes re-list without a manual pull. */
    val revision: Int = 0,

    /**
     * Bumped whenever this device moves to a different network.
     *
     * Here rather than only in the graph so a composable can key a read on it. The hosting card
     * prints the address a PC should mount, computed once per state change because enumerating
     * interfaces is a syscall each - and a change of network is exactly when that answer is
     * wrong and nothing else about the state has moved.
     */
    val networkMoves: Long = 0L,
)

/**
 * Owns the tabs, the split, the clipboard and the drag - everything that is *between* panes.
 *
 * A pane owns its own location, history, selection and search ([PaneController]); this class
 * owns nothing a single pane could own. The split shows two tabs at once rather than giving
 * each pane its own tab strip, which is what the mock settled on and what keeps "which tab
 * am I looking at" answerable.
 */
class BrowserViewModel(private val graph: FiletGraph) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _tabs = MutableStateFlow<List<PaneController>>(emptyList())
    val tabs: StateFlow<List<PaneController>> = _tabs.asStateFlow()

    val prefs get() = graph.prefs

    /** For the row that reads a folder's item count. Read-only use only. */
    val vfs get() = graph.vfs
    val bookmarks get() = graph.bookmarks
    val recents get() = graph.recents
    val ledger get() = graph.ledger

    private var nextId = 1
    private var started = false

    /** Which handler is open over the browser, if any. */
    private val _openRequest = MutableStateFlow<OpenRequest?>(null)
    val openRequest: StateFlow<OpenRequest?> = _openRequest.asStateFlow()

    /** The file the "Open with" sheet is asking about. */
    private val _chooserFor = MutableStateFlow<VNode?>(null)
    val chooserFor: StateFlow<VNode?> = _chooserFor.asStateFlow()

    val registry get() = graph.registry

    /**
     * Re-measure every volume: a card can be unmounted while the app is backgrounded.
     *
     * **A failed read never blanks the cards.** `roots()` returning nothing is not the same fact
     * as there being no volumes - it is also what a throw looks like after `getOrElse`, and the
     * reported fault of the drive information vanishing *as though Filet had been disconnected
     * from its own device* is exactly that: a list replaced by the result of a read that failed.
     * The previous list is kept instead, and the failure is recorded rather than rendered.
     *
     * An empty list is still allowed to win when it is the truth, which is the first run, before
     * any volume has been seen.
     */
    suspend fun loadVolumes() {
        val roots = runCatching { graph.vfs.roots() }.getOrNull()
        if (roots == null) {
            dev.niccc2007.filet.log.FiletLog.w("volumes", "roots() failed; keeping the cards that were already there")
            return
        }
        if (roots.isEmpty() && _state.value.volumes.isNotEmpty()) {
            dev.niccc2007.filet.log.FiletLog.w("volumes", "roots() came back empty over " +
                _state.value.volumes.size + " known volume(s); keeping them")
            return
        }
        measureVolumes(roots)
    }

    /**
     * Re-read the volumes while a surface showing them is up.
     *
     * Only network ones can change on their own: a local volume is there or it is not, and its
     * free space moves slowly enough that a card draw is a fine time to read it. A mounted share
     * is different - the far device sleeps, wakes, joins another network - and nothing was
     * re-reading it, so a card drawn while the other phone was off stayed "Offline" for the rest
     * of the session even after it came back.
     *
     * Started per visible surface and cancelled with it, so a backgrounded app is not polling
     * the network.
     */
    fun watchVolumes(): kotlinx.coroutines.Job = viewModelScope.launch {
        while (true) {
            kotlinx.coroutines.delay(VOLUME_POLL_MS)
            val hasNetwork = _state.value.volumes.any { it.node.path.scheme != "local" }
            if (!hasNetwork) continue
            loadVolumes()
        }
    }

    /**
     * Re-list when a mounted device changes something on this phone.
     *
     * Coalesced, and that is the whole difficulty. A desktop copying a folder in performs one
     * write per file, so reacting to each would re-list the visible panes hundreds of times in a
     * few seconds - a directory read per file, on a phone, while that same phone is serving the
     * copy. So a write only ARMS a re-list, and the re-list happens once the writes stop for
     * [REMOTE_SETTLE_MS].
     *
     * Settling rather than throttling on purpose: a throttle would redraw mid-copy showing a
     * half-copied folder, and then again at the end. Waiting for quiet shows the finished state
     * once. The cost is that a lone edit takes that long to appear, which is well under the time
     * it takes to look up at the phone.
     */
    private fun watchRemoteWrites() {
        viewModelScope.launch {
            var last = graph.remoteWrites.value
            graph.remoteWrites.collect { now ->
                if (now == last) return@collect
                last = now
                // Wait for quiet. If more writes land while waiting, this job is replaced and the
                // wait starts again, so a long copy re-lists once at the end rather than per file.
                remoteSettle?.cancel()
                remoteSettle = viewModelScope.launch {
                    kotlinx.coroutines.delay(REMOTE_SETTLE_MS)
                    refreshPanes()
                }
            }
        }
    }

    private var remoteSettle: kotlinx.coroutines.Job? = null

    fun start() {
        if (started) return
        started = true
        watchRemoteWrites()
        watchDiscoveredAddresses()
        watchNetworkMoves()
        viewModelScope.launch {
            val roots = dev.niccc2007.filet.log.FiletLog.span("launch", "vfs.roots") {
                runCatching { graph.vfs.roots() }.getOrElse { emptyList() }
            }
            // The order here is the whole of the slow cold start, and it was the wrong way round.
            //
            // Measuring the volumes came first and `restoreTabs` - the panes, which is the part
            // somebody is looking at - came after it. Measuring a volume asks it for its free
            // space, and for a mounted share that is a network round trip: one that is asleep
            // costs a connect timeout per saved address, twice over, before it gives up. Two
            // shares that are away were enough to hold the first screen for twenty seconds, and
            // nothing on it needed them.
            //
            // So the tabs go first and the drives fill in behind them. Same work, and the screen
            // is usable while it happens.
            dev.niccc2007.filet.log.FiletLog.span("launch", "restoreTabs") { restoreTabs(roots.firstOrNull()?.path) }
            // The number that matters, and the one the report was about: everything a person
            // looks at on opening Filet is on screen by here. What follows fills in figures.
            dev.niccc2007.filet.log.FiletLog.i("launch", "first screen ready at +" + dev.niccc2007.filet.log.FiletLog.sinceStart() + "ms")
            loadVolumes()
            updateIndexOnOpen(roots)
        }
    }

    /**
     * Start an index update every time Filet opens, which is the required behaviour.
     *
     * Deliberately quiet. It posts no toast and blocks nothing: a pass that confirms what is
     * still there is the normal state of affairs, not an event. The settings screen shows it
     * running, and the notification carries a Stop, so it is visible without being in the way.
     *
     * Three things it will not do. It will not run when indexing is switched off, because that
     * setting has to mean what it says. It will not start a second pass over one already in
     * flight. And on a device with nothing indexed yet it still runs - that first pass is the
     * BUILD phase and says so, rather than silently doing the most expensive thing Filet can
     * do under a label that suggests otherwise.
     */
    private fun updateIndexOnOpen(volumes: List<VNode>) {
        val roots = volumes.map { it.path }
        if (roots.isEmpty()) return
        if (!graph.prefs.indexEnabled.value) return
        if (graph.indexCoordinator.isCrawling()) return
        graph.indexCoordinator.crawlFully(roots)
    }

    /**
     * Measure each volume once, in one place.
     *
     * Both callers used to build this inline and one of them quietly passed `total = null`, so
     * Home showed a bar-less card depending on which code path had last refreshed. One
     * function, so the two cannot disagree again.
     */
    /**
     * The cards, carrying whatever was last measured.
     *
     * **A figure already known is never thrown away to re-measure it**, and that is the point of
     * this function rather than a plain map. Home hides a card whose figures are null, so
     * publishing the list unmeasured - which is what makes the cards appear before the slow
     * volumes answer - would make every one of them vanish and come back on each pass. The first
     * version of this did exactly that, every eight seconds.
     *
     * It is also the reported fault, from the other direction: *the home drives information
     * disappears as if Filet got disconnected from my device*. A volume whose measurement fails
     * once - a busy superuser shell, a document provider that declines for a moment - lost its
     * figures, and losing its figures is losing its card. Now a failure leaves the last known
     * figure in place; only `roots()` no longer listing a volume takes it off the screen, which
     * is the one signal that actually means it is gone.
     */
    private suspend fun volumeInfos(roots: List<VNode>): List<VolumeInfo> {
        val known = _state.value.volumes.associateBy { it.node.path }
        return roots.map { node ->
            val before = known[node.path]
            VolumeInfo(
                node = node,
                label = volumeLabel(node),
                free = before?.free,
                total = before?.total,
                remote = graph.vfs.isRemote(node.path),
            )
        }
    }

    /**
     * Draw every card at once, then fill in its figures as they arrive.
     *
     * Three separate costs were being paid in series here, and all three are avoidable:
     *
     *  1. **The cards waited for the slowest volume.** Nothing is shown until the whole list is
     *     built, so one sleeping share decided when Home appeared. Now the cards are published
     *     unmeasured - a card with no figure yet is a card you can read and tap - and each figure
     *     lands when it lands.
     *  2. **The volumes were measured one after another.** They are independent, and a local
     *     volume answers in microseconds while a remote one may never answer. Measured together.
     *  3. **Nothing was bounded.** A remote with several saved addresses tries each in turn, and
     *     an asleep device costs a connect timeout on every one - then does it again for the
     *     total. [MEASURE_BUDGET_MS] caps the whole measurement of one volume, so a drive that is
     *     away costs one budget rather than an unbounded sum of timeouts.
     */
    /**
     * Consecutive failed measurements per volume, for the backoff.
     *
     * Per volume rather than one flag for all of them: a sleeping share must not slow down the
     * polling of a local disk that is answering in a millisecond.
     */
    private val failures = java.util.concurrent.ConcurrentHashMap<dev.niccc2007.filet.vfs.VPath, Int>()

    /** Measurement passes so far, which is what the backoff counts in. */
    private var passes = 0L

    private suspend fun measureVolumes(roots: List<VNode>) {
        _state.update { it.copy(volumes = volumeInfos(roots)) }
        if (roots.isEmpty()) return
        passes++
        kotlinx.coroutines.coroutineScope {
            for (node in roots) {
                // A volume that is not answering is not asked again on every pass. One sleeping
                // share was costing five seconds of the eight-second poll, for ever, to be told
                // the same thing - and the backoff is what makes the poll affordable rather than
                // something to switch off.
                if (!VolumePolling.shouldProbe(passes, failures[node.path] ?: 0)) continue
                launch {
                    val began = System.nanoTime()
                    val measured = runCatching {
                        kotlinx.coroutines.withTimeout(MEASURE_BUDGET_MS) {
                            graph.vfs.freeSpace(node.path) to graph.vfs.totalSpace(node.path)
                        }
                    }.getOrNull()
                    val ms = (System.nanoTime() - began) / 1_000_000
                    if (measured == null) {
                        dev.niccc2007.filet.log.FiletLog.w("volumes", "no figures for " + node.path + " after " + ms + "ms")
                        // Nothing is written back. The card keeps the last figure it had rather
                        // than losing it - and with it, losing its place on Home.
                        failures[node.path] = (failures[node.path] ?: 0) + 1
                        return@launch
                    }
                    failures.remove(node.path)
                    dev.niccc2007.filet.log.FiletLog.mark("volumes", "measured " + node.path, ms)
                    // Patched in by path rather than by rebuilding the list: the list can have
                    // been replaced underneath this while a slow volume was still answering, and
                    // writing a whole list back would undo whatever changed in the meantime.
                    _state.update { st ->
                        st.copy(
                            volumes = st.volumes.map { v ->
                                if (v.node.path != node.path) v
                                else v.copy(free = measured.first, total = measured.second)
                            },
                        )
                    }
                }
            }
        }
    }

    // ── tabs ──

    private fun newPane(): PaneController {
        val pane = PaneController(
            id = nextId++,
            vfs = graph.vfs,
            prefs = graph.prefs,
            scope = viewModelScope,
            searchSources = graph.searchSources,
            indexStatus = { graph.index.status.value },
            generationsFor = { paths -> graph.index.generationsFor(paths) },
            onSearched = { q -> graph.index.steerCrawl(q) },
            onOpened = { node -> openNode(node) },
            // Walking into a folder is the moment its contents are about to be searched, so
            // it is also when indexing it is worth doing. Bounded and skipped entirely if a
            // crawl is already running - see IndexCoordinator.indexFolderNow.
            onLanded = { path -> graph.indexCoordinator.indexFolderNow(path) },
            worldRevision = { _state.value.revision },
            dragging = { _state.value.drag != null },
        )
        pane.rootsForDevice = _state.value.volumes.map { it.node.path }
        return pane
    }

    fun addTab(at: VPath? = null): Int {
        val pane = newPane()
        if (at != null) pane.navigateTo(at, push = false) else pane.openHome()
        _tabs.value = _tabs.value + pane
        val idx = _tabs.value.lastIndex
        focusTab(idx)
        persistTabs()
        return idx
    }

    fun closeTab(index: Int) {
        val list = _tabs.value
        if (index !in list.indices) return
        // Never leave zero tabs: an empty tab strip has no affordance to get back to a folder.
        if (list.size == 1) {
            list[0].openHome()
            persistTabs()
            return
        }
        _tabs.value = list.filterIndexed { i, _ -> i != index }
        val left = _tabs.value.size
        // Clamping BEFORE decrementing was the bug: the clamp had already taken one off for
        // the tab that just went, then the decrement took another, so with enough tabs the
        // pointer landed past the end and `paneFor` returned null - a blank pane. See Tabs.kt.
        _state.update {
            it.copy(
                activeA = indexAfterClose(it.activeA, index, left),
                activeB = indexAfterClose(it.activeB, index, left),
            )
        }
        persistTabs()
    }

    /** Assigns the tab to the focused side, which is what makes one tab strip serve two panes. */
    /**
     * Re-read every pane that has nothing to show.
     *
     * Called on resume and after storage access changes. A tab restored at cold start can list
     * its folder before all-files access is confirmed; without this, it sits empty until the
     * user taps it, which reads as the app forgetting where it was.
     */
    /**
     * Coming back to the app.
     *
     * Two different staleness problems, and both are real:
     *  - a tab that listed nothing because permission had not landed yet ([refreshIfEmpty]);
     *  - a folder changed by something OUTSIDE Filet while it was in the background - a
     *    download finishing, a photo taken, another file manager. Nothing bumped the world
     *    revision for those, so the visible panes are re-read unconditionally on resume rather
     *    than by the counter. Two panes at most, once per resume, which is affordable.
     */
    fun refreshStalePanes() {
        _tabs.value.forEach { it.refreshIfEmpty() }
        refreshPanes()
    }

    /**
     * The bottom bar's Files button.
     *
     * Goes to the main storage volume; when already there, goes Home. The old version only
     * acted when the pane was NOT showing a folder, so pressing it while looking at files -
     * which is almost always - did nothing at all.
     */
    fun goToFiles() {
        val pane = focusedPane() ?: return
        val root = _state.value.volumes.firstOrNull()?.node?.path
        val here = pane.state.value.cwd
        when {
            root == null -> pane.openHome()
            here == root -> pane.openHome()
            else -> pane.navigateTo(root)
        }
    }

    /**
     * Drag a tab into a new position.
     *
     * The active tab is tracked by index, so moving one has to move the pointers with it or
     * the strip highlights whichever tab happens to land on that number - which reads as the
     * drag having selected something else.
     */
    fun moveTab(from: Int, to: Int) {
        val list = _tabs.value
        if (from !in list.indices || to !in list.indices || from == to) return
        _tabs.value = list.movedItem(from, to)
        _state.update {
            it.copy(
                activeA = indexAfterMove(it.activeA, from, to),
                activeB = indexAfterMove(it.activeB, from, to),
            )
        }
        persistTabs()
    }

    /**
     * Refresh, on whatever kind of pane is in front of you.
     *
     * `PaneController.refresh()` re-lists a folder and returns on everything else, so the
     * button was dead on six of the eleven pane kinds - drawn, enabled, and doing nothing.
     * What each kind needs is [refreshPlan], which is a table with a test that fails if a
     * kind is ever added without one.
     *
     * It says what it re-read afterwards, because a refresh with no visible change is
     * indistinguishable from the broken version.
     */
    fun refreshPane(pane: PaneController) {
        val plan = refreshPlan(pane.state.value.kind)
        viewModelScope.launch {
            for (target in plan.targets) applyRefresh(target, pane)
            // Panes that read straight from a store on every composition need a nudge to
            // recompose at all, which is what this is.
            _state.update { it.copy(revision = it.revision + 1) }
            toast("${plan.label} refreshed")
        }
    }

    /**
     * Carry out ONE refresh target.
     *
     * Its own function rather than a `when` inside the button handler, because the button is no
     * longer the only thing that needs these: a change of network has to re-read the volumes and
     * the remotes too, and a second copy of this table is a second place for a target to be
     * forgotten.
     *
     * @param pane the pane being refreshed, or null when the refresh has no pane behind it - a
     *   network change re-reads what is stored, and re-listing whatever happens to be on screen is
     *   not part of that.
     */
    private suspend fun applyRefresh(target: RefreshTarget, pane: PaneController?) {
        when (target) {
            RefreshTarget.LISTING -> pane?.refresh()
            RefreshTarget.VOLUMES -> loadVolumes()
            RefreshTarget.HOME_FEED -> graph.home.refresh()
            RefreshTarget.NEARBY -> graph.nearby.resync()
            RefreshTarget.NEARBY_SCAN -> graph.nearby.startScan()
            RefreshTarget.SHORTCUTS -> graph.shortcuts.reload()
            RefreshTarget.SCRIPTS -> graph.scripts.reload()
            // Nothing cached to drop: the screen re-reads PackageManager when the
            // target fires, which is the only source there is.
            RefreshTarget.INSTALLED_APPS -> Unit
            RefreshTarget.BOOKMARKS -> graph.bookmarks.reload()
            RefreshTarget.RECENTS -> graph.recents.reload()

            // Bug identified: these three shared one branch and one comment claiming
            // all of them were live, and for one of them that was false. Remotes reads
            // its own revision flow, which nothing here was bumping - so the button on
            // that tab toasted "Remotes refreshed" and re-read nothing, including
            // whether root had been granted since, which genuinely changes outside the
            // app. A shared justification is where a dead branch hides, so each target
            // now answers for itself.
            RefreshTarget.REMOTES -> bumpRemotes()

            // Live by construction: `graph.index.status` is a StateFlow the settings
            // and about panes collect, so it is already pushing changes.
            RefreshTarget.INDEX_STATUS -> Unit

            // Live by construction: the job ledger is in-memory and its flow is
            // collected directly, so there is no stored copy that can go stale.
            RefreshTarget.JOBS -> Unit
        }
    }

    /**
     * The network moved, so re-read the things that were true on the old one.
     *
     * The rest of the recovery is not here: the beacon re-announces and re-listens from the graph,
     * because a share can be up with no UI at all. This is the part a person sees - the storage card
     * for a mounted drive going back to answering, and the remotes list redrawing - and it runs
     * through the same target table the refresh button uses rather than a private copy of it.
     *
     * No toast. Nobody asked for this refresh, and a message about a network change arriving while
     * somebody is reading a folder is noise.
     */
    private fun watchNetworkMoves(): kotlinx.coroutines.Job = viewModelScope.launch {
        // The value present when this attaches is the network the app STARTED on, which is not a
        // move. Acting on it measured every volume a second time during the launch, concurrently
        // with the first pass - visible in the log as two of every line, and it cost about a
        // second of a cold start to learn nothing. Skipping a fixed number would have been wrong:
        // if the app starts with no network, the first bump IS a real change, and this skips the
        // baseline rather than the first event.
        var baseline: Long? = null
        graph.netWatch.moves.collect { moves ->
            if (baseline == null) {
                baseline = moves
                return@collect
            }
            dev.niccc2007.filet.log.FiletLog.i("net", "network moved (" + moves + ")")
            _state.update { it.copy(networkMoves = moves) }
            applyRefresh(RefreshTarget.VOLUMES, null)
            applyRefresh(RefreshTarget.REMOTES, null)
        }
    }

    fun focusTab(index: Int) {
        if (index !in _tabs.value.indices) return
        _state.update {
            if (it.focused == Side.A || it.split == SplitMode.OFF) it.copy(activeA = index, focused = Side.A)
            else it.copy(activeB = index)
        }
        persistTabs()
        // A tab that was off screen when a file was moved is behind the world, and until this
        // call switching to it showed the folder as it was before the move - for the life of
        // the app. See FolderFreshness: a tab that is already current does nothing here.
        freshenVisiblePanes()
    }

    fun focusSide(side: Side) = _state.update { it.copy(focused = side) }

    /**
     * The pane a side is showing.
     *
     * Clamped rather than nullable-on-overflow. Every path that changes the tab list is meant
     * to keep the pointers valid, and the one that did not rendered a blank screen instead of
     * failing - so the point of use clamps as well. Null now means what it says: there are no
     * tabs at all.
     */
    fun paneFor(side: Side): PaneController? {
        val s = _state.value
        val list = _tabs.value
        val idx = if (side == Side.A) s.activeA else s.activeB
        return safeTabIndex(idx, list.size)?.let { list[it] }
    }

    fun focusedPane(): PaneController? = paneFor(_state.value.focused)

    fun cycleSplit() {
        cycleSplitState()
        // The second pane has just become visible and may have been off screen through several
        // operations. Same start pipeline as a tab switch.
        freshenVisiblePanes()
    }

    private fun cycleSplitState() = _state.update {
        val next = when (it.split) {
            SplitMode.OFF -> SplitMode.SIDE
            SplitMode.SIDE -> SplitMode.STACK
            SplitMode.STACK -> SplitMode.OFF
        }
        // Opening the split for the first time must not show the same folder twice - that
        // reads as a rendering bug. Point B at the next tab, creating one if needed.
        var b = it.activeB
        if (next != SplitMode.OFF && b == it.activeA) {
            b = if (_tabs.value.size > 1) (it.activeA + 1) % _tabs.value.size else -1
        }
        if (b == -1) {
            addTabInternal(null)
            b = _tabs.value.lastIndex
        }
        it.copy(split = next, activeB = b, focused = if (next == SplitMode.OFF) Side.A else it.focused)
    }

    private fun addTabInternal(at: VPath?) {
        val pane = newPane()
        if (at != null) pane.navigateTo(at, push = false) else pane.openHome()
        _tabs.value = _tabs.value + pane
    }

    // ── opening ──

    fun openNode(node: VNode) {
        val pane = focusedPane() ?: return
        graph.recents.record(node.path, node.isDir)
        // Frecency counts real interactions only. Counting appearances in a result list
        // makes the ranking eat itself (SEARCH.md §5.4).
        viewModelScope.launch { runCatching { graph.index.recordOpen(node.path) } }
        when {
            node.isDir -> pane.navigateTo(node.path)
            // Routed through BundleRoute so the bundle formats stop falling into the archive
            // branch unnoticed. A tap on one still opens the browser; what changed is that
            // Install is now offered for it in the menu, which it never was.
            dev.niccc2007.filet.apk.BundleRoute.tap(node.name, node.isDir) ==
                dev.niccc2007.filet.apk.BundleRoute.Tap.INSPECT -> openApk(node)
            // By whole name, not by extension: `backup.tar.gz` has the extension "gz".
            Archives.canList(node.name) -> mountArchive(node)
            else -> openUnclaimed(node)
        }
    }

    /**
     * A file the name says nothing useful about.
     *
     * Before giving it to a handler, look at what it actually is. An archive renamed to
     * something else used to be a dead end: every routing decision was made on the extension,
     * so a zip saved as `.bin` was a binary blob even though the reader would have opened it
     * without complaint.
     *
     * Only when nothing else has a better claim. See Sniff: an app package, an ebook and a
     * word document are all zips underneath, and putting the archive viewer ahead of their own
     * handler would be a worse bug than the one being fixed.
     */
    private fun openUnclaimed(node: VNode) {
        viewModelScope.launch {
            val kind = runCatching {
                // Sniffing reads the first few hundred bytes of the file, which for an unclaimed
                // file on a mounted share is a socket read.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    graph.vfs.openRead(node.path).use { input ->
                        val head = ByteArray(dev.niccc2007.filet.vfs.provider.Sniff.NEEDED)
                        var read = 0
                        while (read < head.size) {
                            val n = input.read(head, read, head.size - read)
                            if (n <= 0) break
                            read += n
                        }
                        dev.niccc2007.filet.vfs.provider.Sniff.kindOf(head.copyOf(read))
                    }
                }
            }.getOrDefault(dev.niccc2007.filet.vfs.provider.Sniff.Kind.UNKNOWN)

            val confidence = dev.niccc2007.filet.vfs.provider.Sniff.confidence(
                kind = kind,
                nameIsArchive = Archives.canList(node.name),
                nameIsKnownOther = graph.registry.builtInFor(node.extension) != null,
            )
            if (dev.niccc2007.filet.vfs.provider.Sniff.openAsArchive(confidence)) {
                toast("This is a ${kind.name.lowercase()} archive despite its name.")
                mountArchive(node)
            } else {
                openWithRegistered(node)
            }
        }
    }

    /**
     * Walk into a zip or apk as if it were a folder.
     *
     * Only local files can be mounted directly: the zip reader needs random access, and a
     * SAF document has none. Anything else is copied to the cache first, which is honest but
     * slow, so it is left to the caller to decide - hence the failure message rather than a
     * silent copy of a 2 GB archive.
     */
    fun mountArchive(node: VNode) {
        val pane = focusedPane() ?: return
        graph.recents.record(node.path, isDir = false)

        // A local archive opens where it lies.
        val local = graph.vfs.osPath(node.path)
        if (local != null) {
            pane.navigateTo(ArchiveProvider.mount(local))
            return
        }

        // One on a share has no path, and a zip is read by seeking - from the END of the file
        // for its index, then to each entry - so a forward-only stream cannot open one at all.
        // A staged copy is what makes it work; REMOTE-FILES.md has why this is the last resort
        // and what replaces it.
        viewModelScope.launch {
            dev.niccc2007.filet.log.FiletLog.i(
                "archive",
                "opening " + node.path + " (" + node.size + " bytes, remote=" +
                    graph.vfs.isRemote(node.path) + ")",
            )
            runCatching { graph.staging.stage(node.path, node.size, node.mtime) }
                .onSuccess { staged ->
                    if (staged == null) {
                        // Reachable only when the file is not remote after all - which here means
                        // it had no local path AND is not on a remote provider, so nothing can
                        // open it. Said plainly rather than as a shrug, and recorded, because the
                        // last time this surfaced the message gave the reader nothing to act on.
                        dev.niccc2007.filet.log.FiletLog.w("archive", "nothing staged for " + node.path + "; not a remote file")
                        toast("${node.name} is not on a drive Filet can read from.")
                    } else {
                        dev.niccc2007.filet.log.FiletLog.i(
                            "archive",
                            "staged " + staged.length() + " bytes to " + staged.name + ", mounting",
                        )
                        pane.navigateTo(ArchiveProvider.mount(staged.absolutePath))
                    }
                }
                .onFailure {
                    dev.niccc2007.filet.log.FiletLog.e("archive", "could not stage " + node.path, it)
                    toast("Could not read ${node.name}: " + dev.niccc2007.filet.ops.FileOperations.readable(it))
                }
        }
    }

    // ── clipboard ──

    fun copySelection() = withSelection { items ->
        _state.update { it.copy(clipboard = Clipboard(items.map { n -> n.path }, PendingOp.COPY)) }
        toast("${items.size} copied")
        focusedPane()?.clearSelection()
    }

    fun cutSelection() = withSelection { items ->
        _state.update { it.copy(clipboard = Clipboard(items.map { n -> n.path }, PendingOp.MOVE)) }
        toast("${items.size} ready to move")
        focusedPane()?.clearSelection()
    }

    fun paste(side: Side = _state.value.focused) {
        val clip = _state.value.clipboard ?: return
        val pane = paneFor(side) ?: return
        val dest = pane.state.value.cwd ?: return
        viewModelScope.launch {
            val r = if (clip.op == PendingOp.COPY) graph.ops.copy(clip.items, dest)
            else graph.ops.move(clip.items, dest)
            if (clip.op == PendingOp.MOVE) _state.update { it.copy(clipboard = null) }
            // Where each one lands, so the rows appear at the bottom instead of scattering
            // into sort order the instant the paste finishes.
            pane.noteArrived(clip.items.map { dest.child(it.name) })
            reportAndRefresh(r.succeeded, r.failed, if (clip.op == PendingOp.COPY) "copied" else "moved", r.denial)
        }
    }

    /**
     * Paste into a NAMED pane rather than into whichever one has focus.
     *
     * The paste pill is drawn in every pane, so the pane that was tapped is the destination.
     * Going through `focused` here would make the pill in the unfocused half of a split silently
     * paste into the other one, which is the exact confusion the per-pane pill exists to remove.
     */
    fun pasteInto(pane: PaneController) {
        val side = sideOf(pane) ?: return
        focusSide(side)
        paste(side)
    }

    private fun sideOf(pane: PaneController): Side? =
        Side.entries.firstOrNull { paneFor(it)?.id == pane.id }

    fun clearClipboard() = _state.update { it.copy(clipboard = null) }

    // ── operations ──

    fun deleteSelection() = withSelection { items ->
        viewModelScope.launch {
            val r = graph.ops.delete(items.map { it.path })
            reportAndRefresh(r.succeeded, r.failed, "deleted", r.denial)
        }
    }

    fun rename(target: VPath, newName: String) {
        if (newName.isBlank() || newName == target.name) return
        viewModelScope.launch {
            runAction(
                dev.niccc2007.filet.vfs.FileAction.RENAME,
                target,
                onDone = {
                    // A rename is the old row leaving and a new one arriving. Holding the new
                    // name at the bottom is what keeps it findable when the name it was given
                    // sorts it somewhere else entirely.
                    target.parent?.child(newName.trim())?.let { renamed ->
                        _tabs.value.forEach { p -> p.noteGone(target); p.noteArrived(listOf(renamed)) }
                    }
                    toast("Renamed"); refreshPanes()
                },
            ) { graph.vfs.rename(target, newName.trim()) }
        }
    }

    fun createFolder(name: String, side: Side = _state.value.focused) {
        val dest = paneFor(side)?.state?.value?.cwd ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            val made = dest.child(name.trim())
            runAction(
                dev.niccc2007.filet.vfs.FileAction.CREATE_DIR,
                dest,
                onDone = { paneFor(side)?.noteArrived(listOf(made)); toast("Folder created"); refreshPanes() },
            ) { graph.vfs.create(made, isDir = true) }
        }
    }

    fun createFile(name: String, side: Side = _state.value.focused) {
        val dest = paneFor(side)?.state?.value?.cwd ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            val made = dest.child(name.trim())
            runAction(
                dev.niccc2007.filet.vfs.FileAction.CREATE_FILE,
                dest,
                onDone = { paneFor(side)?.noteArrived(listOf(made)); toast("File created"); refreshPanes() },
            ) { graph.vfs.create(made, isDir = false) }
        }
    }

    /**
     * @param typed what the user put in the box, with or without a suffix.
     * @param format which container to write. The suffix comes from the format rather than
     *   from the typed name, so picking Tar + gzip and leaving "Photos.zip" in the box makes
     *   a `Photos.tar.gz` and not a gzipped tar wearing a zip's name.
     */
    fun compressSelection(
        typed: String,
        format: ArchiveFormat,
        options: ArchiveOptions = ArchiveOptions.NONE,
    ) = withSelection { items ->
        val dest = focusedPane()?.state?.value?.cwd ?: return@withSelection
        viewModelScope.launch {
            val base = Archives.baseName(typed.trim()).ifEmpty { "Archive" }
            val existing = runCatching { graph.vfs.list(dest).map { it.name }.toHashSet() }
                .getOrDefault(HashSet())
            val name = archiveName(base, format) { it in existing }
            val r = graph.ops.compress(
                items, dest.child(name), format, { graph.files.scratchPath(it) }, options,
            )
            // Blanked whatever happened. The array exists so it can be wiped, and a password
            // left in the heap after a failed compress is the same password left after a
            // successful one.
            options.clearPassword()
            reportAndRefresh(r.succeeded, r.failed, "compressed into $name", r.denial)
        }
    }

    /**
     * Roughly how much the current selection weighs.
     *
     * Only the top level, deliberately: it feeds the split-size check, which needs an order of
     * magnitude rather than an exact figure, and walking a selection of folders to get an exact
     * one would make opening the Compress dialog a job of its own.
     */
    fun selectionBytes(): Long =
        focusedPane()?.selectedNodes()?.sumOf { it.size.coerceAtLeast(0L) } ?: 0L

    /**
     * Name and size for everything selected, for the output-size estimate.
     *
     * A folder reports -1 rather than 0: the estimator says "at least" when it cannot measure
     * something, and a folder counted as zero bytes would turn an unknown into a confident
     * wrong answer.
     */
    fun selectionSizes(): List<Pair<String, Long>> =
        focusedPane()?.selectedNodes()?.map { it.name to if (it.isDir) -1L else it.size } ?: emptyList()

    /** Every format Compress offers, in the order the table declares them. */
    val archiveFormats: List<ArchiveFormat> get() = Archives.creatable

    // ── extraction: plan first, then run exactly that ──

    /**
     * What the pending extraction will do, or null when none is pending.
     *
     * Held here rather than in the sheet so that adjusting an option re-plans through the same
     * code the extractor will walk. A sheet that kept its own copy and edited it would be a
     * second implementation of the decision, which is the thing this whole design avoids.
     */
    private val _extractPlan = MutableStateFlow<PendingExtract?>(null)
    val extractPlan: StateFlow<PendingExtract?> = _extractPlan.asStateFlow()

    data class PendingExtract(
        val archiveRoot: VPath,
        val into: VPath,
        val label: String,
        val options: ExtractOptions,
        val plan: ExtractPlan,
        val busy: Boolean = false,
    )

    /** Open the preview. Nothing is written until it is confirmed. */
    fun extract(node: VNode, into: VPath? = null) {
        val dest = into ?: focusedPane()?.state?.value?.cwd ?: return
        val root = if (node.path.scheme == ArchiveProvider.SCHEME) node.path
        else ArchiveProvider.mount(node.path.path)
        viewModelScope.launch {
            val options = ExtractOptions()
            val plan = graph.extractOps.plan(root, dest, node.name, options)
            _extractPlan.value = PendingExtract(root, dest, node.name, options, plan)
        }
    }

    /** Extract into the other pane, which is what a two-pane file manager is for. */
    fun extractToOtherPane(node: VNode) {
        val here = _state.value.focused
        val other = paneFor(if (here == Side.A) Side.B else Side.A)?.state?.value?.cwd ?: return
        extract(node, other)
    }

    /** Re-plan with changed options. The preview always shows the current answer. */
    fun adjustExtract(change: (ExtractOptions) -> ExtractOptions) {
        val current = _extractPlan.value ?: return
        val options = change(current.options)
        viewModelScope.launch {
            val plan = graph.extractOps.plan(current.archiveRoot, current.into, current.label, options)
            _extractPlan.value = current.copy(options = options, plan = plan)
        }
    }

    fun cancelExtract() { _extractPlan.value = null }

    /** Carry out exactly the plan on screen. */
    fun confirmExtract() {
        val pending = _extractPlan.value ?: return
        _extractPlan.value = pending.copy(busy = true)
        viewModelScope.launch {
            val r = graph.extractOps.run(pending.archiveRoot, pending.into, pending.plan, pending.label)
            _extractPlan.value = null
            reportAndRefresh(r.succeeded, r.failed, "extracted", r.denial)
        }
    }

    /**
     * True when the selection is exactly one archive Filet can read.
     *
     * Asked by the menu builder rather than decided in it: "is this an archive" is one answer
     * and `Archives.canList` is where it lives, so a format added to the table shows its
     * extract rows without anybody editing a menu.
     */
    // -- picking, when another app asked for a file --

    /**
     * Non-null while Filet is running as somebody else's file picker.
     *
     * A plain property rather than a flow: it is set once before the first composition and
     * never changes, because the request came in on the Intent that started the activity.
     * Everything that reads it is asking "am I a picker right now", which is a fact about the
     * activity rather than a piece of state that moves.
     */
    var pickRequest: dev.niccc2007.filet.pick.PickRequest? = null

    val picking: Boolean get() = pickRequest != null

    /** The asking app's name, for the notice strip. Null when the caller could not be named. */
    var pickCaller: String? = null

    /** Hand the selection back to the app that asked. Set by the picker activity. */
    var onPickConfirm: (() -> Unit)? = null

    /** Give up and return a cancel. Set by the picker activity. */
    var onPickCancel: (() -> Unit)? = null

    /** What the picker would hand over: the focused pane's selection, files and folders both. */
    fun pickSelection(): List<VNode> = focusedPane()?.selectedNodes().orEmpty()

    /**
     * Whether Install should be offered for what is selected.
     *
     * One file, and it names itself as an app package. See BundleRoute: the extension is the
     * right test here because the platform installer reads the real contents and refuses
     * anything that is not a package, so a wrong guess costs a refusal rather than a bad
     * install - while NOT offering install on a correctly named bundle is the fault that
     * shipped.
     */
    fun selectionIsInstallable(): Boolean {
        val items = focusedPane()?.selectedNodes() ?: return false
        val one = items.singleOrNull() ?: return false
        return !one.isDir && dev.niccc2007.filet.apk.BundleRoute.installable(one.name)
    }

    /** Install whatever single package is selected. Routes bundles through the session installer. */
    fun installSelection() {
        val one = focusedPane()?.selectedNodes()?.singleOrNull() ?: return
        installApk(one)
    }

    fun selectionIsArchive(): Boolean {
        val items = focusedPane()?.selectedNodes() ?: return false
        return items.size == 1 && !items[0].isDir && Archives.canList(items[0].name)
    }

    /** True when there is a second pane on screen to extract into. */
    fun isSplit(): Boolean = _state.value.split != SplitMode.OFF

    /** The three destinations, from a selection rather than from a node. */
    fun extractSelection() = withSelection { items -> extract(items[0]) }

    fun extractSelectionToPicked() = withSelection { items -> extractToPicked(items[0]) }

    fun extractSelectionToOtherPane() = withSelection { items -> extractToOtherPane(items[0]) }

    /** "Extract to ...": browse to a folder, then preview the extraction into that. */
    fun extractToPicked(node: VNode) {
        pickFolder("Extract ${node.name} to", "Extract into this") { dest -> extract(node, dest) }
    }

    // -- choosing a destination by browsing --

    /**
     * A folder being chosen, and what to do once it is.
     *
     * Deliberately not a [Dialog] case: it carries a callback, and that family is a set of value
     * types compared for equality. Generic on purpose too - extracting is the first caller, not
     * the only conceivable one.
     */
    class FolderPick(
        val title: String,
        val confirmLabel: String,
        val at: VPath,
        val entries: List<VNode>,
        val loading: Boolean,
        val atRoot: Boolean,
        val onPick: (VPath) -> Unit,
    )

    private val _folderPick = MutableStateFlow<FolderPick?>(null)
    val folderPick: StateFlow<FolderPick?> = _folderPick.asStateFlow()

    fun pickFolder(
        title: String,
        confirmLabel: String,
        start: VPath? = null,
        onPick: (VPath) -> Unit,
    ) {
        val at = start ?: focusedPane()?.state?.value?.cwd ?: return
        _folderPick.value = FolderPick(
            title, confirmLabel, at, emptyList(), loading = true, atRoot = isVolumeRoot(at), onPick = onPick,
        )
        loadPick(at)
    }

    /** Move the picker. The callback survives; only the location changes. */
    fun browsePick(to: VPath) {
        val current = _folderPick.value ?: return
        _folderPick.value = FolderPick(
            current.title, current.confirmLabel, to, emptyList(), true, isVolumeRoot(to), current.onPick,
        )
        loadPick(to)
    }

    fun pickUp() {
        val current = _folderPick.value ?: return
        if (current.atRoot) return
        browsePick(current.at.parent ?: return)
    }

    fun cancelPick() { _folderPick.value = null }

    /** Take the folder that is open. Picking is always "this one", never a selected row. */
    fun confirmPick() {
        val current = _folderPick.value ?: return
        _folderPick.value = null
        current.onPick(current.at)
    }

    private fun loadPick(at: VPath) {
        viewModelScope.launch {
            // Folders only. A file is never a destination, and listing them would make the
            // chooser a second file browser in which two rows in three cannot be tapped.
            val entries = runCatching { graph.vfs.list(at).filter { it.isDir } }
                .getOrElse { emptyList() }
                .sortedBy { it.name.lowercase() }
            val current = _folderPick.value ?: return@launch
            // Someone can tap through faster than a slow volume lists. Only the listing for
            // where the picker actually is may be applied.
            if (current.at != at) return@launch
            _folderPick.value = FolderPick(
                current.title, current.confirmLabel, at, entries, false, current.atRoot, current.onPick,
            )
        }
    }

    /**
     * True at the top of a volume.
     *
     * Walking above one lands on a path the provider cannot list, so the picker stops here
     * rather than showing an empty folder with no way back out of it.
     */
    private fun isVolumeRoot(p: VPath): Boolean =
        p.isRoot || _state.value.volumes.any { it.node.path == p }

    private inline fun withSelection(block: (List<VNode>) -> Unit) {
        val pane = focusedPane() ?: return
        val items = pane.selectedNodes()
        if (items.isEmpty()) return
        block(items)
    }

    /**
     * Say what happened, including WHY when something did not.
     *
     * The reason was always captured - every operation records `readable(throwable)` per failed
     * path - and then thrown away here, so a failure surfaced as "0 copied, 1 failed" with
     * nothing to act on. A count alone cannot be debugged by the person holding the phone, and it
     * cannot be reported usefully either.
     *
     * One reason, not a list: several files failing usually fail the same way, and a toast is not
     * a place for an error report. The path is named only when a single item failed, because with
     * forty of them the name of one is noise.
     */
    private fun reportAndRefresh(
        ok: Int,
        failures: List<Pair<dev.niccc2007.filet.vfs.VPath, String>>,
        verb: String,
        denial: dev.niccc2007.filet.vfs.Denial? = null,
    ) {
        val failed = failures.size
        val why = failures.firstOrNull()?.second
        // A refusal gets the dialogue instead of the toast. Both would be two messages about one
        // event, and the toast is the one that cannot say what to do about it.
        if (denial != null) showDenial(denial) else toast(
            when {
                failed == 0 -> "$ok $verb"
                failed == 1 && why != null -> "${failures[0].first.name}: $why"
                why != null -> "$ok $verb, $failed failed - $why"
                else -> "$ok $verb, $failed failed"
            },
        )
        _tabs.value.forEach { it.clearSelection() }
        refreshPanes()
    }

    fun showDenial(d: dev.niccc2007.filet.vfs.Denial) = _state.update { it.copy(denied = d) }

    fun dismissDenial() = _state.update { it.copy(denied = null) }

    /**
     * Run one action the same way every other action runs.
     *
     * The single-target twin of `FileOperations.act`: ask what the backend allows before
     * touching anything, do the work off the caller's thread because a remote path is a socket,
     * and turn a refusal into a dialogue rather than an error string in a toast.
     *
     * Used by the actions that speak to the VFS directly - rename, new folder, new file - which
     * are exactly the ones that used to report a refusal as whatever the backend called it.
     */
    private suspend fun <T> runAction(
        action: dev.niccc2007.filet.vfs.FileAction,
        path: dev.niccc2007.filet.vfs.VPath,
        onDone: (T) -> Unit,
        work: suspend () -> T,
    ) {
        graph.vfs.permit(action, path)?.let { showDenial(it); return }
        val remote = graph.vfs.isRemote(path)
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { work() } }
            .onSuccess(onDone)
            .onFailure { t ->
                dev.niccc2007.filet.vfs.ActionGate.fromFailure(action, path, t, remote)
                    ?.let { showDenial(it); return }
                toast(dev.niccc2007.filet.ops.FileOperations.readable(t))
            }
    }

    /**
     * Something wrote to the filesystem: move the world on, then re-list what is on screen.
     *
     * **The bump comes first and that ordering is the fix.** It used to come last, so the two
     * visible panes re-listed at the OLD revision and were immediately behind again - and every
     * tab that was not on screen was never marked stale at all, which is exactly the reported symptom:
     * a file moved out of a folder stayed drawn in any other tab showing that folder, forever.
     * Now the bump marks every pane stale and each one re-reads as it becomes visible.
     */
    fun refreshPanes() {
        _state.update { it.copy(revision = it.revision + 1) }
        val s = _state.value
        // Never under a finger that is holding something.
        //
        // This is the path that actually cancelled the drag, and gating `freshenIfStale` alone
        // did not stop it: `relist()` is called straight from here and never consults the
        // freshness rule. A row's gesture is keyed on its path and survives recomposition, but
        // not the row leaving the composition - which is exactly what re-listing does to it.
        //
        // The revision above is still bumped, so every pane is marked stale and `endDrag`
        // freshens them the moment the finger lifts. Deferred, not dropped.
        //
        // It bites hardest on a network pane, which is where it was reported: a mounted share
        // arms the remote-write settle timer, so a re-list can land at any moment while the
        // other device is doing anything at all.
        if (s.drag != null) return

        // relist, not refresh: this runs right after something was written, and `refresh` is
        // the gesture that puts the sort back. Using it here would sort the new file away in
        // the same frame it was created.
        _tabs.value.getOrNull(s.activeA)?.relist()
        if (s.split != SplitMode.OFF) _tabs.value.getOrNull(s.activeB)?.relist()
    }

    /**
     * Re-read the panes on screen if the world has moved since they last listed.
     *
     * The "start pipeline" requirement. Called on tab switch, on split change and on resume -
     * anywhere a folder becomes visible. Costs nothing when nothing has changed.
     */
    fun freshenVisiblePanes() {
        val s = _state.value
        _tabs.value.getOrNull(s.activeA)?.freshenIfStale()
        if (s.split != SplitMode.OFF) _tabs.value.getOrNull(s.activeB)?.freshenIfStale()
    }

    /** Re-sort and re-filter every tab without touching disk, after a settings change. */

    // ── drag and drop ──

    fun beginDrag(items: List<VNode>, fromTab: Int) {
        if (items.isEmpty()) return
        _state.update { it.copy(drag = DragState(items, fromTab)) }
    }

    fun dragOver(target: DropTarget?) = _state.update { s ->
        val drag = s.drag ?: return@update s
        if (target == drag.over) return@update s
        s.copy(drag = drag.copy(over = target, drop = planFor(drag.items, target)))
    }

    /**
     * What a drop on [target] would do - the same rules a desktop file manager uses.
     *
     * - **Same volume moves, a different volume copies.** Dragging between two folders on the
     *   phone is a move; dragging onto an SD card or a network share is a copy, because
     *   silently relocating a file off the volume it lives on is how people lose things.
     * - A folder cannot be dropped into itself or into its own subtree.
     * - Dropping into the folder the items already live in is refused, not performed.
     *
     * Returns null when the pointer is over nothing droppable.
     */
    private fun planFor(items: List<VNode>, target: DropTarget?): DropPlan? {
        if (items.isEmpty()) return null
        val dest = when (target) {
            is DropTarget.Folder -> target.path
            is DropTarget.Pane -> _tabs.value.firstOrNull { it.id == target.paneId }?.state?.value?.cwd
            null -> null
        } ?: return null
        // A volume root's last segment is a number - "/storage/emulated/0" reads as "Move to
        // 0", which names nothing. Use the volume's own label when the destination IS a root.
        val label = _state.value.volumes.firstOrNull { it.node.path == dest }?.label
            ?: dest.name.ifEmpty { dest.scheme }

        val selfDrop = items.any { it.isDir && it.path.contains(dest) }
        if (selfDrop) return DropPlan(dest, label, move = true, refusal = "Cannot drop a folder into itself")
        // Dropping onto the folder they came from is not a mistake to refuse - it is the
        // gesture for "give me another one of these". It used to answer "Already in X", which
        // is true and useless: it names what you can see and offers nothing.
        if (items.all { it.path.parent == dest }) {
            return DropPlan(dest, label, move = false, duplicate = true)
        }

        // "Same volume" is the scheme plus the volume root - /storage/emulated/0 and
        // /storage/XXXX-XXXX are both `local:` and a rename across them is not a rename.
        val move = items.all { sameVolume(it.path, dest) }
        return DropPlan(dest, label, move)
    }

    private fun sameVolume(a: VPath, b: VPath): Boolean {
        if (a.scheme != b.scheme) return false
        val roots = _state.value.volumes.map { it.node.path }
        val ra = roots.firstOrNull { it.contains(a) }
        val rb = roots.firstOrNull { it.contains(b) }
        // Unknown on either side: fall back to the scheme, which is the weaker but never-wrong
        // answer for providers that have no volume list at all.
        if (ra == null || rb == null) return true
        return ra == rb
    }

    /**
     * @param move null means "use the plan" - which is what a release does. A caller passes a
     *   value only to override it, e.g. a Copy here chosen from a drop menu.
     */
    fun endDrag(move: Boolean? = null) {
        val drag = _state.value.drag
        _state.update { it.copy(drag = null) }
        // A re-list that came due mid-drag was deferred rather than dropped - see
        // FolderFreshness - so the moment the finger lifts, let the panes catch up. A drop that
        // changes something bumps the revision again below and re-lists a second time, which is
        // one wasted listing in exchange for never tearing a list out from under a gesture.
        _tabs.value.forEach { it.freshenIfStale() }
        val plan = drag?.drop ?: return
        if (plan.refusal != null) { toast(plan.refusal); return }
        val paths = drag.items.map { it.path }

        // Its own question, not the move-or-copy one: there is nothing to choose between here,
        // only a yes. Moving something into the folder it is already in is a no-op.
        if (plan.duplicate) {
            _state.update {
                it.copy(
                    duplicateAsk = PendingDuplicate(
                        paths = paths,
                        label = drag.items.firstOrNull()?.name ?: plan.destLabel,
                        count = paths.size,
                    ),
                )
            }
            return
        }

        // An explicit override - a Copy chosen from a drop menu - skips the question, because it
        // has already been answered.
        if (move != null) { runDrop(paths, plan.dest, move) ; return }

        when (DragRules.resolve(graph.prefs.drag.value)) {
            DropAction.MOVE -> runDrop(paths, plan.dest, move = true)
            DropAction.COPY -> runDrop(paths, plan.dest, move = false)
            DropAction.ASK -> _state.update {
                it.copy(
                    dropAsk = PendingDrop(
                        paths = paths,
                        dest = plan.dest,
                        destLabel = plan.destLabel,
                        count = paths.size,
                        suggested = DragRules.suggested(plan.move),
                    ),
                )
            }
        }
    }

    /** Answer the prompt. [remember] stores the choice so it is not asked again. */
    fun answerDrop(action: DropAction, remember: Boolean) {
        val pending = _state.value.dropAsk ?: return
        _state.update { it.copy(dropAsk = null) }
        if (remember) graph.prefs.setDrag(DragRules.remembered(action))
        runDrop(pending.paths, pending.dest, move = action == DropAction.MOVE)
    }

    fun cancelDrop() = _state.update { it.copy(dropAsk = null) }

    fun cancelDuplicate() = _state.update { it.copy(duplicateAsk = null) }

    /** Make a copy of each item beside itself. */
    fun confirmDuplicate() {
        val pending = _state.value.duplicateAsk ?: return
        _state.update { it.copy(duplicateAsk = null) }
        viewModelScope.launch {
            val r = graph.ops.duplicate(pending.paths)
            reportAndRefresh(r.succeeded, r.failed, "copied", r.denial)
        }
    }

    private fun runDrop(paths: List<VPath>, dest: VPath, move: Boolean) {
        viewModelScope.launch {
            val r = if (move) graph.ops.move(paths, dest) else graph.ops.copy(paths, dest)
            reportAndRefresh(r.succeeded, r.failed, if (move) "moved" else "copied", r.denial)
        }
    }

    /** The stored drag behaviour, for the settings row. */
    val dragBehaviour get() = graph.prefs.drag

    fun setDragBehaviour(b: DragBehaviour) = graph.prefs.setDrag(b)

    fun cancelDrag() = _state.update { it.copy(drag = null) }

    // ── chrome ──

    fun openSwitcher(open: Boolean) = _state.update { it.copy(switcherOpen = open) }
    fun openActivity(open: Boolean) = _state.update { it.copy(activityOpen = open) }
    fun toast(msg: String?) = _state.update { it.copy(toast = msg) }
    fun consumeToast() = _state.update { it.copy(toast = null) }

    /** Always a folder: this is the toolbar star, and it bookmarks where you are standing. */
    fun toggleBookmark(path: VPath) {
        graph.bookmarks.toggle(path, isDir = true)
        toast(if (graph.bookmarks.contains(path)) "Bookmarked" else "Bookmark removed")
    }


    // ── dialogs ──

    private val _dialog = MutableStateFlow<Dialog?>(null)
    val dialog: StateFlow<Dialog?> = _dialog.asStateFlow()

    fun dismissDialog() = _dialog.update { null }
    fun askNewFolder() = _dialog.update { Dialog.NewFolder }
    fun askNewFile() = _dialog.update { Dialog.NewFile }
    fun askRename(node: VNode) = _dialog.update { Dialog.Rename(node) }
    fun askCompress() = _dialog.update { Dialog.Compress }
    fun showProperties(node: VNode) = _dialog.update { Dialog.Properties(node) }

    fun confirmDelete() {
        val items = focusedPane()?.selectedNodes().orEmpty()
        if (items.isEmpty()) return
        _dialog.update { Dialog.ConfirmDelete(items.size, items.first().name) }
    }

    /** Named after the folder, which is what a zip of a selection is nearly always for. */
    fun suggestedArchiveName(): String {
        val pane = focusedPane() ?: return "archive.zip"
        val sel = pane.selectedNodes()
        val stem = if (sel.size == 1) sel[0].name.substringBeforeLast('.')
        else pane.state.value.cwd?.name?.ifEmpty { "archive" } ?: "archive"
        return "$stem.zip"
    }

    /**
     * Why this location cannot be written to, or null when it can.
     *
     * Archives and APKs mount read-only, so every create/rename/delete/paste in one is refused
     * by the provider. Asking here means the UI can drop or grey those actions instead of
     * offering them and turning the refusal into an error.
     */
    fun writeBlockReason(path: VPath?): String? {
        if (path == null) return "Open a folder first"
        if (graph.vfs.canWrite(path)) return null
        return when (path.scheme) {
            "apk" -> "APKs open read-only — use Decompile to change one"
            "zip" -> "Archives open read-only — extract it to change what is inside"
            else -> "This location is read-only"
        }
    }

    fun canWriteHere(path: VPath?): Boolean = writeBlockReason(path) == null

    fun canPaste(): Boolean = _state.value.clipboard != null &&
        focusedPane()?.state?.value?.cwd != null

    // ── things only an Activity can do ──
    //
    // The ViewModel owns the decision, the Activity owns the Intent. Holding an Activity here
    // would leak it across a rotation, and holding only the application Context cannot start
    // a picker or a chooser.

    var onIntent: ((android.content.Intent) -> Unit)? = null
    /** @param startAt where the picker should open, or null for wherever it likes. */
    var onPickFolder: ((android.net.Uri?) -> Unit)? = null
    var onExit: (() -> Unit)? = null
    var onShare: ((List<VNode>) -> Unit)? = null
    var onCheckUpdates: (() -> Unit)? = null

    fun requestExit() { onExit?.invoke() }

    fun requestAllFiles() {
        val intent = dev.niccc2007.filet.vfs.provider.StorageAccess.resolvableRequestIntent(graph.app)
        if (intent == null) toast("This device has no all-files settings screen.")
        else onIntent?.invoke(intent)
    }

    fun requestFolderGrant(startAt: android.net.Uri? = null) {
        val cb = onPickFolder
        if (cb == null) toast("Cannot open the folder picker right now.") else cb(startAt)
    }

    // ── Termux ──

    /** Whether Termux is on this phone, decided once. */
    val termuxInstalled: Boolean by lazy {
        dev.niccc2007.filet.integrations.Termux.isInstalled(graph.app)
    }

    /**
     * Ask for access to Termux's own directory.
     *
     * Its files are in another app's private data, so nothing on an unrooted phone can read
     * them directly - Termux's own DocumentsProvider is the supported way in, and Filet already
     * treats a granted SAF tree as an ordinary volume. One tap, once per install, and after
     * that Termux is a pane you can drag files into.
     */
    fun connectTermux(home: Boolean = true) {
        if (!termuxInstalled) { toast("Termux is not installed."); return }
        requestFolderGrant(dev.niccc2007.filet.integrations.Termux.pickerHint(home))
        // Says WHERE, because that is the part he could not find: the picker opens on internal
        // storage and Termux is behind the hamburger menu, not on the first screen. The
        // EXTRA_INITIAL_URI hint is sent and this phone's picker ignores it, so the
        // instruction is the thing that actually works.
        toast("Open the menu in the picker and choose Termux, then Use this folder.")
    }

    /** Open Termux itself. */
    fun launchTermux() {
        val intent = dev.niccc2007.filet.integrations.Termux.launch(graph.app)
        if (intent == null) toast("Termux is not installed.")
        else onIntent?.invoke(intent)
    }

    /** A granted SAF tree becomes a first-class volume, not a special case. */
    fun onFolderGranted(uri: android.net.Uri) {
        viewModelScope.launch {
            val roots = runCatching { graph.vfs.roots() }.getOrElse { emptyList() }
            _state.update { st -> st.copy(volumes = volumeInfos(roots)) }
            val added = roots.lastOrNull { it.path.scheme == "saf" }
            if (added != null) {
                focusedPane()?.navigateTo(added.path)
                toast("Added ${added.name}")
            }
        }
    }

    // ── handlers (PLAN.md L2) ──

    /** The routing table itself, for the Settings list of per-extension defaults. */
    val handlers get() = graph.registry

    /** Single tap: whatever the registry says opens this type. */
    fun openWithRegistered(node: VNode) {
        when (val h = graph.registry.handlerFor(node)) {
            HandlerId.EXTERNAL -> openExternally(node)
            HandlerId.ARCHIVE -> mountArchive(node)
            HandlerId.APK -> openApk(node)
            else -> _openRequest.value = OpenRequest(node, h)
        }
    }

    /** Double tap: always ask, even when a default exists. That is what a chooser is for. */
    fun openChooser(node: VNode) { _chooserFor.value = node }

    fun dismissChooser() { _chooserFor.value = null }

    fun openWith(node: VNode, handler: HandlerId, remember: Boolean) {
        if (remember) graph.registry.setDefault(node.extension, handler)
        _chooserFor.value = null
        when (handler) {
            // "Another app" is half an answer - it says hand it off, not to whom. Ask, and
            // carry the Always/Just-once the user already gave rather than asking twice.
            HandlerId.EXTERNAL -> askWhichApp(node, forceRemember = if (remember) true else null)
            HandlerId.ARCHIVE -> mountArchive(node)
            HandlerId.APK -> openApk(node)
            else -> _openRequest.value = OpenRequest(node, handler)
        }
    }

    fun closeHandler() { _openRequest.value = null }

    // ── handing a file to another app, and remembering which one ──

    private val _appPickerFor = MutableStateFlow<AppPick?>(null)

    /** Non-null while Filet is asking which installed app should open something. */
    val appPickerFor: StateFlow<AppPick?> = _appPickerFor.asStateFlow()

    fun dismissAppPicker() { _appPickerFor.value = null }

    /**
     * Hand the file to another app - the same one as last time, if there was one.
     *
     * The old code called `Intent.createChooser` and that is why "hand to another app" asked
     * every single time. Android runs that chooser and never says what was picked, so there
     * was nothing to remember. Filet lists the candidates itself now, launches the component
     * directly, and writes the answer down.
     *
     * @param force true to ask again even when an app is remembered - the "Open with" action,
     *   as opposed to a plain tap.
     */
    fun openExternally(node: VNode, force: Boolean = false) {
        val ext = node.extension
        val remembered = if (force) null else graph.registry.externalFor(ext)
        if (remembered != null && ExternalApps.resolves(graph.app, remembered)) {
            launchIn(node, remembered)
            return
        }
        // Remembered but gone - say so rather than silently reopening the picker, because
        // "it used to open in X" is the thing the user will be confused about.
        if (remembered != null) {
            graph.registry.clearExternal(ext)
            toast("${remembered.label} is no longer installed — pick another")
        }
        askWhichApp(node)
    }

    /**
     * Raise Filet's own app list for this file.
     *
     * @param forceRemember non-null when the user has already said whether this should stick,
     *   so the sheet states the decision instead of asking for it a second time.
     */
    fun askWhichApp(node: VNode, forceRemember: Boolean? = null) {
        val mime = dev.niccc2007.filet.handlers.mimeOf(node)
        // Empty is a normal answer, not a refusal. Nothing declares a `.blend` or a `.sav`,
        // and the sheet's second tier - every launchable app - is exactly the case for it.
        // Turning that away with a toast was the dead end.
        val apps = ExternalApps.candidates(graph.app, mime)
        _appPickerFor.value = AppPick(node, apps, forceRemember)
    }

    /**
     * The user picked an app.
     *
     * @param remember write it into the per-extension defaults. Off unless the user ticked
     *   the box in the sheet - see [remembersByDefault]. Round 5 defaulted this on, which
     *   turned opening one file in one app once into a permanent routing rule.
     */
    fun openWithApp(node: VNode, app: ExternalApp, remember: Boolean) {
        _appPickerFor.value = null
        if (remember && node.extension.isNotEmpty()) {
            // alsoRoute only when the handler already says EXTERNAL. Answering "open this one
            // in VLC" should not silently stop Filet's own player being the default for mp4.
            val routed = graph.registry.handlerForExtension(node.extension) == HandlerId.EXTERNAL
            graph.registry.setExternal(node.extension, app, alsoRoute = routed)
        }
        launchIn(node, app)
    }

    private fun launchIn(node: VNode, app: ExternalApp) {
        viewModelScope.launch {
            val uri = runCatching { localUriForShare(node) }.getOrNull()
            if (uri == null) { toast("This file cannot be handed to another app."); return@launch }
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, dev.niccc2007.filet.handlers.mimeOf(node))
                .setComponent(app.component)
                .addFlags(
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                )
            val cb = onIntent
            if (cb == null) { toast("Cannot open another app right now."); return@launch }
            // An explicit component that refuses the intent throws rather than showing a
            // chooser, so the failure has to be caught and named here.
            runCatching { cb(i) }.onFailure {
                graph.registry.clearExternal(node.extension)
                toast("${app.label} would not open it — that default is cleared")
            }
        }
    }

    fun shareOne(node: VNode) {
        val cb = onShare
        if (cb == null) toast("Sharing is unavailable.") else cb(listOf(node))
    }

    /**
     * What a Share press is asking about, or null when nothing is asking.
     *
     * Bug identified, twice over. The first time it was the viewers handing straight to
     * Android's share sheet, so Filet's own network share - a link any browser here can open,
     * with nothing installed at the other end - was the one destination the button could not
     * reach. That was fixed for the viewers and only for the viewers: four other Share
     * presses still went straight past the chooser, and the reported one was the New files
     * row, where the whole point is that a file just arrived and you want to pass it on.
     *
     * So it is a held REQUEST rather than a held node now. Two reasons, and the second is the
     * one that matters:
     *  - a selection is a list, and the chooser had no way to express one
     *  - the targets travel WITH the ask. The sheet used to call `shareTargets(local = true)`
     *    with the flag hardcoded, which was true by luck because the only caller had already
     *    checked it. Any new caller would have been offered a network share for a file inside
     *    an archive, which has nothing to serve and fails after being chosen (R1).
     */
    data class ShareAsk(
        val items: List<VNode>,
        val targets: List<dev.niccc2007.filet.handlers.ShareTarget>,
    ) {
        /** What to call this on the sheet: the file, or how many of them. */
        val title: String get() =
            if (items.size == 1) items.first().name else "${items.size} files"
    }

    private val _shareChoice = MutableStateFlow<ShareAsk?>(null)
    val shareChoice: StateFlow<ShareAsk?> = _shareChoice.asStateFlow()

    /**
     * Share these files, asking where only when there is a real choice.
     *
     * The single entry point for every Share press in the app that offers one destination
     * button. Folders are dropped rather than refused: selecting a folder alongside files and
     * pressing Share should send the files, not stop and explain itself.
     *
     * **Every file has to be local for the network option to appear, not merely one of them.**
     * A mixed selection would serve some and silently drop the rest, and a share that quietly
     * sends four of six files is worse than one that only offers the sheet.
     */
    fun share(items: List<VNode>) {
        val files = items.filterNot { it.isDir }
        if (files.isEmpty()) { toast("Select a file to share."); return }
        val local = files.all { graph.vfs.osPath(it.path) != null }
        val targets = dev.niccc2007.filet.handlers.shareTargets(local)
        if (targets.size == 1) { shareToApps(files); return }
        _shareChoice.value = ShareAsk(files, targets)
    }

    /** One file. */
    fun share(node: VNode) = share(listOf(node))

    fun dismissShareChoice() { _shareChoice.value = null }

    fun shareVia(target: dev.niccc2007.filet.handlers.ShareTarget) {
        val ask = _shareChoice.value ?: return
        _shareChoice.value = null
        when (target) {
            dev.niccc2007.filet.handlers.ShareTarget.APPS -> shareToApps(ask.items)
            dev.niccc2007.filet.handlers.ShareTarget.NETWORK -> ask.items.forEach { shareOneNearby(it) }
        }
    }

    /** Straight to Android's sheet, no question asked. For buttons that say that is what they do. */
    private fun shareToApps(items: List<VNode>) {
        onShare?.invoke(items) ?: toast("Sharing is unavailable right now.")
    }

    /** One file into the shared set, starting the server if it is not already up. */
    fun shareOneNearby(node: VNode) {
        graph.nearby.shared.share(node)
        if (!graph.nearby.isRunning()) graph.nearby.start()
        toast("Shared — open Nearby for the link")
    }

    // ── file content, for the viewers ──

    /**
     * Blocking, and every caller is already on an IO dispatcher - the viewers all are.
     *
     * The `runBlocking` names IO explicitly rather than inheriting the caller's context, so
     * opening the stream is off the main thread even if a future caller forgets. The READS that
     * follow are still the caller's, which is what the contract above is about.
     */
    fun openRead(node: VNode): java.io.InputStream =
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            graph.vfs.openRead(node.path)
        }

    suspend fun readText(node: VNode): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            graph.vfs.openRead(node.path).use { String(it.readBytes(), Charsets.UTF_8) }
        }

    /**
     * A save of a file that lives inside an archive, waiting on the choice.
     *
     * Null when no such save is pending. Held rather than resolved immediately because the
     * answer is the user's: updating a 7z rewrites the whole thing, and that is a decision, not
     * a detail.
     */
    private val _archiveSave = MutableStateFlow<PendingArchiveSave?>(null)
    val archiveSave: StateFlow<PendingArchiveSave?> = _archiveSave.asStateFlow()

    class PendingArchiveSave(
        val node: VNode,
        val text: String,
        val archiveName: String,
        /** What updating the archive would cost, in a sentence. */
        val cost: String,
        /** Non-null when the archive cannot hold the edit at all - then only the other way out. */
        val refusal: String?,
        val busy: Boolean = false,
        val onSaved: () -> Unit,
    )

    /** Update the archive in place, having shown what it costs. */
    fun confirmArchiveSave() {
        val pending = _archiveSave.value ?: return
        if (pending.refusal != null) return
        _archiveSave.value = PendingArchiveSave(
            pending.node, pending.text, pending.archiveName, pending.cost, null, true, pending.onSaved,
        )
        viewModelScope.launch {
            val id = ledger.start("Saving into", pending.archiveName)
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val bytes = pending.text.toByteArray(Charsets.UTF_8)
                    ArchiveEdits.replace(pending.node.path) { java.io.ByteArrayInputStream(bytes) }
                }
            }.onSuccess {
                ledger.finish(id)
                _archiveSave.value = null
                pending.onSaved()
                toast("Saved into ${pending.archiveName}")
                refreshPanes()
            }.onFailure {
                val why = dev.niccc2007.filet.ops.FileOperations.readable(it)
                ledger.fail(id, why)
                _archiveSave.value = null
                toast("Could not save: $why")
            }
        }
    }

    /**
     * The other answer, and the one that is offered for every format including the ones that
     * cannot be written at all: put the edited file somewhere else and leave the archive alone.
     */
    fun saveArchiveMemberElsewhere() {
        val pending = _archiveSave.value ?: return
        _archiveSave.value = null
        pickFolder("Save ${pending.node.name} to", "Save here") { folder ->
            viewModelScope.launch {
                val id = ledger.start("Saving", pending.node.name)
                runCatching {
                    val taken = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { graph.vfs.list(folder).map { it.name }.toHashSet() }
                            .getOrDefault(hashSetOf())
                    }
                    // Never silently over something already there. The whole reason somebody
                    // picks this option is that they did not want to overwrite anything.
                    val name = freeName(pending.node.name, taken)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        graph.vfs.create(folder.child(name), isDir = false)
                        graph.vfs.openWrite(folder.child(name)).use {
                            it.write(pending.text.toByteArray(Charsets.UTF_8))
                        }
                    }
                    name
                }.onSuccess { name ->
                    ledger.finish(id)
                    pending.onSaved()
                    toast("Saved as $name")
                    refreshPanes()
                }.onFailure {
                    val why = dev.niccc2007.filet.ops.FileOperations.readable(it)
                    ledger.fail(id, why)
                    toast("Could not save: $why")
                }
            }
        }
    }

    fun cancelArchiveSave() { _archiveSave.value = null }

    /** `notes.txt` -> `notes (2).txt`, counting up until nothing is in the way. */
    private fun freeName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot <= 0) name else name.substring(0, dot)
        val ext = if (dot <= 0) "" else name.substring(dot)
        var n = 2
        while ("$stem ($n)$ext" in taken) n++
        return "$stem ($n)$ext"
    }

    /**
     * Write pending byte edits into a file.
     *
     * Read-modify-write, because there is no partial write to a mounted share and none through a
     * document provider either. That is why there is a ceiling and why it is checked here, before
     * anything is read, rather than discovered when a phone runs out of memory holding two copies
     * of the file.
     *
     * The edits are applied to the bytes that were actually read back, not to the page the screen
     * happened to be showing. A hex editor that writes what it last drew would write whatever was
     * on screen over whatever the file became if anything else touched it in between.
     */
    fun saveBytes(node: VNode, edits: dev.niccc2007.filet.handlers.HexEdits, onSaved: () -> Unit) {
        if (edits.isEmpty) return
        if (node.size > dev.niccc2007.filet.handlers.HexEdits.MAX_PATCH_BYTES) {
            toast(
                "Too large to patch: saving rewrites the whole file, and this one is " +
                    humanSize(node.size) + ".",
            )
            return
        }
        viewModelScope.launch {
            val written = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val original = graph.vfs.openRead(node.path).use { it.readBytes() }
                    val patched = edits.applyTo(original, bufferStart = 0L)
                    val out = graph.vfs.openWrite(node.path)
                    try { out.write(patched) } finally { out.close() }
                    patched.size
                }
            }
            written
                .onSuccess {
                    dev.niccc2007.filet.log.FiletLog.i(
                        "hex", "wrote " + edits.count + " byte edit(s) to " + node.path,
                    )
                    toast("Saved " + edits.count + " change" + if (edits.count == 1) "" else "s")
                    onSaved()
                }
                .onFailure {
                    dev.niccc2007.filet.log.FiletLog.e("hex", "could not patch " + node.path, it)
                    // A refusal from the far end gets the dialogue that says what to do about it;
                    // anything else is an ordinary failure and says what went wrong.
                    val denial = dev.niccc2007.filet.vfs.ActionGate.fromFailure(
                        dev.niccc2007.filet.vfs.FileAction.WRITE,
                        node.path,
                        it,
                        remote = graph.vfs.isRemote(node.path),
                    )
                    if (denial != null) showDenial(denial)
                    else toast("Could not save: " + dev.niccc2007.filet.ops.FileOperations.readable(it))
                }
        }
    }

    /** Everything this file's container already carries. */
    suspend fun readMetadata(node: VNode): List<Pair<String, String>> =
        graph.metadata.read(node.path)

    /**
     * Write the fields back, optionally keeping a copy of the file first.
     *
     * Field by field, because that is the store's unit of work and it verifies each write by
     * reading it back. A failure part way therefore leaves the earlier fields written, which is
     * why the backup is offered and why it is taken BEFORE anything is touched rather than as a
     * rollback afterwards - there is no rollback for a rewritten container.
     */
    fun writeMetadata(
        node: VNode,
        fields: List<Pair<String, String>>,
        backup: Boolean,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (backup) {
                        val copy = node.path.parent?.child(node.name + ".bak")
                        if (copy != null) {
                            graph.vfs.openRead(node.path).use { input ->
                                val out = graph.vfs.openWrite(copy)
                                try { input.copyTo(out) } finally { out.close() }
                            }
                        }
                    }
                    var written = 0
                    for ((key, value) in fields) {
                        val r = graph.metadata.put(node.path, key, value)
                        if (r is dev.niccc2007.filet.metadata.MetadataStore.Result.Ok) written++
                    }
                    written
                }
            }
            onDone()
            result
                .onSuccess {
                    dev.niccc2007.filet.log.FiletLog.i("metadata", "wrote $it field(s) to ${node.path}")
                    toast(if (it == 0) "Nothing changed" else "Saved $it field" + if (it == 1) "" else "s")
                    refreshPanes()
                }
                .onFailure {
                    dev.niccc2007.filet.log.FiletLog.e("metadata", "could not write ${node.path}", it)
                    toast("Could not write metadata: " + dev.niccc2007.filet.ops.FileOperations.readable(it))
                }
        }
    }

    fun saveText(node: VNode, text: String, onSaved: () -> Unit) {
        // A file inside an archive cannot simply be written to - the provider refuses, and it
        // is right to. This is the prompt instead: update the archive, or put the
        // edited file somewhere else.
        if (ArchiveEdits.isMember(node.path)) {
            viewModelScope.launch {
                val refusal = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ArchiveEdits.refusalFor(node.path) }.getOrElse { it.message }
                }
                val cost = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { EditCosts.describe(ArchiveEdits.costOf(node.path)) }
                        .getOrElse { "Filet could not read this archive well enough to say what a save would cost." }
                }
                _archiveSave.value = PendingArchiveSave(
                    node = node,
                    text = text,
                    archiveName = ArchiveEdits.archiveName(node.path),
                    cost = cost,
                    refusal = refusal,
                    onSaved = onSaved,
                )
            }
            return
        }
        viewModelScope.launch {
            val id = ledger.start("Saving", node.name)
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    graph.vfs.openWrite(node.path).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
            }.onSuccess {
                ledger.finish(id)
                onSaved()
                toast("Saved")
                refreshPanes()
            }.onFailure {
                val why = dev.niccc2007.filet.ops.FileOperations.readable(it)
                ledger.fail(id, why)
                toast("Could not save: $why")
            }
        }
    }

    /**
     * Write an edited image beside the original.
     *
     * Never over it. [editedName] picks a name that is not taken and is not the original's,
     * and this is the only path that writes an edit - so "the editor ate my photo" is not a
     * bug this app can have. The caller hands over encoded bytes rather than a bitmap, which
     * keeps the image format decision with the editor that knows whether alpha matters.
     *
     * @param extension what the bytes actually are, which is not always what the original
     *   was: a `.heic` edited and re-encoded comes back as JPEG and must be named as one.
     * @param onSaved the name that was actually used, so the editor can say it out loud.
     */
    fun saveEditedImage(node: VNode, bytes: ByteArray, extension: String, onSaved: (String) -> Unit) {
        viewModelScope.launch {
            val folder = node.path.parent
            if (folder == null) { toast("There is nowhere to save this."); return@launch }
            if (!graph.vfs.canWrite(folder)) { toast("This folder is read-only."); return@launch }
            val id = ledger.start("Saving", node.name)
            runCatching {
                val taken = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { graph.vfs.list(folder).map { it.name }.toHashSet() }
                        .getOrDefault(HashSet())
                }
                val name = editedName(node.name, extension) { it in taken }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    graph.vfs.openWrite(folder.child(name)).use { out -> out.write(bytes) }
                }
                name
            }.onSuccess { name ->
                ledger.finish(id)
                toast("Saved as $name")
                refreshPanes()
                onSaved(name)
            }.onFailure {
                val why = dev.niccc2007.filet.ops.FileOperations.readable(it)
                ledger.fail(id, why)
                toast("Could not save: $why")
            }
        }
    }

    /**
     * Everything sitting next to a file, for the player's queue.
     *
     * Returns the file alone if the folder cannot be listed - a track on a share that has
     * dropped out should still play from the copy already open, not refuse because its
     * neighbours are unreachable.
     */
    suspend fun siblingsOf(node: VNode): List<VNode> {
        val folder = node.path.parent ?: return listOf(node)
        return runCatching { graph.vfs.list(folder) }.getOrElse { listOf(node) }
    }

    /** The sort the user is looking at, so a queue runs in the order on screen. */
    val currentSort: dev.niccc2007.filet.data.SortSpec get() = graph.prefs.sort.value

    fun osPathOf(node: VNode): String? = graph.vfs.osPath(node.path)

    /**
     * A `Uri` the media players can seek in.
     *
     * `MediaPlayer` and `VideoView` need a seekable source and cannot take an `InputStream`,
     * so anything without a real path is copied to the cache first. That is honest but slow,
     * hence the cap: past it the viewer says so rather than silently copying 2 GB.
     */
    suspend fun localUriFor(node: VNode): android.net.Uri? {
        osPathOf(node)?.let { return graph.files.localUri(graph.files.fileAt(it)) }
        if (node.size > CACHE_COPY_LIMIT) return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val out = graph.files.playCache(node.name)
            if (!graph.files.exists(out) || graph.files.size(out) != node.size) {
                graph.vfs.openRead(node.path).use { input ->
                    graph.vfs.openWrite(graph.files.vpath(out)).use { o -> input.copyTo(o) }
                }
            }
            graph.files.localUri(out)
        }
    }

    /** Like [localUriFor] but always a FileProvider URI, because this one leaves the process. */
    private suspend fun localUriForShare(node: VNode): android.net.Uri? {
        val os = osPathOf(node) ?: localUriFor(node)?.path ?: return null
        return graph.files.shareUri(graph.files.fileAt(os))
    }

    /** Grantable URIs for a selection. The Activity turns these into a chooser. */
    suspend fun shareUrisFor(nodes: List<VNode>): List<android.net.Uri> =
        nodes.filterNot { it.isDir }.mapNotNull { localUriForShare(it) }

    // ── inbound intents (PLAN.md L2) ──

    /** Another app asked Filet to VIEW something. */
    fun openIncoming(uri: android.net.Uri, mime: String?) {
        viewModelScope.launch {
            val node = incomingNode(uri) ?: run { toast("Filet cannot open that link."); return@launch }
            if (node.isDir) focusedPane()?.navigateTo(node.path) else openWithRegistered(node)
        }
    }

    /**
     * Another app SENT files. That is a paste, not an open: the user picks the destination.
     *
     * Loading them onto the clipboard rather than dumping them somewhere is the difference
     * between a file manager and a download folder.
     */
    fun acceptIncoming(uris: List<android.net.Uri>) {
        viewModelScope.launch {
            val nodes = uris.mapNotNull { incomingNode(it) }
            if (nodes.isEmpty()) { toast("Nothing usable was shared."); return@launch }
            _state.update { st -> st.copy(clipboard = Clipboard(nodes.map { n -> n.path }, PendingOp.COPY)) }
            toast("${nodes.size} ready \u2014 open a folder and paste")
        }
    }

    private suspend fun incomingNode(uri: android.net.Uri): VNode? {
        val path = when (uri.scheme) {
            "file" -> uri.path?.let { VPath.of("local", it) }
            "content" -> {
                // A tree URI can be addressed directly; a single-document URI is copied to
                // the cache, because there is no stable VFS path for one.
                dev.niccc2007.filet.vfs.provider.SafProvider.pathOfDocumentUri(uri)
                    ?: cacheIncoming(uri)
            }
            else -> null
        } ?: return null
        return runCatching { graph.vfs.stat(path) }.getOrNull()
    }

    private suspend fun cacheIncoming(uri: android.net.Uri): VPath? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "shared"
                val out = graph.files.inboxCache(name)
                val target = graph.files.vpath(out)
                graph.app.contentResolver.openInputStream(uri)!!.use { input ->
                    graph.vfs.openWrite(target).use { o -> input.copyTo(o) }
                }
                target
            }.getOrNull()
        }

    private fun openApk(node: VNode) {
        _openRequest.value = OpenRequest(node, HandlerId.APK)
    }

    // ── installed apps ──

    /** Every app the platform will admit to. See the manifest note on package visibility. */
    suspend fun installedApps() =
        dev.niccc2007.filet.apk.InstalledApps.list(graph.app)

    /** Where extracted packages land. */
    val extractedApksFolder: dev.niccc2007.filet.vfs.VPath
        get() = dev.niccc2007.filet.vfs.VPath.of(
            "local",
            "/storage/emulated/0/" + dev.niccc2007.filet.apk.InstalledApps.FOLDER,
        )

    /**
     * Copy an installed app's APK out to [extractedApksFolder].
     *
     * A split install becomes a FOLDER of parts rather than one file. Writing only the base
     * would produce something that looks like a complete APK and fails at install time, because
     * the base deliberately lacks the density and ABI resources that live in the splits - which
     * is a worse outcome than refusing, since the failure arrives much later and elsewhere.
     */
    fun extractInstalled(app: dev.niccc2007.filet.apk.InstalledApp) {
        viewModelScope.launch {
            val root = extractedApksFolder
            val id = graph.ledger.start("Extracting ${app.label}")
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ensureFolder(root)
                    val sources = listOf(app.apkPath) + app.splitPaths
                    val into = if (app.split) {
                        val dir = root.child(
                            dev.niccc2007.filet.apk.InstalledApps.folderNameFor(app),
                        )
                        ensureFolder(dir)
                        dir
                    } else {
                        root
                    }
                    for ((n, source) in sources.withIndex()) {
                        ensureActive()
                        val name = if (app.split) source.substringAfterLast('/')
                        else dev.niccc2007.filet.apk.InstalledApps.fileNameFor(app)
                        graph.ledger.progress(id, (n + 1f) / sources.size, name)
                        // Through the VFS, not java.io: an installed APK is at a real path that
                        // the local provider addresses like any other file, and reaching past
                        // L0 here would skip the dispatch and the permission gate as well as
                        // breaking R3.
                        val from = dev.niccc2007.filet.vfs.VPath.of("local", source)
                        graph.vfs.openWrite(into.child(name)).use { out ->
                            graph.vfs.openRead(from).use { input -> input.copyTo(out, 64 * 1024) }
                        }
                    }
                    into
                }
            }.onSuccess { into ->
                graph.ledger.finish(id)
                dev.niccc2007.filet.media.MediaAnnounce.announce(graph.app, listOf(into))
                toast(
                    if (app.split) "Extracted ${app.parts} parts into ${into.name}"
                    else "Extracted ${app.label}",
                )
            }.onFailure {
                graph.ledger.fail(id, dev.niccc2007.filet.ops.FileOperations.readable(it))
                toast("Could not extract ${app.label}: " + dev.niccc2007.filet.ops.FileOperations.readable(it))
            }
        }
    }

    private suspend fun ensureFolder(path: dev.niccc2007.filet.vfs.VPath) {
        if (runCatching { graph.vfs.stat(path) }.getOrNull() == null) {
            path.parent?.let { ensureFolder(it) }
            runCatching { graph.vfs.create(path, isDir = true) }
        }
    }

    /**
     * Open the folder extracted packages land in.
     *
     * Same reason as the received folder: a tool that writes somewhere and cannot show you
     * where is only slightly better than one that does not write at all.
     */
    /** Open the folder this run's log is being written into. */
    fun openLogsFolder() {
        val pane = focusedPane() ?: return
        viewModelScope.launch {
            val folder = dev.niccc2007.filet.vfs.VPath.of(
                "local",
                "/storage/emulated/0/" + dev.niccc2007.filet.log.FiletLog.FOLDER,
            )
            if (runCatching { graph.vfs.stat(folder) }.getOrNull() == null) {
                toast("No logs have been written yet.")
                return@launch
            }
            pane.navigateTo(folder)
        }
    }

    fun openExtractedApksFolder() {
        val pane = focusedPane() ?: return
        viewModelScope.launch {
            val folder = extractedApksFolder
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ensureFolder(folder) } }
            if (runCatching { graph.vfs.stat(folder) }.getOrNull() == null) {
                toast("Nothing has been extracted yet.")
                return@launch
            }
            pane.navigateTo(folder)
        }
    }

    /**
     * Open the folder incoming files land in, creating it if this is the first time.
     *
     * The folder has always existed - `/storage/emulated/0/Filet/Received` - and there was no
     * way to reach it from the screen that fills it, which is not much better than not having
     * one. The question it answers is where the extracted files went.
     */
    fun openReceivedFolder() {
        val pane = focusedPane() ?: return
        viewModelScope.launch {
            val folder = graph.nearby.receivedFolder
            runCatching { graph.nearby.shared.ensureFolders() }
            if (runCatching { graph.vfs.stat(folder) }.getOrNull() == null) {
                toast("Nothing has been received yet.")
                return@launch
            }
            pane.navigateTo(folder)
        }
    }

    /**
     * Share the selection, asking where.
     *
     * For the context menu, which offers a single "Share" and nothing beside it - so if this
     * did not ask, network sharing would be unreachable from there entirely.
     */
    fun shareSelection() = share(focusedPane()?.selectedNodes().orEmpty())

    /**
     * Share the selection to another app, without asking.
     *
     * For the selection bar's **Send**, which sits next to its own **Nearby** button. Both
     * destinations are already one press away there, so a chooser would put the sheet two
     * presses behind a menu whose first entry is what the button already said it does.
     */
    fun shareSelectionToApps() {
        val items = focusedPane()?.selectedNodes().orEmpty().filterNot { it.isDir }
        if (items.isEmpty()) { toast("Select a file to share."); return }
        shareToApps(items)
    }

    fun openUrl(url: String) {
        onIntent?.invoke(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        )
    }

    /** Opens Trawl if it is installed, or its release page if it is not. */
    fun openTrawl() {
        val pm = graph.app.packageManager
        val launch = TRAWL_PACKAGES.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        if (launch != null) onIntent?.invoke(launch)
        else openUrl("https://github.com/uukjtisa/Trawl")
    }

    // -- updates (github flavour only) --

    private val _update = MutableStateFlow<UpdateState?>(null)

    /** Non-null while the update sheet should be on screen. */
    val update: StateFlow<UpdateState?> = _update.asStateFlow()

    private var downloadJob: Job? = null

    fun dismissUpdate() {
        downloadJob?.cancel()
        downloadJob = null
        _update.value = null
    }

    /**
     * Look for a newer build.
     *
     * Shows the sheet either way. "You are up to date" is information the user asked for by
     * pressing the button, and answering a deliberate press with silence reads as a dead
     * control.
     */
    fun checkForUpdates() {
        if (!dev.niccc2007.filet.BuildConfig.UPDATER_ENABLED) {
            toast("This build gets updates from where you installed it.")
            return
        }
        if (_update.value is UpdateState.Checking) return
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            val result = Updater.latest()
            val release = result.getOrNull()
            _update.value = when {
                result.isFailure -> UpdateState.Failed(
                    "Could not reach GitHub. Check the connection and try again."
                )
                // Reachable, and nothing published. Saying so beats blaming the network for
                // a repository that simply has no releases yet.
                release == null -> UpdateState.NoReleases
                release.version > Updater.installed -> UpdateState.Available(release)
                else -> UpdateState.UpToDate(release)
            }
        }
    }

    /**
     * The check that nobody asked for, on app start.
     *
     * Everything about whether to run it and whether to say anything is in `UpdatePrompt.kt`;
     * this is the wiring. Failures are silent by design - a background check that could not
     * reach GitHub is not news, and a toast about it would be the app interrupting to report
     * that nothing happened.
     */
    fun checkForUpdatesQuietly() {
        if (!dev.niccc2007.filet.BuildConfig.UPDATER_ENABLED) return
        val now = System.currentTimeMillis()
        if (!shouldCheck(reminderState(), now)) return
        viewModelScope.launch {
            val release = Updater.latest().getOrNull()
            // Recorded whether or not anything was found, so an unreachable GitHub does not
            // mean a check on every single launch.
            storeReminder(reminderState().copy(lastCheckedAt = System.currentTimeMillis()))
            if (release == null) return@launch
            if (!shouldPrompt(release.version, Updater.installed, reminderState(), now, LAUNCH_ID)) return@launch
            pendingRelease = release
            UpdateNotifier.notify(graph.app, release)
        }
    }

    /**
     * Open the sheet for the release a notification was about.
     *
     * Falls back to a fresh check rather than doing nothing: the notification may have outlived
     * the process that posted it, and a tap that opens the app and then sits there is the same
     * as a broken notification.
     */
    fun openUpdateFromNotification(tag: String?) {
        UpdateNotifier.clear(graph.app)
        val held = pendingRelease
        if (held != null && (tag == null || held.tag == tag)) {
            _update.value = UpdateState.Available(held)
        } else {
            checkForUpdates()
        }
    }

    /** The user answered the "when should I ask again" row. */
    fun answerReminder(choice: RemindChoice, release: Release) {
        storeReminder(
            applyChoice(choice, release.version.toString(), System.currentTimeMillis(), reminderState(), LAUNCH_ID)
        )
        UpdateNotifier.clear(graph.app)
        dismissUpdate()
        toast(
            when (choice) {
                RemindChoice.NEVER ->
                    "No more update notifications. Check for updates still works, and About can turn them back on."
                RemindChoice.SKIP_VERSION -> "Skipping ${release.version}. A newer one will still say."
                RemindChoice.LATER -> "Back next time you open Filet."
                else -> "Asking again ${choice.label().lowercase()}."
            }
        )
    }

    /** The Settings switch. Turning it back on forgets whatever silenced it. */
    fun setUpdateNotifications(on: Boolean) {
        val state = reminderState()
        storeReminder(if (on) reenable(state) else state.copy(notificationsOff = true))
        if (!on) UpdateNotifier.clear(graph.app)
    }

    val updateNotificationsOn: StateFlow<Boolean> = graph.prefs.updateNotificationsOff
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, !graph.prefs.updateNotificationsOff.value)

    /** The release a notification is currently about, if this process posted it. */
    private var pendingRelease: Release? = null

    /**
     * This run of the app, as a number.
     *
     * Only ever compared for equality, so any value unique to the process will do; the start
     * time is one that is already lying around. It is what makes "Later" mean "until Filet is
     * opened again" rather than "for zero milliseconds".
     */
    private val LAUNCH_ID: Long = android.os.Process.getStartElapsedRealtime().takeIf { it > 0 }
        ?: System.nanoTime()

    private fun reminderState() = ReminderState(
        silencedUntil = graph.prefs.updateSilencedUntil.value,
        silencedLaunch = silencedLaunchThisRun,
        silencedVersion = graph.prefs.updateSilencedVersion.value,
        skippedVersion = graph.prefs.updateSkippedVersion.value,
        notificationsOff = graph.prefs.updateNotificationsOff.value,
        lastCheckedAt = graph.prefs.updateLastCheckedAt.value,
    )

    /**
     * "Later", said during this run.
     *
     * Held in memory on purpose rather than in prefs: "remind me after opening" has to stop
     * meaning anything once the app is opened again, and the only honest way to store
     * "until the process dies" is in the process.
     */
    private var silencedLaunchThisRun = 0L

    private fun storeReminder(state: ReminderState) {
        silencedLaunchThisRun = state.silencedLaunch
        graph.prefs.setUpdateReminder(
            silencedUntil = state.silencedUntil,
            silencedVersion = state.silencedVersion,
            skippedVersion = state.skippedVersion,
            notificationsOff = state.notificationsOff,
            lastCheckedAt = state.lastCheckedAt,
        )
    }

    fun downloadUpdate(release: Release) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            Updater.download(graph.app, release).collect { step ->
                _update.value = when (step) {
                    is Download.Progress -> UpdateState.Downloading(release, step.percent, step.bytes, step.total)
                    is Download.Finished -> UpdateState.Ready(release, step.bytes)
                    is Download.Failed -> UpdateState.Failed(step.reason)
                }
            }
        }
    }

    /**
     * Hand the APK to the system installer.
     *
     * The sheet stays up rather than closing. The installer is a separate activity the user
     * can cancel, and coming back to a screen that has forgotten what it was doing is worse
     * than coming back to one still offering Install.
     */
    fun installUpdate() {
        val intent = Updater.install(graph.app)
        if (intent == null) { toast("Could not hand the update to the installer."); return }
        val cb = onIntent
        if (cb == null) { toast("Could not open the installer."); return }
        runCatching { cb(intent) }.onFailure { toast("The installer refused it: ${it.message}") }
    }

    fun openReleasePage(url: String) {
        if (url.isEmpty()) return
        runCatching {
            onIntent?.invoke(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            )
        }
    }

    fun copyPathToClipboard(path: VPath?) {
        if (path == null) return
        val cm = graph.app.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("path", path.path))
        toast("Path copied")
    }

    // ── index ──

    val indexStatus get() = graph.index.status
    val home get() = graph.home

    /**
     * When each tracked file was first noticed in a tracked folder.
     *
     * Beside the feed rather than inside it, because the feed writes these and the history
     * screen reads them; owning it on one side would mean reaching through that side to get at
     * it from the other.
     */
    val firstSeen = dev.niccc2007.filet.home.FirstSeenStore(prefs)
    val tracked get() = graph.tracked

    fun onIndexToggled(enabled: Boolean) {
        val roots = _state.value.volumes.map { it.node.path }
        graph.indexCoordinator.setEnabled(enabled, roots)
        (graph.index as? dev.niccc2007.filet.index.SqliteIndex)?.setEnabled(enabled)
        toast(if (enabled) "Index on" else "Index off — search still works, just slower")
    }

    /** The run the user started, for the Stop button and the reason it ended. */
    val indexRun get() = graph.indexCoordinator.run

    /**
     * Index everything and keep going.
     *
     * Unbounded on purpose. The old two-minute budget did not pause-and-resume - a truncated
     * crawl restarts at the roots - so on a full phone it re-indexed the same first slice on
     * every press and the rest was never reached.
     */
    fun reindexNow() {
        val roots = _state.value.volumes.map { it.node.path }
        if (roots.isEmpty()) { toast("No volume to index."); return }
        if (graph.indexCoordinator.isCrawling()) { toast("Already indexing."); return }
        graph.indexCoordinator.crawlFully(roots)
        openActivity(true)
    }

    fun stopIndexing() {
        graph.indexCoordinator.stopCrawl()
        toast("Indexing stopped")
    }

    fun clearIndex() {
        viewModelScope.launch {
            // Stop first. clear() takes the same lock the crawl holds, so clearing during a
            // full crawl would block until the crawl finished - which looks exactly like the
            // button doing nothing.
            graph.indexCoordinator.stopCrawl()
            graph.index.clear()
            toast("Index cleared")
        }
    }

    // ── scripts (PLAN.md L4) ──

    val scripts get() = graph.scripts

    private val _scriptRunning = MutableStateFlow<String?>(null)
    val scriptRunning: StateFlow<String?> = _scriptRunning.asStateFlow()

    private val _scriptResult = MutableStateFlow<dev.niccc2007.filet.script.ScriptResult?>(null)
    val scriptResult: StateFlow<dev.niccc2007.filet.script.ScriptResult?> = _scriptResult.asStateFlow()

    /**
     * Runs a script the user has already consented to.
     *
     * The consent itself lives in the UI, because it must be an explicit, unambiguous act -
     * an intent may OFFER a script, only a person may run one (PLAN.md L4).
     */
    fun runScript(script: dev.niccc2007.filet.script.Script) {
        if (_scriptRunning.value != null) return
        viewModelScope.launch {
            _scriptRunning.value = script.id
            val jobId = ledger.start("Running script", script.name)
            val result = graph.scriptEngine.run(script.source, script.permissions)
            _scriptResult.value = result
            _scriptRunning.value = null
            if (result.ok) ledger.finish(jobId, "${result.elapsedMs} ms")
            else ledger.fail(jobId, result.error ?: "failed")
            toast(if (result.ok) "Script finished" else "Script failed")
            refreshPanes()
        }
    }

    /**
     * Create a script and open it in the editor.
     *
     * Seeded with a working header rather than an empty file: the permission block is the one
     * part of a Filet script that is not guessable, and a blank editor is where a new script
     * goes to die.
     */
    fun newScript() {
        val stub = """
            -- @name    My script
            -- @read    local:///storage/emulated/0/Download
            -- @write   local:///storage/emulated/0/Download
            -- @network false
            -- @timeout 30
            --
            -- Delete what you do not need. Every path this script may touch has to be
            -- listed above, and you approve that list before it runs.

            for _, item in ipairs(fs.list("local:///storage/emulated/0/Download")) do
              print(item.name)
            end
        """.trimIndent()
        val script = graph.scripts.save(null, stub)
        editScript(script)
        toast("New script created")
    }

    /** Put the bundled examples back, for anyone who deleted them and wants a reference. */
    fun restoreScriptExamples() {
        graph.scripts.restoreExamples()
        toast("Examples restored")
    }

    /** Opens the script in the code editor, as a real file in Filet's own storage. */
    fun editScript(script: dev.niccc2007.filet.script.Script) {
        val path = graph.files.vpath(graph.files.scriptFile(script.id))
        viewModelScope.launch {
            val node = runCatching { graph.vfs.stat(path) }.getOrNull()
            if (node == null) toast("That script file has gone missing.")
            else _openRequest.value = OpenRequest(node, HandlerId.TEXT)
        }
    }

    fun deleteScript(script: dev.niccc2007.filet.script.Script) {
        graph.scripts.delete(script.id)
        toast("Script deleted")
    }

    /** Re-reads scripts from disk after the editor saved one. */
    fun reloadScripts() = graph.scripts.reload()

    // ── APK work (PLAN.md M6) ──

    private val _apkWork = MutableStateFlow<String?>(null)
    val apkWork: StateFlow<String?> = _apkWork.asStateFlow()

    private val _manifestDraft = MutableStateFlow<dev.niccc2007.filet.apk.ManifestDraft?>(null)
    val manifestDraft: StateFlow<dev.niccc2007.filet.apk.ManifestDraft?> = _manifestDraft.asStateFlow()

    private var manifestBlock: com.reandroid.arsc.chunk.xml.AndroidManifestBlock? = null

    suspend fun inspectApk(node: VNode) = graph.apkTools.inspect(node.path)

    /** Walk into the APK as a folder - the same navigation as any other container. */
    fun browseApk(node: VNode) {
        closeHandler()
        val os = graph.vfs.osPath(node.path)
        if (os == null) { toast("Browse APKs from local storage."); return }
        focusedPane()?.navigateTo(dev.niccc2007.filet.vfs.provider.ApkProvider.mount(os))
    }

    /** Open one dex as a tree of smali. The provider does the disassembly on demand. */
    fun browseDex(node: VNode, dexEntry: String) {
        closeHandler()
        val os = graph.vfs.osPath(node.path)
        if (os == null) { toast("Browse APKs from local storage."); return }
        val root = dev.niccc2007.filet.vfs.provider.ApkProvider.mount(os)
        focusedPane()?.navigateTo(root.child(dexEntry))
    }

    /** The folder a decompiled APK lives in. App-private, so a rebuild never touches the original. */
    private fun workDirFor(node: VNode) = graph.files.apkWorkDir(node.name)

    /**
     * Why an APK action cannot run right now, or null when it can.
     *
     * PLAN.md R1 the other way round: a button that throws a toast the moment you press it
     * was never a button, it was a trap. Each of these mirrors an early return in the action
     * it names, so the two cannot drift into disagreeing.
     */
    fun apkBlockReason(node: VNode, action: ApkAction): String? {
        if (_apkWork.value != null) return "Busy — " + _apkWork.value
        if (graph.vfs.osPath(node.path) == null) {
            return "Only APKs on local storage, not inside an archive or on a remote"
        }
        return when (action) {
            ApkAction.DECOMPILE, ApkAction.SIGN, ApkAction.INSTALL, ApkAction.MANIFEST -> null
            ApkAction.REBUILD ->
                if (workDirFor(node).isDirectory) null else "Decompile it first — there is nothing to rebuild from"
        }
    }

    fun decompileApk(node: VNode) {
        if (_apkWork.value != null) return
        viewModelScope.launch {
            val jobId = ledger.start("Decompiling", node.name)
            _apkWork.value = "decompiling…"
            runCatching {
                graph.apkTools.decompile(node.path, workDirFor(node)) { step ->
                    _apkWork.value = "decompiling $step"
                    ledger.progress(jobId, null, step)
                }
            }.onSuccess { dir ->
                val where = graph.files.vpath(dir)
                ledger.finish(jobId, where.path)
                _apkWork.value = null
                toast("Decompiled to " + where.name)
                // Open the working folder, because the next thing anyone does is edit it.
                focusedPane()?.navigateTo(where)
            }.onFailure {
                ledger.fail(jobId, it.message ?: "failed")
                _apkWork.value = null
                toast("Decompile failed: ${it.message}")
            }
        }
    }

    fun rebuildApk(node: VNode) {
        if (_apkWork.value != null) return
        val work = workDirFor(node)
        if (!work.isDirectory) { toast("Decompile it first."); return }
        viewModelScope.launch {
            val jobId = ledger.start("Rebuilding", node.name)
            _apkWork.value = "rebuilding…"
            runCatching {
                graph.apkTools.rebuildAndSign(
                    original = node.path,
                    workDir = work,
                    keys = graph.signingKeys,
                    key = resolveSigningKey(),
                    // minSdk is read from the APK being rebuilt. A guess here decides the
                    // dex format and the signature schemes, and gets it wrong for every app
                    // that is not the one it was guessed for.
                ) { step ->
                    _apkWork.value = step
                    ledger.progress(jobId, null, step)
                }
            }.onSuccess { out ->
                ledger.finish(jobId, out.name)
                _apkWork.value = null
                toast("Rebuilt and signed: ${out.name}")
                refreshPanes()
            }.onFailure {
                ledger.fail(jobId, it.message ?: "failed")
                _apkWork.value = null
                toast("Rebuild failed: ${it.message}")
            }
        }
    }

    fun signApk(node: VNode) {
        if (_apkWork.value != null) return
        viewModelScope.launch {
            val jobId = ledger.start("Signing", node.name)
            _apkWork.value = "signing…"
            runCatching {
                graph.apkTools.signOnly(node.path, graph.signingKeys, resolveSigningKey())
            }.onSuccess { out ->
                ledger.finish(jobId, out.name)
                _apkWork.value = null
                toast("Signed: ${out.name}")
                refreshPanes()
            }.onFailure {
                ledger.fail(jobId, it.message ?: "failed")
                _apkWork.value = null
                toast("Signing failed: ${it.message}")
            }
        }
    }

    /**
     * The key the Sign buttons use.
     *
     * Generated on first use rather than demanded up front: asking someone to produce a
     * keystore before they can press Sign is how an on-device toolchain stops being used.
     */
    private fun resolveSigningKey(): dev.niccc2007.filet.apk.SigningKey {
        val keys = graph.signingKeys
        keys.defaultAlias?.let { alias -> keys.loadFromAndroidKeystore(alias)?.let { return it } }
        val alias = keys.newAlias()
        val key = keys.generate(alias, "Filet on-device key")
        keys.defaultAlias = alias
        return key
    }

    fun installApk(node: VNode) {
        viewModelScope.launch {
            // A split bundle cannot go to the system installer as a file - it is a base plus
            // its per-architecture, per-density and per-language pieces, and they have to be
            // committed together in one session.
            if (dev.niccc2007.filet.apk.SplitPackage.isSplitBundle(node.name)) {
                installSplitBundle(node)
                return@launch
            }
            val os = graph.vfs.osPath(node.path)
            if (os == null) { toast("Install from local storage."); return@launch }
            val uri = graph.files.shareUri(graph.files.fileAt(os))
            if (uri == null) { toast("Could not hand this APK to the installer."); return@launch }
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            onIntent?.invoke(i)
        }
    }

    /**
     * Install an `.xapk`, `.apkm` or `.apks`.
     *
     * The bundle is mounted as the archive it is, the pieces this device needs are chosen by
     * [dev.niccc2007.filet.apk.SplitPackage], and each one is streamed straight from inside the
     * archive into the install session. Nothing is unpacked to disk: a large bundle would
     * otherwise need twice its own size free, on the device least likely to have it.
     */
    private suspend fun installSplitBundle(node: VNode) {
        val mount = runCatching {
            // .path, not .toString(): toString() carries the scheme, and mounting
            // "local:///storage/..." builds a container path that cannot be read.
            dev.niccc2007.filet.vfs.provider.ArchiveProvider.mount(node.path.path)
        }.getOrNull()
        if (mount == null) { toast("This bundle could not be opened."); return }

        val members = runCatching { graph.vfs.list(mount) }.getOrElse {
            toast("This bundle could not be read: ${it.message ?: "unreadable"}"); return
        }
        val byName = members.associateBy { it.name }
        val dpi = graph.app.resources.displayMetrics.densityDpi
        val chosen = dev.niccc2007.filet.apk.SplitPackage.pick(
            entries = members.map { it.name },
            abis = dev.niccc2007.filet.apk.SplitInstall.deviceAbis(),
            density = dev.niccc2007.filet.apk.SplitInstall.deviceDensity(dpi),
            languages = listOf(java.util.Locale.getDefault().language),
        )
        if (chosen.isEmpty()) { toast("There are no APKs inside this bundle."); return }

        val extras = dev.niccc2007.filet.apk.SplitPackage.extras(members.map { it.name })
        toast("Installing ${chosen.size} piece(s) from ${node.name}…")

        // The whole install, not just the opens: each piece is streamed into the session from
        // this thread, and a bundle can be sitting on a mounted share.
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            dev.niccc2007.filet.apk.SplitInstall.install(
                context = graph.app,
                label = node.name.substringBeforeLast('.'),
                entries = chosen,
            ) { entry ->
                val member = byName[entry] ?: return@install null
                runCatching { graph.vfs.openRead(member.path) to member.size }.getOrNull()
            }
        }

        when (result) {
            is dev.niccc2007.filet.apk.SplitInstall.Result.Handed ->
                // Said out loud because an OBB is not part of the install and the game will
                // ask for its data on first run with no explanation of where it went.
                if (extras.any { it.endsWith(".obb", ignoreCase = true) }) {
                    toast("Confirm the install. This bundle also carries an OBB data file that has to be placed by hand.")
                }
            is dev.niccc2007.filet.apk.SplitInstall.Result.Failed -> toast(result.why)
        }
    }

    fun editManifest(node: VNode) {
        _openRequest.value = OpenRequest(node, HandlerId.MANIFEST)
    }

    fun loadManifest(node: VNode) {
        viewModelScope.launch {
            runCatching { graph.apkTools.readManifest(node.path) }
                .onSuccess { block ->
                    manifestBlock = block
                    _manifestDraft.value = snapshot(block)
                }
                .onFailure { toast("Could not read the manifest: ${it.message}") }
        }
    }

    fun editManifestField(edit: (com.reandroid.arsc.chunk.xml.AndroidManifestBlock) -> Unit) {
        val block = manifestBlock ?: return
        runCatching { edit(block) }.onFailure { toast("That change was refused: ${it.message}") }
        _manifestDraft.value = snapshot(block)
    }

    fun applyManifest(node: VNode) {
        val block = manifestBlock ?: return
        viewModelScope.launch {
            val work = workDirFor(node)
            if (!work.isDirectory) {
                toast("Decompile the APK first \u2014 the edit needs somewhere to live.")
                return@launch
            }
            runCatching { graph.apkTools.writeManifest(work, block) }
                .onSuccess { toast("Manifest written \u2014 now Rebuild & sign") }
                .onFailure { toast("Could not write the manifest: ${it.message}") }
        }
    }

    private fun snapshot(b: com.reandroid.arsc.chunk.xml.AndroidManifestBlock) =
        dev.niccc2007.filet.apk.ManifestDraft(
            packageName = b.packageName ?: "",
            versionName = b.versionName ?: "",
            versionCode = b.versionCode ?: 0,
            minSdk = b.minSdkVersion ?: 0,
            targetSdk = b.targetSdkVersion ?: 0,
            debuggable = b.isDebuggable,
            permissions = b.usesPermissions.orEmpty().sorted(),
        )

    // ── provenance (PLAN.md §5.1) ──

    val bridge get() = graph.bridge

    /**
     * Where this file came from, asking the index first and the companion app second.
     *
     * Index first because it is local and instant; the bridge second because the other app
     * may know about a file that arrived before Filet ever crawled it.
     */
    suspend fun provenanceOf(path: VPath): dev.niccc2007.filet.index.Provenance? =
        graph.index.provenanceOf(path) ?: graph.bridge.provenanceOf(path.toString())

    /** Search by where a file came from, which is the thing nothing else on Android does. */
    fun searchByOrigin(origin: String) {
        val pane = focusedPane() ?: return
        pane.openSearch(true)
        pane.setScope(dev.niccc2007.filet.index.SearchScope.PROVENANCE)
        pane.setQuery("from:$origin")
    }

    fun requestRedownload(origin: String, format: String?, name: String?) {
        val intent = graph.bridge.requestRedownload(origin, format, name)
        if (intent == null) toast("Trawl is not installed, or is signed with a different key.")
        else onIntent?.invoke(intent)
    }

    fun refreshBridgeJobs() {
        viewModelScope.launch { runCatching { graph.bridge.pullJobs() } }
    }

    // ── nearby (PLAN.md M9) ──

    val nearby get() = graph.nearby
    val connections get() = graph.connections

    /** A peer opens in a pane, exactly like a volume. That is the whole feature. */
    fun openPeer(peer: dev.niccc2007.filet.nearby.Peer) {
        val pane = focusedPane() ?: return
        pane.navigateTo(dev.niccc2007.filet.vfs.provider.net.PeerProvider.mount(peer.uuid))
    }

    /**
     * Open a saved network place in the focused pane.
     *
     * This did not exist. The Remotes row had an Edit button and nothing else, so a place could
     * be added and then never reached - which reads as the connection having silently failed.
     *
     * The path shape is the one every network provider shares: `scheme:///<connectionId>/<path>`,
     * so the provider looks the connection up by id and a VPath stays a plain string.
     */
    fun openConnection(c: dev.niccc2007.filet.vfs.provider.net.NetConnection) {
        val pane = focusedPane() ?: return
        // **The share is part of the path.** Every network provider addresses as
        // `scheme:///<id>/<share>/<rest>`, and the roots listing builds exactly that. Leaving the
        // share out navigated to `scheme:///<id>`, so the client asked the server for `/` - which
        // a hosting phone answers 400, because nothing lives above `/a/<code>`. It presented as
        // an empty folder rather than as an error, which is why it looked like a listing problem.
        val parts = listOf(c.share, c.startPath)
            .map { it.trim('/') }
            .filter { it.isNotEmpty() }
        val path = dev.niccc2007.filet.vfs.VPath.of(
            c.protocol.scheme,
            "/" + c.id + if (parts.isEmpty()) "" else "/" + parts.joinToString("/"),
        )
        pane.navigateTo(path)
        toast("Opening " + c.label.ifEmpty { c.host })
    }

    /**
     * The name of a saved network place, for a path that carries its id.
     *
     * Network paths are `scheme:///<connectionId>/<remote>`, so the head of every breadcrumb was
     * a generated id nobody has seen. Null for anything that is not a known connection, and the
     * caller then draws the raw segment - a path that still works, just less friendly.
     */
    /** The icon inside an APK file, for the inspector's identity block. */
    suspend fun apkIcon(node: VNode): android.graphics.Bitmap? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val os = graph.vfs.osPath(node.path) ?: return@withContext null
            dev.niccc2007.filet.media.Thumbnails.apkIcon(graph.app, os, 128)
        }

    /** What is already installed under [packageName]: its version code and signing digest. */
    suspend fun installedFacts(packageName: String) = graph.apkTools.installedFacts(packageName)

    /** How many of [permissions] Android classes as dangerous. */
    suspend fun dangerousPermissionCount(permissions: List<String>) =
        graph.apkTools.dangerousCount(permissions)

    /** Which permissions Android classes as dangerous, so the inspector can lead with them. */
    suspend fun dangerousPermissions(permissions: List<String>) =
        graph.apkTools.dangerous(permissions)

    fun connectionName(scheme: String, id: String): String? {
        val c = graph.connections.byId(id) ?: return null
        if (c.protocol.scheme != scheme) return null
        return c.label.ifBlank { c.host }
    }

    /** Offer the selection to peers and browsers, in place - nothing is copied. */
    fun shareSelectionNearby() {
        val items = focusedPane()?.selectedNodes().orEmpty()
        if (items.isEmpty()) { toast("Select something first."); return }
        items.forEach { graph.nearby.shared.share(it) }
        if (!graph.nearby.isRunning()) graph.nearby.start()
        focusedPane()?.clearSelection()
        toast("${items.size} shared \u2014 open Nearby for the link")
    }

    /**
     * Put the selection's paths on the clipboard as text.
     *
     * The OS spelling where there is one - `/storage/emulated/0/Download/a.txt` - because that
     * is what you paste into a terminal or a script. A backend with no OS path falls back to
     * the VFS spelling, which at least round-trips inside Filet rather than being a lie that
     * looks like a path.
     */
    fun copyPathsOfSelection() = withSelection { items ->
        val text = items.joinToString("\n") { graph.vfs.osPath(it.path) ?: it.path.toString() }
        copyText(text, if (items.size == 1) "Path copied" else "${items.size} paths copied")
    }

    fun copyText(text: String, message: String) {
        val cm = graph.app.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("filet", text))
        toast(message)
    }

    // ── remotes (PLAN.md M8) ──

    private val _remotesRevision = MutableStateFlow(0)
    val remotesRevision: StateFlow<Int> = _remotesRevision.asStateFlow()

    /**
     * Put the left pane on the right and the right pane on the left.
     *
     * Only the two ACTIVE indices move, not the tab list: swapping the tabs themselves would
     * reorder the strip, which is a different thing the user did not ask for and would have to
     * undo by dragging.
     *
     * Focus follows the pane, not the side. Somebody who swaps because they want to act on the
     * other tree has already chosen which tree they mean, and moving the focus to whatever
     * happens to be on the left afterwards throws that away.
     */
    fun swapPanes() {
        if (_state.value.split == SplitMode.OFF) return
        _state.update {
            it.copy(
                activeA = it.activeB,
                activeB = it.activeA,
                focused = if (it.focused == Side.A) Side.B else Side.A,
            )
        }
        // The divider sits at a fraction of the body, so a split that is not 50/50 would
        // otherwise leave the pane that just moved with the other one's share of the screen.
        prefs.setSplitRatio(1f - prefs.splitRatio.value)
        toast("Panes swapped")
    }

    // ── what Home opens ──

    /** Shown once ever, after the first time a folder becomes Home. */
    private val _homeHint = MutableStateFlow(false)
    val homeHint: StateFlow<Boolean> = _homeHint.asStateFlow()

    fun setHomeFolder(path: VPath?) {
        prefs.setHomeFolder(path?.toString())
        if (HomeTarget.shouldRemind(prefs.homeHintShown.value, settingAFolder = path != null)) {
            _homeHint.value = true
            prefs.markHomeHintShown()
        } else {
            toast(if (path == null) "Home is the overview again" else "Home is now this folder")
        }
        // Every open Home pane, not just the visible one: a tab left on Home in the other half
        // of a split would otherwise keep drawing the old target until it was navigated.
        _state.update { it.copy(revision = it.revision + 1) }
    }

    fun dismissHomeHint() { _homeHint.value = false }

    /**
     * What a Home pane should draw right now.
     *
     * Existence is checked here rather than stored, because the folder can be deleted or an SD
     * card removed between one launch and the next, and Home is the screen that opens first.
     */
    fun homeResolution(): HomeTarget.Resolution {
        val stored = prefs.homeFolder.value?.let { runCatching { VPath.parse(it) }.getOrNull() }
        return HomeTarget.resolve(stored) { p ->
            runCatching { kotlinx.coroutines.runBlocking { graph.vfs.stat(p) } != null }
                .getOrDefault(false)
        }
    }

    // ── hosting this phone over WebDAV ──

    val davState: StateFlow<dev.niccc2007.filet.webdav.DavState> get() = graph.davState

    /**
     * Start one share, and keep an eye on the idle clocks while anything runs.
     *
     * The idle check is a slow poll rather than a scheduled stop, because a window can be changed
     * while a share is running and a timer set at start would carry the old value. One poll covers
     * every share; each has its own window and closes itself on its own terms.
     */
    fun startHosting(id: Long) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                graph.webdav.start(id)
            }
            val up = graph.davState.value.shares.firstOrNull { it.id == id }?.running == true
            if (up) {
                // The same foreground service the share uses. Without it the socket lives only
                // as long as the process does, and a drive letter that vanishes on switching
                // apps is worse than no drive letter.
                dev.niccc2007.filet.nearby.NearbyService.start(graph.app)
                toast("Sharing on the network")
            } else {
                toast("Could not start this share")
            }
        }
        watchHostIdle()
    }

    private var hostIdleWatch: kotlinx.coroutines.Job? = null

    private fun watchHostIdle() {
        if (hostIdleWatch?.isActive == true) return
        hostIdleWatch = viewModelScope.launch {
            while (graph.davState.value.running) {
                kotlinx.coroutines.delay(30_000)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    graph.webdav.stopIfIdle()
                }
            }
        }
    }

    /** Stop one share. The others, and the socket, stay up if any remain. */
    fun stopHosting(id: Long) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                graph.webdav.stop(id)
            }
            // Nudged rather than stopped: another share may still be running behind it, and this
            // lets the service work out for itself whether it still has a reason to exist.
            dev.niccc2007.filet.nearby.NearbyService.start(graph.app)
            toast("Stopped sharing")
        }
    }

    /** Stop everything. What the card's top-level stop means. */
    fun stopAllHosting() {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.webdav.stop() }
            dev.niccc2007.filet.nearby.NearbyService.start(graph.app)
            toast("Stopped hosting")
        }
    }

    /** Bring up every share that asked to open with Filet. Called once, at startup. */
    fun startAutoHosting() {
        if (dev.niccc2007.filet.webdav.DavShares.autoStarting(graph.webdav.shares).isEmpty()) return
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                graph.webdav.startAutoShares()
            }
            if (graph.davState.value.running) {
                dev.niccc2007.filet.nearby.NearbyService.start(graph.app)
                watchHostIdle()
            }
        }
    }

    // ---- editing the share list ------------------------------------------------------------

    private fun publishDav() {
        graph.davState.value = graph.webdav.state()
    }

    /**
     * Store an edited or new share.
     *
     * A running share keeps running on the settings it started with: moving a root under a drive
     * Explorer has already mounted leaves it holding paths that no longer resolve, which looks to
     * the user like every file vanished. The card says so rather than the change being silent.
     */
    fun saveShare(share: dev.niccc2007.filet.webdav.DavShare) {
        val shares = graph.webdav.shares
        val clean = share.copy(
            label = share.label.trim().ifBlank { "Share" },
            code = share.code?.trim()?.takeIf { it.isNotEmpty() },
        )
        val before = shares.firstOrNull { it.id == clean.id }
        graph.webdav.shares = dev.niccc2007.filet.webdav.DavShares.upsert(shares, clean)
        val live = graph.davState.value.shares.firstOrNull { it.id == clean.id }?.running == true
        if (live) {
            // Policy reaches the running share at once. Only the endpoint waits, and only then is
            // there anything to warn about - a blanket warning is what made "allow changes" look
            // like it had been saved and ignored.
            graph.webdav.applyLive(clean)
            if (before != null && dev.niccc2007.filet.webdav.DavShares.needsRestart(before, clean)) {
                toast("The address changed - restart this share to use it")
            }
        }
        publishDav()
    }

    /** A new share, named and coded so it cannot collide with one already there. */
    fun newShare(): dev.niccc2007.filet.webdav.DavShare {
        val shares = graph.webdav.shares
        return dev.niccc2007.filet.webdav.DavShare(
            id = dev.niccc2007.filet.webdav.DavShares.newId(shares),
            label = dev.niccc2007.filet.webdav.DavShares.freeLabel(shares, "New share"),
        )
    }

    fun deleteShare(id: Long) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                graph.webdav.stop(id)
            }
            val before = graph.webdav.shares
            graph.webdav.shares = dev.niccc2007.filet.webdav.DavShares.remove(before, id)
            publishDav()
            if (graph.webdav.shares.size == before.size) toast("Keeping the last share")
            else toast("Share removed")
        }
    }

    /**
     * Let the desktop change files, for this session only.
     *
     * Applies to a running share immediately - unlike the settings above, because this one is not
     * baked into the endpoint and revoking it has to be instant to be worth having.
     */
    fun setShareWritable(id: Long, on: Boolean) {
        graph.webdav.setWritable(id, on)
        val shares = graph.webdav.shares
        graph.webdav.shares = dev.niccc2007.filet.webdav.DavShares.upsert(
            shares,
            shares.first { it.id == id }.copy(writable = on),
        )
        publishDav()
    }

    fun kickHostClient(address: String) {
        graph.webdav.kick(address)
    }

    fun setHostPort(port: Int) {
        graph.webdav.preferredPort = port.coerceIn(1024, 65535)
        publishDav()
        if (graph.davState.value.running) toast("Takes effect the next time you start hosting")
    }

    /** Pick the folder for one share, starting from wherever the pane is. */
    fun pickShareFolder(id: Long) {
        val start = focusedPane()?.state?.value?.cwd
            ?: graph.app.let { VPath.of("local", "/storage/emulated/0") }
        pickFolder("Share this folder", "Share this one", start) { dest ->
            val sh = graph.webdav.shares.firstOrNull { it.id == id } ?: return@pickFolder
            saveShare(sh.copy(customRoot = dest.toString()))
        }
    }


    /**
     * The addresses a computer can reach this phone on, reused from the Nearby server.
     *
     * One walk of the interfaces, not two: they answer the same question and having each keep
     * its own copy is how the two screens came to disagree about which network the phone is on.
     */
    fun hostAddresses(): List<dev.niccc2007.filet.nearby.NetAddress> =
        dev.niccc2007.filet.nearby.NetAddresses.all()

    /**
     * Re-read the saved connections and the root grant on the next composition.
     *
     * Its own counter rather than the app revision because the Remotes pane keys its reads on
     * this one; bumping the app revision instead recomposes the pane without invalidating
     * either `remember`, which is exactly how the refresh there came to do nothing.
     */
    fun bumpRemotes() {
        _remotesRevision.value = _remotesRevision.value + 1
    }

    /** Phones on this network that are hosting right now. */
    val davBeacon get() = graph.davBeacon

    /**
     * Turn a discovered host into a connection, filled in from what it announced.
     *
     * Everything except the code, which is not broadcast - so this hands back a connection that
     * is complete apart from the one field only the person can supply, and the add form opens on
     * it rather than on an empty one.
     */
    fun connectionFromHost(h: dev.niccc2007.filet.webdav.DavBeacon.Host):
        dev.niccc2007.filet.vfs.provider.net.NetConnection =
        dev.niccc2007.filet.vfs.provider.net.NetConnection(
            id = graph.connections.newId(),
            protocol = dev.niccc2007.filet.vfs.provider.net.NetProtocol.WEBDAV,
            label = h.name,
            host = h.host,
            port = h.port,
            user = "",
            password = "",
            // The advertised prefix ONLY, with no code - the code is not broadcast. The form
            // turns what gets typed into `a/<code>`; leaving the bare prefix here put the letter
            // "a" into a box labelled Access code, which is neither the code nor something anybody
            // could correct without knowing the URL shape.
            share = h.basePath.trim('/'),
            anonymous = false,
            // Carried so this entry can be recognised again when the device turns up on a
            // different network. Without it, identity is the address - which is the one thing
            // about a host guaranteed to change.
            deviceId = h.deviceId,
        )

    /**
     * Teach saved remotes the addresses their devices are currently seen at.
     *
     * Runs for as long as the app is in the foreground - the scan is started by the activity
     * rather than by a screen, which is the correction: this collector was already here and the
     * flow it watches was only ever filled while the Remotes screen was open, so learning could
     * not happen in the background it was written for.
     *
     * What a sighting is allowed to write lives in `Sightings`, with its test. Two things follow
     * from one: an address for a device the entry recognises, and - for an entry that has no
     * device id and is sitting at an address the host is announcing from - the id itself, which
     * is what lets it recognise anything at all afterwards.
     */
    fun watchDiscoveredAddresses(): kotlinx.coroutines.Job = viewModelScope.launch {
        graph.davBeacon.state.collect { state ->
            var learned = false
            for (h in state.hosts) {
                if (graph.connections.learnFrom(sightingOf(h))) learned = true
            }
            // Only when something actually changed: discovery re-announces constantly, and a
            // bump per packet would re-list the panes forever.
            if (learned) {
                bumpRemotes()
                // And re-probe the drives straight away. A remote that has just been taught where
                // its device went is reachable NOW; waiting for the next poll leaves its storage
                // card reading Offline for up to a poll interval after it is actually back, which
                // is the difference between "it recovered" and "it recovered eventually".
                applyRefresh(RefreshTarget.VOLUMES, null)
            }
        }
    }

    /** A discovered host as the matching rules see it. */
    fun sightingOf(h: dev.niccc2007.filet.webdav.DavBeacon.Host) =
        dev.niccc2007.filet.vfs.provider.net.Sightings.Seen(h.deviceId, h.host, h.port)

    /** The saved remote a discovered host already is, or null when it is something new. */
    fun savedRemoteFor(h: dev.niccc2007.filet.webdav.DavBeacon.Host) =
        graph.connections.savedFor(sightingOf(h))

    /**
     * Attach a discovered host to a remote that was saved by hand, on the person's word.
     *
     * Needed because automatic learning has one gap it cannot close by itself: an entry with no
     * device id, whose address has ALREADY changed, has nothing left to match on. Every entry
     * made before ids existed is one network change away from that, and this is the one tap that
     * ends it - afterwards the entry carries the id and keeps up on its own.
     */
    fun linkRemote(connectionId: String, h: dev.niccc2007.filet.webdav.DavBeacon.Host) {
        val c = graph.connections.byId(connectionId) ?: return
        if (graph.connections.link(connectionId, sightingOf(h))) {
            bumpRemotes()
            toast("${c.label.ifEmpty { c.host }} now knows ${h.host}")
        } else {
            toast("${c.label.ifEmpty { c.host }} already knew that address")
        }
    }

    fun saveConnection(c: dev.niccc2007.filet.vfs.provider.net.NetConnection) {
        graph.connections.save(c)
        bumpRemotes()
        toast("Saved ${c.label.ifEmpty { c.host }}")
    }

    fun deleteConnection(id: String) {
        graph.connections.delete(id)
        bumpRemotes()
    }

    fun openRemote(c: dev.niccc2007.filet.vfs.provider.net.NetConnection) {
        focusedPane()?.navigateTo(VPath.of(c.protocol.scheme, "/" + c.id + "/"))
    }

    /**
     * Root is asked for only when the user opts in, because asking spawns a shell and
     * triggers the superuser prompt - which must never happen at startup.
     */
    fun enableRoot() {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                dev.niccc2007.filet.vfs.provider.RootProvider.isAvailable()
            }
            if (ok) {
                toast("Root granted")
                focusedPane()?.navigateTo(VPath.of("root", "/"))
            } else {
                toast("This device did not grant root.")
            }
        }
    }

    // ── surfaces (PLAN.md L5) ──

    /**
     * Pin the selection to the home screen.
     *
     * The shortcut stores the file's stable ID, never its path, so moving the file afterwards
     * does not break the tile - which is the whole reason M4 could not precede M3.
     */
    // ── selection actions ──
    //
    // Each one states what it needs. A toolbar button that silently does nothing when the
    // selection is the wrong shape is the same dead switch as a button that does nothing at
    // all - so the ones that need exactly one file are disabled in the bar, and these say so
    // too for the paths that reach them another way.

    /** Bookmark every selected folder — and files, which the Bookmarks page shows as shortcuts. */
    fun bookmarkSelection() {
        val items = focusedPane()?.selectedNodes().orEmpty()
        if (items.isEmpty()) return
        items.forEach { graph.bookmarks.add(it.path, it.name, isDir = it.isDir) }
        focusedPane()?.clearSelection()
        toast(if (items.size == 1) "Bookmarked" else "Bookmarked ${items.size}")
    }

    fun openWithSelection() {
        val node = focusedPane()?.selectedNodes()?.singleOrNull() ?: run {
            toast("Open with works on one file at a time.")
            return
        }
        focusedPane()?.clearSelection()
        openChooser(node)
    }

    fun renameSelection() {
        val node = focusedPane()?.selectedNodes()?.singleOrNull() ?: run {
            toast("Rename works on one file at a time.")
            return
        }
        askRename(node)
    }

    fun detailsForSelection() {
        val node = focusedPane()?.selectedNodes()?.singleOrNull() ?: run {
            toast("Details are for one file at a time.")
            return
        }
        showProperties(node)
    }

    /** The home-screen shortcut flow, which opens its own settings sheet. */
    fun shortcutSelection() {
        val items = focusedPane()?.selectedNodes().orEmpty()
        if (items.isEmpty()) return
        _shortcutFor.value = items.first()
    }

    private val _shortcutFor = MutableStateFlow<VNode?>(null)
    val shortcutFor: StateFlow<VNode?> = _shortcutFor.asStateFlow()
    fun dismissShortcutSheet() { _shortcutFor.value = null }

    /**
     * Create the home-screen shortcut the sheet was filled in for.
     *
     * Recorded in [dev.niccc2007.filet.shortcuts.ShortcutStore] whether or not the launcher
     * accepts it, because several launchers accept silently and report nothing back - and a
     * list that only shows confirmed ones would be permanently empty on those devices.
     */
    fun createShortcut(node: VNode, label: String, handler: HandlerId?) {
        _shortcutFor.value = null
        if (!dev.niccc2007.filet.shortcuts.Shortcuts.isSupported(graph.app)) {
            toast("This launcher does not accept home-screen shortcuts.")
            return
        }
        viewModelScope.launch {
            val id = graph.index.idFor(node.path)
            val ok = dev.niccc2007.filet.shortcuts.Shortcuts.pin(
                graph.app, node, graph.index, label, handler?.name,
            )
            graph.shortcuts.remember(
                dev.niccc2007.filet.shortcuts.ShortcutRecord(
                    id = dev.niccc2007.filet.shortcuts.Shortcuts.shortcutId(id, node.path),
                    kind = dev.niccc2007.filet.shortcuts.ShortcutKind.FILE,
                    label = label,
                    target = node.path.toString(),
                    handler = handler?.name,
                )
            )
            focusedPane()?.clearSelection()
            toast(if (ok) "Ask your launcher where to put it" else "The launcher refused.")
        }
    }

    /** A script on the home screen: one tap runs it. */
    fun shortcutForScript(script: dev.niccc2007.filet.script.Script) {
        val id = "script:${script.id}"
        val ok = dev.niccc2007.filet.shortcuts.Shortcuts.pinAction(
            graph.app, id, script.name, "script:${script.id}",
        )
        graph.shortcuts.remember(
            dev.niccc2007.filet.shortcuts.ShortcutRecord(
                id = id,
                kind = dev.niccc2007.filet.shortcuts.ShortcutKind.SCRIPT,
                label = script.name,
                target = script.id,
            )
        )
        toast(if (ok) "Ask your launcher where to put it" else "The launcher refused.")
    }

    /** An app action on the home screen — indexing, sharing, search. */
    fun shortcutForAction(action: dev.niccc2007.filet.shortcuts.AppAction) {
        val id = "action:${action.name}"
        val ok = dev.niccc2007.filet.shortcuts.Shortcuts.pinAction(
            graph.app, id, action.label, action.name,
        )
        graph.shortcuts.remember(
            dev.niccc2007.filet.shortcuts.ShortcutRecord(
                id = id,
                kind = dev.niccc2007.filet.shortcuts.ShortcutKind.ACTION,
                label = action.label,
                target = action.name,
            )
        )
        toast(if (ok) "Ask your launcher where to put it" else "The launcher refused.")
    }

    val shortcuts get() = graph.shortcuts

    // ── Home rows ──

    /**
     * Open a Home entry, and clean it up if it is no longer there.
     *
     * Home lists remembered things - recent files, tracked downloads - and the filesystem
     * moves on without telling it. A row whose file was deleted used to sit there for ever
     * and do nothing when tapped. Now the tap is also the check: if it is gone, say so once
     * and take the row out.
     */
    fun openHomeEntry(node: VNode) {
        viewModelScope.launch {
            val live = runCatching { graph.vfs.stat(node.path) }.getOrNull()
            if (live == null) {
                forgetHomeEntry(node)
                toast("That file is gone — removed from the list.")
                return@launch
            }
            if (live.isDir) focusedPane()?.navigateTo(live.path) else openNode(live)
        }
    }

    /** Drop a Home row from wherever it is remembered. */
    fun forgetHomeEntry(node: VNode) {
        graph.recents.remove(node.path)
        graph.bookmarks.remove(node.path)
        graph.home.refresh()
        _state.update { it.copy(revision = it.revision + 1) }
    }

    /** Show the file in its folder, with the row selected. */
    fun revealInFolder(node: VNode) {
        val parent = node.path.parent
        if (parent == null) { toast("That is already a root."); return }
        val pane = focusedPane() ?: return
        viewModelScope.launch {
            if (runCatching { graph.vfs.stat(node.path) }.getOrNull() == null) {
                forgetHomeEntry(node)
                toast("That file is gone — removed from the list.")
                return@launch
            }
            pane.navigateTo(parent)
            // Asked for, not timed. The old version waited a fixed 160ms and then selected,
            // which is too short on a big folder and a stall on a small one - and it never
            // scrolled at all, so the file was selected somewhere off screen. The listing now
            // carries the request and acts when it actually has rows.
            pane.revealOnly(node)
        }
    }

    fun bookmarkOne(node: VNode) {
        graph.bookmarks.add(node.path, node.name, isDir = node.isDir)
        toast("Bookmarked")
    }

    fun shortcutOne(node: VNode) { _shortcutFor.value = node }

    /** Open what a recorded shortcut points at, from inside the app. */
    fun openShortcutRecord(record: dev.niccc2007.filet.shortcuts.ShortcutRecord) {
        when (record.kind) {
            dev.niccc2007.filet.shortcuts.ShortcutKind.FILE -> {
                openResolvedTarget(record.target, record.handler)
            }
            dev.niccc2007.filet.shortcuts.ShortcutKind.SCRIPT ->
                runShortcutAction("script:${record.target}")
            dev.niccc2007.filet.shortcuts.ShortcutKind.ACTION ->
                runShortcutAction(record.target)
        }
    }

    /** A shortcut tapped on the home screen that is not a file. */
    /**
     * A pane to act on, waiting for one if the app has only just started.
     *
     * **This is the whole shortcut bug, reported three times.** A shortcut launches the app
     * cold, so `handleIncoming` runs during the first composition - and at that moment
     * `restoreTabs` has not finished, `_tabs` is empty, and `focusedPane()` is null. Every
     * action then ran `focusedPane()?.something()`, which on null is not an error: it is a
     * no-op. The app opened, looked completely normal, and did nothing.
     *
     * A file shortcut worked throughout, which is exactly why this survived three reports:
     * opening a file goes to a handler overlay and never touches a pane, so the one case
     * anybody tested was the one case that could not fail.
     *
     * The timeout is a real answer rather than a guard: if tabs cannot be restored in five
     * seconds something is badly wrong, and silently waiting forever would reproduce the
     * original bug with extra steps.
     */
    private suspend fun paneForShortcut(): PaneController? {
        if (_tabsReady.value) return focusedPane()
        return withTimeoutOrNull(5_000) {
            _tabsReady.first { it }
            focusedPane()
        }
    }

    /**
     * Whether [restoreTabs] has finished, including deciding which tab is active.
     *
     * Separate from `_tabs.isNotEmpty()` on purpose - see the comment where it is set.
     */
    private val _tabsReady = MutableStateFlow(false)

    fun runShortcutAction(raw: String) {
        viewModelScope.launch { runShortcutActionNow(raw) }
    }

    private suspend fun runShortcutActionNow(raw: String) {
        val pane = paneForShortcut()
        if (pane == null) {
            toast("Filet could not open a tab for that shortcut.")
            return
        }
        dev.niccc2007.filet.shortcuts.scriptIdOrNull(raw)?.let { id ->
            val script = graph.scripts.scripts.value.firstOrNull { it.id == id }
            if (script == null) toast("That script no longer exists.")
            else if (graph.scripts.isApproved(script)) runScript(script)
            else {
                pane.openSpecial(PaneKind.SCRIPTS, "Scripts")
                toast("Approve it once, then it can run from the home screen.")
            }
            return
        }
        // `parse`, not `valueOf`: a launcher icon outlives the version that made it, and a
        // crash on tap is the worst possible way to say "that action was renamed".
        when (dev.niccc2007.filet.shortcuts.AppAction.parse(raw)) {
            dev.niccc2007.filet.shortcuts.AppAction.INDEX_NOW -> reindexNow()
            dev.niccc2007.filet.shortcuts.AppAction.SHARE_NEARBY -> {
                pane.openSpecial(PaneKind.NEARBY, "Nearby")
                graph.nearby.start()
            }
            dev.niccc2007.filet.shortcuts.AppAction.STOP_SHARING -> {
                // Opens the tab as well as stopping. Something was running and is now not, and
                // a shortcut that silently changes state and shows nothing is indistinguishable
                // from one that did nothing.
                pane.openSpecial(PaneKind.NEARBY, "Nearby")
                graph.nearby.stop()
            }
            dev.niccc2007.filet.shortcuts.AppAction.SEARCH -> pane.openSearch(true)
            dev.niccc2007.filet.shortcuts.AppAction.BOOKMARKS ->
                pane.openSpecial(PaneKind.BOOKMARKS, "Bookmarks")
            dev.niccc2007.filet.shortcuts.AppAction.RECENT ->
                pane.openSpecial(PaneKind.RECENT, "Recent")
            null -> toast("That shortcut points at something Filet no longer has.")
        }
    }

    /** A pinned shortcut or a widget row resolved to a path and handed us the result. */
    /**
     * Open a saved place from the bookmark list.
     *
     * The decision is [placeAction], which is tested; this only carries the outcomes out.
     * Both non-folder outcomes need a [VNode] and therefore a stat, so they share one. The
     * difference is that a record with no stored kind gets the answer written back, so an old
     * bookmark pays for that stat once rather than on every tap.
     *
     * @param pane the pane the list is drawn in, so a tap moves that pane and not another.
     */
    fun openPlace(path: VPath, isDir: Boolean?, pane: PaneController) {
        val action = placeAction(path, isDir)
        if (action is PlaceAction.Navigate) { pane.navigateTo(action.path); return }
        val learn = action is PlaceAction.Resolve
        viewModelScope.launch {
            val node = runCatching { graph.vfs.stat(path) }.getOrNull()
            if (node == null) {
                toast("That bookmark no longer points anywhere.")
                return@launch
            }
            if (learn) graph.bookmarks.learnKind(path, node.isDir)
            when (placeActionResolved(path, node.isDir)) {
                is PlaceAction.Navigate -> pane.navigateTo(path)
                else -> openNode(node)
            }
        }
    }

    fun openResolvedTarget(raw: String, handlerOverride: String?) {
        val path = runCatching { VPath.parse(raw) }.getOrNull() ?: return
        viewModelScope.launch {
            val node = runCatching { graph.vfs.stat(path) }.getOrNull()
            if (node == null) { toast("That file is no longer there."); return@launch }
            if (node.isDir) {
                val pane = paneForShortcut()
                if (pane == null) { toast("Filet could not open a tab for that folder."); return@launch }
                pane.navigateTo(path)
                // The tab is named after the folder, so a shortcut to Download does not leave a
                // tab still calling itself Home.
                focusSide(_state.value.focused)
                return@launch
            }
            val handler = handlerOverride
                ?.let { h -> runCatching { HandlerId.valueOf(h) }.getOrNull() }
            when {
                // A shortcut pinned to "another app" means the app that type opens in, not a
                // prompt. Tapping a home-screen icon and being asked a question is the exact
                // opposite of what a shortcut is for.
                handler == HandlerId.EXTERNAL -> openExternally(node)
                handler != null -> openWith(node, handler, remember = false)
                else -> openWithRegistered(node)
            }
        }
    }

    // ── persistence ──

    private fun persistTabs() {
        val arr = JSONArray()
        for (t in _tabs.value) {
            val s = t.state.value
            arr.put(
                JSONObject()
                    .put("kind", s.kind.name)
                    .put("path", s.cwd?.toString() ?: "")
            )
        }
        graph.prefs.putString(
            KEY_TABS,
            JSONObject().put("tabs", arr)
                .put("a", _state.value.activeA)
                .put("b", _state.value.activeB)
                .put("split", _state.value.split.name)
                .toString(),
        )
    }

    private fun restoreTabs(fallback: VPath?) {
        // The setting. Off means start with Home alone - and the saved list is
        // deliberately LEFT on disk rather than cleared, so turning the setting back on
        // restores the tabs that were open rather than starting from nothing.
        val raw = TabRestore.savedStateFor(graph.prefs.getString(KEY_TABS), graph.prefs.restoreTabs.value)
        val restored = ArrayList<PaneController>()
        var a = 0
        var b = 0
        var split = SplitMode.OFF
        if (raw != null) {
            runCatching {
                val o = JSONObject(raw)
                val arr = o.getJSONArray("tabs")
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    val pane = newPane()
                    val p = t.optString("path", "")
                    val kind = runCatching { PaneKind.valueOf(t.optString("kind")) }.getOrNull()
                    when {
                        kind == PaneKind.FOLDER && p.isNotEmpty() ->
                            runCatching { VPath.parse(p) }.getOrNull()
                                ?.let { pane.navigateTo(it, push = false) } ?: pane.openHome()
                        kind != null && kind != PaneKind.FOLDER -> pane.openSpecial(kind, kind.name.lowercase().replaceFirstChar { c -> c.uppercase() })
                        else -> pane.openHome()
                    }
                    restored += pane
                }
                a = o.optInt("a", 0)
                b = o.optInt("b", 0)
                split = runCatching { SplitMode.valueOf(o.optString("split")) }.getOrDefault(SplitMode.OFF)
            }
        }
        if (restored.isEmpty()) {
            val home = newPane().also { it.openHome() }
            val first = newPane().also { p -> fallback?.let { p.navigateTo(it, push = false) } ?: p.openHome() }
            restored += home
            restored += first
            a = 1
        }
        _tabs.value = restored
        _state.update {
            it.copy(
                activeA = a.coerceIn(0, restored.lastIndex),
                activeB = b.coerceIn(0, restored.lastIndex),
                split = split,
            )
        }
        val roots = _state.value.volumes.map { it.node.path }
        restored.forEach { it.rootsForDevice = roots }
        // LAST. Anything waiting on a usable pane waits on this, not on the tab list becoming
        // non-empty: the list is populated a moment before `activeA` is set, and waiting on
        // the list alone meant a shortcut acted on tab 0 while the user was looking at tab 2.
        _tabsReady.value = true
    }

    private fun volumeLabel(node: VNode): String = when {
        node.path.path.contains("emulated") -> "Internal storage"
        node.path.scheme == "saf" -> node.name.ifEmpty { "Granted folder" }

        // A mounted remote is named by the REMOTE, not by its path.
        //
        // A share's node name is its share segment, which for a Filet host is the access code -
        // so the card read "SARAH8" and the name set on the connection was never used anywhere
        // it could be seen. A code is an implementation detail of the address; it is not what
        // the device is called.
        node.path.scheme != "local" ->
            connectionIdOf(node.path)?.let { connectionName(node.path.scheme, it) }
                ?: node.name.ifEmpty { "Network drive" }

        else -> node.name.ifEmpty { "Storage" }
    }

    /**
     * The saved-remote id out of a `scheme:/<id>/<remote>` path.
     *
     * The first segment, by the addressing every net provider shares - see `netPath`. Null for
     * anything that is not shaped like one rather than guessing at a substring.
     */
    private fun connectionIdOf(path: dev.niccc2007.filet.vfs.VPath): String? =
        path.path.trim('/').substringBefore('/').takeIf { it.isNotEmpty() }

    private companion object {
        const val KEY_TABS = "tabs.v1"

        /** Release and debug both, so a development build is never bridge-blind (PLAN.md §5.4). */
        val TRAWL_PACKAGES = listOf("dev.niccc2007.trawl", "dev.niccc2007.trawl.debug", "com.junkfood.seal")

        /** Past this, copying a file into the cache just to play it is not worth the wait. */
        const val CACHE_COPY_LIMIT = 256L * 1024 * 1024
    }
}

/**
 * How long the writes from a mounted device must stop before the panes re-list.
 *
 * Long enough that a desktop copying a folder in produces ONE re-listing rather than one per
 * file, short enough that a single edit appears before anyone has finished looking up at the
 * phone. See `watchRemoteWrites`.
 */
private const val REMOTE_SETTLE_MS = 600L

/**
 * How often a surface showing volumes re-reads them.
 *
 * Slow enough that a mounted share is not asked constantly, quick enough that turning the other
 * device on is noticed while somebody is still looking at the screen.
 */
private const val VOLUME_POLL_MS = 8_000L

/**
 * How long one volume gets to report its size before it is drawn without a figure.
 *
 * A local volume answers in microseconds. A mounted share that is awake answers in milliseconds,
 * and one that is asleep answers never - it costs a connect timeout per saved address, and then
 * does it again for the total. Unbounded, that sum is what held the first screen for twenty
 * seconds; a card with no figure on it is a far smaller problem than a Home screen that has not
 * arrived, and the next poll fills it in.
 */
private const val MEASURE_BUDGET_MS = 3_000L

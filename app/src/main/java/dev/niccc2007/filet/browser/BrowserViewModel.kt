package dev.niccc2007.filet.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.niccc2007.filet.FiletGraph
import dev.niccc2007.filet.data.SettingsTransfer
import dev.niccc2007.filet.handlers.ExternalApp
import dev.niccc2007.filet.handlers.ExternalApps
import dev.niccc2007.filet.handlers.HandlerId
import dev.niccc2007.filet.handlers.editedName
import dev.niccc2007.filet.vfs.provider.ArchiveFormat
import dev.niccc2007.filet.vfs.provider.ArchiveNaming
import dev.niccc2007.filet.vfs.provider.ArchiveEdits
import dev.niccc2007.filet.vfs.provider.ArchiveOptions
import dev.niccc2007.filet.vfs.provider.EditCosts
import dev.niccc2007.filet.vfs.provider.ExtractOptions
import dev.niccc2007.filet.vfs.provider.ExtractPlan
import dev.niccc2007.filet.vfs.provider.Archives
import dev.niccc2007.filet.vfs.provider.archiveName
import dev.niccc2007.filet.ops.ClipBatch
import dev.niccc2007.filet.ops.ClipTray
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
import dev.niccc2007.filet.update.RETRY_AFTER_FAILURE_MS
import dev.niccc2007.filet.update.shouldCheck
import dev.niccc2007.filet.update.shouldPrompt
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Side-by-side is the default because it is the MT Manager layout the mock settled on. */
/**
 * Dual pane, or not.
 *
 * There used to be a third: STACK, the panes one above the other. It is gone on request, and
 * the reasoning holds up - two panes exist to move things BETWEEN them, that gesture is
 * sideways, and the swipe that changes which one you are in is sideways too. A stacked split
 * made both of those read against the layout, and it halved the thing a file list has least
 * of on a phone, which is height.
 */
enum class SplitMode { OFF, SIDE }

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
    /** What the drive is called everywhere it is named: the name given to it, if any. */
    val label: String,
    val free: Long?,
    val total: Long?,
    val remote: Boolean = false,
    /**
     * The name the system gives it, before any rename. For Details, the rename field's
     * placeholder, and anything that recognises a drive by name - a renamed Termux tree is
     * still Termux.
     */
    val systemLabel: String = label,
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

/**
 * A file plus the handler that should show it.
 *
 * @param run the pictures and videos beside it in the list it was opened from, when there are
 *   any to swipe to. See [dev.niccc2007.filet.handlers.ViewerRun].
 * @param back the file this one was opened from by a link inside it, which Back returns to. See
 *   [BrowserViewModel.followLink].
 * @param returning this request is that return: the screen goes straight back to where the file
 *   was left rather than offering its saved spot. See `ReadingSpots.returnSpot`.
 */
data class OpenRequest(
    val node: VNode,
    val handler: HandlerId,
    val run: dev.niccc2007.filet.handlers.ViewerRun? = null,
    val back: OpenRequest? = null,
    val returning: Boolean = false,
)

data class AppState(
    // `tabIds` used to sit here, always empty, never written and never read - until the
    // swipe hint read it to decide whether there was a tab that way. The answer was
    // therefore always no, so a swipe that worked perfectly well announced "No tab that
    // way" while doing it. A field that is dead but looks live is worse than no field.
    val activeA: Int = 0,
    val activeB: Int = 0,
    val split: SplitMode = SplitMode.OFF,
    val focused: Side = Side.A,
    /**
     * Everything copied or cut and not yet dealt with.
     *
     * Was a single `Clipboard?`, which is what made copying from several places mean pasting
     * after every one of them: each Copy replaced the last. See [ClipTray] for why the unit
     * is a batch rather than a flat list of files.
     */
    val clips: ClipTray = ClipTray(),
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
    /** Shutdown Filet, waiting for a yes, with what it would end. See [BrowserViewModel.askStop]. */
    val stopAsk: List<RunningWork.Item>? = null,
    val volumes: List<VolumeInfo> = emptyList(),
    val switcherOpen: Boolean = false,
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
    val folderVisits get() = graph.folderVisits
    val folderPeek get() = graph.folderPeek
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
            // `remote`, not a scheme test: a superuser volume and a granted document tree are
            // not reached over the network, and re-polling every volume on their account is
            // work with no question behind it.
            val hasNetwork = _state.value.volumes.any { it.remote }
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

    /**
     * Show a drive's new name the moment it is given, or restored from a settings file.
     *
     * The cards are rebuilt on every measuring pass anyway, which would pick a rename up within
     * a poll - but a name typed into a dialog that only appears eight seconds later reads as the
     * rename having failed. Only the two names change here, never the figures, and the panes are
     * re-equipped so the search chips and drive headers say it too.
     */
    /**
     * The tree turned on or off: every tab is put where the rule says it stands - its drive's
     * tree, opened down to the folder the list was showing, or the folder the tree had focused.
     * See `TreeShape`. Every tab and not just the visible ones, so one switched to later is not
     * found in the shape of a view that is no longer on.
     */
    private fun watchTreeView() {
        viewModelScope.launch {
            prefs.treeView.drop(1).collect { _tabs.value.forEach { it.reshape() } }
        }
    }

    private fun watchDriveNames() {
        viewModelScope.launch {
            prefs.driveNames.drop(1).collect { names ->
                _state.update { s ->
                    s.copy(
                        volumes = s.volumes.map { v ->
                            v.copy(
                                label = dev.niccc2007.filet.data.DriveNames.resolve(
                                    names, dev.niccc2007.filet.data.DriveNames.idOf(v.node.path), v.systemLabel,
                                ),
                            )
                        },
                    )
                }
                _tabs.value.forEach(::equip)
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        watchRemoteWrites()
        watchDiscoveredAddresses()
        watchNetworkMoves()
        watchDriveNames()
        watchTreeView()
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
            // AFTER the restore, never before: the restore itself walks every pane to its saved
            // folder, and a collector running through that would write the half-restored list
            // back over the one being read.
            //
            // Never for a picker, which browses in tabs of its own - see [ownTabs].
            if (!ownTabs) watchPanesForPersist()
            // The number that matters, and the one the report was about: everything a person
            // looks at on opening Filet is on screen by here. What follows fills in figures.
            dev.niccc2007.filet.log.FiletLog.i("launch", "first screen ready at +" + dev.niccc2007.filet.log.FiletLog.sinceStart() + "ms")
            loadVolumes()
            updateIndexOnOpen(roots)
        }
    }

    /**
     * Ask the index whether it wants a pass, now that Filet is open.
     *
     * Bug identified here, and it was the largest battery cost the app has had: this called
     * `crawlFully(roots)` unconditionally - unbounded, over every volume including two network
     * shares and the root mounts, with the foreground service and its notification - on every
     * open. Measured at 46% of a day's battery. See [dev.niccc2007.filet.index.IndexCoordinator.onOpen],
     * which now makes the same staleness decision launch has always made, of local volumes only,
     * and never starts the service on its own.
     *
     * Never for a picker. Another app asking Filet for a photo is not Filet being opened, and a
     * picker that crawled the device was the most expensive possible way to answer that.
     */
    private fun updateIndexOnOpen(volumes: List<VNode>) {
        if (ownTabs) return
        graph.indexCoordinator.onOpen(volumes.map { it.path })
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
        val names = prefs.driveNames.value
        return roots.map { node ->
            val before = known[node.path]
            val system = volumeLabel(node)
            VolumeInfo(
                node = node,
                label = dev.niccc2007.filet.data.DriveNames.resolve(
                    names, dev.niccc2007.filet.data.DriveNames.idOf(node.path), system,
                ),
                free = before?.free,
                total = before?.total,
                remote = graph.vfs.isRemote(node.path),
                systemLabel = system,
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
        // Pushed on every pass, because the volume list is not fixed at launch: a share
        // connects, a card is slotted, a folder is granted. Equipping panes only at creation
        // left a share that arrived later missing from `rootsForDevice` for the rest of the
        // session - which made `jumpTo` fall back to navigating, and would now cost the reach
        // ladder the one rung that can see that share at all.
        _tabs.value.forEach(::equip)
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
            onNotice = { msg -> toast(msg) },
            covered = { graph.coveredVolumes },
            onGone = { path -> graph.forgetGone(path) },
            onSearched = { q -> graph.index.steerCrawl(q) },
            onOpened = { node, from -> openNode(node, from) },
            // Walking into a folder is the moment its contents are about to be searched, so
            // it is also when indexing it is worth doing. Bounded and skipped entirely if a
            // crawl is already running - see IndexCoordinator.indexFolderNow.
            onLanded = { path -> graph.indexCoordinator.indexFolderNow(path) },
            worldRevision = { _state.value.revision },
            dragging = { _state.value.drag != null },
            // One selection while a pick is live: the pick's. See `PickPort`.
            pick = pickPort(),
        )
        equip(pane)
        return pane
    }

    fun addTab(at: VPath? = null): Int {
        val pane = newPane()
        if (at != null) pane.jumpTo(at, push = false) else pane.openHome()
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

    /**
     * Every tab closed at once, leaving one on Home: the clean slate, in one tap.
     *
     * Decision: no confirmation. This is a clean-up done often, and a question every time is the
     * cost that stops it being used. Split goes off with the tabs, because one tab cannot fill
     * two sides; turning split on again gives the second side its own tab, as it always does.
     * The new tab is a new pane rather than the old first one sent Home, so its Back history and
     * scroll memory go too - a reset that kept them would still Back into the old session.
     */
    fun closeAllTabs() {
        val closed = _tabs.value.size
        _tabs.value = listOf(newPane().also { it.openHome() })
        _state.update { it.copy(activeA = 0, activeB = 0, split = SplitMode.OFF, focused = Side.A) }
        persistTabs()
        if (closed > 1) toast("Closed $closed tabs")
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
            // walking: the volume root IS the trunk, so there is nothing to expand down to.
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
     * No toast afterwards. It used to say "Folder refreshed", and on a pull that was a white
     * pill flashing up over a list that had just answered the gesture itself: the pull's own
     * indicator turns once when it fires, which is the acknowledgement, so the toast said the
     * same thing a second time in a louder voice.
     */
    fun refreshPane(pane: PaneController) {
        val plan = refreshPlan(pane.state.value.kind)
        viewModelScope.launch {
            for (target in plan.targets) applyRefresh(target, pane)
            // Panes that read straight from a store on every composition need a nudge to
            // recompose at all, which is what this is.
            _state.update { it.copy(revision = it.revision + 1) }
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
            RefreshTarget.PLAYLISTS -> {
                graph.playlists.reload()
                // A file that was re-tagged outside Filet should show its new name.
                graph.trackCards.clear()
            }
            RefreshTarget.RECENTS -> graph.recents.reload()

            // Nothing cached to drop either: the list is a snapshot of the store taken when
            // the pane composed, so re-reading it is all a refresh here can mean. The flow is
            // what the screen is watching.
            RefreshTarget.RESUME_POINTS -> bumpResume()
            RefreshTarget.CATEGORY -> bumpCategory()
            RefreshTarget.CAMERA -> bumpCamera()

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

    /** Focus whichever side [pane] is on: a screen that knows its pane and not its side. */
    fun focusPane(pane: PaneController) {
        sideOf(pane)?.let(::focusSide)
    }

    /**
     * Move ONE side along the tab strip.
     *
     * Per side, and that is the whole point of it: in a split, the pane you swiped changes
     * tab and the other pane stays exactly where it was. [focusTab] cannot do this - it
     * picks its side from whichever one currently has focus, which is right for a tap on a
     * chip and wrong for a gesture that has already told you which pane it means.
     *
     * The side that moved takes focus, because the finger was in it. Returns false when
     * there is no tab that way, so the caller can say so rather than looking broken.
     */
    fun stepTab(side: Side, step: Int): Boolean {
        val s = _state.value
        val onSide = if (s.split == SplitMode.OFF) Side.A else side
        val current = if (onSide == Side.A) s.activeA else s.activeB
        val next = PaneSwipe.nextTab(current, step, _tabs.value.size) ?: return false
        _state.update {
            if (onSide == Side.A) it.copy(activeA = next, focused = Side.A)
            else it.copy(activeB = next, focused = Side.B)
        }
        persistTabs()
        // Same reason as focusTab: a tab that was off screen while a file moved is behind
        // the world until something asks it to catch up.
        freshenVisiblePanes()
        return true
    }

    /** What the tab a swipe would land on is called, for the hint while you are pushing. */
    fun tabTitleAt(index: Int): String? = _tabs.value.getOrNull(index)?.state?.value?.title

    /** How many tabs there are, for anything deciding whether there is one that way. */
    fun tabCount(): Int = _tabs.value.size

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
            SplitMode.SIDE -> SplitMode.OFF
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
        if (at != null) pane.jumpTo(at, push = false) else pane.openHome()
        _tabs.value = _tabs.value + pane
    }

    // ── opening ──

    /** Count use of a folder for Quick access. See `QuickAccess.usedBy` and `workedIn`. */
    private fun creditUse(folder: VPath?) {
        folder?.let(QuickAccess::workedIn)?.let { graph.folderVisits.credit(it) }
    }

    /**
     * Open what was tapped.
     *
     * @param from the list it was tapped in, as drawn - a folder, search results, a category.
     *   A picture or video opened from one can be swiped to its neighbours there.
     */
    /**
     * @param back the request this file is opened from by a link, which Back returns to. Carried
     *   to wherever the request is made, because that can be after a read of the file's first
     *   bytes - see [followLink].
     */
    fun openNode(node: VNode, from: List<VNode>? = null, back: OpenRequest? = null) {
        // Here and not in `openWithRegistered`: archives and APKs branch off below before that
        // is reached, and a pick must never open an inspector over the picker.
        if (pickTap(node)) return
        val pane = focusedPane() ?: return
        graph.recents.record(node.path, node.isDir)
        creditUse(QuickAccess.usedBy(node.path, node.isDir))
        // Frecency counts real interactions only. Counting appearances in a result list
        // makes the ranking eat itself (SEARCH.md §5.4).
        viewModelScope.launch { runCatching { graph.index.recordOpen(node.path) } }
        when {
            // Arriving, and this one needed an audit to establish rather than a guess.
            //
            // Every door into this function that can hand it a FOLDER guards first - the
            // bookmarks list, the recents list and go-to-containing-folder all read
            // `if (isDir) jumpTo(...) else openNode(...)`. The one caller that passes a folder
            // straight through is the search result list, and a search hit is by definition
            // from outside the pane. So the folder branch is always an arrival, and using
            // `navigateTo` here re-rooted the tree on every folder opened from a search.
            node.isDir -> pane.jumpTo(node.path)
            // Routed through BundleRoute so the bundle formats stop falling into the archive
            // branch unnoticed. A tap on one still opens the browser; what changed is that
            // Install is now offered for it in the menu, which it never was.
            dev.niccc2007.filet.apk.BundleRoute.tap(node.name, node.isDir) ==
                dev.niccc2007.filet.apk.BundleRoute.Tap.INSPECT -> openApk(node)
            // By whole name, not by extension: `backup.tar.gz` has the extension "gz".
            Archives.canList(node.name) -> mountArchive(node)
            else -> openUnclaimed(node, from, back)
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
    private fun openUnclaimed(node: VNode, from: List<VNode>? = null, back: OpenRequest? = null) {
        viewModelScope.launch {
            val kind = runCatching {
                // Sniffing reads the first few hundred bytes of the file, which for an unclaimed
                // file on a mounted share is a socket read.
                kotlinx.coroutines.withContext(Dispatchers.IO) {
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
                openWithRegistered(node, from, back)
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
        creditUse(QuickAccess.usedBy(node.path, isDir = false))

        // A local archive opens where it lies.
        val local = graph.vfs.osPath(node.path)
        if (local != null) {
            // walking: descending into an archive that was tapped in this listing.
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
                        // walking: the same descent, via a staged copy when the archive is remote.
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

    fun copySelection() = withSelection { items -> loadClipboard(items, PendingOp.COPY) }

    fun cutSelection() = withSelection { items -> loadClipboard(items, PendingOp.MOVE) }

    /**
     * Put a batch on the clipboard.
     *
     * Adds rather than replaces, which is the whole of the request: copying from three
     * folders used to mean pasting three times, because each Copy threw the last one away.
     *
     * The collision rules live in [ClipTray.add] and are tested there - a path already queued
     * to MOVE cannot be in a second batch, because the first paste takes it away and the
     * second fails with "no longer there", which reads as a bug in Filet rather than as two
     * instructions that could not both be true. What is said here is what was dropped.
     */
    private fun loadClipboard(items: List<VNode>, op: PendingOp) {
        if (items.isEmpty()) return
        val paths = items.map { it.path }
        val parents = paths.mapNotNull { it.parent }.distinct()
        val batch = ClipBatch(
            id = nextClipId(),
            items = paths,
            op = op,
            // Null when they came from several places, which is the one case a folder name
            // would be a lie about.
            from = parents.singleOrNull(),
            addedAt = System.currentTimeMillis(),
        )
        val added = _state.value.clips.add(batch)
        _state.update { it.copy(clips = added.tray) }
        val verb = if (op == PendingOp.COPY) "copied" else "ready to move"
        val skipped = added.droppedAlreadyQueued
        val kept = batch.items.size - skipped
        toast(
            when {
                !added.added -> "Already queued to move"
                skipped > 0 -> "$kept $verb \u2014 $skipped already queued"
                else -> "${paths.size} $verb"
            },
        )
        focusedPane()?.clearSelection()
    }

    private var clipSeq = 0
    private fun nextClipId(): String = "clip-${++clipSeq}"

    /** Everything picked, in every batch, into this pane. */
    fun paste(side: Side = _state.value.focused) {
        val pane = paneFor(side) ?: return
        pasteBatches(pane, _state.value.clips.batches.map { it.id })
    }

    /**
     * Paste into a NAMED pane rather than into whichever one has focus.
     *
     * The dock is drawn in every pane, so the pane that was tapped is the destination. Going
     * through `focused` here would make the button in the unfocused half of a split silently
     * paste into the other one, which is the exact confusion the per-pane dock exists to
     * remove.
     */
    fun pasteInto(pane: PaneController) =
        pasteBatches(pane, _state.value.clips.batches.map { it.id })

    /** One batch, by id. The quiet button on a tray card. */
    fun pasteBatch(pane: PaneController, id: String) = pasteBatches(pane, listOf(id))

    /**
     * Run the picked files of [ids] into this pane's folder, one batch at a time.
     *
     * Sequential on purpose. Two batches landing in the same folder at once race on name
     * collisions - `ArchiveNaming`-style de-duplication reads the directory, and two readers
     * both see the name free and both take it. A copy is IO-bound anyway, so the parallelism
     * would buy nothing to pay for that.
     */
    private fun pasteBatches(pane: PaneController, ids: List<String>) {
        val dest = pane.state.value.target ?: return
        sideOf(pane)?.let { focusSide(it) }
        val blocked = writeBlockReason(dest)
        if (blocked != null) {
            toast(blocked)
            return
        }
        val wanted = ids.mapNotNull { _state.value.clips.batch(it) }.filter { it.pickedCount > 0 }
        if (wanted.isEmpty()) {
            toast("Nothing is ticked to paste")
            return
        }
        creditUse(dest)
        viewModelScope.launch {
            var copied = 0
            var moved = 0
            val failures = mutableListOf<Pair<dev.niccc2007.filet.vfs.VPath, String>>()
            var denial: dev.niccc2007.filet.vfs.Denial? = null
            for (batch in wanted) {
                val payload = batch.payload
                val r = if (batch.op == PendingOp.COPY) graph.ops.copy(payload, dest)
                else graph.ops.move(payload, dest)
                if (batch.op == PendingOp.COPY) copied += r.succeeded else moved += r.succeeded
                failures += r.failed
                denial = denial ?: r.denial
                // A PARTLY failed batch keeps its failed items and stays on the clipboard.
                // A clipboard that empties itself after a half-failure is how somebody loses
                // track of what actually moved. See ClipTray.afterPaste.
                _state.update {
                    it.copy(
                        clips = it.clips.afterPaste(batch.id, dest, r.failed.map { f -> f.first }.toSet()),
                    )
                }
                // Where each one lands, so the rows appear at the bottom instead of
                // scattering into sort order the instant the paste finishes.
                pane.noteArrived(payload.map { dest.child(it.name) })
            }
            val verb = listOfNotNull(
                "copied".takeIf { copied > 0 },
                "moved".takeIf { moved > 0 },
            ).joinToString(" and ").ifEmpty { "pasted" }
            reportAndRefresh(copied + moved, failures, verb, denial)
        }
    }

    private fun sideOf(pane: PaneController): Side? =
        Side.entries.firstOrNull { paneFor(it)?.id == pane.id }

    fun clearClipboard() = _state.update { it.copy(clips = ClipTray()) }

    /** Take one batch off for good. The × on a tray card. */
    fun dropClipBatch(id: String) = _state.update { it.copy(clips = it.clips.remove(id)) }

    /** Tick or untick one file inside a batch. "Not this time", and reversible. */
    fun pickClipFile(id: String, path: dev.niccc2007.filet.vfs.VPath, on: Boolean) =
        _state.update {
            it.copy(clips = if (on) it.clips.pick(id, path) else it.clips.unpick(id, path))
        }

    fun pickClipAll(id: String, on: Boolean) =
        _state.update {
            it.copy(clips = if (on) it.clips.pickAll(id) else it.clips.pickNone(id))
        }

    /**
     * Take one file off the clipboard for good.
     *
     * Distinct from unticking, and the tray says so: unticking is "not this time", this is
     * "I did not mean to pick it up".
     */
    fun dropClipFile(id: String, path: dev.niccc2007.filet.vfs.VPath) =
        _state.update { it.copy(clips = it.clips.without(id, path)) }

    // ── operations ──

    /**
     * Delete the focused pane's selection, or [explicit] when a dialogue was raised for files
     * outside any selection (see [confirmDeleteOf]). One delete either way, through the same
     * operation, so it is a job in Activity like every other.
     */
    fun deleteSelection(explicit: List<VNode>? = null) {
        if (explicit != null) {
            if (explicit.isNotEmpty()) deleteItems(explicit)
            return
        }
        withSelection { deleteItems(it) }
    }

    private fun deleteItems(items: List<VNode>) {
        creditUse(items.firstOrNull()?.path?.parent)
        viewModelScope.launch {
            val r = graph.ops.delete(items.map { it.path })
            reportAndRefresh(r.succeeded, r.failed, "deleted", r.denial)
        }
    }

    fun rename(target: VPath, newName: String) {
        if (newName.isBlank() || newName == target.name) return
        creditUse(target.parent)
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
        // `target`, not `cwd`. In a tree the highlighted folder is the one being worked in
        // and it is usually not the one in the path bar - see `PaneState.active`. A
        // highlight that did not also decide where things land would be decoration, and
        // worse than no highlight at all.
        val dest = paneFor(side)?.state?.value?.target ?: return
        if (name.isBlank()) return
        creditUse(dest)
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
        val dest = paneFor(side)?.state?.value?.target ?: return
        if (name.isBlank()) return
        creditUse(dest)
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
     * @param typed the name, without its extension.
     * @param suffix the extension, which is the caller's and not the format's. A 7z named
     *   `.mcaddon` is still a 7z - see `ArchiveNaming`.
     * @param format which container to write.
     */
    fun compressSelection(
        typed: String,
        format: ArchiveFormat,
        suffix: String = format.suffix,
        options: ArchiveOptions = ArchiveOptions.NONE,
    ) = withSelection { items ->
        // `target`, not `cwd`. With tree view on a pane's `cwd` stays at the volume root and
        // only `active` moves as somebody opens branches - so `cwd` is not "where the user is",
        // and reading it here put the result at the top of the storage instead of in the folder
        // they were looking at. See `PaneState.target`.
        val dest = selectionFolder() ?: return@withSelection
        // Said before any work starts, because the work may finish in a tenth of a second or
        // run for a minute and the reader cannot tell which in advance. The job row is where
        // the progress is; this is the pointer to it.
        toast("Added archive creation to the activity list")
        viewModelScope.launch {
            val existing = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    graph.vfs.list(dest).map { it.name }.toHashSet()
                }
            }.getOrDefault(HashSet())
            val name = ArchiveNaming.fileName(typed, suffix) { it in existing }
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
        // `target`, not `cwd`. With tree view on a pane's `cwd` stays at the volume root and
        // only `active` moves as somebody opens branches - so `cwd` is not "where the user is",
        // and reading it here put the result at the top of the storage instead of in the folder
        // they were looking at. See `PaneState.target`.
        // A category has no folder to extract into, so it starts beside the archive - and still
        // follows the pane the moment somebody walks into a folder, as from anywhere else.
        val dest = into ?: focusedPane()?.state?.value?.target ?: node.path.parent ?: return
        val root = if (node.path.scheme == ArchiveProvider.SCHEME) node.path
        else ArchiveProvider.mount(node.path.path)
        viewModelScope.launch {
            val options = ExtractOptions()
            val plan = graph.extractOps.plan(root, dest, node.name, options)
            _extractPlan.value = PendingExtract(root, dest, node.name, options, plan)
            // Drawn into the pane that is about to receive them, and re-made whenever an
            // option changes - or the folder does - so the ghosts never describe a stale plan.
            // This is the only thing that makes it obvious a folder is about to gain forty
            // files, now that there is no pick tab and no second confirm.
            _pickPreview.value = ghostsOf(plan, node.name)
            // Only once an explicit destination was NOT given. "Extract to the other pane"
            // names its target and must not then follow this pane around.
            if (into == null) followFolderWhileExtracting(root, node.name)
        }
    }

    /**
     * Re-aim a running extraction at whatever folder is being looked at.
     *
     * ## Why "Extract here" and "Extract to" are one action now
     *
     * They were two menu items, and the only difference between them was where the extraction
     * pointed: here used the folder you were standing in, to opened a tab so you could go and
     * find another one. Two code paths, two previews, two sets of bugs - and the "to" half had
     * to invent a pick tab, a destination to carry, and a confirmation to end it.
     *
     * Once the ghosts follow the folder there is nothing left to separate them. Start the
     * extraction, and the folder you are looking at IS the destination: stay where you are and
     * it is "here", walk somewhere and it is "to". Nothing to choose up front, nothing to learn,
     * and one path that can be wrong in one place.
     *
     * Deliberately NOT cancelled when you leave the folder. Cancelling on a step away would
     * make the feature fragile in a way nothing else here is - a stray back-swipe would destroy
     * the setup - and it would force the destination to be decided before you are allowed to
     * look around, which is the thing the pick tab existed to work around.
     *
     * Re-planned on the FOLDER alone, so a refresh, a sort or a rename redraws from the plan in
     * hand while actually walking rebuilds it. `collectLatest` drops a plan still being built
     * when the folder moves again.
     */
    private fun followFolderWhileExtracting(root: VPath, label: String) {
        extractWatch?.cancel()
        extractWatch = viewModelScope.launch {
            _state
                .map { it.focused }
                .flatMapLatest { side -> paneFor(side)?.state ?: kotlinx.coroutines.flow.emptyFlow() }
                .map { it.target }
                .distinctUntilChanged()
                .collectLatest { dest ->
                    // A pane with no folder - mid-load, or a special pane - is not a
                    // destination. The extraction keeps the one it has rather than losing it.
                    if (dest == null) return@collectLatest
                    val current = _extractPlan.value ?: return@collectLatest
                    if (current.into == dest) return@collectLatest
                    val plan = graph.extractOps.plan(root, dest, label, current.options)
                    // Still the same extraction: a cancel or a commit between the two lines
                    // must not put a card back on screen.
                    if (_extractPlan.value == null) return@collectLatest
                    _extractPlan.value = current.copy(into = dest, plan = plan)
                    _pickPreview.value = ghostsOf(plan, label)
                }
        }
    }

    private var extractWatch: kotlinx.coroutines.Job? = null

    /** Extract into the other pane, which is what a two-pane file manager is for. */
    fun extractToOtherPane(node: VNode) {
        val here = _state.value.focused
        // The other pane's folder, which in tree view is its open branch rather than its root.
        val other = paneFor(if (here == Side.A) Side.B else Side.A)?.state?.value?.target ?: return
        extract(node, other)
    }

    /** Re-plan with changed options. The preview always shows the current answer. */
    fun adjustExtract(change: (ExtractOptions) -> ExtractOptions) {
        val current = _extractPlan.value ?: return
        val options = change(current.options)
        viewModelScope.launch {
            val plan = graph.extractOps.plan(current.archiveRoot, current.into, current.label, options)
            _extractPlan.value = current.copy(options = options, plan = plan)
            // The ghosts follow the options. Lifting a wrapper changes what lands in this
            // folder, and a preview that kept the old answer would be the one thing on screen
            // disagreeing with the plan about to run.
            if (_pickPreview.value != null) _pickPreview.value = ghostsOf(plan, current.label)
        }
    }

    fun cancelExtract() {
        extractWatch?.cancel()
        extractWatch = null
        _extractPlan.value = null
        _pickPreview.value = null
    }

    /** Carry out exactly the plan on screen. */
    fun confirmExtract() {
        val pending = _extractPlan.value ?: return
        _extractPlan.value = pending.copy(busy = true)
        creditUse(pending.into)
        // Pinned from here on. The destination is whatever is on screen at the moment Extract
        // is pressed, and it must not move under a running extraction.
        extractWatch?.cancel()
        extractWatch = null
        viewModelScope.launch {
            val r = graph.extractOps.run(pending.archiveRoot, pending.into, pending.plan, pending.label)
            _extractPlan.value = null
            _pickPreview.value = null
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

    /**
     * Whether this browser's tabs are its own rather than the app's saved session.
     *
     * Set by the picker activity, which is a second browser in a second process-local view
     * model, reading and writing the same preferences as the one the user left open.
     *
     * Bug identified here: it restored the saved tab list, so opening the picker resumed
     * somebody's browsing session inside a modal chooser - and then wrote its own walking back
     * over that list. Picking a photo through the categories left the browser sitting on the
     * Photos tab afterwards, with the tab they had actually been using closed. Two browsers
     * sharing one saved session is not a session.
     *
     * It also started in the wrong place. The restore aims at a volume root, which for an
     * image pick is a folder of folders; Home has the categories on it, and Photos is most of
     * the answer already.
     *
     * So a picker reads nothing and writes nothing: one tab, opened on Home, discarded with
     * the activity.
     */
    var ownTabs: Boolean = false

    /** The asking app's name, for the notice strip. Null when the caller could not be named. */
    var pickCaller: String? = null

    /** Hand the selection back to the app that asked. Set by the picker activity. */
    var onPickConfirm: (() -> Unit)? = null

    /** Give up and return a cancel. Set by the picker activity. */
    var onPickCancel: (() -> Unit)? = null

    /** What the picker would hand over: the focused pane's selection, files and folders both. */
    /**
     * What an external picker hands back. See [_pickChosen] for why this is not a pane's
     * selection: the report form's own attachment pick comes through here, and choosing a photo
     * from the Photos tab put it in a list this never read.
     */
    fun pickSelection(): List<VNode> = _pickChosen.value

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

    /**
     * The two destinations, from a selection rather than from a node.
     *
     * Two, not three. "Extract to..." is gone: it opened a pick tab so a folder could be
     * chosen, and the ghosts follow the folder now, so starting the extraction and walking
     * somewhere does the same job with no tab, no second confirm and no second code path. See
     * `followFolderWhileExtracting`. The other pane stays, because that one names a
     * destination that is NOT where you are standing and cannot be reached by walking without
     * losing your place.
     */
    fun extractSelection() = withSelection { items -> extract(items[0]) }

    fun extractSelectionToOtherPane() = withSelection { items -> extractToOtherPane(items[0]) }

    /**
     * A plan's top-level items as ghosts.
     *
     * One function, because there is now one Extract. It was shared by "Extract to" and
     * "Extract here" so the two previews could not drift; those are one action now, and this
     * is still the single place a plan becomes ghosts - so the drawing and the extractor
     * cannot describe different operations, which is the failure the plan/extractor split
     * exists to prevent.
     */
    private fun ghostsOf(
        plan: ExtractPlan,
        archiveName: String,
    ): dev.niccc2007.filet.pick.PickPreview {
        // Only what lands in THIS folder. The deeper entries are inside those, and listing
        // every file of a thousand-entry archive would bury the folder it is drawn into.
        val top = plan.items.filter { !it.path.trimEnd('/').contains('/') }
        val ghosts = top.take(dev.niccc2007.filet.pick.PickPreview.SHOWN).map {
            dev.niccc2007.filet.pick.PickPreview.Ghost(
                name = it.path.trimEnd('/').substringAfterLast('/'),
                isDir = it.isDir,
                note = when {
                    it.collides != null -> "already here"
                    it.added -> "new folder"
                    else -> ""
                },
                invented = it.added,
            )
        }
        val clashes = plan.collisions.size
        return dev.niccc2007.filet.pick.PickPreview(
            title = "Extract $archiveName here",
            detail = buildString {
                append(dev.niccc2007.filet.browser.plural(plan.entryCount, "entry", "entries"))
                if (plan.needsBytes > 0) {
                    append("  ·  ")
                    append(dev.niccc2007.filet.browser.humanSize(plan.needsBytes))
                }
                if (clashes > 0) {
                    append("  ·  ")
                    append(dev.niccc2007.filet.browser.plural(clashes, "name clash", "name clashes"))
                }
            },
            entries = ghosts,
            extra = (top.size - ghosts.size).coerceAtLeast(0),
            blocked = if (!plan.fits) {
                "Not enough room: needs " + dev.niccc2007.filet.browser.humanSize(plan.needsBytes) +
                    ", " + dev.niccc2007.filet.browser.humanSize(plan.freeBytes) + " free"
            } else {
                null
            },
        )
    }

    // -- picking, when Filet asks itself --

    /**
     * A pick in progress inside Filet: what was asked for, who hears the answer, and which tab
     * to go back to.
     *
     * It runs in a tab of its own, opened at the start folder and closed when the pick ends, so
     * the browser - thumbnails, sort, search, tree - is the picker. A separate tab is what keeps
     * cancel clean: the pane the pick was started from is never navigated, so whatever it was
     * showing (Home, Settings, a half-scrolled folder) is exactly as it was afterwards, with
     * nothing to remember and replay.
     */
    class LivePick(
        val spec: dev.niccc2007.filet.pick.PickSpec,
        val onResult: (dev.niccc2007.filet.pick.PickResult) -> Unit,
        val pane: PaneController,
        val returnTo: Int,
        /**
         * What the caller's operation would produce in a given folder, or null for a pick with
         * nothing to show.
         *
         * Supplied by the caller because only the caller knows: an extract has a plan, a save
         * has a filename. See `PickPreview`.
         */
        val preview: (suspend (dev.niccc2007.filet.vfs.VPath) -> dev.niccc2007.filet.pick.PickPreview?)? = null,
    )

    private val _inAppPick = MutableStateFlow<LivePick?>(null)
    val inAppPick: StateFlow<LivePick?> = _inAppPick.asStateFlow()

    /**
     * What has been chosen for the live pick, wherever it was chosen from.
     *
     * ## Why the pick keeps its own list instead of reading a pane's selection
     *
     * Bug identified here: the confirm read `live.pane.selectedNodes()` - the selection of the
     * ONE pane the pick happened to start in. Every other surface in the app has its own idea of
     * what is selected, or no idea at all, so choosing a photo anywhere but that pane's folder
     * listing put the file nowhere the confirm would look. On the Photos tab a tap did not even
     * register: the card never highlighted, and "Use these" answered "Select a picture first"
     * about a picture that had just been tapped.
     *
     * A pick is not a property of a pane. It is a question the app is being asked, and it should
     * be answerable from anywhere the file can be seen - the folder list, a category, the gallery,
     * search results, Home. So the chosen set lives with the pick, every surface writes to this
     * one list, and the confirm reads the same list it was written into.
     *
     * Holds [VNode]s rather than paths because the filter needs to ask what a node IS - its
     * extension, its size - to decide whether it qualifies, and a path alone cannot answer that
     * without another stat.
     */
    private val _pickChosen = MutableStateFlow<List<dev.niccc2007.filet.vfs.VNode>>(emptyList())
    val pickChosen: StateFlow<List<dev.niccc2007.filet.vfs.VNode>> = _pickChosen.asStateFlow()

    /**
     * Whether the pick card is rolled up out of the way.
     *
     * On the pick, not on a pane, for the same reason as [_pickChosen]: walking into another tab
     * must not expand a card somebody deliberately folded away, and must not fold away one they
     * left open. Reset whenever a pick starts, because a card that remembered being minimised
     * from a previous pick would open with the question already hidden.
     */
    private val _pickMinimised = MutableStateFlow(false)
    val pickMinimised: StateFlow<Boolean> = _pickMinimised.asStateFlow()

    fun setPickMinimised(value: Boolean) { _pickMinimised.value = value }

    /**
     * The name a save pick will write, as it is typed on the card. On the pick for the same
     * reason as [_pickChosen]; reset when a pick starts, to the name its caller offered.
     */
    private val _pickName = MutableStateFlow("")
    val pickName: StateFlow<String> = _pickName.asStateFlow()

    fun setPickName(value: String) { _pickName.value = value }

    /** Whether something is at [path] now. For the save card's "Replace", asked of the folder itself. */
    suspend fun exists(path: VPath): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching { graph.vfs.stat(path) != null }.getOrDefault(false)
    }

    /** Whether this exact file is in the pick's chosen list, for a row or card to light up. */
    fun isPickChosen(path: dev.niccc2007.filet.vfs.VPath): Boolean =
        _pickChosen.value.any { it.path == path }

    /**
     * The preview standing between the confirm and the commit, or null.
     *
     * Held here rather than inside [LivePick] so the pane can draw its ghosts without
     * recomposing on every other part of the pick, and so a second confirm press commits
     * rather than rebuilding what is already on screen.
     */
    private val _pickPreview = MutableStateFlow<dev.niccc2007.filet.pick.PickPreview?>(null)
    val pickPreview: StateFlow<dev.niccc2007.filet.pick.PickPreview?> = _pickPreview.asStateFlow()

    /** True while the preview is being built, so the button can say it is working. */
    private val _pickPreviewBusy = MutableStateFlow(false)
    val pickPreviewBusy: StateFlow<Boolean> = _pickPreviewBusy.asStateFlow()

    /** Drop the preview and go back to choosing, without ending the pick. */

    // ── carrying a setup to another phone ──

    /**
     * Non-null while the export chooser is up, carrying how much each section would hold.
     *
     * A flow of its own rather than a `Dialog` case: the counts are read from storage when
     * the chooser opens, and a value type compared for equality is the wrong home for them.
     */
    private val _exportPlan = MutableStateFlow<Map<SettingsTransfer.Section, Int>?>(null)
    val exportPlan: StateFlow<Map<SettingsTransfer.Section, Int>?> = _exportPlan.asStateFlow()

    /** The file being imported, already read and understood, before anything is applied. */
    data class ImportOffer(val from: String, val reading: SettingsTransfer.Reading.Ok)

    private val _importOffer = MutableStateFlow<ImportOffer?>(null)
    val importOffer: StateFlow<ImportOffer?> = _importOffer.asStateFlow()

    fun dismissTransfer() {
        _exportPlan.value = null
        _importOffer.value = null
    }

    /**
     * Count what there is to take, then offer the chooser.
     *
     * Counting first is what lets the chooser grey out a section that would carry nothing.
     * The counts are deliberately different units per section - preferences for the settings
     * groups, entries for the lists - because "3" under Bookmarks meaning three bookmarks is
     * what somebody expects, and a count of preference keys there would be meaningless.
     */
    fun openSettingsExport() {
        val counts = LinkedHashMap<SettingsTransfer.Section, Int>()
        for (section in SettingsTransfer.Section.entries) {
            counts[section] = when (section) {
                SettingsTransfer.Section.REMOTES -> graph.connections.all().size
                SettingsTransfer.Section.BOOKMARKS -> graph.bookmarks.items.value.size
                else -> graph.prefs.snapshot(section.keys).size
            }
        }
        _exportPlan.value = counts
    }

    /**
     * Write the file, into a folder picked in Filet itself.
     *
     * Filet's own picker rather than the system document creator: this is a file manager, so
     * choosing where a file goes is the thing it is for, and the system sheet would hand back
     * a `content://` write that bypasses the VFS every other write in the app goes through.
     */
    fun exportSettings(sections: Set<SettingsTransfer.Section>, withSecrets: Boolean) {
        _exportPlan.value = null
        pick(
            dev.niccc2007.filet.pick.PickSpec("Where to put the settings file", dev.niccc2007.filet.pick.PickSpec.Want.FOLDER, confirmLabel = "Save here"),
        ) pick@{ r ->
            val dest = (r as? dev.niccc2007.filet.pick.PickResult.Picked)?.first ?: return@pick
            viewModelScope.launch {
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US)
                    .format(java.util.Date())
                val name = SettingsTransfer.fileNameFor(stamp)
                val text = SettingsTransfer.build(
                    appVersion = dev.niccc2007.filet.BuildConfig.VERSION_NAME,
                    exportedAt = System.currentTimeMillis(),
                    sections = sections,
                    values = graph.prefs.snapshot(SettingsTransfer.EXPORTED),
                    remotes = if (SettingsTransfer.Section.REMOTES in sections) {
                        graph.connections.exportAll(withSecrets)
                    } else {
                        emptyList()
                    },
                    withSecrets = withSecrets,
                )
                val at = dest.child(name)
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        graph.vfs.openWrite(at).use { it.write(text.toByteArray()) }
                    }
                }.isSuccess
                if (!ok) {
                    toast("Could not write the settings file here.")
                    return@launch
                }
                // The unportable count is reported at the point of writing, not buried in the
                // file: a granted folder that will not survive the trip is something to know
                // now, while the other phone is still being set up.
                val stranded = SettingsTransfer.unportableCount(graph.prefs.getString("bookmarks"))
                toast(
                    if (stranded > 0) "Saved $name · $stranded granted folder(s) cannot travel"
                    else "Saved $name",
                )
                refreshPanes()
            }
        }
    }

    /** Pick a settings file and read it, without applying any of it yet. */
    fun openSettingsImport() {
        pick(
            dev.niccc2007.filet.pick.PickSpec(
                "Pick a settings file",
                dev.niccc2007.filet.pick.PickSpec.Want.FILE,
                dev.niccc2007.filet.pick.PickFilter.Extensions(setOf("json"), "a settings file (.json)"),
            ),
        ) pick@{ r ->
            val path = (r as? dev.niccc2007.filet.pick.PickResult.Picked)?.first ?: return@pick
            viewModelScope.launch {
                val text = runCatching {
                    withContext(Dispatchers.IO) {
                        graph.vfs.openRead(path).use { it.readBytes().decodeToString() }
                    }
                }.getOrNull()
                if (text == null) {
                    toast("Could not read that file.")
                    return@launch
                }
                when (val reading = SettingsTransfer.read(text)) {
                    is SettingsTransfer.Reading.Refused -> toast(reading.why)
                    is SettingsTransfer.Reading.Ok ->
                        _importOffer.value = ImportOffer(path.name, reading)
                }
            }
        }
    }

    /**
     * Apply the chosen sections.
     *
     * Preferences are written straight into the store rather than through each typed setter,
     * because there is no setter for half of these keys. The flows built from the old values are
     * then re-read (`Prefs.reload`), along with the stores that cache a list of their own, so
     * the theme, the layout and the openers change where they stand. Before that re-read existed
     * this ended in "restart Filet for the look and layout".
     */
    fun importSettings(sections: Set<SettingsTransfer.Section>) {
        val offer = _importOffer.value ?: return
        _importOffer.value = null
        viewModelScope.launch {
            var keys = 0
            var remotes = 0
            for (section in sections) {
                if (section == SettingsTransfer.Section.REMOTES) {
                    remotes = graph.connections.importAll(offer.reading.remotes())
                    continue
                }
                keys += graph.prefs.restore(offer.reading.valuesOf(section))
                // The stores that cache their list have to be told. A preference written
                // underneath a `MutableStateFlow` is a value nothing is watching.
                when (section) {
                    SettingsTransfer.Section.BOOKMARKS -> graph.bookmarks.reload()
                    SettingsTransfer.Section.OPENERS -> graph.registry.reload()
                    else -> Unit
                }
            }
            graph.prefs.reload()
            val parts = buildList {
                if (keys > 0) add("$keys setting(s)")
                if (remotes > 0) add("$remotes network drive(s)")
            }
            refreshPanes()
            toast(if (parts.isEmpty()) "Nothing to import." else "Imported " + parts.joinToString(" and "))
        }
    }

    /**
     * Open `filet.json` in Filet's own editor, writing it first if it does not exist yet.
     *
     * Saving it is the whole of applying it: `ConfigSync` hears the file change and applies it
     * where it stands, and says what it skipped and on which line.
     */
    fun editSettingsFile() {
        viewModelScope.launch {
            graph.configSync.ensureSettingsFile()
            val path = VPath.of("local", graph.configSync.settingsPath())
            val node = runCatching { graph.vfs.stat(path) }.getOrNull()
            if (node == null) {
                toast("Could not write filet.json.")
                return@launch
            }
            openWith(node, HandlerId.TEXT, remember = false)
        }
    }


    /**
     * The one way Filet picks a file or a folder for itself. See `PickSpec`.
     *
     * [onResult] is called exactly once, with `Picked` or `Cancelled`, so a caller holding a
     * spinner always hears back. The old dialogue never reported a cancel, which left the cover
     * art buttons disabled after backing out of the chooser.
     */
    fun pick(
        spec: dev.niccc2007.filet.pick.PickSpec,
        preview: (suspend (dev.niccc2007.filet.vfs.VPath) -> dev.niccc2007.filet.pick.PickPreview?)? = null,
        onResult: (dev.niccc2007.filet.pick.PickResult) -> Unit,
    ) {
        // A second pick replaces a live one, and the first caller is told it ended.
        if (_inAppPick.value != null) finishPick(dev.niccc2007.filet.pick.PickResult.Cancelled)
        // Null opens the tab on Home, which every pick does unless its operation belongs to a
        // place; see PickSpec.startIn.
        val start = spec.startIn()
        val s = _state.value
        val returnTo = if (s.focused == Side.A || s.split == SplitMode.OFF) s.activeA else s.activeB
        val added = runCatching { addTab(start) }
        dev.niccc2007.filet.log.FiletLog.i(
            "pick",
            "addTab returned ${added.getOrNull()} of ${_tabs.value.size} tabs" +
                (added.exceptionOrNull()?.let { ", THREW " + it } ?: ""),
        )
        val pane = added.getOrNull()?.let { _tabs.value.getOrNull(it) }
        if (pane == null) {
            dev.niccc2007.filet.log.FiletLog.w("pick", "no pane for the pick tab; giving up")
            onResult(dev.niccc2007.filet.pick.PickResult.Cancelled)
            return
        }
        _pickPreview.value = null
        _pickChosen.value = emptyList()
        pickAnchor = null
        _pickMinimised.value = false
        _pickName.value = spec.name
        _inAppPick.value = LivePick(spec, onResult, pane, returnTo, preview)
        watchPickFolder(pane, spec, preview)
        // Logged because this subsystem has been hard to diagnose from the outside: the
        // symptom of every fault in it is "a tab opened and nothing else happened", which is
        // the same sentence whether the pick never started, started and was replaced, or
        // started and was never drawn. These three lines tell them apart - see `PickNotice`,
        // which logs the drawing end.
        dev.niccc2007.filet.log.FiletLog.i(
            "pick",
            "started ${spec.want} \"${spec.title}\" at ${start?.path ?: "Home"}, tab ${_tabs.value.size - 1}, " +
                "returning to $returnTo",
        )
    }

    /**
     * Keep the ghosts showing what WOULD happen in the folder being looked at, as it changes.
     *
     * ## Why this follows the folder instead of being built once
     *
     * The ghosts used to be produced by the first of two confirm presses, for the folder you
     * were standing in at that moment, and then left alone. Walk somewhere else and the
     * preview stayed behind: the new folder showed nothing, the old folder's ghosts were still
     * the ones on file, and committing extracted into the folder you had walked TO. One
     * decision described in one place and taken in another.
     *
     * So the preview is a function of where you are, recomputed whenever that changes. There
     * is then nothing for a second press to confirm, which is what let the extra step go - and
     * nowhere for the two halves to disagree, because the ghosts and the destination are now
     * the same fact read twice.
     *
     * Rebuilt on the FOLDER, not on the listing: `distinctUntilChanged` over the destination
     * alone means a refresh, a sort, a selection or a rename redraws the ghosts from the
     * existing preview rather than rebuilding it, while actually walking somewhere does
     * rebuild. `collectLatest` drops a build still running when the folder moves again, so
     * flicking through folders costs the last one rather than all of them.
     *
     * The job dies with the pick: it is cancelled in `finishPick`, so a cancelled or committed
     * pick leaves nothing running.
     */
    private fun watchPickFolder(
        pane: PaneController,
        spec: dev.niccc2007.filet.pick.PickSpec,
        preview: (suspend (dev.niccc2007.filet.vfs.VPath) -> dev.niccc2007.filet.pick.PickPreview?)?,
    ) {
        pickWatch?.cancel()
        if (preview == null) return
        pickWatch = viewModelScope.launch {
            pane.state
                .map { spec.resultFor(it.cwd, emptyList(), it.active)?.paths?.firstOrNull() }
                .distinctUntilChanged()
                .collectLatest { where ->
                    if (where == null) {
                        _pickPreview.value = null
                        return@collectLatest
                    }
                    _pickPreviewBusy.value = true
                    val made = runCatching { preview(where) }.getOrNull()
                    _pickPreviewBusy.value = false
                    // Only if this pick is still the live one: a build that finishes after the
                    // pick ended must not put ghosts back on screen.
                    if (_inAppPick.value?.pane === pane) _pickPreview.value = made
                }
        }
    }

    private var pickWatch: kotlinx.coroutines.Job? = null

    /**
     * How a row draws while anything is being picked, or null when nothing is.
     *
     * One rule for both kinds of pick: Filet's own, and another app's through `PickActivity`.
     */
    fun pickRowState(node: VNode): dev.niccc2007.filet.pick.PickSpec.RowState? = activePickSpec()?.rowState(node)

    private fun activePickSpec(): dev.niccc2007.filet.pick.PickSpec? =
        _inAppPick.value?.spec ?: pickRequest?.let { request ->
            dev.niccc2007.filet.pick.PickSpec(
                title = "",
                want = if (request.allowMultiple) dev.niccc2007.filet.pick.PickSpec.Want.FILES else dev.niccc2007.filet.pick.PickSpec.Want.FILE,
                accept = dev.niccc2007.filet.pick.PickFilter.Request(request),
            )
        }

    /**
     * A tap on a file while a pick is live. True when the pick handled it, so it is not opened.
     *
     * A file that does not qualify says why instead of doing nothing; one that does is taken at
     * once for a single in-app pick, and selected otherwise so the bar's Use button confirms.
     */
    fun pickTap(node: VNode): Boolean {
        val spec = activePickSpec() ?: return false
        if (node.isDir) return false
        when (spec.rowState(node)) {
            dev.niccc2007.filet.pick.PickSpec.RowState.DIMMED -> toast(spec.refusal(node))
            dev.niccc2007.filet.pick.PickSpec.RowState.ACCEPTED -> {
                // Into the PICK's list, not a pane's selection - see [_pickChosen]. Public
                // because the surfaces that can show a file are not all panes: a category, the
                // gallery and Home all reach a file the pick would accept, and each of them
                // used to open it instead, because this was private and they could not ask.
                // One tap ends a single in-app pick: the caller asked for one file and has it,
                // so there is nothing left to confirm. An external one still needs its confirm,
                // because the result goes back through the Activity.
                if (spec.want == dev.niccc2007.filet.pick.PickSpec.Want.SAVE) {
                    // A save takes the NAME of a file tapped, as a desktop save dialogue does;
                    // the card then says "Replace" so nothing is saved over unannounced.
                    _pickName.value = node.name
                } else if (_inAppPick.value != null && spec.want == dev.niccc2007.filet.pick.PickSpec.Want.FILE) {
                    finishPick(dev.niccc2007.filet.pick.PickResult.Picked(listOf(node.path)))
                } else {
                    _pickChosen.update { spec.chosenAfter(it, node) }
                    // Out as well as in, as the browser's own anchor moves on a deselect.
                    pickAnchor = node.path
                }
            }
            dev.niccc2007.filet.pick.PickSpec.RowState.FOLDER -> return false
        }
        return true
    }

    /**
     * The last file a pick took or let go of deliberately, which a hold further along extends a
     * range from. On the pick and not on a pane because most taps that fill a pick arrive through
     * [pickTap] from surfaces that are not the pane - see [PickPort].
     */
    private var pickAnchor: VPath? = null

    /**
     * A range, a slide, Select all or Invert during a pick: [add] taken where the pick accepts
     * them, [remove] dropped. A pick for one file, a folder or a save name has no range, so it
     * takes [target] as a tap would, if anything.
     */
    private fun pickRun(add: List<VNode>, remove: Set<VPath>, target: VNode?) {
        val spec = activePickSpec() ?: return
        if (spec.want != dev.niccc2007.filet.pick.PickSpec.Want.FILES) {
            if (target != null && !target.isDir && !isPickChosen(target.path)) pickTap(target)
            return
        }
        _pickChosen.update {
            PickRun.after(it, add, remove) { n -> spec.rowState(n) == dev.niccc2007.filet.pick.PickSpec.RowState.ACCEPTED }
        }
        if (target != null) pickAnchor = target.path
    }

    /**
     * The pick as a pane sees it. A function rather than a property: a pane is made during
     * construction, before a property declared below it would be initialised, and every member
     * this reads is read at the moment of the gesture.
     */
    private fun pickPort(): PickPort = object : PickPort {
        override fun live() = pickingAnything
        override fun chosen() = _pickChosen.value.mapTo(HashSet()) { it.path }
        override fun anchor() = pickAnchor
        override fun take(node: VNode) { pickTap(node) }
        override fun run(add: List<VNode>, remove: Set<VPath>, target: VNode?) = pickRun(add, remove, target)
    }

    /** True while any pick is live, for the chrome that changes while one is. */
    val pickingAnything: Boolean get() = picking || _inAppPick.value != null

    /**
     * The notice strip's confirm and the selection bar's Use button.
     *
     * TWO presses when the caller offers a preview, and that is the point of it. The first
     * builds what the operation would produce and shows it in the destination's own listing;
     * the second commits. A folder pick used to end on one press, with the tab closing and the
     * work happening somewhere the reader was no longer looking - so nothing ever said what
     * was about to appear. See `PickPreview`.
     */
    fun confirmPicking() {
        val live = _inAppPick.value
        if (live == null) {
            onPickConfirm?.invoke()
            return
        }
        // `active` as well as `cwd`: in tree view `cwd` is the volume root and the folder being
        // looked at is the open branch. See PickSpec.folderOf.
        val here = live.pane.state.value
        val result = live.spec.resultFor(here.cwd, _pickChosen.value, here.active, _pickName.value)
        if (result == null) {
            toast(
                when (live.spec.want) {
                    dev.niccc2007.filet.pick.PickSpec.Want.SAVE ->
                        dev.niccc2007.filet.pick.PickName.problem(_pickName.value) ?: "Open a folder first"
                    dev.niccc2007.filet.pick.PickSpec.Want.FOLDER -> "Open a folder first"
                    dev.niccc2007.filet.pick.PickSpec.Want.FILE,
                    dev.niccc2007.filet.pick.PickSpec.Want.FILES -> "Select ${live.spec.accept.what} first"
                },
            )
            return
        }
        // A save into a folder that cannot take one is refused HERE, before the pick ends, so
        // the answer is to walk somewhere else rather than to start again.
        if (live.spec.want == dev.niccc2007.filet.pick.PickSpec.Want.SAVE) {
            writeBlockReason(result.first.parent)?.let { toast(it); return }
        }
        // ONE confirm, always.
        //
        // This used to be two: the first press built the preview and the second committed. It
        // was wrong in both directions. It asked twice for one decision, and - because the
        // destination was recomputed from the pane on the SECOND press while the ghosts had
        // been built for wherever you stood on the FIRST - browsing between the two presses
        // extracted into one folder while showing the contents of another. The ghosts are live
        // now (see `watchPickFolder`), so what is on screen is already the answer and there is
        // nothing left to confirm a second time.
        finishPick(result)
    }

    /** Cancel, from the notice or the back gesture. Nothing is applied. */
    fun cancelPicking() {
        if (_inAppPick.value != null) finishPick(dev.niccc2007.filet.pick.PickResult.Cancelled) else onPickCancel?.invoke()
    }

    private fun finishPick(result: dev.niccc2007.filet.pick.PickResult) {
        val live = _inAppPick.value ?: return
        pickWatch?.cancel()
        pickWatch = null
        _inAppPick.value = null
        _pickChosen.value = emptyList()
        pickAnchor = null
        _pickMinimised.value = false
        _pickName.value = ""
        _pickPreview.value = null
        _pickPreviewBusy.value = false
        // By identity, not by the index it was given: tabs can be closed or moved mid-pick.
        val idx = _tabs.value.indexOf(live.pane)
        if (idx >= 0) {
            closeTab(idx)
        } else {
            // Said out loud rather than shrugged off. A pick tab that outlives its pick is an
            // ordinary-looking tab nobody opened, and the only way anybody finds out is by
            // counting tabs - which is how two of them came to be sitting there.
            dev.niccc2007.filet.log.FiletLog.w("pick", "the pick tab was already gone; nothing closed")
        }
        focusTab(live.returnTo.coerceIn(0, _tabs.value.lastIndex.coerceAtLeast(0)))
        // The selection the pick was started FROM goes too.
        //
        // Bug identified here: a pick is almost always started from a selected row, and the
        // selection was left behind when the pick ended. The pane it belongs to has re-listed
        // by then, so the set holds a path whose row is drawn from a fresh node - the row did
        // not look selected while the set said it was, and the next tap on it therefore
        // toggled it OFF, invisibly. Which presents exactly as reported: that one file cannot
        // be selected, while selecting a different file first makes it work.
        //
        // Cleared whatever the outcome. A cancelled pick means the operation is not happening,
        // and a confirmed one means it already has.
        _tabs.value.forEach { it.clearSelection() }
        live.onResult(result)
    }

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
        runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { work() } }
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
            // Dropping on a pane drops into the folder it is SHOWING, which with tree view
            // on is its open branch and not its volume root.
            is DropTarget.Pane -> _tabs.value.firstOrNull { it.id == target.paneId }?.state?.value?.target
            null -> null
        } ?: return null
        // A volume root's last segment is a number - "/storage/emulated/0" reads as "Move to
        // 0", which names nothing. Use the volume's own label when the destination IS a root.
        val label = _state.value.volumes.firstOrNull { it.node.path == dest }?.label
            ?: dest.name.ifEmpty { dest.scheme }

        val selfDrop = items.any { it.isDir && it.path.contains(dest) }
        if (selfDrop) return DropPlan(dest, label, move = true, refusal = "Cannot drop a folder into itself")
        // Said while hovering, not after the drop. A zip in the tree takes a drop as a folder does,
        // and a mounted archive refuses writes - so without this the copy started and failed.
        writeBlockReason(dest)?.let { return DropPlan(dest, label, move = false, refusal = it) }
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

        // A drop between panes ALWAYS asks.
        //
        // It used to be a setting with an "always do this" tick that stored the answer. Both
        // halves are gone together, deliberately: with the setting removed there would be
        // nowhere to undo a remembered choice, so a single tick would have silently made every
        // future drop a move - which is the one mistake in this app that destroys data.
        run {
            _state.update {
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

    /** Answer the prompt. Asked every time; nothing is remembered. */
    fun answerDrop(action: DropAction) {
        val pending = _state.value.dropAsk ?: return
        _state.update { it.copy(dropAsk = null) }
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

    fun cancelDrag() = _state.update { it.copy(drag = null) }

    // ── chrome ──

    fun openSwitcher(open: Boolean) = _state.update { it.copy(switcherOpen = open) }
    /**
     * The Activity tab, in the focused pane - from the bar, the side bar, a job's notification,
     * and Index now.
     *
     * It was a bottom sheet from the bar and a tab from the side bar, two drawings of one ledger
     * with different rows: the sheet could not open a result or show a job's figures. See
     * `ActivityBody`.
     */
    fun openActivity() {
        focusedPane()?.openSpecial(PaneKind.ACTIVITY, SpecialPanes.Pinned.ACTIVITY.label)
    }

    /** A finished job's result, opened: a folder in the pane, a file in whatever opens it. */
    fun openJobResult(raw: String) {
        val path = runCatching { dev.niccc2007.filet.vfs.VPath.parse(raw) }.getOrNull() ?: return
        viewModelScope.launch {
            val node = runCatching { withContext(Dispatchers.IO) { graph.vfs.stat(path) } }.getOrNull()
            if (node == null) {
                toast("That is not there any more.")
                return@launch
            }
            if (node.isDir) focusedPane()?.jumpTo(path) else openNode(node)
        }
    }

    /** A finished job's result, shown in the folder that holds it. */
    fun showJobInFolder(raw: String) {
        val path = runCatching { dev.niccc2007.filet.vfs.VPath.parse(raw) }.getOrNull() ?: return
        viewModelScope.launch {
            val node = runCatching { withContext(Dispatchers.IO) { graph.vfs.stat(path) } }.getOrNull()
            if (node == null) {
                toast("That is not there any more.")
                return@launch
            }
            revealInFolder(node)
        }
    }
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

    /**
     * Where a new folder or file would land, named, for the dialog to show.
     *
     * Only when it is NOT the folder in the path bar. Repeating the path bar back at
     * somebody is noise; saying "in thumb" when the bar reads /storage/emulated/0 is the
     * one case where the answer is not already on screen.
     */
    fun createTargetName(): String? {
        val pane = focusedPane()?.state?.value ?: return null
        val active = pane.active ?: return null
        return active.name.ifBlank { active.toString() }
    }
    fun askNewFile() = _dialog.update { Dialog.NewFile }
    fun askRename(node: VNode) = _dialog.update { Dialog.Rename(node) }
    fun askCompress() {
        // Asked before the dialog, not after it: refusing once a name has been typed wastes
        // the typing. Only reachable off a folder - a folder pane always has somewhere to write.
        if (selectionFolder() == null) {
            toast("These are in different folders. Pick them from one folder to compress them together.")
            return
        }
        _dialog.update { Dialog.Compress }
    }
    /**
     * Properties, which re-stats a node nobody has measured.
     *
     * The guard is here and not only at the player's door because `size < 0` means "not
     * looked at" wherever it comes from, and this dialog is reachable from the video screen,
     * the music face, the action sheet and the overflow. Opened on a video that was reached
     * through the player it showed **Size (-1 bytes)** and **Modified unknown** for a file
     * sitting in Download.
     *
     * A node that already has a size is shown immediately, with no coroutine and no frame of
     * delay - which is every caller that came from a listing.
     */
    fun showProperties(node: VNode) {
        if (node.isDir || node.size >= 0) {
            _dialog.update { Dialog.Properties(node) }
            return
        }
        viewModelScope.launch {
            val real = runCatching { graph.vfs.stat(node.path) }.getOrNull()
            _dialog.update { Dialog.Properties(real ?: node) }
        }
    }

    fun confirmDelete() {
        val items = focusedPane()?.selectedNodes().orEmpty()
        if (items.isEmpty()) return
        _dialog.update { Dialog.ConfirmDelete(items.size, items.first().name) }
    }

    /**
     * Ask before deleting [items] that are not a selection - a held tile's peek, a viewer's
     * Delete. [then] runs once the delete is confirmed.
     */
    fun confirmDeleteOf(items: List<VNode>, then: (() -> Unit)? = null) {
        if (items.isEmpty()) return
        _dialog.update { Dialog.ConfirmDelete(items.size, items.first().name, items, then) }
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
     * APKs mount read-only, and so does an archive Filet cannot rewrite - RAR, an encrypted or
     * split one, a staged copy of one on a share. Asking here means the UI can drop or grey
     * those actions instead of offering them and turning the refusal into an error, and the
     * archive's own refusal says which of those it is.
     */
    fun writeBlockReason(path: VPath?): String? {
        if (path == null) return "Open a folder first"
        if (graph.vfs.canWrite(path)) return null
        return when (path.scheme) {
            "apk" -> "APKs open read-only — use Decompile to change one"
            "zip" -> graph.vfs.writeRefusal(path) ?: "Archives open read-only — extract it to change what is inside"
            else -> "This location is read-only"
        }
    }

    fun canWriteHere(path: VPath?): Boolean = writeBlockReason(path) == null

    /**
     * The folder the selection's own writes go into: the pane's, or on a list with no folder
     * behind it (a category, New files) the one folder every selected file is in. Null when they
     * are spread over several. See `TreeSelection.commonFolder`.
     */
    fun selectionFolder(): VPath? {
        val pane = focusedPane() ?: return null
        pane.state.value.target?.let { return it }
        return TreeSelection.commonFolder(pane.selectedNodes().map { it.path })
    }

    /**
     * Why the selection cannot be written to, or null.
     *
     * Bug identified here: the selection bar asked [writeBlockReason] about the pane's folder, and
     * a category has none - so every write on a category's selection answered "Open a folder
     * first", about files sitting in an ordinary writable folder. Off a folder, the question is
     * asked of the folders the selected files are actually in.
     */
    fun selectionWriteBlock(): String? {
        val pane = focusedPane() ?: return null
        val s = pane.state.value
        if (s.kind == PaneKind.FOLDER) return writeBlockReason(s.cwd)
        return pane.selectedNodes().mapNotNullTo(LinkedHashSet()) { it.path.parent }
            .firstNotNullOfOrNull { writeBlockReason(it) }
    }

    fun canPaste(): Boolean = _state.value.clips.totalPicked > 0 &&
        focusedPane()?.state?.value?.cwd != null

    // ── things only an Activity can do ──
    //
    // The ViewModel owns the decision, the Activity owns the Intent. Holding an Activity here
    // would leak it across a rotation, and holding only the application Context cannot start
    // a picker or a chooser.

    var onIntent: ((android.content.Intent) -> Unit)? = null
    /** Start an activity FOR A RESULT; the answer comes back through [deliverResult]. */
    var onIntentForResult: ((android.content.Intent) -> Unit)? = null

    // Held here rather than by the Activity, so an answer that arrives after the Activity was
    // recreated - the sheet in front, the phone turned - still finds who asked.
    private var awaitingResult: ((Int, android.content.Intent?) -> Unit)? = null

    /** Start [intent] for a result and hand the answer to [onResult]. One at a time. */
    fun startForResult(intent: android.content.Intent, onResult: (Int, android.content.Intent?) -> Unit) {
        val start = onIntentForResult ?: run { onIntent?.invoke(intent); return }
        awaitingResult = onResult
        start(intent)
    }

    /** The answer to [startForResult]. Called by the Activity. */
    fun deliverResult(code: Int, data: android.content.Intent?) {
        val waiting = awaitingResult ?: return
        awaitingResult = null
        waiting(code, data)
    }

    /**
     * Offer a Wi-Fi network to Android's own "save this network?" sheet, and say what became of
     * it. See `WifiAdd` for why this is started for a result and nothing else works.
     *
     * Where the phone will not show the sheet, or Android could not save the network, the
     * fallback is the one there always was: Wi-Fi settings, with the password already copied.
     */
    fun addWifiNetwork(intent: android.content.Intent, ssid: String, password: String) {
        val started = android.os.SystemClock.elapsedRealtime()
        startForResult(intent) { code, data ->
            val codes = data?.getIntegerArrayListExtra(android.provider.Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)
            val outcome = dev.niccc2007.filet.qr.WifiAdd.outcome(
                ok = code == android.app.Activity.RESULT_OK,
                codes = codes,
                elapsedMs = android.os.SystemClock.elapsedRealtime() - started,
            )
            val name = ssid.ifEmpty { "The network" }
            when (outcome) {
                dev.niccc2007.filet.qr.WifiAdd.Outcome.SAVED -> toast("$name saved. The phone joins it when it is in range.")
                dev.niccc2007.filet.qr.WifiAdd.Outcome.ALREADY -> _dialog.update { Dialog.WifiSaved(name, password) }
                dev.niccc2007.filet.qr.WifiAdd.Outcome.DECLINED -> toast("$name was not added.")
                dev.niccc2007.filet.qr.WifiAdd.Outcome.FAILED -> wifiByHand("Android could not save $name", password)
                dev.niccc2007.filet.qr.WifiAdd.Outcome.NOT_SHOWN -> wifiByHand("This phone will not offer to save it", password)
            }
        }
    }

    /** Wi-Fi settings, with the password on the clipboard first: it is the one thing that screen asks for. */
    private fun wifiByHand(why: String, password: String) {
        if (password.isNotEmpty()) copySecret(password, "$why. The password is copied for Wi-Fi settings.")
        else toast("$why. Pick it in Wi-Fi settings.")
        onIntent?.invoke(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
    }
    /** @param startAt where the picker should open, or null for wherever it likes. */
    var onPickFolder: ((android.net.Uri?) -> Unit)? = null
    var onExit: (() -> Unit)? = null
    var onShare: ((List<VNode>) -> Unit)? = null
    var onCheckUpdates: (() -> Unit)? = null

    // ── back, inside a screen that is itself inside a pane ────────────────────────────────
    //
    // Bug identified here: a pane's content can have its own levels - a playlist opened from
    // the playlist list, a remote opened from the remotes list - and each of those screens
    // declared a `BackHandler` of its own. Two unrelated mechanisms were then deciding one
    // gesture: the browser's single handler, which walks the pane's history, and whichever
    // inner handler happened to be composed. Which of them ran depended on composition order,
    // and when the browser's won, backing out of an open playlist left the Playlists tab
    // entirely and landed on Home - a level the reader never asked to leave.
    //
    // One mechanism instead. An inner screen REGISTERS what back should undo while it is on
    // screen, the browser's handler asks here first, and a claim that reports it consumed the
    // press stops there. Ordering cannot decide it any more because there is only one handler.
    private val innerBacks = ArrayDeque<Pair<Long, () -> Boolean>>()
    private var innerBackIds = 0L

    /**
     * Claim the back gesture while a screen inside a pane has a level to close.
     *
     * @param undo returns true when it consumed the press. Innermost claim wins, which is the
     *   order they were registered in, so a drill-down inside a drill-down still unwinds one
     *   level at a time.
     * @return a token to hand back to [releaseInnerBack].
     */
    fun claimInnerBack(undo: () -> Boolean): Long {
        val id = ++innerBackIds
        innerBacks.addLast(id to undo)
        return id
    }

    fun releaseInnerBack(token: Long) {
        innerBacks.removeAll { it.first == token }
    }

    /** True when an inner screen took the press. Called first by the browser's handler. */
    fun consumeInnerBack(): Boolean {
        // Last registered is innermost. A claim that says it did nothing is passed over rather
        // than swallowing the press, so a screen can register once and decide per press.
        for (i in innerBacks.indices.reversed()) {
            if (innerBacks[i].second()) return true
        }
        return false
    }

    /**
     * Go to what a finished job produced.
     *
     * A folder is opened; a FILE is revealed in its folder with the row selected, because the
     * useful thing after compressing is seeing the archive sitting among its neighbours rather
     * than opening it. Anything that is no longer there says so instead of opening a blank
     * pane - the work finished, and what it made can still have been moved since.
     */
    fun openJobDestination(raw: String) {
        val path = runCatching { dev.niccc2007.filet.vfs.VPath.parse(raw) }.getOrNull() ?: return
        viewModelScope.launch {
            val node = runCatching { withContext(Dispatchers.IO) { graph.vfs.stat(path) } }.getOrNull()
            if (node == null) {
                toast("That is not there any more.")
                return@launch
            }
            if (node.isDir) focusedPane()?.jumpTo(path) else revealInFolder(node)
        }
    }

    /**
     * Open Filet's own folder in a tab.
     *
     * Through the ordinary browser rather than a dedicated screen, because it IS an ordinary
     * folder - which is the entire point of having moved the files there. The one thing worth
     * saying is that the derived half is dotted, so reaching it means hidden entries are on;
     * jumping straight into it sidesteps that rather than leaving somebody looking at a `Filet`
     * folder that appears to have nothing in it.
     */
    fun openFiletFolder(cacheOnly: Boolean = true) {
        val dir = runCatching {
            if (cacheOnly) dev.niccc2007.filet.data.FiletStore.cacheRoot(graph.app)
            else dev.niccc2007.filet.data.FiletStore.root(graph.app)
        }.getOrNull() ?: return
        openJobDestination(graph.files.vpath(dir).toString())
    }

    /**
     * Restart the app so restored settings take effect.
     *
     * A restore copies preference FILES, and `Prefs` built its StateFlows from the old
     * contents when the graph was constructed - so without this the restore appears to do
     * nothing at all, which is the worst of the available outcomes: the data is back, the
     * screen says otherwise, and somebody concludes the feature is broken and stops using it.
     *
     * The process is ended rather than the Activity recreated, because the object graph lives
     * on the Application and would survive a recreate with the stale values still in it.
     */
    fun restartForRestoredSettings() {
        toast("Settings restored. Restarting…")
        val context = graph.app
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            runCatching { if (intent != null) context.startActivity(intent) }
            kotlin.system.exitProcess(0)
        }
    }

    /**
     * Stop everything Filet is running and end the process. Debug builds only.
     *
     * ## Why this is not "press back until it closes"
     *
     * Filet keeps three foreground services - playback, the share server and the indexer - and
     * each exists precisely so that leaving the app does NOT stop it. That is correct, and it
     * means there is no ordinary way to answer "is anything still running", which is the
     * question somebody debugging a battery or a heat complaint actually has.
     *
     * Services first, then the process. Ending the process while a foreground service is up
     * invites Android to restart it, which would quietly disprove the thing this was used to
     * check. The settings are written out before any of it, because this is a hard stop and
     * `onPause` is not going to get its turn.
     */
    fun shutDownEverything() {
        if (!dev.niccc2007.filet.BuildConfig.DEBUG) return
        toast("Stopping everything…")
        val context = graph.app
        runCatching { playback.stop() }
        for (service in listOf(
            dev.niccc2007.filet.playback.PlaybackService::class.java,
            dev.niccc2007.filet.nearby.NearbyService::class.java,
            dev.niccc2007.filet.index.IndexService::class.java,
        )) {
            runCatching { context.stopService(android.content.Intent(context, service)) }
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    dev.niccc2007.filet.data.SettingsHome.snapshot(context)
                }
            }
            // Long enough for the services to actually go, since stopService is a request
            // rather than an instruction.
            kotlinx.coroutines.delay(400)
            kotlin.system.exitProcess(0)
        }
    }

    fun requestExit() { onExit?.invoke() }

    /**
     * Leave the task entirely, not just this screen. Set by the activity hosting the browser.
     *
     * Separate from [onExit], which finishes one activity and leaves the task - and every
     * service - exactly where it was. That difference is the whole reason Shutdown Filet exists.
     */
    var onStopFilet: (() -> Unit)? = null

    /** One of the entries in the side bar's Filet card. */
    fun onPinned(p: SpecialPanes.Pinned) {
        when (p) {
            SpecialPanes.Pinned.ACTIVITY -> openActivity()
            SpecialPanes.Pinned.SETTINGS -> focusedPane()?.openSpecial(PaneKind.SETTINGS, p.label)
            SpecialPanes.Pinned.ABOUT -> focusedPane()?.openSpecial(PaneKind.ABOUT, p.label)
            SpecialPanes.Pinned.STOP -> askStop()
        }
    }

    /** What would end right now, in the sheet's words. */
    fun runningWorkNow(): List<RunningWork.Item> {
        val np = graph.playback.state.value
        val running = graph.ledger.jobs.value.filter { it.running }
        return RunningWork.of(
            playing = if (np.playing) np.current?.title else null,
            crawling = graph.indexCoordinator.isCrawling(),
            jobs = running.map { it.title to RunningWork.leavesPartial(it.title) },
            hosting = runCatching { graph.webdav.state().shares.count { it.running } }.getOrDefault(0),
            nearby = graph.nearby.state.value.running,
        )
    }

    /**
     * Shutdown Filet, asking first unless asked not to.
     *
     * The tick on the sheet turns the asking off, but never when something would be cut off
     * partway. Naming a copy in flight before it is interrupted is not a confirmation that can
     * be given in advance - the one time it matters is the one time nobody remembered there
     * was a copy running.
     */
    fun askStop() {
        val items = runningWorkNow()
        if (!graph.prefs.stopConfirm.value && items.none { it.interrupts }) {
            stopFilet()
        } else {
            _state.update { it.copy(stopAsk = items) }
        }
    }

    fun cancelStop() = _state.update { it.copy(stopAsk = null) }

    /**
     * End everything Filet is running, the way a fresh install would find it.
     *
     * Wound down in order rather than just killed, because the parts with something outside the
     * process have to be told: the player releases its session so another app gets audio focus
     * back cleanly, a share being hosted closes its socket instead of leaving clients hanging,
     * and the services take their notifications down. The scheduled sweep is cancelled as well,
     * so nothing wakes Filet back up while it is meant to be stopped; the next open schedules it
     * again. Then the process ends, which is what stops whatever the list above did not name.
     */
    fun stopFilet() {
        _state.update { it.copy(stopAsk = null) }
        viewModelScope.launch {
            runCatching { graph.playback.stop() }
            runCatching { graph.indexCoordinator.stop() }
            runCatching { dev.niccc2007.filet.index.IndexService.stop(graph.app) }
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { graph.webdav.stop() }
                runCatching { graph.nearby.stop() }
            }
            runCatching { dev.niccc2007.filet.nearby.NearbyService.stop(graph.app) }
            runCatching {
                androidx.work.WorkManager.getInstance(graph.app)
                    .cancelUniqueWork(dev.niccc2007.filet.index.IndexCoordinator.WORK_NAME)
            }
            onStopFilet?.invoke()
            // Long enough for the stop intents to reach the services and their notifications to
            // leave the shade; a kill that races them leaves a notification for a dead process.
            kotlinx.coroutines.delay(STOP_SETTLE_MS)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /**
     * Open the first of [intents] this phone can actually handle.
     *
     * A list rather than one intent, because the per-app Settings pages are not guaranteed:
     * some OEM builds refuse `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` with a package
     * URI and only answer the generic list. A dead button is worse than one extra tap, so
     * every caller passes its fallbacks and this takes the first that resolves.
     */
    fun openFirstThatResolves(intents: List<android.content.Intent>) {
        val pm = graph.app.packageManager
        val usable = intents.firstOrNull { it.resolveActivity(pm) != null }
        if (usable == null) toast("This phone has no settings page for that.")
        else onIntent?.invoke(usable)
    }

    /** Whether every selected item is somewhere this phone's media index can hold. */
    /**
     * One item is selected and it lives somewhere other than the folder on screen: a search hit
     * from below it, a category, New files. Where Open location is a step somewhere.
     */
    fun selectionIsElsewhere(): Boolean {
        val pane = focusedPane() ?: return false
        val items = pane.selectedNodes()
        if (items.size != 1) return false
        val s = pane.state.value
        return s.kind != PaneKind.FOLDER || items[0].path.parent != s.target
    }

    /** Show the one selected item in its folder. */
    fun revealSelection() {
        val node = focusedPane()?.selectedNodes()?.singleOrNull() ?: return
        revealInFolder(node)
    }

    fun selectionIsOnThisDevice(): Boolean {
        val items = focusedPane()?.selectedNodes().orEmpty()
        return items.isNotEmpty() &&
            items.all { dev.niccc2007.filet.media.MediaAnnounce.worthAnnouncing(it.path) }
    }

    /**
     * Hand the selection to the media index by hand.
     *
     * For files that reached the phone some way Filet did not write - restored from a backup,
     * dropped in by another app, pulled over adb. Android stopped background-scanning storage
     * years ago, so such a file is on the disk and in no gallery, and nothing will ever notice
     * it on its own.
     */
    fun announceSelectionToMedia() = withSelection { selection ->
        // Pictures, video and audio only - what the row counted when it said "3 of 5".
        val items = selection.filter {
            val kind = FileKind.of(it)
            kind == FileKind.IMAGE || kind == FileKind.VIDEO || kind == FileKind.AUDIO
        }
        val paths = items.map { it.path }
        val real = dev.niccc2007.filet.media.MediaAnnounce.filesystemPaths(paths)
        if (real.isEmpty()) {
            toast("Nothing here is on this phone's own storage.")
            return@withSelection
        }
        dev.niccc2007.filet.media.MediaAnnounce.announce(graph.app, paths)
        // "Sent to" rather than "added": the scanner is asked, and what it decides to index is
        // its business. Claiming the gallery now has them would be a promise we cannot keep
        // for a file it refuses to recognise.
        toast(if (real.size == 1) "Sent to the media index" else "${real.size} sent to the media index")
        focusedPane()?.clearSelection()
    }

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
            _tabs.value.forEach(::equip)
            val added = roots.lastOrNull { it.path.scheme == "saf" }
            if (added != null) {
                // walking: a folder created here a moment ago; the pane is already its parent.
                focusedPane()?.navigateTo(added.path)
                toast("Added ${added.name}")
            }
        }
    }

    // ── handlers (PLAN.md L2) ──

    /** The routing table itself, for the Settings list of per-extension defaults. */
    val handlers get() = graph.registry

    /** Single tap: whatever the registry says opens this type. */
    fun openWithRegistered(node: VNode, from: List<VNode>? = null, back: OpenRequest? = null) {
        when (val h = graph.registry.handlerFor(node)) {
            HandlerId.EXTERNAL -> openExternally(node)
            HandlerId.ARCHIVE -> mountArchive(node)
            HandlerId.APK -> openApk(node)
            else -> _openRequest.value = OpenRequest(node, h, from?.let { viewerRun(node, it) }, back = back)
        }
    }

    /**
     * The pictures and videos in [from] a viewer opened on [node] can swipe through.
     *
     * Only files that open in Filet's own picture or video viewer: a photo whose type somebody
     * sent to another app is not a page, because reaching it would mean leaving Filet.
     */
    private fun viewerRun(node: VNode, from: List<VNode>): dev.niccc2007.filet.handlers.ViewerRun? =
        dev.niccc2007.filet.handlers.ViewerRun.of(from, node) { n ->
            when (FileKind.of(n)) {
                FileKind.IMAGE -> graph.registry.handlerFor(n) == HandlerId.IMAGE
                FileKind.VIDEO -> graph.registry.handlerFor(n) == HandlerId.MEDIA
                else -> false
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

    /**
     * Back out of the file on screen: to the file it was opened from by a link, or out of the
     * viewers when it was opened from the browser.
     *
     * Bug identified here: a link from one document to another replaced the first, so Back out
     * of the second closed the only screen there was and landed in the folder list, with the
     * document that was being read gone. Each request now carries the one beneath it.
     */
    fun closeHandler() {
        _openRequest.value = _openRequest.value?.back?.copy(returning = true)
    }

    /**
     * Out of every viewer at once, however deep the links went - for a step that leaves them for
     * the browser on purpose: a folder, Reveal in folder, an APK walked into.
     */
    fun leaveHandlers() { _openRequest.value = null }

    /**
     * A link inside the file on screen, followed. A file opens on top of the one showing, so Back
     * comes back to it, at the place it was left; a folder leaves the viewers for the browser,
     * since a folder is not a page.
     *
     * Through [openNode], so a file reached by a link is opened in every sense - recorded among
     * the recently opened, ranked, routed to its own viewer - and the request it makes carries
     * the one it was opened from.
     */
    fun followLink(path: dev.niccc2007.filet.vfs.VPath) {
        viewModelScope.launch {
            val target = withContext(Dispatchers.IO) { runCatching { graph.vfs.stat(path) }.getOrNull() }
            when {
                target == null -> toast("${path.name} is not there.")
                target.isDir -> {
                    leaveHandlers()
                    openNode(target)
                }
                // Bug identified here: the request was given its `back` after `openNode` returned,
                // read off whatever request was showing then. Every file that is not an archive
                // or a bundle has its first bytes read before it is routed (`openUnclaimed`), so
                // its request arrived a moment later, after that check had already found the old
                // one and done nothing. Back out of the linked file then had nothing to return
                // to and landed in the folder list. The one underneath now travels with the open.
                else -> openNode(target, back = _openRequest.value?.copy(returning = false))
            }
        }
    }

    /**
     * A viewer was swiped to another file of its run.
     *
     * The request follows, so whatever asks "what is open" hears the file on screen rather than
     * the one first tapped. Not recorded in Recently opened: passing a photo on the way to
     * another is not opening it.
     */
    fun viewerMoved(run: dev.niccc2007.filet.handlers.ViewerRun) {
        val r = _openRequest.value ?: return
        val node = run.current
        val handler = if (FileKind.of(node) == FileKind.VIDEO) HandlerId.MEDIA else HandlerId.IMAGE
        _openRequest.value = r.copy(node = node, handler = handler, run = run)
    }

    /**
     * Open the full player on whatever is playing now.
     *
     * What the strip does when it is tapped. It goes through `OpenRequest` like every other
     * viewer rather than having a route of its own, so the player opens, closes and stacks
     * exactly as it does when a track is tapped in a folder - and the back gesture needs no
     * special case.
     */
    /**
     * Give a video being played as audio its picture back.
     *
     * Only the flag is flipped. The media host is already composed around this node, so
     * clearing it swaps the music face for the video screen in place - and that screen reads
     * the background player's position and takes the file over, so the picture resumes where
     * the sound got to rather than from the beginning.
     */
    fun openVideoOfTrack(track: dev.niccc2007.filet.playback.Track?) {
        graph.playback.watchAsVideo()
        val node = nodeOfTrack(track) ?: return
        // Also issued as an open, for the case where the music face was reached from the
        // strip on some other screen: the flag alone changes nothing if the host is not
        // already showing this file.
        _openRequest.value = OpenRequest(node, HandlerId.MEDIA)
    }

    fun maximisePlayer() {
        val node = nodeOfTrack(graph.playback.state.value.current) ?: return
        _openRequest.value = OpenRequest(node, HandlerId.MEDIA)
    }

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
        // A sound or picture Android has no decoder for - WMA, TIFF - comes here by default
        // rather than to a player that could only fail on it. Saying why, before asking where,
        // is the difference between "Filet refuses this" and "Filet sent this somewhere it will
        // play". Not on the explicit "another app" action, which is already that choice.
        if (!force) {
            dev.niccc2007.filet.vfs.types.FileTypes.notPlayableHere(ext, android.os.Build.VERSION.SDK_INT)?.let { toast(it) }
        }
        // A tap with no remembered app gets the one complete Open with - every Filet viewer
        // AND every app - not an apps-only list. `force` is the explicit "another app" action.
        if (force) askWhichApp(node) else openChooser(node)
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

    /**
     * A local file holding [node]'s bytes, for the readers that cannot take a stream.
     *
     * The work is `LocalCopy`'s: PLAN.md R3 keeps `java.io.File` out of this layer, and
     * `check-r3.mjs` refused the first version of this, which did the copying here. It was
     * right to - "give me a local copy of this" is a storage operation, and it belongs beside
     * the staging it shares a cache directory with.
     *
     * The REFUSAL is still said here, because a toast is this layer's job and a size limit the
     * other layer enforces is a null that somebody has to explain.
     */
    val localCopy: dev.niccc2007.filet.ops.LocalCopy get() = graph.localCopy

    /**
     * Say why a reader got nothing, which is this layer's half of the job.
     *
     * The first version of this returned the file, and `check-r3.mjs` refused it a second
     * time - correctly, and for a subtler reason than the first. Doing the copying here was
     * the obvious violation; naming `java.io.File` in a signature is the quieter one, and it
     * is the one that matters, because a handle that can be passed up a layer WILL be, and
     * then the rule is about where the import is rather than where the filesystem is touched.
     *
     * So the reader asks `LocalCopy` directly and this only explains a failure, which needs a
     * size and a name and no file at all.
     */
    fun sayWhyNoCopy(node: VNode) {
        if (node.size > dev.niccc2007.filet.ops.LocalCopy.MAX_BYTES) {
            toast("${node.name} is too large to open this way.")
        } else {
            toast("${node.name} could not be opened from where it is.")
        }
    }

    suspend fun readText(node: VNode): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
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
            // No Stop on a save, on purpose: it writes over the file in place, and a save stopped
            // half way is the file cut short. It takes as long as writing one text file does.
            val id = ledger.start("Saving into", pending.archiveName, kind = dev.niccc2007.filet.jobs.JobKind.SAVE)
            runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val bytes = pending.text.toByteArray(Charsets.UTF_8)
                    ArchiveEdits.replace(pending.node.path, graph.stagedArchiveDirs()) { java.io.ByteArrayInputStream(bytes) }
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
        pick(
            dev.niccc2007.filet.pick.PickSpec(
                "Save ${pending.node.name} to",
                dev.niccc2007.filet.pick.PickSpec.Want.FOLDER,
                confirmLabel = "Save here",
                // Beside the archive it came out of: the one pick that starts in a place.
                start = dev.niccc2007.filet.pick.PickSpec.besideArchive(pending.node.path),
                verb = "save",
                subject = pending.node.name,
            ),
        ) pick@{ r ->
            val folder = (r as? dev.niccc2007.filet.pick.PickResult.Picked)?.first ?: return@pick
            viewModelScope.launch {
                val id = ledger.start("Saving", pending.node.name, kind = dev.niccc2007.filet.jobs.JobKind.SAVE)
                runCatching {
                    val taken = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        runCatching { graph.vfs.list(folder).map { it.name }.toHashSet() }
                            .getOrDefault(hashSetOf())
                    }
                    // Never silently over something already there. The whole reason somebody
                    // picks this option is that they did not want to overwrite anything.
                    val name = freeName(pending.node.name, taken)
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
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
                kotlinx.coroutines.withContext(Dispatchers.IO) {
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

    /**
     * Whether the one selected file is a format whose metadata Filet knows anything about.
     *
     * Read-only formats count: the screen can still say what it found and why it cannot write it
     * back, which is more useful than the action not being there.
     */
    fun selectionHasMetadata(): Boolean {
        val items = focusedPane()?.selectedNodes().orEmpty()
        val one = items.singleOrNull() ?: return false
        if (one.isDir) return false
        return dev.niccc2007.filet.metadata.MetadataSupport.forExtension(one.extension).isNotEmpty()
    }

    /** Open the metadata screen on the one selected file. */
    fun metadataForSelection() {
        val one = focusedPane()?.selectedNodes().orEmpty().singleOrNull() ?: return
        openWith(one, dev.niccc2007.filet.handlers.HandlerId.METADATA, remember = false)
    }

    /** Everything this file's container already carries. */
    suspend fun readMetadata(node: VNode): List<Pair<String, String>> =
        graph.metadata.read(node.path)

    /** What this file's metadata is written as, by its bytes rather than its name. */
    suspend fun metadataKind(node: VNode): dev.niccc2007.filet.metadata.MetadataStore.Kind =
        graph.metadata.kindOf(node.path)

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
        /** True when the attached picture was replaced or removed; [cover] is then the new one. */
        coverChanged: Boolean = false,
        /** The picture to attach, or null to remove it. Only read when [coverChanged]. */
        cover: dev.niccc2007.filet.metadata.Cover? = null,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
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
                    var refusal: String? = null
                    var failure: String? = null
                    // The picture is one more edit, written after the backup like the fields.
                    val edits: List<suspend () -> dev.niccc2007.filet.metadata.MetadataStore.Result> =
                        fields.map { (key, value) -> suspend { graph.metadata.put(node.path, key, value) } } +
                            (if (coverChanged) listOf(suspend { graph.metadata.putCover(node.path, cover) }) else emptyList())
                    for (edit in edits) {
                        when (val r = edit()) {
                            is dev.niccc2007.filet.metadata.MetadataStore.Result.Ok -> written++
                            // A refusal is the format's answer and worth saying out loud. The
                            // old version counted it as "nothing changed", so a field the
                            // container cannot hold looked exactly like a field that was
                            // already correct - which is how somebody types a value four times.
                            is dev.niccc2007.filet.metadata.MetadataStore.Result.Refused ->
                                if (refusal == null) refusal = r.why
                            is dev.niccc2007.filet.metadata.MetadataStore.Result.Failed ->
                                if (failure == null) failure = r.why
                            dev.niccc2007.filet.metadata.MetadataStore.Result.Unchanged -> Unit
                        }
                    }
                    Triple(written, refusal, failure)
                }
            }
            onDone()
            result
                .onSuccess { (written, refusal, failure) ->
                    dev.niccc2007.filet.log.FiletLog.i(
                        "metadata",
                        "wrote " + written + " field(s) to " + node.path +
                            (if (refusal != null) " (refused: " + refusal + ")" else "") +
                            (if (failure != null) " (failed: " + failure + ")" else ""),
                    )
                    toast(
                        when {
                            failure != null -> failure
                            refusal != null && written == 0 -> refusal
                            refusal != null -> "Saved " + written + ", and one field was refused"
                            written == 0 -> "Nothing changed"
                            else -> "Saved " + written + " field" + if (written == 1) "" else "s"
                        },
                    )
                    refreshPanes()
                }
                .onFailure {
                    dev.niccc2007.filet.log.FiletLog.e("metadata", "could not write ${node.path}", it)
                    toast("Could not write metadata: " + dev.niccc2007.filet.ops.FileOperations.readable(it))
                }
        }
    }

    /** The attached picture this file carries, if its container has one. */
    suspend fun readCover(node: VNode): dev.niccc2007.filet.metadata.Cover? =
        graph.metadata.cover(node.path)

    /**
     * Pick an image with Filet itself for the attached picture, and check it - but write
     * nothing: the picture waits for Save like every other edit (see [writeMetadata]).
     *
     * The size check is here rather than in the store because it is about taste, not safety: a
     * cover is carried inside every copy of the file forever, and a 12 MP photo pasted in as
     * album art makes a four-minute song bigger than the album.
     *
     * @param onPicked the picture, or null when nothing was picked or it was refused.
     */
    fun pickCover(node: VNode, onPicked: (dev.niccc2007.filet.metadata.Cover?) -> Unit) {
        pick(
            dev.niccc2007.filet.pick.PickSpec(
                "Choose a picture for " + node.name,
                dev.niccc2007.filet.pick.PickSpec.Want.FILE,
                dev.niccc2007.filet.pick.PickFilter.PICTURES,
                subject = node.name,
            ),
        ) pick@{ r ->
            val picked = (r as? dev.niccc2007.filet.pick.PickResult.Picked)?.first
            if (picked == null) {
                // Backed out: nothing attached, and the screen's buttons come back.
                onPicked(null)
                return@pick
            }
            viewModelScope.launch {
                val bytes = runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        graph.vfs.openRead(picked).use { it.readBytes() }
                    }
                }.getOrNull()
                if (bytes == null) {
                    toast("Could not read that picture.")
                    onPicked(null)
                    return@launch
                }
                if (dev.niccc2007.filet.metadata.Cover.sniff(bytes) == null) {
                    toast("That is not an image Filet recognises, so it was not attached.")
                    onPicked(null)
                    return@launch
                }
                if (bytes.size > MAX_COVER_BYTES) {
                    toast(
                        "That picture is " + humanSize(bytes.size.toLong()) + ". Cover art is " +
                            "carried inside the file itself, so Filet keeps it under " +
                            humanSize(MAX_COVER_BYTES.toLong()) + ".",
                    )
                    onPicked(null)
                    return@launch
                }
                onPicked(dev.niccc2007.filet.metadata.Cover.of(bytes))
            }
        }
    }

    /** Save the attached picture as a file of its own, beside the one it came out of. */
    fun extractCover(node: VNode, onDone: () -> Unit) {
        viewModelScope.launch {
            val written = graph.metadata.extractCover(node.path)
            if (written == null) {
                toast("There is no picture in this file to extract.")
            } else {
                dev.niccc2007.filet.log.FiletLog.i("metadata", "extracted cover to " + written)
                toast("Saved " + written.name)
                refreshPanes()
            }
            onDone()
        }
    }

    /** One sentence for whatever the store answered, so every cover action reports the same way. */
    private fun report(result: dev.niccc2007.filet.metadata.MetadataStore.Result, done: String) {
        when (result) {
            is dev.niccc2007.filet.metadata.MetadataStore.Result.Ok -> {
                dev.niccc2007.filet.log.FiletLog.i("metadata", done.lowercase())
                toast(done)
                refreshPanes()
            }
            dev.niccc2007.filet.metadata.MetadataStore.Result.Unchanged -> toast("Nothing changed")
            is dev.niccc2007.filet.metadata.MetadataStore.Result.Refused -> toast(result.why)
            is dev.niccc2007.filet.metadata.MetadataStore.Result.Failed -> toast(result.why)
        }
    }

    /**
     * Replace [node]'s bytes without a moment where a failure can truncate it: written beside it,
     * read back and checked, then swapped in. See `SafeReplace`. Throws on failure, original intact.
     */
    suspend fun replaceVerified(node: VNode, bytes: ByteArray, verify: (ByteArray) -> Unit) {
        val folder = node.path.parent ?: throw IllegalStateException("There is nowhere to save this.")
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            dev.niccc2007.filet.docs.SafeReplace.replace(
                dev.niccc2007.filet.docs.VfsSiblings(graph.vfs, folder), node.name, bytes, verify,
            )
        }
        refreshPanes()
    }

    fun saveText(node: VNode, text: String, onSaved: () -> Unit) {
        // A file inside an archive cannot simply be written to - the provider refuses, and it
        // is right to. This is the prompt instead: update the archive, or put the
        // edited file somewhere else.
        if (ArchiveEdits.isMember(node.path)) {
            viewModelScope.launch {
                val refusal = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching { ArchiveEdits.refusalFor(node.path, graph.stagedArchiveDirs()) }.getOrElse { it.message }
                }
                val cost = kotlinx.coroutines.withContext(Dispatchers.IO) {
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
            val id = ledger.start("Saving", node.name, kind = dev.niccc2007.filet.jobs.JobKind.SAVE)
            runCatching {
                val bytes = text.toByteArray(Charsets.UTF_8)
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val folder = node.path.parent
                    // Bug identified here: this was `openWrite(node.path)`, which TRUNCATES
                    // before it writes. A failure part-way - storage full, a share dropping, the
                    // process killed - left the file empty or half written, and the thing
                    // destroyed was the document somebody had just been editing. Saving must
                    // not be able to lose the file being saved, so it goes beside, is read
                    // back, and is swapped in. Same path the office editor uses.
                    if (folder == null) {
                        graph.vfs.openWrite(node.path).use { it.write(bytes) }
                    } else {
                        dev.niccc2007.filet.docs.SafeReplace.replace(
                            dev.niccc2007.filet.docs.VfsSiblings(graph.vfs, folder),
                            node.name,
                            bytes,
                        ) { back ->
                            // Cheap and worth it: a short write is the failure this guards
                            // against, and length is what proves it did not happen.
                            require(back.size == bytes.size) {
                                "The saved copy is " + back.size + " bytes, not " + bytes.size + "."
                            }
                        }
                    }
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
     * Ask where to save a new picture made from [node], and under what name.
     *
     * The picker's save mode (`PickSpec.Want.SAVE`): it opens in [node]'s folder offering the
     * name [editedName] would have picked, and the answer is a path that may already exist - the
     * card has said "Replace" before the confirm if it does.
     *
     * @param extension what the new file will be. It decides the name offered, which files in
     *   the folder can be tapped to take their name, and the extension a typed name is given.
     */
    fun chooseSaveAs(node: VNode, extension: String, onChosen: (VPath) -> Unit) {
        val folder = node.path.parent
        viewModelScope.launch {
            val taken = if (folder == null) HashSet() else kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { graph.vfs.list(folder).map { it.name }.toHashSet() }.getOrDefault(HashSet())
            }
            pick(
                dev.niccc2007.filet.pick.PickSpec(
                    title = "Choose where to save a copy of " + node.name,
                    want = dev.niccc2007.filet.pick.PickSpec.Want.SAVE,
                    accept = dev.niccc2007.filet.pick.PickFilter.Extensions(
                        dev.niccc2007.filet.pick.PickName.sameKind(extension),
                        "a .$extension file",
                    ),
                    name = editedName(node.name, extension) { it in taken },
                    saveExtension = extension,
                    verb = "save",
                    subject = "a copy of " + node.name,
                ),
            ) { result ->
                if (result is dev.niccc2007.filet.pick.PickResult.Picked) onChosen(result.first)
            }
        }
    }

    /** [path] with a name ending in what the bytes are, for a new file; an original keeps its name. */
    private fun named(path: VPath, extension: String, isNew: Boolean): VPath {
        if (!isNew) return path
        val parent = path.parent ?: return path
        return parent.child(dev.niccc2007.filet.pick.PickName.fitted(path.name, extension))
    }

    /**
     * Put a picture's bytes at [target]. A new file is written straight; one that exists is
     * replaced through the written-beside, read-back, swapped-in path (`SafeReplace`), so a
     * failure at any step leaves it as it was.
     */
    private suspend fun writePicture(target: VPath, bytes: ByteArray, verify: (ByteArray) -> Unit = {}) {
        val folder = target.parent ?: error("There is nowhere to save this.")
        writeBlockReason(folder)?.let { error(it) }
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (graph.vfs.stat(target) != null) {
                dev.niccc2007.filet.docs.SafeReplace.replace(
                    dev.niccc2007.filet.docs.VfsSiblings(graph.vfs, folder), target.name, bytes, verify,
                )
            } else {
                graph.vfs.openWrite(target).use { it.write(bytes) }
            }
        }
    }

    /**
     * After a picture is written: its old thumbnails dropped, the gallery told, the panes re-read.
     *
     * The gallery has to be told by hand. A picture written straight through the filesystem is
     * seen by no file operation, so nothing else announces it - which is how an edited copy came
     * to be on the disk and in no gallery at all, and a turn saved over a photo still showed the
     * old way up there.
     */
    private fun pictureWritten(target: VPath) {
        dev.niccc2007.filet.media.Thumbnails.forget(target)
        dev.niccc2007.filet.media.MediaAnnounce.announce(graph.app, listOf(target))
        refreshPanes()
    }

    /**
     * Save the turn the picture viewer is showing: over the original, or to [to].
     *
     * Read from the FILE rather than from the screen, so a turn never costs resolution - the
     * viewer's copy is scaled down to fit the phone. `PictureTurn` decides what each format
     * allows; for a JPEG it is one number in the file, every pixel untouched.
     *
     * @param quarters clockwise, 1 to 3.
     * @param to where a new file goes, from [chooseSaveAs]; null replaces the original.
     * @param onDone whether it was saved, so the viewer can stop waiting either way.
     */
    fun saveTurnedPicture(node: VNode, quarters: Int, to: VPath?, onDone: (Boolean) -> Unit) {
        var target = to ?: node.path
        viewModelScope.launch {
            val id = ledger.start("Saving", target.name, kind = dev.niccc2007.filet.jobs.JobKind.SAVE)
            runCatching {
                // The whole file is held twice at the peak, the original and the turned copy.
                if (node.size > TURN_MAX_BYTES) error("This picture is too large to turn here.")
                val bytes = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    graph.vfs.openRead(node.path).use { it.readBytes() }
                }
                val turned = kotlinx.coroutines.withContext(Dispatchers.Default) {
                    dev.niccc2007.filet.handlers.PictureTurn.turn(bytes, node.extension, quarters, replacing = to == null)
                }
                // Named for what was made, should that differ from what the name was picked for.
                target = named(target, turned.extension, to != null)
                writePicture(target, turned.bytes) { back ->
                    // The read-back already proved the bytes; this proves they say what was
                    // meant, which is the part a bug here would get wrong.
                    val want = turned.orientation ?: return@writePicture
                    check(dev.niccc2007.filet.media.JpegOrientation.read(back) == want) {
                        "The saved copy does not say the turn it was given."
                    }
                }
            }.onSuccess {
                ledger.finish(id)
                pictureWritten(target)
                toast(if (to == null) "Turned and saved" else "Saved as " + target.name)
                onDone(true)
            }.onFailure {
                val why = dev.niccc2007.filet.ops.FileOperations.readable(it)
                ledger.fail(id, why)
                toast("Could not save: $why")
                onDone(false)
            }
        }
    }

    /**
     * Save an edit: over the original, or to [to].
     *
     * Over the original only when that was the choice made, in so many words, from the editor's
     * Save menu - and through [writePicture], so the file being saved over cannot be lost to a
     * save that fails half way. A new file goes wherever the picker said, under the name typed
     * there. The caller hands over encoded bytes rather than a bitmap, which keeps the format
     * decision with the editor that knows whether alpha matters.
     *
     * A JPEG carries the original's EXIF block across (`JpegOrientation.carried`): without it,
     * every edited photo lost when and where it was taken, and a gallery sorting by date moved it
     * to today.
     *
     * @param extension what the bytes actually are, which is not always what the original was:
     *   a `.heic` edited and re-encoded comes back as JPEG and can only be saved as new.
     * @param to where a new file goes, from [chooseSaveAs]; null replaces the original.
     * @param onSaved the name that was actually used, so the editor can say it out loud.
     */
    fun saveEditedImage(node: VNode, bytes: ByteArray, extension: String, to: VPath?, onSaved: (String) -> Unit) {
        val target = named(to ?: node.path, extension, to != null)
        viewModelScope.launch {
            val id = ledger.start("Saving", target.name, kind = dev.niccc2007.filet.jobs.JobKind.SAVE)
            runCatching {
                if (to == null && !dev.niccc2007.filet.pick.PickName.sameKind(node.extension).contains(extension.lowercase())) {
                    error("An edited " + node.extension.uppercase() + " can only be saved as a new file.")
                }
                val withDetails = if (dev.niccc2007.filet.vfs.types.FileTypes.isJpeg(extension)) {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        runCatching {
                            val head = graph.vfs.openRead(node.path).use { dev.niccc2007.filet.media.JpegOrientation.head(it) }
                            dev.niccc2007.filet.media.JpegOrientation.carried(head, bytes)
                        }.getOrNull()
                    } ?: bytes
                } else {
                    bytes
                }
                writePicture(target, withDetails)
            }.onSuccess {
                ledger.finish(id)
                pictureWritten(target)
                toast(if (to == null) "Saved" else "Saved as " + target.name)
                onSaved(target.name)
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
     * The player, which is in the graph and not here.
     *
     * Exposed so the player screen and the strip can reach it the way they reach everything
     * else. It is NOT owned by this view model, and that is the point: a view model is scoped
     * to what is on screen, and the whole of the reported fault was playback dying with the
     * thing that was on screen.
     */
    val playback: dev.niccc2007.filet.playback.Playback get() = graph.playback

    /**
     * The node a queue entry came from, or null when it has since moved or gone.
     *
     * A [dev.niccc2007.filet.playback.Track] carries an id rather than a node, so anything
     * wanting to act on the file - Share, Properties, Open with - comes back through here. Null
     * is an ordinary answer: a track can be playing out of the cache after its original was
     * deleted, and the player keeps working.
     */
    fun nodeOfTrack(track: dev.niccc2007.filet.playback.Track?): VNode? {
        val id = track?.id ?: return null
        val path = runCatching { dev.niccc2007.filet.vfs.VPath.parse(id) }.getOrNull() ?: return null
        // Rebuilt from the path rather than looked up in whatever the pane is listing. The
        // player outlives the folder it was opened from, so by the time Share is pressed the
        // pane may be showing something else entirely - and a lookup against the visible list
        // would answer null for a track that is playing perfectly well.
        return VNode(path = path, isDir = false, size = -1L, mtime = 0L)
    }

    /**
     * Act on the file behind a queue entry, having actually looked at it first.
     *
     * [nodeOfTrack] rebuilds a node from the track's path alone, which is right for
     * identifying the file and wrong for describing it: the node it returns carries
     * `size = -1` and `mtime = 0` because nothing has stat'd it. Properties then printed
     * **Size (-1 bytes)** and **Modified unknown** for a file sitting on the phone, from
     * both the music face and the video screen.
     *
     * So the stat happens here, off the main thread, and the action runs with whatever came
     * back. The unstat'd node is still the fallback and still an ordinary answer - a track
     * can be playing out of the cache after its original was deleted, and Share on it should
     * do something rather than nothing.
     */
    private fun withTrackNode(track: dev.niccc2007.filet.playback.Track?, fallback: VNode?, act: (VNode) -> Unit) {
        val rough = nodeOfTrack(track) ?: fallback ?: return
        viewModelScope.launch {
            val real = runCatching { graph.vfs.stat(rough.path) }.getOrNull()
            act(real ?: rough)
        }
    }

    /** Properties for what is playing, with a real size and date on it. */
    fun propertiesOfTrack(track: dev.niccc2007.filet.playback.Track?, fallback: VNode?) =
        withTrackNode(track, fallback) { showProperties(it) }

    /** Share what is playing. */
    fun shareTrack(track: dev.niccc2007.filet.playback.Track?, fallback: VNode?) =
        withTrackNode(track, fallback) { share(it) }

    /** Hand what is playing to another app. */
    fun openTrackExternally(track: dev.niccc2007.filet.playback.Track?, fallback: VNode?) =
        withTrackNode(track, fallback) { openExternally(it, force = true) }

    /**
     * A `Uri` the media players can seek in.
     *
     * The work is in `TrackMedia`, because the player in the graph needs the same answer and
     * must not reach a view model to get it. This stays as the call every screen already makes.
     */
    suspend fun localUriFor(node: VNode): android.net.Uri? =
        dev.niccc2007.filet.playback.TrackMedia.uriFor(graph.vfs, graph.files, node)

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
    /**
     * @param edit true when another app sent ACTION_EDIT rather than ACTION_VIEW - it wants
     *   the file changed, so the editor opens regardless of which handler the type is
     *   registered to. Honouring the registered handler here would answer "edit this" with a
     *   read-only preview, which is a worse answer than refusing.
     */
    fun openIncoming(
        uri: android.net.Uri,
        mime: String?,
        edit: Boolean = false,
        /**
         * Which of Filet's viewers the other app asked for, or null for the usual routing.
         *
         * Filet publishes its viewers to the system under their own names - "Filet Video
         * Player", "Filet PDF Reader" - as activity aliases of the one activity, so the alias
         * that was launched IS the answer to "which viewer did they want". See
         * `open/InboundViewers.kt`.
         */
        viewer: HandlerId? = null,
    ) {
        viewModelScope.launch {
            val node = incomingNode(uri) ?: run { toast("Filet cannot open that link."); return@launch }
            when {
                node.isDir -> focusedPane()?.jumpTo(node.path)
                // Never remembered: opening one file from outside Filet must not rewrite what
                // Filet itself uses for that type from then on.
                viewer != null -> openWith(node, viewer, remember = false)
                edit -> openWith(node, HandlerId.TEXT, remember = false)
                else -> openWithRegistered(node)
            }
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
            loadClipboard(nodes, PendingOp.COPY)
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
        kotlinx.coroutines.withContext(Dispatchers.IO) {
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
            val id = graph.ledger.start(
                "Extracting ${app.label}",
                cancel = coroutineContext[kotlinx.coroutines.Job],
                kind = dev.niccc2007.filet.jobs.JobKind.APK,
                to = root.toString(),
                runAgain = { extractInstalled(app) },
            )
            runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
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
            pane.jumpTo(folder)
        }
    }

    fun openExtractedApksFolder() {
        val pane = focusedPane() ?: return
        viewModelScope.launch {
            val folder = extractedApksFolder
            runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { ensureFolder(folder) } }
            if (runCatching { graph.vfs.stat(folder) }.getOrNull() == null) {
                toast("Nothing has been extracted yet.")
                return@launch
            }
            pane.jumpTo(folder)
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
            pane.jumpTo(folder)
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

    /**
     * Open something from a recent-files list, folder or file.
     *
     * Here rather than at the two call sites, because the drawer and the Home feed must not
     * disagree about what tapping the same row does.
     */
    fun openRecent(entry: dev.niccc2007.filet.data.RecentEntry) {
        if (entry.isDir) {
            focusedPane()?.jumpTo(entry.path)
        } else {
            openHomeEntry(VNode(entry.path, false, -1, entry.at))
        }
    }

    /** Whether Trawl is on the phone. A row that opens a store page is an advertisement. */
    fun trawlInstalled(): Boolean {
        val pm = graph.app.packageManager
        return TRAWL_PACKAGES.any { runCatching { pm.getLaunchIntentForPackage(it) }.getOrNull() != null }
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
     * The check that nobody asked for, when the app comes to the front.
     *
     * Everything about whether to run it and whether to say anything is in `UpdatePrompt.kt`;
     * this is the wiring. Failures are silent by design - a background check that could not
     * reach GitHub is not news, and a toast about it would be the app interrupting to report
     * that nothing happened.
     *
     * ## Three faults, reported as "the update popup never appears"
     *
     * 1. **It only ever posted a notification.** There was no popup. The sheet existed and
     *    the only way to it was tapping that notification, so on a phone where Filet's
     *    notifications are off or buried, a new version was never mentioned at all. It now
     *    opens the sheet as well - which is what "tell me when there is an update" means.
     * 2. **It only ran when the Activity was CREATED.** Filet holds a foreground service
     *    while music plays or a share is hosted, so its process survives; going
     *    back to it from recents resumes the same Activity and the one-shot `DisposableEffect`
     *    never fired again. It is called on every resume now, with the interval doing the
     *    throttling it was always meant to do.
     * 3. **A failed check burned the whole twelve-hour window.** `lastCheckedAt` was stored
     *    whether or not GitHub answered, so one minute without a connection meant no check
     *    until the next day. Only a check that actually reached GitHub is persisted; a failed
     *    one is remembered for this session only, so it retries on the next resume without
     *    hammering anything.
     */
    fun checkForUpdatesQuietly() {
        val now = System.currentTimeMillis()
        val cold = !askedThisTask
        askedThisTask = true
        dev.niccc2007.filet.update.UpdateGate.beforeNetwork(
            dev.niccc2007.filet.BuildConfig.UPDATER_ENABLED,
            reminderState(),
            now,
            cold,
            lastUpdateAttempt,
        )?.let { dev.niccc2007.filet.update.UpdateGate.log(it); return }
        lastUpdateAttempt = now
        viewModelScope.launch {
            val result = Updater.latest()
            if (result.isSuccess) {
                storeReminder(reminderState().copy(lastCheckedAt = System.currentTimeMillis()))
            }
            val verdict = dev.niccc2007.filet.update.UpdateGate.afterNetwork(
                result, Updater.installed, reminderState(), now, LAUNCH_ID,
            )
            dev.niccc2007.filet.update.UpdateGate.log(verdict)
            val release =
                (verdict as? dev.niccc2007.filet.update.UpdateVerdict.Ask)?.release ?: return@launch
            pendingRelease = release
            UpdateNotifier.notify(graph.app, release)
            // Through the one queue, so it cannot land on top of the first-run welcome.
            dev.niccc2007.filet.notice.Notices.offer(dev.niccc2007.filet.notice.NoticeKind.UPDATE)
        }
    }

    /**
     * Open the sheet for the queued release. False when the sheet is busy or nothing is held.
     *
     * Separate from the check so the notice queue decides WHEN, and this decides only whether
     * it is still possible - a download in progress or a sheet somebody opened themselves must
     * never be replaced by one that arrived on a timer.
     */
    fun showQueuedUpdate(): Boolean {
        val r = pendingRelease ?: return false
        if (_update.value != null) return false
        _update.value = UpdateState.Available(r)
        return true
    }

    /**
     * When GitHub was last ASKED, as opposed to when it last answered.
     *
     * In memory rather than in the preferences on purpose: it exists to stop a retry loop
     * inside one session, not to postpone tomorrow's check because today's failed.
     */
    private var lastUpdateAttempt = 0L

    /**
     * Whether this task has asked yet.
     *
     * A ViewModel lives as long as its Activity, and an Activity is destroyed when the task is
     * swiped away - so a false here IS a cold launch, which is the distinction the process's
     * own start time cannot make. See `shouldCheck`.
     */
    private var askedThisTask = false

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

    init {
        followVisibleFolders()
    }

    /**
     * Tell the watcher which folders are on screen, and re-list a pane when its folder changes.
     *
     * The folders the visible panes show are the watcher's whole list now. It used to be the
     * tracked folders, which fed the New files list; that list reads the media index instead
     * (see `HomeFeed`) and the watcher's remaining job is keeping the folder somebody is looking
     * at current when another app writes into it.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun followVisibleFolders() {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(_state, _tabs) { s, tabs ->
                listOfNotNull(
                    tabs.getOrNull(s.activeA),
                    if (s.split != SplitMode.OFF) tabs.getOrNull(s.activeB) else null,
                )
            }
                .flatMapLatest { panes ->
                    if (panes.isEmpty()) {
                        kotlinx.coroutines.flow.flowOf(emptySet())
                    } else {
                        kotlinx.coroutines.flow.combine(panes.map { it.state }) { states ->
                            states.mapNotNull { if (it.kind == PaneKind.FOLDER) it.cwd else null }.toSet()
                        }
                    }
                }
                .distinctUntilChanged()
                .collect { graph.watchedFolders.value = it }
        }
        graph.onFolderChanged = { dir ->
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                val s = _state.value
                val tabs = _tabs.value
                val visible = listOfNotNull(
                    tabs.getOrNull(s.activeA),
                    if (s.split != SplitMode.OFF) tabs.getOrNull(s.activeB) else null,
                )
                for (p in visible) if (p.state.value.cwd == dir) p.relist()
            }
        }
    }

    fun onIndexToggled(enabled: Boolean) {
        val roots = _state.value.volumes.map { it.node.path }
        graph.indexCoordinator.setEnabled(enabled, roots)
        (graph.index as? dev.niccc2007.filet.index.SqliteIndex)?.setEnabled(enabled)
        toast(if (enabled) "Index on" else "Index off — search still works, just slower")
    }

    /** Smart or Manual. The setting is already written; this makes the schedule match it. */
    fun onIndexAutoToggled(auto: Boolean) {
        graph.indexCoordinator.setAuto(auto)
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
        // Narrowed BEFORE the empty check, so a phone holding only shares and root mounts is told
        // there is nothing to index rather than shown a run that silently does nothing.
        val roots = dev.niccc2007.filet.index.Indexable.volumes(
            _state.value.volumes.map { it.node.path },
            graph.indexCoordinator.indexedShares(),
        )
        if (roots.isEmpty()) { toast("No volume to index."); return }
        if (graph.indexCoordinator.isCrawling()) { toast("Already indexing."); return }
        // Run again on the activity row is this function again: a person asking, which is the
        // only way the unbounded crawl may start (tools/check-crawl.mjs).
        graph.indexCoordinator.crawlFully(roots, runAgain = { reindexNow() })
        openActivity()
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
            val jobId = ledger.start(
                "Running script", script.name,
                cancel = coroutineContext[kotlinx.coroutines.Job],
                kind = dev.niccc2007.filet.jobs.JobKind.SCRIPT,
                runAgain = { runScript(script) },
            )
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
        leaveHandlers()
        val os = graph.vfs.osPath(node.path)
        if (os == null) { toast("Browse APKs from local storage."); return }
        // walking: descending into the APK that was tapped.
        focusedPane()?.navigateTo(dev.niccc2007.filet.vfs.provider.ApkProvider.mount(os))
    }

    /** Open one dex as a tree of smali. The provider does the disassembly on demand. */
    fun browseDex(node: VNode, dexEntry: String) {
        leaveHandlers()
        val os = graph.vfs.osPath(node.path)
        if (os == null) { toast("Browse APKs from local storage."); return }
        val root = dev.niccc2007.filet.vfs.provider.ApkProvider.mount(os)
        // walking: a step deeper inside the APK already mounted in this pane.
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
            val jobId = ledger.start(
                "Decompiling", node.name,
                cancel = coroutineContext[kotlinx.coroutines.Job],
                kind = dev.niccc2007.filet.jobs.JobKind.APK,
                from = node.path.toString(),
                runAgain = { decompileApk(node) },
            )
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
                focusedPane()?.jumpTo(where)
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
            val jobId = ledger.start(
                "Rebuilding", node.name,
                cancel = coroutineContext[kotlinx.coroutines.Job],
                kind = dev.niccc2007.filet.jobs.JobKind.APK,
                from = node.path.toString(),
                runAgain = { rebuildApk(node) },
            )
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
            val jobId = ledger.start(
                "Signing", node.name,
                cancel = coroutineContext[kotlinx.coroutines.Job],
                kind = dev.niccc2007.filet.jobs.JobKind.APK,
                from = node.path.toString(),
                runAgain = { signApk(node) },
            )
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
        val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
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
        // Widened deliberately. `from:` is a field rather than a reach now (SEARCH.md §13.4),
        // so without this the question would be asked of whichever folder the pane happened to
        // be standing in - and "where did this come from" is never a question about one folder.
        pane.setReach(ReachLadder.wholePhone(pane.reachDrives))
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
        // walking: a mount root, so there is no trunk above it to open.
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
        pane.jumpTo(path)
        // Stamped on OPEN, which is the intent. A share reached for and failed is still the
        // one most likely to be reached for again - that is when somebody is fixing it.
        graph.connections.save(c.copy(lastUsedAt = System.currentTimeMillis()))
        bumpRemotes()
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
        kotlinx.coroutines.withContext(Dispatchers.IO) {
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

    /**
     * [copyText] for a password, a two-factor secret or an activation code: marked sensitive, so
     * Android 13 and later leave it out of the clipboard preview and the keyboard's suggestions.
     */
    fun copySecret(text: String, message: String) {
        val cm = graph.app.getSystemService(android.content.ClipboardManager::class.java)
        val clip = android.content.ClipData.newPlainText("filet", text)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm?.setPrimaryClip(clip)
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
            kotlinx.coroutines.withContext(Dispatchers.IO) {
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
            kotlinx.coroutines.withContext(Dispatchers.IO) {
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
            kotlinx.coroutines.withContext(Dispatchers.IO) { graph.webdav.stop() }
            dev.niccc2007.filet.nearby.NearbyService.start(graph.app)
            toast("Stopped hosting")
        }
    }

    /** Bring up every share that asked to open with Filet. Called once, at startup. */
    fun startAutoHosting() {
        if (dev.niccc2007.filet.webdav.DavShares.autoStarting(graph.webdav.shares).isEmpty()) return
        viewModelScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
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
            kotlinx.coroutines.withContext(Dispatchers.IO) {
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

    /**
     * A share editor to reopen, carrying what was typed into it before a folder was picked.
     *
     * Bug identified here: the picker opens in a tab of its own, so the editor closed for it and
     * its draft lived nowhere. The pick was then applied to the stored share by id - and a new
     * share is not stored until Save, so the lookup found nothing and the folder was dropped
     * along with the name and code typed so far. The draft is held here instead, where leaving
     * the Remotes tab cannot reach it, and the editor comes back with it either way.
     */
    private val _shareDraft = MutableStateFlow<dev.niccc2007.filet.webdav.DavShare?>(null)
    val shareDraft: StateFlow<dev.niccc2007.filet.webdav.DavShare?> = _shareDraft.asStateFlow()

    fun shareDraftTaken() {
        _shareDraft.value = null
    }

    /**
     * Pick the folder for a share being edited. Opens on Home, as every pick does.
     *
     * Nothing is saved: the folder joins the draft, and Save in the editor stores it with the
     * rest. A cancelled pick reopens the editor unchanged.
     */
    fun pickShareFolder(draft: dev.niccc2007.filet.webdav.DavShare) {
        pick(
            dev.niccc2007.filet.pick.PickSpec(
                "Share this folder",
                dev.niccc2007.filet.pick.PickSpec.Want.FOLDER,
                confirmLabel = "Share this one",
                verb = "share",
            ),
        ) { r ->
            val dest = (r as? dev.niccc2007.filet.pick.PickResult.Picked)?.first
            _shareDraft.value = if (dest == null) draft else draft.copy(customRoot = dest.toString())
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
    fun connectionFromHost(h: dev.niccc2007.filet.webdav.DavHost):
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
    fun sightingOf(h: dev.niccc2007.filet.webdav.DavHost) =
        dev.niccc2007.filet.vfs.provider.net.Sightings.Seen(h.deviceId, h.host, h.port)

    /** The saved remote a discovered host already is, or null when it is something new. */
    fun savedRemoteFor(h: dev.niccc2007.filet.webdav.DavHost) =
        graph.connections.savedFor(sightingOf(h))

    /**
     * Attach a discovered host to a remote that was saved by hand, on the person's word.
     *
     * Needed because automatic learning has one gap it cannot close by itself: an entry with no
     * device id, whose address has ALREADY changed, has nothing left to match on. Every entry
     * made before ids existed is one network change away from that, and this is the one tap that
     * ends it - afterwards the entry carries the id and keeps up on its own.
     */
    fun linkRemote(connectionId: String, h: dev.niccc2007.filet.webdav.DavHost) {
        val c = graph.connections.byId(connectionId) ?: return
        if (graph.connections.link(connectionId, sightingOf(h))) {
            bumpRemotes()
            toast("${c.label.ifEmpty { c.host }} now knows ${h.host}")
        } else {
            toast("${c.label.ifEmpty { c.host }} already knew that address")
        }
    }

    /**
     * What happened when each remote was last dialled, by connection id.
     *
     * In memory rather than stored. A verdict is a fact about the network a moment ago, and a
     * remembered one is a claim about a share that may well have been unplugged since - which
     * is the thing the screen's own comment refused to make before there was a probe at all.
     */
    private val _remoteTests = MutableStateFlow(
        emptyMap<String, dev.niccc2007.filet.remotes.RemoteProbe.Verdict>(),
    )
    val remoteTests: StateFlow<Map<String, dev.niccc2007.filet.remotes.RemoteProbe.Verdict>> =
        _remoteTests.asStateFlow()

    /** Which remotes are being dialled right now, so their row can say so. */
    private val _remoteTesting = MutableStateFlow(emptySet<String>())
    val remoteTesting: StateFlow<Set<String>> = _remoteTesting.asStateFlow()

    /**
     * Dial a saved remote and say what happened.
     *
     * ## Why this lists rather than opening a socket
     *
     * A bare TCP connect would prove something is listening on the port and nothing else - not
     * that the credentials work, not that the share name exists, not that the protocol version
     * is one both ends speak. Those are the failures people actually have, and all three
     * present as a successful connect. Listing the remote's own root is the cheapest operation
     * that exercises the whole path, and it is the same call that a tap on the row makes - so a
     * green verdict means the thing somebody is about to do will work.
     *
     * The classification is `RemoteProbe`, which is pure and tested. Nothing here has an
     * opinion about what an exception means.
     */
    fun testConnection(c: dev.niccc2007.filet.vfs.provider.net.NetConnection) {
        if (c.id in _remoteTesting.value) return
        _remoteTesting.value = _remoteTesting.value + c.id
        viewModelScope.launch {
            val parts = listOf(c.share, c.startPath)
                .map { it.trim('/') }
                .filter { it.isNotEmpty() }
            val path = VPath.of(
                c.protocol.scheme,
                "/" + c.id + if (parts.isEmpty()) "" else "/" + parts.joinToString("/"),
            )
            // Every address this remote has, because that is what opening it would try.
            //
            // Bug identified here: the backstop below was one address's timeout plus a margin,
            // while `NetDial` walks the saved addresses in SEQUENCE - so a remote with three
            // addresses needed up to three timeouts to finish and the test killed it after one.
            // The second and third address were never dialled, and the verdict handed back was
            // the first address's timeout. Testing a remote whose primary address had moved
            // therefore always failed, while OPENING the same remote worked, because opening it
            // was never under this clock.
            //
            // The budget is per address, so the test gets exactly the chance the real thing
            // does. Capped, because a long timeout times the addresses allowed by `Endpoints`
            // would otherwise let one row spin for a quarter of an hour.
            val tried = graph.connections.candidates(c)
            val budgetMs = (tried.size.coerceAtLeast(1) * (c.timeoutSeconds.coerceIn(5, 60) + 5) * 1000L)
                .coerceAtMost(180_000L)
            val verdict = runCatching {
                withContext(Dispatchers.IO) {
                    // The provider's own timeout should fire first; this is the backstop for a
                    // server that accepts a socket and then says nothing.
                    kotlinx.coroutines.withTimeout(budgetMs) {
                        graph.vfs.list(path) { }
                    }
                }
            }
            val result = verdict.fold(
                onSuccess = {
                    // WHICH address answered, not just that one did. `NetDial` writes the
                    // address that worked to `lastGood`, so after a successful dial this names
                    // it - and on a remote with several addresses that is the one thing the
                    // test is being asked.
                    val good = graph.connections.byId(c.id)?.lastGood?.takeIf { it.isNotBlank() }
                    when {
                        good == null -> dev.niccc2007.filet.remotes.RemoteProbe.WORKING
                        tried.size <= 1 -> dev.niccc2007.filet.remotes.RemoteProbe.WORKING
                        else -> dev.niccc2007.filet.remotes.RemoteProbe.WORKING.copy(detail = "at $good")
                    }
                },
                onFailure = {
                    val v = dev.niccc2007.filet.remotes.RemoteProbe.verdictFor(it)
                    // A remote with one address that failed says why. One with several says how
                    // many were tried, because "unreachable" on a three-address remote reads as
                    // the other two having been skipped - which is exactly what used to happen.
                    if (tried.size <= 1) v
                    else v.copy(
                        detail = listOf(v.detail, "${tried.size} addresses tried")
                            .filter { d -> d.isNotBlank() }
                            .joinToString(" · "),
                    )
                },
            )
            _remoteTests.value = _remoteTests.value + (c.id to result)
            _remoteTesting.value = _remoteTesting.value - c.id
        }
    }

    /** Copy a saved remote, credentials and all, as a new entry waiting to be edited. */
    fun duplicateConnection(
        c: dev.niccc2007.filet.vfs.provider.net.NetConnection,
    ): dev.niccc2007.filet.vfs.provider.net.NetConnection =
        c.copy(
            id = graph.connections.newId(),
            label = (c.label.ifEmpty { c.host }) + " copy",
            // Not carried: the new entry has not been opened, and a learned address belongs to
            // the device that taught it rather than to a copy of its settings.
            lastUsedAt = 0L,
            lastGood = "",
        )

    fun forgetConnection(c: dev.niccc2007.filet.vfs.provider.net.NetConnection) {
        graph.connections.delete(c.id)
        _remoteTests.value = _remoteTests.value - c.id
        bumpRemotes()
        toast((c.label.ifEmpty { c.host }) + " forgotten")
    }

    /** Finding servers that are not Filet. See `ServerScan`. */
    val serverScan get() = graph.serverScan

    /**
     * Start an entry from something the scan found.
     *
     * The protocol, the host and the port are known, so the form opens with only the sign-in
     * left - which is the whole difference between discovery and a note of an IP address.
     */
    fun connectionFromFound(
        found: dev.niccc2007.filet.remotes.ServerScan.Found,
    ): dev.niccc2007.filet.vfs.provider.net.NetConnection =
        dev.niccc2007.filet.remotes.RemoteKinds.blank(
            // The existing `kindFor` takes a whole connection, so it is given a bare one
            // carrying only the protocol and port - which is all that function reads, and
            // reproducing its table here would be a second copy to keep in step.
            dev.niccc2007.filet.remotes.RemoteKinds.kindFor(
                dev.niccc2007.filet.vfs.provider.net.NetConnection(
                    id = "",
                    protocol = found.protocol,
                    label = "",
                    host = found.host,
                    port = found.port,
                    user = "",
                    password = "",
                ),
            ),
            graph.connections.newId(),
        ).copy(
            protocol = found.protocol,
            host = found.host,
            port = found.port,
            // The announced name where there is one, and the address where there is not. Not
            // left blank: the label is what the storage card shows, and a blank one there was
            // a real fault once already.
            label = found.name.takeIf { it != found.host }.orEmpty(),
            // Where a Filet host said its share starts. Only Filet publishes this, so for
            // everything else it is empty and the root is what gets mounted - which is the
            // right assumption for a NAS and the wrong one for a Filet share, whose base path
            // is `/a/<code>` and was otherwise left for somebody to work out from an address.
            share = found.path.orEmpty(),
            // Carried so the entry can be recognised again at a different address. Same reason
            // as `connectionFromHost`: identity cannot be the one thing guaranteed to change.
            deviceId = found.deviceId,
        )

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
        // walking: the share's own root.
        focusedPane()?.navigateTo(VPath.of(c.protocol.scheme, "/" + c.id + "/"))
    }

    /**
     * Root is asked for only when the user opts in, because asking spawns a shell and
     * triggers the superuser prompt - which must never happen at startup.
     */
    fun enableRoot() {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(Dispatchers.IO) {
                dev.niccc2007.filet.vfs.provider.RootProvider.isAvailable()
            }
            if (ok) {
                toast("Root granted")
                // walking: the filesystem root is itself the trunk.
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
     * Home lists remembered things - recent files, files added to the phone - and the filesystem
     * moves on without telling it. A row whose file was deleted used to sit there for ever
     * and do nothing when tapped. Now the tap is also the check: if it is gone, say so once
     * and take the row out.
     */
    fun openHomeEntry(node: VNode, from: List<VNode>? = null) {
        viewModelScope.launch {
            val live = runCatching { graph.vfs.stat(node.path) }.getOrNull()
            if (live == null) {
                forgetHomeEntry(node)
                toast("That file is gone — removed from the list.")
                return@launch
            }
            // A pick gets first refusal, exactly as it does in a folder listing.
            //
            // Bug identified here: this is the one funnel Home, a category list and the
            // category gallery all tap through, and it went straight to opening the file. So
            // while a pick was live, tapping a photo in the Photos tab OPENED the photo - the
            // card never lit up, nothing was added to the pick, and the confirm then said to
            // select a picture first. A file the pick cannot use now says why, which is the
            // same answer a dimmed row gives, instead of quietly doing something else.
            if (!live.isDir && pickTap(live)) return@launch
            if (live.isDir) focusedPane()?.jumpTo(live.path) else openNode(live, from)
        }
    }

    /** Drop a Home row from wherever it is remembered. */
    fun forgetHomeEntry(node: VNode) {
        graph.recents.remove(node.path)
        graph.bookmarks.remove(node.path)
        graph.home.refresh()
        _state.update { it.copy(revision = it.revision + 1) }
    }

    /**
     * Leave the folder a New files row came from out of the feed from now on.
     *
     * The FOLDER, not the file. A row in New files is a file somebody did not put there - a
     * thumbnail cache, a chat app's media directory, a game's asset dump - and hiding one of
     * forty thousand of them is no use; the folder is the unit the noise arrives in.
     *
     * Stored as a path prefix and applied inside the query, so blocking takes effect on the
     * next page rather than filtering rows after they have been counted. See
     * `CategoryQuery.newSelection`.
     */
    fun blockHomeFolder(node: VNode) {
        val folder = (if (node.isDir) node.path else node.path.parent) ?: run {
            toast("That has no folder to block.")
            return
        }
        val path = folder.path
        // A root would hide the whole device, and the preference is read back by code that
        // cannot tell a deliberate choice from a mistake, so it is refused here rather than
        // defended everywhere downstream.
        if (path.isBlank() || path == "/" || path.trimEnd('/').count { it == '/' } == 0) {
            toast("A whole volume cannot be blocked.")
            return
        }
        prefs.blockFolder(path)
        graph.home.refresh()
        toast("Hidden from New files: ${folder.name}")
    }

    /**
     * Pick a folder, or files, for New files to leave out from now on.
     *
     * Through the in-app picker, so the place is pointed at rather than typed. A file is held
     * in the same list as the folders: the feed's test is "this path, or anything under it",
     * which for a file is just the file.
     */
    fun pickNewFilesExclusion(folder: Boolean) {
        pick(
            dev.niccc2007.filet.pick.PickSpec(
                if (folder) "Hide a folder from New files" else "Hide files from New files",
                if (folder) dev.niccc2007.filet.pick.PickSpec.Want.FOLDER else dev.niccc2007.filet.pick.PickSpec.Want.FILES,
                confirmLabel = if (folder) "Hide this folder" else "Hide these",
                verb = "hide",
            ),
        ) { r ->
            val picked = (r as? dev.niccc2007.filet.pick.PickResult.Picked)?.paths.orEmpty()
            // New files reads this device's media index; a share's path names nothing in it.
            val local = picked.filter { it.scheme == "local" }
            var added = 0
            for (p in local) {
                val path = p.path
                // A volume root would hide the whole device. Same refusal as a held row.
                if (path.isBlank() || path == "/" || path.trimEnd('/').count { it == '/' } == 0) continue
                if (path.trimEnd('/') == "/storage/emulated/0") continue
                prefs.blockFolder(path)
                added++
            }
            if (added > 0) {
                graph.home.refresh()
                val what = if (added == 1) local.first().name else "$added items"
                toast("Hidden from New files: " + what)
            } else if (picked.isNotEmpty()) {
                toast(if (local.isEmpty()) "New files only lists this device" else "A whole volume cannot be hidden.")
            }
        }
    }

    /** Let a blocked folder back into the feed. */
    fun unblockHomeFolder(path: String) {
        prefs.unblockFolder(path)
        graph.home.refresh()
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
            pane.jumpTo(parent)
            // Asked for, not timed. The old version waited a fixed 160ms and then selected,
            // which is too short on a big folder and a stall on a small one - and it never
            // scrolled at all, so the file was selected somewhere off screen. The listing now
            // carries the request and acts when it actually has rows.
            pane.revealOnly(node)
        }
    }

    // ── playlists ──
    //
    // A playlist is a kept queue and Favourites is one of them; see `Playlists` for why there
    // is one concept here rather than a music tab. Everything below is a thin call onto that
    // store - the rules live there, where they can be tested.

    val playlists get() = graph.playlists
    val trackCards get() = graph.trackCards

    /** The file picker the "Add to playlist" dialogue is open for, or null. */
    private val _playlistFor = MutableStateFlow<List<VNode>>(emptyList())
    val playlistFor: StateFlow<List<VNode>> = _playlistFor.asStateFlow()

    fun dismissPlaylistPicker() { _playlistFor.value = emptyList() }

    /**
     * Offer to put these in a playlist.
     *
     * Only the playable ones. Asked for explicitly to cover video as well as audio, which it
     * does by construction - a playlist holds paths and the player already knows a video
     * from a song - but a PDF in a playlist is a row that can never do anything, so the
     * filter happens here rather than being discovered at play time.
     */
    fun askAddToPlaylist(nodes: List<VNode>) {
        val playable = nodes.filter {
            val kind = FileKind.of(it)
            kind == FileKind.AUDIO || kind == FileKind.VIDEO
        }
        if (playable.isEmpty()) {
            toast("Nothing here is a track Filet can play.")
            return
        }
        _playlistFor.value = playable
    }

    /**
     * Add the track that is PLAYING, rather than the file the player was opened on.
     *
     * Reported: add a song to a playlist, press next, add the new one - and it says it is
     * already there. It was. The player screen is opened with a `VNode`, and the overflow
     * was still handing that same node over three tracks later, so the second add was the
     * first song again and the dialog correctly refused it.
     *
     * The queue is the authority on what is playing, and it holds paths. So this resolves
     * one, which also means a track that has been deleted under the player says so instead
     * of being added as a row pointing at nothing.
     */
    /**
     * Open the folder a track lives in, from its path.
     *
     * A playlist row holds a path and not a node - a playlist is a list of places, and
     * statting every one of them to draw a list would be a directory read per row. So the
     * stat happens here, once, when somebody asks to be taken there.
     */
    fun revealPathInFolder(path: dev.niccc2007.filet.vfs.VPath) {
        viewModelScope.launch {
            val node = runCatching {
                withContext(Dispatchers.IO) { graph.vfs.stat(path) }
            }.getOrNull()
            if (node == null) {
                toast("That track is not there any more.")
                return@launch
            }
            revealInFolder(node)
        }
    }

    fun askAddToPlaylistPath(path: dev.niccc2007.filet.vfs.VPath) {
        viewModelScope.launch {
            val node = runCatching {
                withContext(Dispatchers.IO) { graph.vfs.stat(path) }
            }.getOrNull()
            if (node == null) {
                toast("That track is not there any more.")
                return@launch
            }
            askAddToPlaylist(listOf(node))
        }
    }

    /**
     * The same dialogue for a set of paths, which is what a list of tracks has.
     *
     * Statted first and in parallel, because the dialogue takes nodes and a selection of forty
     * on a share is forty round trips - sequentially that is a sheet that opens seconds after
     * the tap. Anything that will not stat is dropped with a count, rather than the whole
     * selection failing because one file was deleted.
     */
    fun askAddToPlaylistPaths(paths: List<dev.niccc2007.filet.vfs.VPath>) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val nodes = withContext(Dispatchers.IO) {
                coroutineScope {
                    paths
                        .map { p -> async { runCatching { graph.vfs.stat(p) }.getOrNull() } }
                        .awaitAll()
                        .filterNotNull()
                }
            }
            if (nodes.isEmpty()) {
                toast("None of those are still there.")
                return@launch
            }
            if (nodes.size < paths.size) toast("${paths.size - nodes.size} are no longer there")
            askAddToPlaylist(nodes)
        }
    }

    /**
     * Choose another folder for All Music to scan, through the app's own chooser.
     *
     * Stored relative to the primary volume when it is under it, absolute when it is not - so a
     * folder on an SD card keeps its own path and a folder on this phone survives the volume
     * being mounted somewhere else. See `AllMusicFilter.prefixes`.
     */
    fun pickAllMusicFolder() {
        pick(
            dev.niccc2007.filet.pick.PickSpec(
                "Scan this folder for music",
                dev.niccc2007.filet.pick.PickSpec.Want.FOLDER,
                confirmLabel = "Scan this folder",
            ),
        ) { r ->
            val path = (r as? dev.niccc2007.filet.pick.PickResult.Picked)?.paths?.firstOrNull()
                ?: return@pick
            // Stored as the absolute path it is. Making it relative to the primary volume
            // would need `Environment` up here, which is a storage API in a view model - and
            // `AllMusicFilter.prefixes` already takes an absolute folder as it finds it, so
            // the relativising bought nothing but a layering violation.
            graph.prefs.addAllMusicFolder(path.path)
            toast("All Music now scans ${path.name}")
        }
    }

    /**
     * Measure the beat of every audio file in the selection, in the background.
     *
     * Resolving a path to a playable URI is a per-file read, so it happens off the main thread
     * and the selection is cleared immediately - the work is listed in Activity from the moment
     * it starts, which is where somebody looks for it, and keeping the selection highlighted
     * for however long the resolving took read as the button not having fired.
     */
    /** One track, from a playlist row's menu. */
    fun makeBeatMapForPath(path: dev.niccc2007.filet.vfs.VPath) {
        viewModelScope.launch {
            val node = runCatching { withContext(Dispatchers.IO) { graph.vfs.stat(path) } }.getOrNull()
            if (node == null) {
                toast("That is not there any more.")
                return@launch
            }
            val uri = runCatching {
                dev.niccc2007.filet.playback.TrackMedia.uriFor(graph.vfs, graph.files, node)
            }.getOrNull()
            if (uri == null) {
                toast("That could not be read.")
                return@launch
            }
            if (graph.beatMaker.isStored(uri)) {
                toast("Already measured.")
                return@launch
            }
            graph.beatMaker.make(uri, node.name)
            toast("Measuring the beat. See Activity.")
        }
    }

    /**
     * Every track in a playlist, as one job.
     *
     * The whole point of doing this ahead of time: the pulse is ready for a list somebody is
     * about to put on, rather than measured one track at a time while they wait for each. The
     * tracks already measured are skipped without being read, so running it twice on a list
     * that has grown by two costs two tracks rather than the whole list.
     */
    fun makeBeatMapsForPlaylist(id: String) {
        viewModelScope.launch {
            val list = graph.playlists.get(id)
            if (list == null) {
                toast("That list is gone.")
                return@launch
            }
            val paths = list.tracks
            if (paths.isEmpty()) {
                toast("Nothing in that list.")
                return@launch
            }
            val tracks = withContext(Dispatchers.IO) {
                paths.mapNotNull { p ->
                    val node = runCatching { graph.vfs.stat(p) }.getOrNull() ?: return@mapNotNull null
                    if (FileKind.of(node) != FileKind.AUDIO) return@mapNotNull null
                    val uri = runCatching {
                        dev.niccc2007.filet.playback.TrackMedia.uriFor(graph.vfs, graph.files, node)
                    }.getOrNull() ?: return@mapNotNull null
                    uri to node.name
                }
            }
            if (tracks.isEmpty()) {
                toast("Nothing in that list could be read.")
                return@launch
            }
            graph.beatMaker.makeAll(tracks, list.name)
            toast("Measuring ${tracks.size} tracks. See Activity.")
        }
    }

    fun makeBeatMapsForSelection() {
        val items = focusedPane()?.selectedNodes().orEmpty()
            .filter { FileKind.of(it) == FileKind.AUDIO }
        if (items.isEmpty()) return
        focusedPane()?.clearSelection()
        viewModelScope.launch {
            val tracks = items.mapNotNull { node ->
                val uri = runCatching {
                    dev.niccc2007.filet.playback.TrackMedia.uriFor(graph.vfs, graph.files, node)
                }.getOrNull() ?: return@mapNotNull null
                uri to node.name
            }
            if (tracks.isEmpty()) {
                toast("Those could not be read.")
                return@launch
            }
            if (tracks.size == 1) {
                graph.beatMaker.make(tracks[0].first, tracks[0].second)
            } else {
                graph.beatMaker.makeAll(tracks, "${tracks.size} tracks")
            }
            toast(
                if (tracks.size == 1) "Measuring the beat. See Activity."
                else "Measuring ${tracks.size} tracks. See Activity.",
            )
        }
    }

    fun addToPlaylistSelection() {
        // The tracks only. The row is offered for a mixed selection and labelled "11 of 12";
        // adding all twelve would put the screenshot in the playlist as a row that never plays.
        val items = focusedPane()?.selectedNodes().orEmpty().filter {
            val kind = FileKind.of(it)
            kind == FileKind.AUDIO || kind == FileKind.VIDEO
        }
        if (items.isEmpty()) return
        focusedPane()?.clearSelection()
        askAddToPlaylist(items)
    }

    /**
     * Put these in a playlist, or take them out of it.
     *
     * The dialogue STAYS OPEN. It is a set of tick boxes now rather than a list of buttons, and
     * the whole point of boxes is filing one track into three lists without the dialogue closing
     * under the first tap. It is dismissed by Done, by the scrim, or by the close in the corner.
     *
     * Adding is idempotent - `Playlist.plusAll` skips what is already there - so a mixed
     * selection needs no de-duplication here.
     */
    fun setPlaylistMembership(id: String, nodes: List<VNode>, member: Boolean) {
        val list = graph.playlists.get(id) ?: return
        val paths = nodes.map { it.path }
        if (member) {
            val before = list.size
            graph.playlists.add(id, paths)
            val added = (graph.playlists.get(id)?.size ?: before) - before
            toast(
                when {
                    added == 0 -> "Already in ${list.name}"
                    added == nodes.size && added == 1 -> "Added to ${list.name}"
                    else -> "$added added to ${list.name}"
                },
            )
        } else {
            for (p in paths) graph.playlists.remove(id, p)
            val gone = list.size - (graph.playlists.get(id)?.size ?: list.size)
            toast(
                when {
                    gone <= 0 -> "Not in ${list.name}"
                    gone == 1 -> "Taken out of ${list.name}"
                    else -> "$gone taken out of ${list.name}"
                },
            )
        }
    }

    fun newPlaylistWith(name: String, nodes: List<VNode>) {
        val id = graph.playlists.create(name, nodes.map { it.path })
        _playlistFor.value = emptyList()
        toast("${graph.playlists.get(id)?.name ?: name} created")
    }

    /** The heart. One press, and the toast says which way it went. */
    fun toggleFavourite(path: dev.niccc2007.filet.vfs.VPath) {
        val on = graph.playlists.toggleFavourite(path)
        toast(if (on) "Added to Favourites" else "Removed from Favourites")
    }

    /**
     * Play a kept list, from [from].
     *
     * The paths are turned into nodes first so a track that has been deleted or moved is
     * skipped rather than failing one at a time as the queue reaches it. What is gone is
     * still SHOWN in the playlist - a list that quietly shrinks is how somebody loses one
     * they spent an hour on - it just is not handed to the decoder.
     */
    fun playPlaylist(id: String, from: Int = 0, shuffle: Boolean = false) =
        startPlaylist(id, tapped = null, tappedAt = from, shuffle = shuffle)

    /**
     * Play a playlist starting at the track that was tapped, identified by its path.
     *
     * The path and not its row number. See `PlaylistHealth`: the queue leaves out whatever no
     * longer stats, so a row number from the screen and a position in the queue are two indices
     * into two lists of different lengths, and treating them as one played the wrong song.
     */
    fun playPlaylistTrack(id: String, path: VPath, shuffle: Boolean = false) =
        startPlaylist(id, tapped = path.toString(), tappedAt = 0, shuffle = shuffle)

    private fun startPlaylist(id: String, tapped: String?, tappedAt: Int, shuffle: Boolean) {
        val list = graph.playlists.get(id) ?: return
        viewModelScope.launch {
            val nodes = list.tracks.mapNotNull { p ->
                runCatching { withContext(Dispatchers.IO) { graph.vfs.stat(p) } }.getOrNull()
            }
            if (nodes.isEmpty()) {
                toast("Nothing in ${list.name} is still there.")
                return@launch
            }
            val tracks = dev.niccc2007.filet.playback.tracksOf(nodes)
            val stored = list.tracks.map { it.toString() }
            val at = dev.niccc2007.filet.playback.PlaylistHealth.let { h ->
                if (tapped != null) {
                    h.startIndex(stored, tracks.map { it.id }, tapped)
                } else {
                    h.startIndexAt(stored, tracks.map { it.id }, tappedAt)
                }
            }.coerceIn(0, tracks.lastIndex)
            // asAudio: a playlist is a music context. A video in one is there because
            // somebody chose to put it there, and throwing the video player at track four
            // of a queue of songs is the opposite of what the list is for. Watch is still
            // one tap away on the player, which is where it belongs here.
            graph.playback.play(tracks, at, asAudio = true, from = list.name)
            graph.playback.setShuffle(shuffle)
            maximisePlayer()
            // Opening a list and playing out of it are the two moments the gaps matter, so the
            // probe runs here too rather than only when the detail screen is composed.
            checkPlaylistTracks(id)
        }
    }

    private val _missingTracks = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Playlist entries whose files did not stat, by `VPath.toString()`.
     *
     * One set across every list rather than one per list: a path is gone or it is not, and the
     * same file is commonly in three playlists. The screens filter it by what they are drawing.
     */
    val missingTracks: StateFlow<Set<String>> = _missingTracks.asStateFlow()

    private var trackProbe: Job? = null

    /**
     * Stat a playlist's tracks and publish the ones that are not there.
     *
     * Bounded and cancellable: a list of two thousand entries on a share would otherwise be two
     * thousand round trips held open behind a screen somebody has already left. A newer probe
     * replaces an older one, because the newer screen is the one being looked at.
     */
    fun checkPlaylistTracks(id: String) {
        val list = graph.playlists.get(id) ?: return
        trackProbe?.cancel()
        trackProbe = viewModelScope.launch {
            val gone = HashSet<String>()
            for (p in list.tracks.take(PROBE_CAP)) {
                val there = runCatching { withContext(Dispatchers.IO) { graph.vfs.stat(p) } }.getOrNull()
                if (there == null) gone += p.toString()
            }
            // Merged rather than replaced, so probing list B does not un-mark list A's gaps
            // while both are on screen in a split view. Entries that came back are dropped.
            val checked = list.tracks.take(PROBE_CAP).map { it.toString() }.toSet()
            _missingTracks.value = (_missingTracks.value - checked) + gone
        }
    }

    /**
     * Drop the entries whose files are gone from this device.
     *
     * Only the ones `PlaylistHealth.removable` vouches for - a path on a share that did not
     * answer is left alone, because "deleted" and "the server is off" look identical from here
     * and only one of them is worth editing somebody's playlist over.
     */
    fun prunePlaylistMissing(id: String) {
        val list = graph.playlists.get(id) ?: return
        val gone = _missingTracks.value
        val drop = list.tracks.filter {
            it.toString() in gone &&
                dev.niccc2007.filet.playback.PlaylistHealth.removable(it.toString())
        }
        if (drop.isEmpty()) {
            toast("Nothing to clean up here.")
            return
        }
        for (p in drop) graph.playlists.remove(id, p)
        _missingTracks.value = _missingTracks.value - drop.map { it.toString() }.toSet()
        toast(if (drop.size == 1) "Removed 1 missing track." else "Removed ${drop.size} missing tracks.")
    }

    /**
     * Bumped whenever the remembered positions change, so the Carry on pane can redraw.
     *
     * A revision rather than a flow of the rows themselves: the store is a file this process
     * writes several times a minute while anything plays, and turning every one of those into
     * a list rebuild would mean re-reading and re-sorting a hundred rows for a screen that is
     * usually not even composed. The pane reads when it is told something changed.
     */
    private val _resumeRevision = MutableStateFlow(0)
    val resumeRevision: StateFlow<Int> = _resumeRevision.asStateFlow()

    fun bumpResume() {
        _resumeRevision.value = _resumeRevision.value + 1
    }

    /**
     * Bumped to re-query a category pane. Same shape as [resumeRevision], same reasoning.
     *
     * The query is cheap but it is not free - a cursor walk over every image row on the phone -
     * so it happens when somebody asks for it rather than on a timer.
     */
    private val _categoryRevision = MutableStateFlow(0)
    val categoryRevision: StateFlow<Int> = _categoryRevision.asStateFlow()

    /**
     * Paths Filet has just removed that the media index may still list. A category leaves
     * them out, so a delete is gone from the screen when it returns. See `JustRemoved`.
     */
    val justRemoved: StateFlow<Set<String>> get() = dev.niccc2007.filet.media.MediaAnnounce.justRemoved.paths

    /** Bumped when a batch of Filet's own writes is in the media index. See `MediaAnnounce.indexed`. */
    val mediaIndexed: StateFlow<Int> get() = dev.niccc2007.filet.media.MediaAnnounce.indexed

    fun bumpCategory() {
        _categoryRevision.value = _categoryRevision.value + 1
    }

    /** Bumped to start the scanner's camera again. See [RefreshTarget.CAMERA]. */
    private val _cameraRevision = MutableStateFlow(0)
    val cameraRevision: StateFlow<Int> = _cameraRevision.asStateFlow()

    fun bumpCamera() {
        _cameraRevision.value = _cameraRevision.value + 1
    }

    /**
     * The code the scanner has read, until its dialog is closed.
     *
     * Held here rather than in the screen because "From a picture" goes through the picker,
     * which is a tab of its own: the scanner is not composed while it is open, and an answer
     * written into the screen's own state would land in a composition that no longer exists.
     * The picture's code would be read and then shown nowhere.
     */
    private val _scanFound = MutableStateFlow<dev.niccc2007.filet.qr.ScanHit?>(null)
    val scanFound: StateFlow<dev.niccc2007.filet.qr.ScanHit?> = _scanFound.asStateFlow()

    fun scanned(hit: dev.niccc2007.filet.qr.ScanHit?) {
        _scanFound.value = hit
    }

    /**
     * Choose a picture in Filet's own picker and read the code in it, for the scanner.
     *
     * A picture with no code says so here, naming it, rather than handing the screen a null it
     * would have to explain; a cancelled pick says nothing, because nothing was asked.
     */
    fun scanPicture() {
        pick(
            dev.niccc2007.filet.pick.PickSpec(
                "Read a code from a picture",
                dev.niccc2007.filet.pick.PickSpec.Want.FILE,
                accept = dev.niccc2007.filet.pick.PickFilter.Mime(listOf("image/*"), "a picture"),
                verb = "read the code in",
            ),
        ) pick@{ r ->
            val path = (r as? dev.niccc2007.filet.pick.PickResult.Picked)?.first ?: return@pick
            viewModelScope.launch {
                val text = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching {
                        dev.niccc2007.filet.qr.QrDecode.fromStream({
                            kotlinx.coroutines.runBlocking { graph.vfs.openRead(path) }
                        })
                    }.getOrNull()
                }
                if (text == null) toast("No code found in " + path.name)
                else scanned(dev.niccc2007.filet.qr.ScanHit(text, fromPicture = true))
            }
        }
    }

    /**
     * Carry on with something that was part way through. See `ResumeStore`.
     *
     * ## Why the folder comes with it
     *
     * Playing the one file would make a queue of one, so Next would be dead and the track
     * would stop at its end - and somebody resuming an album at track four wants track five
     * after it. So the folder is listed and the queue is made from it, with the cursor on the
     * track being resumed. That is the same thing tapping the file in the browser does, which
     * is the point: this is a shortcut to a place, not a different way of playing.
     *
     * ## And why a missing file is a sentence rather than a silent removal
     *
     * The row stays in the list until somebody forgets it on purpose. A file that has been
     * deleted since is a thing worth being TOLD about - the alternative is a row that does
     * nothing when tapped, which is indistinguishable from the app being broken.
     *
     * @param fromStart true to ignore the remembered position, which is the other thing
     *   somebody wants from this list often enough to be worth a menu row.
     */
    fun resumePoint(
        point: dev.niccc2007.filet.playback.ResumeStore.Point,
        fromStart: Boolean = false,
    ) {
        viewModelScope.launch {
            val path = runCatching {
                dev.niccc2007.filet.vfs.VPath.parse(point.path)
            }.getOrNull()
            if (path == null) {
                toast("That is not a place any more.")
                return@launch
            }
            val node = runCatching {
                withContext(Dispatchers.IO) { graph.vfs.stat(path) }
            }.getOrNull()
            if (node == null) {
                toast("${point.title} is not there any more.")
                return@launch
            }
            if (fromStart) graph.playback.resume.forget(point.path)
            // A row played to its end starts over: carrying on at the end would end it at once.
            val restart = fromStart ||
                dev.niccc2007.filet.playback.Finished.at(point.positionMs, point.durationMs)
            // The folder around it, so the queue continues past this track. A listing that
            // fails - a card pulled out, a permission withdrawn - falls back to the one file
            // rather than refusing: one track playing beats a tap that does nothing.
            val siblings = runCatching {
                withContext(Dispatchers.IO) {
                    val out = ArrayList<dev.niccc2007.filet.vfs.VNode>()
                    path.parent?.let { parent -> graph.vfs.list(parent) { chunk -> out += chunk } }
                    out
                }
            }.getOrNull().orEmpty()
            val playable = dev.niccc2007.filet.playback.tracksOf(
                siblings.filter {
                    val kind = FileKind.of(it)
                    kind == FileKind.AUDIO || kind == FileKind.VIDEO
                }.sortedBy(prefs.sort.value),
            )
            val at = playable.indexOfFirst { it.id == point.path }
            if (at >= 0) {
                // Explicit, not left to the resume preference: this row IS the request to
                // carry on, and a tap on it starting at zero because a setting was off would
                // make the list a lie.
                graph.playback.play(
                    playable,
                    at,
                    startAtMs = if (restart) 0L else point.positionMs,
                    asAudio = !point.video,
                    from = "",
                    fromStart = restart,
                    by = dev.niccc2007.filet.playback.OpenedBy.CARRY_ON,
                )
            } else {
                val one = dev.niccc2007.filet.playback.tracksOf(listOf(node))
                graph.playback.play(
                    one,
                    0,
                    startAtMs = if (restart) 0L else point.positionMs,
                    asAudio = !point.video,
                    from = "",
                    fromStart = restart,
                    by = dev.niccc2007.filet.playback.OpenedBy.CARRY_ON,
                )
            }
            maximisePlayer()
        }
    }

    /** Add a whole folder's worth, or a selection, to what is already playing. */
    fun enqueueSelection(next: Boolean = false) {
        val items = focusedPane()?.selectedNodes().orEmpty().filter {
            val kind = FileKind.of(it)
            kind == FileKind.AUDIO || kind == FileKind.VIDEO
        }
        if (items.isEmpty()) {
            toast("Nothing here is a track Filet can play.")
            return
        }
        focusedPane()?.clearSelection()
        graph.playback.enqueue(dev.niccc2007.filet.playback.tracksOf(items), next = next)
        toast(if (next) "Playing next" else "Added to the queue")
    }

    /** What is playing, kept. The player's overflow. */
    fun saveQueueAsPlaylist(name: String) {
        val queue = graph.playback.state.value.queue
        if (queue.isEmpty()) {
            toast("Nothing is playing.")
            return
        }
        val paths = queue.mapNotNull { runCatching { VPath.parse(it.id) }.getOrNull() }
        val id = graph.playlists.create(name, paths)
        toast("${graph.playlists.get(id)?.name ?: name} saved \u2014 ${paths.size} tracks")
    }

    /** Everything selected is something the player can open. Empty is not playable. */
    fun selectionIsPlayable(): Boolean {
        val items = focusedPane()?.selectedNodes().orEmpty()
        return items.isNotEmpty() && items.all {
            val kind = FileKind.of(it)
            kind == FileKind.AUDIO || kind == FileKind.VIDEO
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
                pane.openSpecial(PaneKind.RECENT, "Opened")
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
        if (action is PlaceAction.Navigate) { pane.jumpTo(action.path); return }
        val learn = action is PlaceAction.Resolve
        viewModelScope.launch {
            val node = runCatching { graph.vfs.stat(path) }.getOrNull()
            if (node == null) {
                toast("That bookmark no longer points anywhere.")
                return@launch
            }
            if (learn) graph.bookmarks.learnKind(path, node.isDir)
            when (placeActionResolved(path, node.isDir)) {
                is PlaceAction.Navigate -> pane.jumpTo(path)
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
                // jumpTo, not navigateTo: a shortcut ARRIVES, and in a tree that means
                // opening the trunk down to the folder rather than making the folder the
                // trunk. See `PaneController.revealInTree`.
                pane.jumpTo(path)
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

    /**
     * Write the tab list now, for the moment the app leaves the screen. Every change is already
     * written as it happens (see [watchPanesForPersist]); this is the last write on the way out,
     * so what is kept when the app leaves is exactly what it was showing.
     */
    fun saveTabsNow() = persistTabs()

    /** Where the tabs are kept. See [dev.niccc2007.filet.data.TabSession] for why not in the settings. */
    private val tabSession by lazy { dev.niccc2007.filet.data.TabSession(graph.app) }

    /** The settings key the tabs lived under before [tabSession]: read once, then cleared. */
    private var oldTabsKeyCleared = false

    private fun persistTabs() {
        // A picker never writes the session. See [ownTabs].
        if (ownTabs) return
        val arr = JSONArray()
        for (t in _tabs.value) arr.put(TabCodec.encode(recordOf(t)))
        tabSession.save(
            JSONObject().put("tabs", arr)
                .put("a", _state.value.activeA)
                .put("b", _state.value.activeB)
                .put("split", _state.value.split.name)
                .toString(),
        )
        if (!oldTabsKeyCleared) {
            oldTabsKeyCleared = true
            if (graph.prefs.getString(KEY_TABS) != null) graph.prefs.putString(KEY_TABS, null)
        }
    }

    /**
     * Everything one tab needs to come back exactly as it was left. See [TabRecord].
     *
     * The folds travel with the tab. Restoring the folder and dropping the tree it was opened
     * into gives back a collapsed trunk, which for somebody working three levels down is most of
     * their place lost - the path is only half of where they were. `foldsToSave` keeps the
     * VISIBLE folds, shallowest first, capped: a fold whose parent is shut cannot be seen, and
     * saving it would reopen a branch on restore that was not open when the app closed.
     *
     * The title is kept only where it is not the kind's own name, and the scroll position is the
     * one the pane filed for the folder it is in.
     */
    private fun recordOf(pane: PaneController): TabRecord {
        val s = pane.state.value
        val (behind, ahead) = pane.history()
        return TabRecord(
            kind = s.kind,
            path = s.cwd,
            title = s.title.takeIf { s.kind != PaneKind.FOLDER && s.kind != PaneKind.HOME },
            category = s.category?.id,
            folds = TreeRows.foldsToSave(s.cwd, s.open),
            focus = s.active,
            scroll = pane.placeOf(s)?.let { pane.scroll.at(it) },
            search = s.search.takeIf { it.open && it.query.isNotBlank() }
                ?.let { SearchRecord(it.query, it.reach) },
            // The newest of each, which are the ones Back and Forward reach first.
            places = pane.scroll.all().takeLast(TabHistory.PLACES),
            kept = pane.keptViews(),
            back = behind.takeLast(TabHistory.KEEP).map(TabHistory::recordOf),
            forward = ahead.takeLast(TabHistory.KEEP).map(TabHistory::recordOf),
        )
    }

    /** What decides whether the saved tabs are out of date, for [watchPanesForPersist]. */
    private fun signatureOf(st: PaneState): String =
        st.kind.name + ":" + (st.cwd?.toString() ?: "") + ":" +
            TreeRows.foldsToSave(st.cwd, st.open).joinToString(",") + ":" +
            (st.active?.toString() ?: "") + ":" + (st.category?.id ?: "") + ":" +
            (if (st.search.open) st.search.query + "/" + st.search.reach else "")

    /**
     * Keep the saved tab list current as somebody walks around.
     *
     * Bug identified here: `persistTabs` ran only on a tab OPERATION - add, close, move, focus,
     * split. Navigating inside a tab wrote nothing, so swiping the app out of the task switcher
     * and reopening it restored every tab to wherever it had been at the last tab operation,
     * which in a long session is an arbitrary folder from minutes earlier. The feature looked
     * like it worked, because opening a tab is itself a tab operation.
     *
     * A collector over the panes rather than a call inside `navigateTo`: a `PaneController` has
     * no reference to this ViewModel by design. The signature below is a short string, so an
     * unchanged state costs one comparison.
     *
     * Written on EVERY change, with no pause. Bug identified here: a half-second debounce sat
     * in front of the write, so anything done in the last moment before the phone went off or
     * the app was swiped away - opening a folder, closing a tab - came back undone. The pause was
     * there because each write rewrote every setting; the tabs have their own file now (see
     * [dev.niccc2007.filet.data.TabSession]), a write is the tabs alone, and Android coalesces a
     * burst of them into one trip to the disk anyway.
     *
     * `collectLatest` on the tab list because panes come and go: adding a tab has to resubscribe
     * onto the new set, and the old subscription must die rather than hold a closed pane alive.
     */
    private fun watchPanesForPersist() {
        viewModelScope.launch {
            _tabs.collectLatest { panes ->
                if (panes.isEmpty()) return@collectLatest
                // A scroll counts as a change too: `scrolled` moves when the pane files a new
                // position, which is once a scroll comes to rest rather than every frame of it.
                combine(panes.map { p -> p.state.combine(p.scrolled) { st, n -> signatureOf(st) + "#" + n } }) {
                    it.joinToString("|")
                }
                    .distinctUntilChanged()
                    .collect { persistTabs() }
            }
        }
    }

    private fun restoreTabs(fallback: VPath?) {
        // The setting. Off means start with Home alone - and the saved list is
        // deliberately LEFT on disk rather than cleared, so turning the setting back on
        // restores the tabs that were open rather than starting from nothing.
        //
        // A picker reads nothing: see [ownTabs]. It opens on Home below.
        val raw = if (ownTabs) null
        else TabRestore.savedStateFor(tabSession.read() ?: graph.prefs.getString(KEY_TABS), graph.prefs.restoreTabs.value)
        val restored = ArrayList<PaneController>()
        var a = 0
        var b = 0
        var split = SplitMode.OFF
        // Searches are put back last, once every pane has its drives: a search over chosen
        // drives resolves against them, and asked before `equip` it would find none.
        val searches = ArrayList<Pair<PaneController, SearchRecord>>()
        if (raw != null) {
            runCatching {
                val o = JSONObject(raw)
                val arr = o.getJSONArray("tabs")
                for (i in 0 until arr.length()) {
                    val pane = newPane()
                    val record = arr.optJSONObject(i)?.let(TabCodec::decode)
                    putBack(pane, record)
                    record?.search?.let { searches += pane to it }
                    restored += pane
                }
                a = o.optInt("a", 0)
                b = o.optInt("b", 0)
                split = runCatching { SplitMode.valueOf(o.optString("split")) }.getOrDefault(SplitMode.OFF)
            }
        }
        if (restored.isEmpty()) {
            if (ownTabs) {
                // One tab, on Home. Home is where the categories are, and a picture pick that
                // starts in Photos is already most of the way to its answer - whereas the
                // volume root a cold start would otherwise land on is a folder of folders.
                restored += newPane().also { it.openHome() }
            } else {
                val home = newPane().also { it.openHome() }
                // walking: the single pane a cold start opens, before any tree exists.
                val first = newPane().also { p -> fallback?.let { p.navigateTo(it, push = false) } ?: p.openHome() }
                restored += home
                restored += first
                a = 1
            }
        }
        _tabs.value = restored
        _state.update {
            it.copy(
                activeA = a.coerceIn(0, restored.lastIndex),
                activeB = b.coerceIn(0, restored.lastIndex),
                split = split,
            )
        }
        restored.forEach(::equip)
        for ((pane, q) in searches) {
            pane.openSearch(true)
            pane.setReach(q.reach)
            pane.setQuery(q.query)
        }
        // LAST. Anything waiting on a usable pane waits on this, not on the tab list becoming
        // non-empty: the list is populated a moment before `activeA` is set, and waiting on
        // the list alone meant a shortcut acted on tab 0 while the user was looking at tab 2.
        _tabsReady.value = true
    }

    /**
     * One saved tab, put back into [pane] as it was left. A record this build cannot read opens
     * Home there rather than dropping the tab, so the tab strip keeps its shape.
     */
    private fun putBack(pane: PaneController, r: TabRecord?) {
        // Its memory first, so the screen it is about to show finds its position and its view
        // already there on the first frame rather than drawing at the top and jumping.
        if (r != null) {
            pane.scroll.restore(r.places)
            pane.restoreKept(r.kept)
        }
        when {
            r == null -> pane.openHome()
            r.kind == PaneKind.FOLDER && r.path != null -> {
                // walking: restoring a tab to its saved folder, as a RETURN, so the listing puts
                // its scroll position back. The tree it had open is put back separately by
                // restoreFolds, so expanding here would fight it.
                pane.navigateTo(r.path, push = false, resume = true)
                r.scroll?.let { marks -> pane.placeOf(pane.state.value)?.let { pane.parkScroll(it, marks) } }
                // And the tree it was opened into, and the branch it was working in. Both after
                // `navigateTo`, so they land on this folder rather than on whatever the pane was
                // showing a moment ago.
                pane.restoreFolds(r.folds)
                r.focus?.let { pane.focusPath(it, record = false) }
            }
            r.kind == PaneKind.CATEGORY ->
                dev.niccc2007.filet.home.MediaCategory.entries.firstOrNull { it.id == r.category }
                    ?.let(pane::openCategory) ?: pane.openHome()
            r.kind == PaneKind.HOME || r.kind == PaneKind.FOLDER -> pane.openHome()
            // A record from before titles were saved reads as the kind's own name, as it did.
            else -> pane.openSpecial(r.kind, r.title ?: r.kind.name.lowercase().replaceFirstChar { c -> c.uppercase() })
        }
        // A tab put back at launch is a return, whatever screen it shows: its lists and its
        // views come back as left. A folder said so already through `navigateTo`.
        if (r != null) pane.markReturn()
        // And its history, after it has arrived: arriving is what would otherwise clear it.
        if (r != null && (r.back.isNotEmpty() || r.forward.isNotEmpty())) {
            pane.restoreHistory(
                r.back.mapNotNull { TabHistory.stateOf(it, pane.id, pane::titleFor) },
                r.forward.mapNotNull { TabHistory.stateOf(it, pane.id, pane::titleFor) },
            )
        }
    }

    /**
     * Give one pane the volume list it reads: roots for [PaneController.jumpTo], drives for the
     * reach ladder.
     *
     * Both come off `_state.volumes`, so they are written together or they disagree - and a
     * ladder offering a drive `jumpTo` cannot resolve is worse than either being absent.
     */
    private fun equip(pane: PaneController) {
        pane.rootsForDevice = _state.value.volumes.map { it.node.path }
        pane.reachDrives = _state.value.volumes.map { v ->
            ReachDrive(
                key = dev.niccc2007.filet.index.VolumeKey(v.node.path),
                label = v.label,
                remote = v.remote,
                // Asked of the scheme, which is the only thing that distinguishes a superuser
                // volume from an ordinary one once it is a VNode like any other.
                system = v.node.path.scheme == dev.niccc2007.filet.vfs.provider.RootProvider.SCHEME,
            )
        }
        // A tab put back before the drives were known was rooted wherever it was saved, with no
        // drive to hang its tree from. Now there is one.
        pane.reshape()
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
        // Asked of the provider, not of the scheme. The test here was `scheme != "local"`,
        // which made every superuser volume fall through to the literal words "Network drive"
        // when its node had no name - so `/system` and `/data` were captioned as network
        // drives on a phone with nothing mounted at all.
        graph.vfs.isRemote(node.path) ->
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

        /**
         * The largest picture worth attaching to a file.
         *
         * Not a safety limit - the store's rewrite ceiling is that. This is about what cover art
         * is for: it is carried inside every copy of the file forever and displayed at a few
         * hundred pixels, so a phone photo pasted in whole would multiply the file's size for no
         * visible gain.
         */
        const val MAX_COVER_BYTES = 8 * 1024 * 1024

        /**
         * How many playlist entries get checked for still being there.
         *
         * A stat is cheap on this phone and a round trip on a share, and the list is drawn
         * before any of it finishes, so the cap is about the tail: past a few hundred rows
         * nobody is scrolling to see whether row 1,400 is dimmed, and the queue drops a gone
         * file whether or not its row was marked.
         */
        const val PROBE_CAP = 400
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

/**
 * How long Shutdown Filet waits between telling the services to stop and ending the process.
 *
 * Measured against nothing more precise than the shade: a kill that races the stop intents
 * leaves a notification up for a process that no longer exists, and tapping it starts Filet
 * again - the opposite of what was asked. Under half a second is not noticeable after a
 * deliberate tap.
 */
private const val STOP_SETTLE_MS = 400L

/**
 * The largest picture a turn is saved for. The file is held whole, twice, while it is turned;
 * a phone camera's JPEG is a few megabytes and a large scan a few tens, so this refuses only
 * what would be out of memory before it was out of this.
 */
private const val TURN_MAX_BYTES = 96L * 1024 * 1024

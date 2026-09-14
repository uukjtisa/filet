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
data class DropPlan(
    val dest: VPath,
    val destLabel: String,
    val move: Boolean,
    /** Non-null when the drop is refused, and why. */
    val refusal: String? = null,
) {
    val label: String
        get() = refusal ?: "${if (move) "Move to" else "Copy to"} $destLabel"
}

data class VolumeInfo(val node: VNode, val label: String, val free: Long?, val total: Long?)

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
    val volumes: List<VolumeInfo> = emptyList(),
    val switcherOpen: Boolean = false,
    val activityOpen: Boolean = false,
    val toast: String? = null,
    /** Bumped whenever a job finishes, so the current panes re-list without a manual pull. */
    val revision: Int = 0,
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

    /** Re-measure every volume: a card can be unmounted while the app is backgrounded. */
    suspend fun loadVolumes() {
        val roots = runCatching { graph.vfs.roots() }.getOrElse { emptyList() }
        _state.update { it.copy(volumes = volumeInfos(roots)) }
    }

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val roots = runCatching { graph.vfs.roots() }.getOrElse { emptyList() }
            _state.update { it.copy(volumes = volumeInfos(roots)) }
            restoreTabs(roots.firstOrNull()?.path)
        }
    }

    /**
     * Measure each volume once, in one place.
     *
     * Both callers used to build this inline and one of them quietly passed `total = null`, so
     * Home showed a bar-less card depending on which code path had last refreshed. One
     * function, so the two cannot disagree again.
     */
    private suspend fun volumeInfos(roots: List<VNode>): List<VolumeInfo> = roots.map { node ->
        VolumeInfo(
            node = node,
            label = volumeLabel(node),
            free = runCatching { graph.vfs.freeSpace(node.path) }.getOrNull(),
            total = runCatching { graph.vfs.totalSpace(node.path) }.getOrNull(),
        )
    }

    // ── tabs ──

    private fun newPane(): PaneController {
        val pane = PaneController(
            id = nextId++,
            vfs = graph.vfs,
            prefs = graph.prefs,
            scope = viewModelScope,
            searchSources = graph.searchSources,
            onSearched = { q -> graph.index.steerCrawl(q) },
            onOpened = { node -> openNode(node) },
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
    fun refreshStalePanes() = _tabs.value.forEach { it.refreshIfEmpty() }

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
            for (target in plan.targets) {
                when (target) {
                    RefreshTarget.LISTING -> pane.refresh()
                    RefreshTarget.VOLUMES -> loadVolumes()
                    RefreshTarget.HOME_FEED -> graph.home.refresh()
                    RefreshTarget.NEARBY -> graph.nearby.resync()
                    RefreshTarget.NEARBY_SCAN -> graph.nearby.startScan()
                    RefreshTarget.SHORTCUTS -> graph.shortcuts.reload()
                    RefreshTarget.SCRIPTS -> graph.scripts.reload()
                    RefreshTarget.BOOKMARKS -> graph.bookmarks.reload()
                    RefreshTarget.RECENTS -> graph.recents.reload()
                    // These three are read through on every composition rather than cached,
                    // so the revision bump below IS their refresh. Listed rather than left out
                    // so the table stays a complete answer to "what does this pane re-read".
                    RefreshTarget.INDEX_STATUS, RefreshTarget.REMOTES, RefreshTarget.JOBS -> Unit
                }
            }
            // Panes that read straight from a store on every composition need a nudge to
            // recompose at all, which is what this is.
            _state.update { it.copy(revision = it.revision + 1) }
            toast("${plan.label} refreshed")
        }
    }

    fun focusTab(index: Int) {
        if (index !in _tabs.value.indices) return
        _state.update {
            if (it.focused == Side.A || it.split == SplitMode.OFF) it.copy(activeA = index, focused = Side.A)
            else it.copy(activeB = index)
        }
        persistTabs()
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

    fun cycleSplit() = _state.update {
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
            node.extension == "apk" -> openApk(node)
            // By whole name, not by extension: `backup.tar.gz` has the extension "gz".
            Archives.canList(node.name) -> mountArchive(node)
            else -> openWithRegistered(node)
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
        if (node.path.scheme != "local") {
            toast("Open archives from local storage for now.")
            return
        }
        graph.recents.record(node.path, isDir = false)
        pane.navigateTo(ArchiveProvider.mount(node.path.path))
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
            reportAndRefresh(r.succeeded, r.failed.size, if (clip.op == PendingOp.COPY) "copied" else "moved")
        }
    }

    fun clearClipboard() = _state.update { it.copy(clipboard = null) }

    // ── operations ──

    fun deleteSelection() = withSelection { items ->
        viewModelScope.launch {
            val r = graph.ops.delete(items.map { it.path })
            reportAndRefresh(r.succeeded, r.failed.size, "deleted")
        }
    }

    fun rename(target: VPath, newName: String) {
        if (newName.isBlank() || newName == target.name) return
        viewModelScope.launch {
            runCatching { graph.vfs.rename(target, newName.trim()) }
                .onSuccess { toast("Renamed"); refreshPanes() }
                .onFailure { toast(dev.niccc2007.filet.ops.FileOperations.readable(it)) }
        }
    }

    fun createFolder(name: String, side: Side = _state.value.focused) {
        val dest = paneFor(side)?.state?.value?.cwd ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { graph.vfs.create(dest.child(name.trim()), isDir = true) }
                .onSuccess { toast("Folder created"); refreshPanes() }
                .onFailure { toast(dev.niccc2007.filet.ops.FileOperations.readable(it)) }
        }
    }

    fun createFile(name: String, side: Side = _state.value.focused) {
        val dest = paneFor(side)?.state?.value?.cwd ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { graph.vfs.create(dest.child(name.trim()), isDir = false) }
                .onSuccess { toast("File created"); refreshPanes() }
                .onFailure { toast(dev.niccc2007.filet.ops.FileOperations.readable(it)) }
        }
    }

    /**
     * @param typed what the user put in the box, with or without a suffix.
     * @param format which container to write. The suffix comes from the format rather than
     *   from the typed name, so picking Tar + gzip and leaving "Photos.zip" in the box makes
     *   a `Photos.tar.gz` and not a gzipped tar wearing a zip's name.
     */
    fun compressSelection(typed: String, format: ArchiveFormat) = withSelection { items ->
        val dest = focusedPane()?.state?.value?.cwd ?: return@withSelection
        viewModelScope.launch {
            val base = Archives.baseName(typed.trim()).ifEmpty { "Archive" }
            val existing = runCatching { graph.vfs.list(dest).map { it.name }.toHashSet() }
                .getOrDefault(HashSet())
            val name = archiveName(base, format) { it in existing }
            val r = graph.ops.compress(items, dest.child(name), format) { graph.files.scratchPath(it) }
            reportAndRefresh(r.succeeded, r.failed.size, "compressed into $name")
        }
    }

    /** Every format Compress offers, in the order the table declares them. */
    val archiveFormats: List<ArchiveFormat> get() = Archives.creatable

    fun extract(node: VNode) {
        val dest = focusedPane()?.state?.value?.cwd ?: return
        viewModelScope.launch {
            val root = if (node.path.scheme == ArchiveProvider.SCHEME) node.path
            else ArchiveProvider.mount(node.path.path)
            val r = graph.ops.extract(root, dest, node.name)
            reportAndRefresh(r.succeeded, r.failed.size, "extracted")
        }
    }

    private inline fun withSelection(block: (List<VNode>) -> Unit) {
        val pane = focusedPane() ?: return
        val items = pane.selectedNodes()
        if (items.isEmpty()) return
        block(items)
    }

    private fun reportAndRefresh(ok: Int, failed: Int, verb: String) {
        toast(if (failed == 0) "$ok $verb" else "$ok $verb, $failed failed")
        _tabs.value.forEach { it.clearSelection() }
        refreshPanes()
    }

    /** Re-list only the panes on screen. Refreshing every tab would be a syscall storm. */
    fun refreshPanes() {
        val s = _state.value
        _tabs.value.getOrNull(s.activeA)?.refresh()
        if (s.split != SplitMode.OFF) _tabs.value.getOrNull(s.activeB)?.refresh()
        _state.update { it.copy(revision = it.revision + 1) }
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
        if (items.all { it.path.parent == dest }) {
            return DropPlan(dest, label, move = true, refusal = "Already in $label")
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
        val plan = drag?.drop ?: return
        if (plan.refusal != null) { toast(plan.refusal); return }
        val doMove = move ?: plan.move
        viewModelScope.launch {
            val paths = drag.items.map { it.path }
            val r = if (doMove) graph.ops.move(paths, plan.dest) else graph.ops.copy(paths, plan.dest)
            reportAndRefresh(r.succeeded, r.failed.size, if (doMove) "moved" else "copied")
        }
    }

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
    var onPickFolder: (() -> Unit)? = null
    var onExit: (() -> Unit)? = null
    var onShare: ((List<VNode>) -> Unit)? = null
    var onCheckUpdates: (() -> Unit)? = null

    fun requestExit() { onExit?.invoke() }

    fun requestAllFiles() {
        val intent = dev.niccc2007.filet.vfs.provider.StorageAccess.resolvableRequestIntent(graph.app)
        if (intent == null) toast("This device has no all-files settings screen.")
        else onIntent?.invoke(intent)
    }

    fun requestFolderGrant() {
        val cb = onPickFolder
        if (cb == null) toast("Cannot open the folder picker right now.") else cb()
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

    // ── file content, for the viewers ──

    /** Blocking. Every caller is already on an IO dispatcher; the viewers all are. */
    fun openRead(node: VNode): java.io.InputStream =
        kotlinx.coroutines.runBlocking { graph.vfs.openRead(node.path) }

    suspend fun readText(node: VNode): String =
        graph.vfs.openRead(node.path).use { String(it.readBytes(), Charsets.UTF_8) }

    fun saveText(node: VNode, text: String, onSaved: () -> Unit) {
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

    /**
     * Open the folder incoming files land in, creating it if this is the first time.
     *
     * The folder has always existed - `/storage/emulated/0/Filet/Received` - and there was no
     * way to reach it from the screen that fills it, which is not much better than not having
     * one. Nic's question was literally "where can i find it".
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

    fun shareSelection() {
        val items = focusedPane()?.selectedNodes().orEmpty().filterNot { it.isDir }
        if (items.isEmpty()) { toast("Select a file to share."); return }
        onShare?.invoke(items) ?: toast("Sharing is unavailable right now.")
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

    fun saveConnection(c: dev.niccc2007.filet.vfs.provider.net.NetConnection) {
        graph.connections.save(c)
        _remotesRevision.value = _remotesRevision.value + 1
        toast("Saved ${c.label.ifEmpty { c.host }}")
    }

    fun deleteConnection(id: String) {
        graph.connections.delete(id)
        _remotesRevision.value = _remotesRevision.value + 1
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
            // After the listing lands, so the selection has something to attach to.
            kotlinx.coroutines.delay(160)
            pane.selectOnly(node)
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
            dev.niccc2007.filet.shortcuts.AppAction.SEARCH -> pane.openSearch(true)
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
        val raw = graph.prefs.getString(KEY_TABS)
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
        else -> node.name.ifEmpty { "Storage" }
    }

    private companion object {
        const val KEY_TABS = "tabs.v1"

        /** Release and debug both, so a development build is never bridge-blind (PLAN.md §5.4). */
        val TRAWL_PACKAGES = listOf("dev.niccc2007.trawl", "dev.niccc2007.trawl.debug", "com.junkfood.seal")

        /** Past this, copying a file into the cache just to play it is not worth the wait. */
        const val CACHE_COPY_LIMIT = 256L * 1024 * 1024
    }
}

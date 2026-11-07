package dev.niccc2007.filet.browser

import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.data.SortSpec
import dev.niccc2007.filet.index.SearchHit
import dev.niccc2007.filet.index.SearchRequest
import dev.niccc2007.filet.index.Reach
import dev.niccc2007.filet.index.SourceKind
import dev.niccc2007.filet.index.HitState
import dev.niccc2007.filet.index.IndexStatus
import dev.niccc2007.filet.index.actionable
import dev.niccc2007.filet.index.afterCheck
import dev.niccc2007.filet.index.nativeToggleMatters
import dev.niccc2007.filet.index.originLine
import dev.niccc2007.filet.index.sourcePlan
import dev.niccc2007.filet.index.spansDrives
import dev.niccc2007.filet.index.walkGaps
import dev.niccc2007.filet.index.SearchSource
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a pane is currently showing. Not every pane shows a directory. */
enum class PaneKind {
    HOME, FOLDER, ABOUT, BOOKMARKS, RECENT, SETTINGS, ACTIVITY, SCRIPTS, NEARBY, REMOTES,
    SHORTCUTS, HISTORY,

    /** Kept queues, and the Favourites the heart writes into. */
    PLAYLISTS,

    /** Installed apps, and extracting their APK back out. */
    APPS,

    /**
     * What was part way through, and where it got to. See `ResumeStore`.
     *
     * Audio and video in one list with a filter rather than a tab each - the reasoning is on
     * `ResumeScreen`, and it is reversible from here if it turns out to be wrong.
     */
    RESUME,

    /**
     * Everything of one kind on the phone - photos, music, packages. Issue #4.
     *
     * WHICH kind is on the pane's state rather than in the enum, because six kinds would be
     * six pane kinds, six routes, six search plans and six refresh plans for one screen that
     * differs by a WHERE clause. See [PaneState.category].
     */
    CATEGORY,

    /**
     * The camera, reading a QR code. A tool, so it lives in Tools; what it finds opens the same
     * dialog a code found in a photo does, so the two can never disagree about what a code is.
     */
    SCAN,
}

data class SearchUi(
    val open: Boolean = false,
    val query: String = "",
    val reach: Reach = Reach.Folder,
    val running: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val painted: Boolean = false,
    /**
     * Search by reading the filesystem and nothing else.
     *
     * A control rather than something to be inferred. When a result is missing, "look again
     * properly" should be a thing that can be pressed - the alternative is a user guessing
     * whether the app is fast or wrong.
     */
    val nativeOnly: Boolean = false,
    /**
     * Where the rows on screen came from.
     *
     * Reported as the Native search toggle doing nothing. Half of that was a real fault; the
     * other half is that a toggle which re-runs a search and legitimately gets the same rows
     * is indistinguishable from a toggle that is not wired to anything. Naming the sources
     * means pressing it always changes something the reader can see.
     */
    val origin: String = "",
    /** False where the index never answers this scope anyway, so the toggle cannot matter. */
    val nativeMatters: Boolean = true,
    /**
     * Shares in reach that did not answer, for the drive headers.
     *
     * Asked of the share directly rather than inferred from the walk returning nothing, because
     * those two look identical from outside - and "no matches" over a share that was never
     * actually reached is the one answer a search must not give. SEARCH.md §13.5.
     */
    val unreachable: Set<dev.niccc2007.filet.index.VolumeKey> = emptySet(),
    /** Rows shown from the index whose check has not come back yet. SEARCH.md §14.3. */
    val checking: Int = 0,
    /** Folders each walker has read so far, by the root it is walking. SEARCH.md §14.8. */
    val walked: Map<VPath, Int> = emptyMap(),
) {
    val active: Boolean get() = open && query.isNotBlank()

    /**
     * Whether anything at all is still working on this answer.
     *
     * The progress line follows this and nothing narrower. A spinner that stops while rows are
     * still arriving - which is what [running] alone did, since the checker outlives the
     * sources - tells the reader the answer is final when it is not.
     */
    val busy: Boolean get() = running || checking > 0
}

data class PaneState(
    val id: Int,
    val kind: PaneKind = PaneKind.HOME,
    val cwd: VPath? = null,
    val title: String = "Home",
    /** Sorted and filtered for display. [total] is the count before the hidden-file filter. */
    val entries: List<VNode> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val selected: Set<VPath> = emptySet(),
    val search: SearchUi = SearchUi(),
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** Set while the path bar is being edited, so the breadcrumb yields to a text field. */
    val editingPath: Boolean = false,
    /**
     * A file the listing should scroll to once it has one.
     *
     * Set by Open location and cleared by the list the moment it has acted. It is
     * state rather than a delay because the listing takes as long as it takes - a fixed wait
     * is either too short on a big folder or a stall on a small one, and the previous version
     * guessed 160ms and then never scrolled at all.
     */
    val revealTarget: VPath? = null,
    /**
     * Which media category a [PaneKind.CATEGORY] pane is showing. Null for every other kind.
     *
     * On the state rather than encoded into [cwd] as a synthetic path: a VPath with a scheme no
     * provider answers would be handed to the VFS by anything that reads `cwd` without checking
     * the kind first, and that is a crash waiting for the next person who adds a feature here.
     */
    val category: dev.niccc2007.filet.home.MediaCategory? = null,

    /** Folders showing their contents in place. Empty unless the tree view is on. */
    val open: Set<VPath> = emptySet(),
    /** What is inside each of those, absent while it is still being read. */
    val branches: Map<VPath, List<VNode>> = emptyMap(),
    /**
     * The folder the pane is WORKING IN, when that is not [cwd].
     *
     * A tree breaks the thing a path bar quietly promised: you can be four levels down an
     * open branch while the bar at the top still names the root. Everything that creates -
     * New folder, New file, Paste - was asking the bar, so it answered the root, and a
     * folder made while looking at a branch appeared somewhere off screen.
     *
     * So the last branch opened becomes the working folder, it is drawn as such (see
     * `FileRow`'s `active`), and the create actions ask [target] rather than `cwd`. Null
     * whenever the tree is folded away or the pane has navigated, which is what keeps this
     * from quietly outliving the thing it points at.
     */
    val active: VPath? = null,
    /**
     * This folder is being RETURNED to - Back, Forward, Up, or a tab put back at launch - so the
     * listing puts back where it was scrolled to in it (see [PaneController.scroll]). Anywhere
     * arrived at fresh starts at the top. Cleared by the listing once it has acted.
     */
    val resume: Boolean = false,
    /**
     * [revealTarget] is to be scrolled to only if it is not already on screen. For a focus put
     * back by Back or Forward: the rail moving is the answer when the row is in view, and a list
     * that re-centres on every press would be the screen jumping for nothing.
     */
    val revealIfHidden: Boolean = false,
) {
    val selecting: Boolean get() = selected.isNotEmpty()

    /**
     * Where something new goes.
     *
     * The open branch if there is one, otherwise the folder in the path bar. One accessor,
     * because "which folder does this act on" is a question six call sites used to answer
     * for themselves - and five of them were right only because the tree did not exist yet.
     */
    val target: VPath? get() = active ?: cwd
    /** Rows actually rendered: search results replace the listing while a search is live. */
    val visible: List<VNode>
        get() = if (search.active) search.hits.map { it.node } else entries
}

/**
 * One pane: a location, its history, its selection, and its own search.
 *
 * A pane is the thing that has a location, so a pane is the thing that owns a search
 * (mock round 4). Two panes means two independent searches; a tab keeps its own.
 *
 * Not a ViewModel. Tabs come and go, and tying each one to the ViewModel lifecycle would
 * mean either leaking closed tabs or losing their scroll position and history.
 */
class PaneController(
    val id: Int,
    private val vfs: Vfs,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val searchSources: List<SearchSource>,
    /** The index status right now, or null where there is no index. */
    private val indexStatus: () -> IndexStatus? = { null },
    /** A short message for the person, for the rare case a row cannot be opened after all. */
    private val onNotice: (String) -> Unit = {},
    /**
     * The `volume.uuid` of every drive the index has walked completely - the drives a walk
     * beside the index has no business re-reading. SEARCH.md §14.2, decision 15.
     */
    private val covered: () -> Set<String> = { emptySet() },
    /**
     * Told about a result whose check found nothing there, so the index can forget it rather
     * than offer it again on the next keystroke and fail the same check again.
     */
    private val onGone: (VPath) -> Unit = {},
    /**
     * Told what was searched for, so a crawl that is still running can be bent toward it.
     *
     * A function rather than the index itself: the pane has no other business with the index,
     * and handing it one would be handing it every other thing an index can do.
     */
    private val onSearched: (String) -> Unit = {},
    /** A file was opened, and the rows it was opened among - see `ViewerRun`. */
    private val onOpened: (VNode, List<VNode>) -> Unit = { _, _ -> },
    /**
     * Called with each folder this pane lands on.
     *
     * Walking into a folder is the strongest signal there is that its contents are about to be
     * searched, so it is also when indexing it is worth doing.
     */
    private val onLanded: (VPath) -> Unit = {},
    /**
     * The world revision, bumped by every operation that writes to the filesystem.
     *
     * A function rather than a value because the pane outlives any single reading of it, and a
     * captured Int would be exactly the stale-capture bug that broke pinch-to-zoom.
     */
    private val worldRevision: () -> Int = { 0 },
    /**
     * Whether a drag is in flight anywhere in the app.
     *
     * A function for the same reason as [worldRevision]: the pane outlives any reading of it,
     * and the whole point is to be asked at the moment the decision is made.
     */
    private val dragging: () -> Boolean = { false },
    /** The live pick, whose list is this pane's selection while one is live. See [PickPort]. */
    private val pick: PickPort = PickPort.None,
) : PaneMemory {
    private val _state = MutableStateFlow(PaneState(id = id))
    val state: StateFlow<PaneState> = _state.asStateFlow()

    private val back = ArrayDeque<PaneState>()
    private val forward = ArrayDeque<PaneState>()
    private var listJob: Job? = null
    private var searchJob: Job? = null

    /**
     * The world revision this pane last completed a listing at.
     *
     * See [FolderFreshness]. A pane that is behind the world is showing rows that may have been
     * moved or deleted from another tab, and that is the stale-folder fault.
     */
    private var listedAt: Int = FolderFreshness.NEVER

    /** Raw listing, kept so a sort or hidden-files change re-renders without re-reading disk. */
    private var raw: List<VNode> = emptyList()

    /**
     * Where this pane was scrolled to in each folder, written by the listing and read when a
     * folder is returned to. On the pane rather than in the view, because the view is the same
     * one for every tab and forgets a folder the moment it shows another.
     */
    val scroll = ScrollMemory()

    private val _scrolled = MutableStateFlow(0)

    /**
     * Bumped whenever the pane files something the saved tab carries that its state does not
     * show - a scroll position, a screen's kept view - so the saved tabs follow those the way
     * they follow a walk.
     */
    val scrolled: StateFlow<Int> = _scrolled.asStateFlow()

    /** Each screen's own choice of view in this tab, by the screen's key. See [PaneMemory]. */
    private val keptViews = LinkedHashMap<String, String>()

    override fun marks(place: String): ScrollMemory.Marks? = scroll.at(place)

    override fun park(place: String, marks: ScrollMemory.Marks) = parkScroll(place, marks)

    override fun kept(key: String): String? = synchronized(keptViews) { keptViews[key] }

    override fun keep(key: String, value: String?) {
        val changed = synchronized(keptViews) {
            val before = keptViews[key]
            if (value == null) keptViews.remove(key) else keptViews[key] = value
            before != value
        }
        if (changed) _scrolled.value++
    }

    /** Everything [keep] holds, for the saved tab. */
    fun keptViews(): Map<String, String> = synchronized(keptViews) { LinkedHashMap(keptViews) }

    /** Put back what [keptViews] gave, before the tab's screen is first drawn. */
    fun restoreKept(views: Map<String, String>) = synchronized(keptViews) { keptViews.putAll(views) }

    /** The history behind and ahead of this pane, oldest first on both, for the saved tab. */
    fun history(): Pair<List<PaneState>, List<PaneState>> = back.toList() to forward.toList()

    /**
     * Give a restored pane the history it was saved with, so Back after a restart walks back
     * through where it had been rather than closing the tab. The states come off the record
     * with their rows empty, as every history entry is kept.
     */
    fun restoreHistory(behind: List<PaneState>, ahead: List<PaneState>) {
        back.clear()
        back.addAll(behind)
        forward.clear()
        forward.addAll(ahead)
        _state.update { it.copy(canGoBack = back.isNotEmpty(), canGoForward = forward.isNotEmpty()) }
    }

    /** The name this pane gives a folder it is in. For a history entry being put back. */
    fun titleFor(path: VPath): String = friendlyTitle(path)

    /** The key [scroll] files a state under: its folder. Null for a pane with no list of its own. */
    fun placeOf(s: PaneState): String? = if (s.kind == PaneKind.FOLDER) s.cwd?.toString() else null

    fun parkScroll(place: String, marks: ScrollMemory.Marks) {
        if (scroll.at(place) == marks) return
        scroll.park(place, marks)
        _scrolled.value++
    }

    /** The listing has put a returned-to folder's position back, or decided there is none. */
    /**
     * The pane's screen was taken away with the pane left standing where it is - another tab
     * brought forward, the window rebuilt, a viewer opened over it. Showing it again is a
     * RETURN, so it comes back exactly where it was; a fresh arrival anywhere clears this.
     */
    fun markReturn() {
        if (!_state.value.resume) _state.update { it.copy(resume = true) }
    }

    override fun returning(): Boolean = _state.value.resume

    fun resumeHandled() {
        if (_state.value.resume) _state.update { it.copy(resume = false) }
    }

    init {
        // A pane watches the presentation prefs itself.
        //
        // Pushing `reapply()` from whichever menu made the change is how the Settings page
        // ended up changing sort order and hidden-files with no visible effect until you
        // navigated - a control that looks broken, which R1 exists to prevent. Observing here
        // means there is one mechanism and every open pane obeys it, wherever the change
        // came from. `drop(1)` skips the current value, which would re-render an empty pane
        // at construction for nothing.
        scope.launch {
            combine(prefs.sort, prefs.showHidden) { sort, hidden -> sort to hidden }
                .drop(1)
                .collect { reapply() }
        }
    }

    // ── navigation ──

    fun openHome() = goto(PaneState(id = id, kind = PaneKind.HOME, title = "Home"))

    fun openSpecial(kind: PaneKind, title: String) = goto(PaneState(id = id, kind = kind, title = title))

    /**
     * Rows held at the bottom because they just arrived. View state, never persisted.
     *
     * Restoring one from disk would put a just-arrived row under a file from last week.
     */
    private var freshTail = FreshTail()

    /**
     * @param resume this is a return to [path] rather than an arrival - see [PaneState.resume].
     */
    fun navigateTo(path: VPath, push: Boolean = true, resume: Boolean = false) {
        // A tree is rooted at its drive, so going anywhere below one is an arrival: the drive,
        // with the way down open and [path] focused. One place for it, so a shortcut, a typed
        // path and a folder opened from a menu cannot each root the tree somewhere else. See
        // TreeShape.
        if (prefs.treeView.value) {
            val root = TreeShape.rootFor(path, rootsForDevice)
            if (root != null && root != path) {
                revealInTree(root, path, push = push)
                return
            }
        }
        listJob?.cancel()
        val prev = _state.value
        // Leaving the folder settles it. Re-listing the SAME folder does not, because that is
        // what happens straight after a paste - and settling there would sort the pasted file
        // away in the same frame it appeared, which is the whole thing being avoided.
        if (prev.cwd != path) freshTail = FreshTail()
        if (push && prev.kind != PaneKind.FOLDER || push && prev.cwd != path) {
            back.addLast(prev.copy(entries = emptyList()))
            forward.clear()
        }
        val moved = prev.cwd != path
        _state.value = prev.copy(
            kind = PaneKind.FOLDER,
            cwd = path,
            // Moving the pane ends whatever branch was being worked in. The working folder
            // only means something relative to the listing it was opened from.
            active = if (moved) null else prev.active,
            title = friendlyTitle(path),
            loading = true,
            error = null,
            selected = emptySet(),
            editingPath = false,
            canGoBack = back.isNotEmpty(),
            canGoForward = forward.isNotEmpty(),
            search = _state.value.search.copy(hits = emptyList(), painted = false),
            // Bug identified while measuring the wait on a camera folder, and it is most of what
            // that wait LOOKED like. The rows were not cleared when the path changed, so for the
            // whole read the pane showed the previous folder's contents under the new folder's
            // breadcrumb. PaneView has a spinner for exactly this moment - `loading &&
            // entries.isEmpty()` - and it could never appear, because entries were never empty.
            // So the honest report of "I am reading a big folder" rendered as "nothing happened
            // when you tapped", which is the worse of the two by a distance.
            //
            // Only on an actual move. Re-listing the SAME folder keeps its rows on purpose: that
            // is what a refresh and a post-paste relist do, and blanking there would flash the
            // whole list for a change of one row.
            entries = if (moved) emptyList() else prev.entries,
            // A request aimed at the folder being left cannot be satisfied in the one being
            // entered, and holding it would make this folder jump to nothing. Kept on a re-list
            // of the same folder, which is what a refresh and a post-paste relist are.
            revealTarget = if (moved) null else prev.revealTarget,
            resume = if (moved) resume else prev.resume,
            revealIfHidden = if (moved) false else prev.revealIfHidden,
        )
        if (moved) raw = emptyList()
        // Read BEFORE the listing starts, not after it finishes: an operation that lands while
        // this read is in flight must leave the pane behind, or its change is the one that gets
        // lost. Stamping with the revision at the end would mark the pane current for a change
        // it never saw.
        val stamp = worldRevision()
        listJob = scope.launch {
            // Accumulated rather than assigned per chunk: `render` sorts the whole set it is
            // given, so each chunk has to arrive on top of the ones before it.
            val acc = ArrayList<VNode>()
            runCatching {
                vfs.list(path) { chunk ->
                    acc += chunk
                    raw = acc.toList()
                    listedAt = stamp
                    // loading stays true until the last chunk. It is what the path bar reads to
                    // show that more is coming, and the spinner is already conditional on there
                    // being no rows yet, so a partial list draws normally underneath it.
                    _state.update { render(it) }
                }
            }
                .onSuccess {
                    // After the listing lands, not before: if reading the folder failed there
                    // is nothing here worth indexing, and telling the indexer otherwise would
                    // spend a crawl budget on a path that does not resolve.
                    onLanded(path)
                    _state.update { render(it.copy(loading = false)) }
                    // A search that was open stays open across navigation; re-run it here
                    // rather than leaving stale hits from the previous folder on screen.
                    if (_state.value.search.active) runSearch(_state.value.search.query)
                }
                .onFailure { e ->
                    // A failure PART WAY through keeps what arrived: those rows were really
                    // read, and throwing them away to show an error would lose the only useful
                    // half of the answer. A failure before the first chunk leaves the pane empty
                    // with the error on it, which is the case this always handled.
                    _state.update { it.copy(loading = false, error = e.readable()) }
                }
        }
    }

    private fun goto(next: PaneState) {
        listJob?.cancel()
        searchJob?.cancel()
        back.addLast(_state.value.copy(entries = emptyList()))
        forward.clear()
        raw = emptyList()
        _state.value = next.copy(canGoBack = back.isNotEmpty(), canGoForward = false)
    }

    fun goBack(): Boolean {
        val prev = back.removeLastOrNull() ?: return false
        forward.addLast(_state.value.copy(entries = emptyList()))
        restore(prev)
        return true
    }

    fun goForward(): Boolean {
        val next = forward.removeLastOrNull() ?: return false
        back.addLast(_state.value.copy(entries = emptyList()))
        restore(next)
        return true
    }

    private fun restore(entry: PaneState) {
        // Written under whichever view was on then, drawn under the one on now: a folder left in
        // the list comes back as a focus in its drive's tree, and a focus left in the tree comes
        // back as that folder in the list.
        val s = shaped(entry)
        val now = _state.value
        if (FocusHistory.isFocusStep(now.kind == PaneKind.FOLDER, now.cwd, s.kind == PaneKind.FOLDER, s.cwd)) {
            restoreFocus(s)
            return
        }
        // Back and Forward are returns: the folder comes back where it was scrolled to.
        val target = s.copy(canGoBack = back.isNotEmpty(), canGoForward = forward.isNotEmpty(), resume = true)
        if (target.kind == PaneKind.FOLDER && target.cwd != null) {
            // Entries cleared for the same reason navigateTo clears them: a restored pane is
            // arriving somewhere else, and the rows in `target` came off the history stack with
            // entries already emptied, so the only thing they could show is the folder being
            // left.
            _state.value = target.copy(loading = true, entries = emptyList())
            raw = emptyList()
            listJob?.cancel()
            val stamp = worldRevision()
            listJob = scope.launch {
                val acc = ArrayList<VNode>()
                runCatching {
                    vfs.list(target.cwd) { chunk ->
                        acc += chunk
                        raw = acc.toList()
                        listedAt = stamp
                        _state.update { s2 -> render(s2) }
                    }
                }
                    .onSuccess { _state.update { s2 -> render(s2.copy(loading = false)) } }
                    .onFailure { e -> _state.update { s2 -> s2.copy(loading = false, error = e.readable()) } }
            }
            // A fold the entry gained by being shaped has never been read.
            if (prefs.treeView.value) {
                for (f in TreeRows.visibleFolds(target.cwd, target.open)) if (f !in target.branches) readBranch(f)
            }
        } else {
            _state.value = target
        }
    }

    /** [s] under the view that is on now, retitled when that moved its folder. See [TreeShape]. */
    private fun shaped(s: PaneState): PaneState {
        val out = TreeShape.shape(s, prefs.treeView.value, rootsForDevice)
        val cwd = out.cwd
        return if (cwd != null && cwd != s.cwd) out.copy(title = friendlyTitle(cwd)) else out
    }

    /**
     * Put this pane where the view that is on now says it stands: the tree turned on or off, or
     * the drives arrived after the pane did. A no-op when it already fits.
     *
     * Not a move, so nothing goes on the history. Turning the tree on opens the way down to the
     * folder the list was showing and focuses it; turning it off shows the focused folder rather
     * than the drive the tree hangs from. See [TreeShape].
     */
    fun reshape() {
        val s = _state.value
        if (s.kind != PaneKind.FOLDER) return
        val cwd = s.cwd ?: return
        if (prefs.treeView.value) {
            val root = TreeShape.rootFor(cwd, rootsForDevice) ?: return
            if (root != cwd) revealInTree(root, s.active ?: cwd, push = false)
        } else {
            val focus = s.active ?: return
            if (focus != cwd) navigateTo(focus, push = false)
        }
    }

    /**
     * Put back the branch that was focused at [s], in the folder the pane is already in.
     *
     * Only the focus moves. The listing and the branches on screen are the current ones - the
     * history entry kept neither, and re-reading a folder to move a rail would be a flash for
     * nothing. The folders above the restored focus are opened if they have been shut since, so
     * what is focused can be seen; the focus itself is opened only if it was open then.
     */
    private fun restoreFocus(s: PaneState) {
        val now = _state.value
        val cwd = now.cwd ?: return
        val at = s.active
        val chain = at?.let { a ->
            TreeRows.pathTo(cwd, a).filter { it != a || a in s.open }.filter { it != cwd }
        }.orEmpty()
        val hidden = chain.any { it !in now.open }
        // With the setting on, the folder Back leaves is folded away behind you (#12).
        val open = if (prefs.treeBackFolds.value) {
            TreeRows.foldLeft(now.open + chain, left = now.active, landing = at, keep = chain.toSet())
        } else {
            now.open + chain
        }
        _state.value = now.copy(
            active = at,
            open = open,
            canGoBack = back.isNotEmpty(),
            canGoForward = forward.isNotEmpty(),
            revealTarget = at ?: now.revealTarget,
            revealIfHidden = at != null,
        )
        for (f in chain) if (f !in now.branches) readBranch(f)
        dev.niccc2007.filet.data.Diag.log("tree", "focus restored $at hidden=$hidden")
    }

    /**
     * Step back through the focus history only, for the system Back press. True when it did.
     *
     * Asked BEFORE a back press closes the deepest fold: a focus is the most recent thing a tap
     * changed, and stepping it back is the smallest undo there is. Once the focus history in
     * this folder is used up, Back closes folds and then leaves, as it did.
     */
    fun stepFocusBack(): Boolean {
        val top = back.lastOrNull() ?: return false
        val now = _state.value
        if (!FocusHistory.isFocusStep(now.kind == PaneKind.FOLDER, now.cwd, top.kind == PaneKind.FOLDER, top.cwd)) {
            return false
        }
        return goBack()
    }

    /**
     * Up one folder.
     *
     * @param push whether this counts as a navigation the Back button can undo. True for the
     *   toolbar's Up arrow, which is a deliberate move. **False for the Back button itself**,
     *   and that is not a detail: with it true, back-as-up pushed the folder you just left onto
     *   the history, so the NEXT back found history and went straight back down into it. Back
     *   then oscillated between two folders forever and the screen never left. Reachable from a
     *   cold start - open Filet at a storage root and press back twice.
     */
    fun goUp(push: Boolean = true): Boolean {
        // In a tree the pane stays at its drive and the focus is where somebody is, so Up takes
        // the focus up a level; from the top of the drive it leaves, as it always did. Without
        // this, Up from four folders down would leave the whole drive in one press.
        val now = _state.value
        val focus = now.active
        if (prefs.treeView.value && now.kind == PaneKind.FOLDER && focus != null) {
            val above = dev.niccc2007.filet.vfs.MountBoundary.up(focus) ?: now.cwd ?: return false
            focusPath(above, record = push)
            _state.value.active?.let { at -> _state.update { it.copy(revealTarget = at, revealIfHidden = true) } }
            return true
        }
        // MountBoundary, not VPath.parent: inside an archive or an APK the `!` marks where the
        // real filesystem stops, and a plain string walk climbs straight through it into a
        // path in a scheme that cannot read it. See MountBoundary.
        val parent = _state.value.cwd?.let { dev.niccc2007.filet.vfs.MountBoundary.up(it) } ?: return false
        // A return, too: the folder above was being read a moment ago, and coming back up to its
        // first row loses the place in it.
        navigateTo(parent, push = push, resume = true)
        return true
    }

    /**
     * The refresh a person asked for. Settles the just-arrived tail.
     *
     * The tail is explicitly a temporary suspension of the sort, so the gesture that says
     * "put this in order" is the one that ends it. Use [relist] for a re-read the app decided
     * to do on its own.
     */
    fun refresh() {
        freshTail = FreshTail()
        // Opened branches are dropped AND RE-READ. A refresh is somebody saying "this is not
        // what is there", so keeping a listing from before it is the one answer that cannot be
        // right - but dropping it alone was worse.
        //
        // Bug identified here: this cleared `branches` and left `open` populated, and the only
        // thing that ever read a branch was `toggleBranch` on the way open. So every fold a
        // person had open rendered as a permanent "Reading..." row after any refresh, with no
        // way back except closing and reopening each one. That shipped. The fold set is the
        // source of truth for what has to be read, which is why the re-read is driven off it.
        _state.value = _state.value.copy(branches = emptyMap())
        // `relist` re-reads the folds itself, and `navigateTo` leaves `open` and `branches`
        // alone, so clearing first and re-listing second is the whole sequence. Calling the
        // re-read again here would start a second listing of every open fold against the
        // first, for no gain.
        relist()
    }

    /**
     * Read every open fold again, from scratch.
     *
     * Driven off `open` rather than off `branches`, deliberately: a branch that is missing
     * from `branches` is exactly the case that needs reading, so iterating the map would skip
     * precisely the folds that are broken.
     *
     * The cost is bounded by what somebody deliberately opened, and `MAX_DEPTH` bounds that in
     * turn. Each read is streamed and paced like any other listing, and a fold closed while
     * its read is in flight stops costing - see [readBranch].
     */
    private fun reReadOpenBranches() {
        for (path in _state.value.open) readBranch(path)
    }

    /**
     * Open or close a folder inside the listing, for the tree view.
     *
     * Children are read once and kept until the pane is refreshed or the branch is closed -
     * a tree that re-read every folder on every recomposition would hammer the card, and one
     * that never re-read would go stale with no way back.
     */
    fun toggleBranch(path: VPath) {
        val now = _state.value
        val next = TreeRows.toggle(now.open, path)
        val opening = path in next
        _state.value = now.copy(
            open = next,
            // Closing drops what was under it, which is also what frees a large branch.
            branches = if (opening) now.branches else now.branches.filterKeys { it in next },
            // Opening a branch is also standing in it. Closing one you were standing in
            // puts you back in the pane's own folder rather than leaving the working
            // folder pointing at something that is no longer on screen - a create action
            // aimed at a folder nobody can see is the fault this whole field exists for.
            active = when {
                opening -> path
                now.active == null -> null
                now.active == path -> null
                TreeBranch.under(path, now.active!!) -> null
                else -> now.active
            },
        )
        dev.niccc2007.filet.data.Diag.log(
            "tree", "toggle $path opening=$opening open=${next.size}",
        )
        if (!opening || now.branches.containsKey(path)) return
        readBranch(path)
    }

    /**
     * Read one fold's children into [PaneState.branches].
     *
     * Extracted from [toggleBranch] because opening a fold was the only thing that could ever
     * trigger it, which made a cleared `branches` map unrecoverable. Two other callers need it:
     * a refresh, which empties the map on purpose, and a re-list, whose folder has changed
     * underneath the folds as well as inside the pane.
     *
     * Idempotent in the way that matters: a path not in `open` publishes nothing, so a fold
     * closed mid-read neither reopens itself nor keeps paying for its listing.
     */
    private fun readBranch(path: VPath) {
        scope.launch {
            // Streamed, like every other listing here. A branch can be a camera folder too,
            // and reading five thousand files before the first row appears is the stall the
            // whole listing pipeline exists to avoid - see `relist`.
            val acc = ArrayList<VNode>()
            var lastPush = 0L
            val showHidden = prefs.showHidden.value
            val sort = prefs.sort.value
            suspend fun publish() {
                // Sorted OFF the main thread. Bug identified here: `vfs.list` hands its chunks
                // back on the caller's dispatcher, which for a pane is the main thread, so every
                // paced publish sorted the whole branch so far - a natural-order sort of a few
                // thousand names - inside a frame. Opening a big folder dropped frames for as long
                // as it streamed, and that stutter is what the chevron turning and the outline
                // fading in were drawn through.
                val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    acc.toList().filter { showHidden || !it.name.startsWith('.') }.sortedBy(sort)
                }
                // Closed while it sorted: publishing now would reopen it.
                if (path !in _state.value.open) return
                _state.value = _state.value.copy(branches = _state.value.branches + (path to rows))
            }
            runCatching {
                vfs.list(path) { chunk ->
                    acc += chunk
                    // A fold closed mid-read must not reopen itself, and must stop costing.
                    if (path !in _state.value.open) return@list
                    // The FIRST chunk goes up immediately and the rest are paced.
                    //
                    // Publishing every chunk sorts the whole accumulation each time, which
                    // on a folder of a few thousand is most of the work and all of the wait
                    // - the rows crawled in rather than arriving. The first chunk is what
                    // makes the branch look open, so it is never delayed; after that a
                    // frame's worth of pacing turns dozens of full sorts into a handful.
                    val now = android.os.SystemClock.elapsedRealtime()
                    val first = _state.value.branches[path] == null
                    if (!first && now - lastPush < PUSH_EVERY_MS) return@list
                    lastPush = now
                    publish()
                }
                // And the last word, always: the final chunk usually falls inside the
                // pacing window, and a branch that is missing its tail until something
                // else happens to redraw it is worse than one that was slow.
                if (path in _state.value.open) publish()
            }
        }
    }

    /**
     * How often a branch being read publishes what it has.
     *
     * One frame. Fast enough that rows appear to stream in, slow enough that a folder of
     * thousands is not re-sorted sixty times on the way.
     */
    private val PUSH_EVERY_MS = 16L

    /**
     * Put back the folds a restored tab had open, and read them.
     *
     * Separate from [revealInTree] because nothing is being revealed: the folder is already
     * where it should be and this only re-opens the branches around it. Using the reveal path
     * would also set `revealTarget`, which scrolls the pane - wrong on a restore, where the
     * scroll position is its own thing and is not restored at all.
     *
     * The read is driven off the fold set, like every other re-read here, so a fold whose
     * parent did not survive the cap simply never opens rather than appearing parentless.
     */
    fun restoreFolds(folds: List<VPath>) {
        if (folds.isEmpty()) return
        _state.update { it.copy(open = it.open + folds) }
        for (f in folds) readBranch(f)
    }

    /** Open one of the whole-phone categories. See `CategoryScreen`. */
    fun openCategory(category: dev.niccc2007.filet.home.MediaCategory) {
        val prev = _state.value
        back.addLast(prev.copy(entries = emptyList()))
        forward.clear()
        _state.value = prev.copy(
            kind = PaneKind.CATEGORY,
            category = category,
            title = category.label,
            // Cleared, or the pane keeps the folder it came from and the path bar claims to be
            // somewhere it is not.
            cwd = null,
            entries = emptyList(),
            selected = emptySet(),
            loading = false,
            error = null,
            canGoBack = back.isNotEmpty(),
            canGoForward = forward.isNotEmpty(),
        )
    }

    /**
     * The chevron: close an open branch, keeping it in view (see [collapseBranch]), or open a
     * closed one. Decided here, from the state as it is when tapped, so the row's callback
     * carries nothing that changes and the row can skip recomposing.
     */
    fun flipBranch(path: VPath) {
        if (path in _state.value.open) collapseBranch(path) else toggleBranch(path)
    }

    /** Fold everything away, for the toolbar's collapse-all. */
    fun collapseTree() {
        _state.value = _state.value.copy(open = emptySet(), branches = emptyMap(), active = null)
    }

    /**
     * Close one branch and keep the folder it belonged to in view.
     *
     * ## The yank this avoids
     *
     * A `LazyColumn` with keys anchors on its first VISIBLE row, so rows disappearing above
     * the viewport cost nothing - that case was already right. The one that is not is closing
     * a branch you are reading INSIDE: the anchor row itself goes, and the list lands wherever
     * the new row at that index happens to be, which is usually somewhere else entirely.
     *
     * So the fold being closed becomes the reveal target, and the existing machinery scrolls
     * it in once the rows have settled. See [revealOnly] for why that is request-driven rather
     * than a timed scroll.
     */
    fun collapseBranch(path: VPath) {
        if (path !in _state.value.open) return
        toggleBranch(path)
        _state.update { it.copy(revealTarget = path) }
    }

    /**
     * Arrive at [target] from outside the pane - a shortcut, a widget, a bookmark, a result.
     *
     * The same as [navigateTo] now that every navigation with the tree on is an arrival (see
     * [TreeShape]); kept as the name those call sites read as. A target under no drive - an
     * archive mount, a remote, a provider with no volume list - is navigated to, and a tree
     * there is rooted at that mount's own top.
     */
    fun jumpTo(target: VPath, push: Boolean = true) = navigateTo(target, push = push)

    /**
     * Close the innermost open branch, for a back press. False when there was none.
     *
     * Only ever true while the tree is actually on: with it off there are no folds, and a back
     * press has to keep meaning what it has always meant. See [TreeRows.deepestFold] for why it
     * is the deepest one.
     */
    fun collapseDeepest(): Boolean {
        if (!prefs.treeView.value) return false
        // Only a fold that is drawn. Bug identified here: `open` keeps the folds of a drive the
        // pane has since left, and of folders inside a closed one, so Back spent presses closing
        // them with nothing on screen changing.
        val s = _state.value
        val drawn = s.cwd?.let { TreeRows.visibleFolds(it, s.open).toSet() } ?: return false
        val fold = TreeRows.deepestFold(drawn) ?: return false
        collapseBranch(fold)
        return true
    }

    /**
     * Land on [target] with the tree open from [root] down to it.
     *
     * ## Why a jump is not a navigation
     *
     * A shortcut, a widget, a bookmark and a search result all arrive from OUTSIDE the pane,
     * and `navigateTo` makes the thing it is given the pane's own folder. In a flat list that
     * is exactly right. In a tree it means the tree's root becomes whatever was tapped, so a
     * shortcut to Downloads produced a tree with Downloads at the top, no internal storage
     * above it and no siblings beside it - a tree with no trunk, which is a flat list wearing
     * guide lines.
     *
     * With the tree on every navigation under a drive comes here; see [TreeShape] for the rule.
     */
    fun revealInTree(root: VPath, target: VPath, push: Boolean = true) {
        val prev = _state.value
        // Already in this drive's tree, the pane does not move and only the focus does, so it is
        // a focus step Back can undo (see FocusHistory) rather than nothing on the history.
        if (push && prev.kind == PaneKind.FOLDER && prev.cwd == root && prev.active != target) {
            back.addLast(prev.copy(entries = emptyList(), branches = emptyMap()))
            forward.clear()
        }
        navigateTo(root, push = push)
        val chain = TreeShape.chainTo(root, target)
        _state.update {
            it.copy(
                open = it.open + chain,
                // The target is also the working folder, so New folder and Paste land where
                // somebody was taken rather than at the volume root they were taken through.
                active = target.takeIf { t -> t != root },
                revealTarget = target,
            )
        }
        // And read them. Bug identified here: the folds were opened and never read - nothing
        // reads a branch except opening it by hand, a refresh, or a re-list - so after "Show
        // where this is" the opened folder sat on "Reading..." until it was closed and opened
        // again. A branch already read is kept: the folder it lists has not changed by being
        // arrived at.
        val s = _state.value
        for (f in chain) if (f !in s.branches) readBranch(f)
    }

    /**
     * Re-read this folder without settling the tail.
     *
     * What every internal re-list uses - the one after a copy, a move, a rename or a create.
     * Those re-reads exist BECAUSE something just arrived, so treating them as a refresh would
     * undo the placement in the same breath as making it.
     */
    fun relist() {
        val s = _state.value
        if (s.kind == PaneKind.FOLDER && s.cwd != null) navigateTo(s.cwd, push = false)
        // And the folds. Bug identified here: this re-listed `cwd` alone, so a folder created
        // or pasted INTO an open branch - which is what `active` makes the default target -
        // did not appear in that branch until it was closed and opened again. The file landed,
        // the app re-read, and the row the person was looking at did not change.
        reReadOpenBranches()
    }

    /**
     * Something just landed in this folder.
     *
     * Paths rather than a count, and the INTENDED destinations rather than confirmed ones: a
     * path that did not actually appear is dropped when the tail is applied, so a partly
     * failed batch corrects itself without this having to know which half failed.
     */
    fun noteArrived(paths: List<VPath>) {
        if (paths.isEmpty()) return
        freshTail = freshTail.arrived(paths)
    }

    /** Drop one from the tail, because it was deleted or renamed away. */
    fun noteGone(path: VPath) {
        freshTail = freshTail.forget(path)
    }

    /**
     * Re-read a folder that has nothing to show but should have.
     *
     * A tab restored at cold start lists its folder immediately - which on a fresh launch can
     * be *before* all-files access is confirmed, so the read fails and the tab renders empty
     * until it is tapped again. That is the bug where "the last tab was in storage and the tab
     * shows empty". Cheap enough to call on every resume and every tab switch, because it
     * does nothing at all when the pane already has rows.
     */
    fun refreshIfEmpty() {
        val s = _state.value
        if (s.kind != PaneKind.FOLDER || s.cwd == null) return
        if (s.loading) return
        if (raw.isNotEmpty() && s.error == null) return
        navigateTo(s.cwd, push = false)
    }

    /**
     * Re-read this folder if the world has moved since it was listed.
     *
     * Called from every path that makes a pane visible - tab switch, split-pane change, app
     * resume, returning from a viewer - so it covers every start pipeline that opens a
     * folder, not only the back-navigation case. Cheap by construction: a pane that is
     * already current does nothing at all, so calling it often costs nothing.
     */
    fun freshenIfStale(visible: Boolean = true) {
        val s = _state.value
        val stale = FolderFreshness.shouldRelist(
            isFolder = s.kind == PaneKind.FOLDER,
            hasPath = s.cwd != null,
            loading = s.loading,
            visible = visible,
            listedAtRevision = listedAt,
            worldRevision = worldRevision(),
            dragging = dragging(),
        )
        if (stale) navigateTo(s.cwd ?: return, push = false)
    }

    fun open(node: VNode) {
        // A result still being checked is checked first. SEARCH.md §14.3: a row may be SEEN
        // before its check comes back, never used. The common case passes in a millisecond and
        // opens as though nothing happened; the rare one leaves the list and says why, instead
        // of handing a dead path to a viewer that fails with something less clear.
        val hit = _state.value.search.takeIf { it.active }?.hits?.firstOrNull { it.node.path == node.path }
        if (hit != null && !actionable(hit.state)) {
            scope.launch {
                val checked = runCatching { vfs.stat(node.path) }
                // A share that does not answer has not said the file is gone. The row stays as
                // it was, still unopenable, and the reason given is the true one.
                if (checked.isFailure) {
                    onNotice("Can't reach that drive right now.")
                    return@launch
                }
                val live = checked.getOrNull()
                settle(hit.key, live)
                if (live == null) {
                    onGone(node.path)
                    onNotice("That file is no longer there.")
                } else {
                    openChecked(live)
                }
            }
            return
        }
        openChecked(node)
    }

    private fun openChecked(node: VNode) {
        val kind = FileKind.of(node)
        when {
            node.isDir -> navigateTo(node.path)
            kind.isContainer -> onOpened(node, emptyList())      // routed by the ViewModel: archive or APK mount
            // The rows as drawn, search results or the listing, so a viewer swipes in the order
            // the eye just saw.
            else -> onOpened(node, _state.value.visible)
        }
    }

    fun startEditingPath(v: Boolean) = _state.update { it.copy(editingPath = v) }

    /**
     * A tab's label.
     *
     * `/storage/emulated/0` is the shared volume, and a tab reading "0" is a tab nobody can
     * identify at a glance. The special cases are few and they are the ones people see daily.
     */
    private fun friendlyTitle(path: VPath): String = when {
        path.path == "/storage/emulated/0" -> "Internal"
        path.path == "/" -> path.scheme
        path.scheme == "zip" || path.scheme == "apk" ->
            path.path.substringBefore('!').substringAfterLast('/').ifEmpty { path.scheme }
        else -> path.name.ifEmpty { path.scheme }
    }

    // ── selection ──

    /**
     * The nodes behind the selected paths, as they were when picked.
     *
     * Bug identified here: [selectedNodes] looked the paths up in the pane's own rows and the
     * search hits, and a file selected inside an open branch is in neither - it lives in
     * `branches`. So it resolved to nothing, the selection bar and the menu were built from an
     * empty list, and an empty list offers only the actions that need no file: what was left
     * looked exactly like a folder's menu. The branches are searched now, and this keeps what
     * was picked so a selection survives its branch being folded away while another is worked
     * in - which is the whole reason to select across a tree.
     */
    private val picked = HashMap<VPath, VNode>()

    /**
     * The rows a screen with no folder behind it is drawing, in the order it draws them: a
     * category, New files. Such a pane has no `entries`, so a range, a select-all and the
     * selection bar's total had nothing to work over - the screen says what is on it instead.
     */
    private val _shown = MutableStateFlow<List<VNode>>(emptyList())
    val shown: StateFlow<List<VNode>> = _shown.asStateFlow()

    /** Called by a special pane's screen whenever the rows it draws change, and with none as it goes. */
    fun showRows(rows: List<VNode>) {
        _shown.value = rows
    }

    /** Set the selection, keeping [picked] to exactly what is in it. */
    private fun choose(next: Set<VPath>, nodes: Iterable<VNode> = emptyList()) {
        for (n in nodes) if (n.path in next) picked[n.path] = n
        picked.keys.retainAll(next)
        _state.update { it.copy(selected = next) }
    }

    /**
     * The rows in the order they are drawn: the tree's, opened branches and all, while the tree
     * is on. A range or a select-all over the pane's own rows alone would skip every row inside
     * an open branch.
     */
    private fun rowsInOrder(s: PaneState): List<VNode> = when {
        // A category or New files: no listing of its own, only what its screen drew.
        s.kind != PaneKind.FOLDER -> _shown.value
        prefs.treeView.value && !s.search.active && s.open.isNotEmpty() ->
            TreeRows.flatten(s.visible, s.open, s.branches).filter { !it.loading && !it.empty }.map { it.node }
        else -> s.visible
    }

    /**
     * True when a live pick took [node], so this pane's own selection must not change.
     *
     * Bug identified here: during a pick, a TAP went to the pick's own list and every other way
     * of selecting - a hold, a double tap, the icon, the peek's Select - went to this pane's
     * selection, which the pick card never reads. A held file showed as selected and was not in
     * the pick; tapping it next started a second selection beside the first. Every one of those
     * gestures arrives through [toggleSelect], [selectOnly] or [extendSelectionTo], so the pick
     * claims the row at those three doors and there is one selection while it is live.
     *
     * @param put what the pick does with a file: [PickPort.take] for a toggle, an add for
     *   [selectOnly], a range for [extendSelectionTo].
     */
    private inline fun pickTook(node: VNode, put: () -> Unit): Boolean = when (PickRoute.of(pick.live(), node.isDir)) {
        PickRoute.PANE -> false
        PickRoute.PICK -> { put(); true }
        PickRoute.NOTHING -> true
    }

    /**
     * The selection a gesture decides against: the pick's list while a pick is live, this pane's
     * otherwise. Every hold, double tap and slide reads it here, never `state.selected`, or the
     * same gesture means one thing in the browser and another in a pick. See [PickPort].
     */
    fun selectionNow(): Set<VPath> = if (pick.live()) pick.chosen() else _state.value.selected

    /** What a hold on [path] means now, against [selectionNow]. See [longPressAction]. */
    fun holdAction(path: VPath): LongPressAction {
        val now = selectionNow()
        return longPressAction(now.size, path in now)
    }

    /**
     * Hand the move from [selectionNow] to [next], over [rows], to the pick. A run never drops a
     * file this gesture did not pass over: see [PickRun.diff].
     */
    private fun pickTo(next: Set<VPath>, rows: List<VNode>, target: VNode?) {
        val (add, remove) = PickRun.diff(rows, pick.chosen(), next)
        pick.run(add, remove, target)
    }

    /**
     * A tap while selecting. Picking a folder takes in what was selected inside it - see
     * [TreeSelection] - and a row a selected folder already includes says so instead.
     */
    fun toggleSelect(node: VNode) {
        if (pickTook(node) { pick.take(node) }) return
        val s = _state.value
        val next = TreeSelection.toggle(s.selected, node.path)
        if (next == null) {
            val by = TreeSelection.includedBy(s.selected, node.path)
            onNotice("Already selected with ${by?.name ?: "its folder"}. Deselect that to pick inside it.")
            return
        }
        anchor = node.path
        choose(next, listOf(node))
    }

    fun selectOnly(node: VNode) {
        // In a pick, a file already chosen stays chosen and becomes the anchor; a toggle here
        // would take it OUT on the press that is meant to pick it.
        if (pickTook(node) { if (node.path in pick.chosen()) pick.run(emptyList(), emptySet(), node) else pick.take(node) }) return
        anchor = node.path
        choose(setOf(node.path), listOf(node))
    }

    /**
     * Open, in the tree, as Xplorer does it (#34): a folder unfolds and is stood in; the folder
     * already stood in folds or unfolds; a folder above the one stood in keeps open and folds the
     * branch it is gone back up out of. A file is opened from the folder it is in.
     *
     * The tree's other tap, [focusRow], only stands in a folder and leaves the folding to a second
     * tap - which is the Filet preset. Both stay, as two choices in the tap table.
     */
    fun treeOpen(node: VNode) {
        val branch = TreeBranch.keyOf(node)
        if (branch == null) {
            focusRow(node)
            open(node)
            return
        }
        val s = _state.value
        if (s.active == branch) {
            flipBranch(branch)
            focusPath(branch, record = false)
            return
        }
        // Recorded FIRST. Bug identified here: opening a branch already stands in it, so with the
        // focus moved after the open, `focusPath` found it there, returned, and recorded nothing.
        // No step was ever kept, so Back found none, fell through to the pane's own folder, and
        // with "Back closes the folder you leave" on folded the branch on its way to the root.
        focusPath(branch)
        val open = _state.value.open
        val folded = TapPlan.foldOnTheWay(open, branch, s.active)
        if (folded != open) _state.update { it.copy(open = folded) }
        if (branch !in _state.value.open) toggleBranch(branch)
    }

    /**
     * Stand in the branch a row belongs to, without opening or closing anything: the folder
     * itself for a folder row, the folder a file is in for a file row. A tree row's tap.
     *
     * Focus is [PaneState.active] - where New folder, New file and Paste land, and what the
     * path bar names. It used to move only when a branch was OPENED, so aiming a paste at a
     * folder that was already open meant closing it and opening it again.
     */
    fun focusRow(node: VNode) {
        // An archive is a branch of its own; a file stands in the branch it is drawn in, which
        // for the top of an archive is the archive.
        val at = TreeBranch.keyOf(node) ?: dev.niccc2007.filet.vfs.MountBoundary.up(node.path) ?: return
        focusPath(at)
    }

    /**
     * A breadcrumb tapped. Inside the tree's own folder, while a branch is focused, it moves the
     * focus up to that crumb - the crumbs below the root are branches of this tree, and walking
     * to one would throw the tree away to re-root it there. Anywhere else it walks, as it always
     * did.
     */
    fun crumbTo(path: VPath) {
        val s = _state.value
        val cwd = s.cwd
        if (cwd != null && s.active != null && TreeBranch.under(cwd, path)) focusPath(path) else navigateTo(path)
    }

    /**
     * Focus [at], or the pane's own folder when [at] is that folder or outside it.
     *
     * @param record put the focus being left on the history, so Back puts it back. True for a
     *   tap; false for anything putting a pane back the way it was, which is not a move.
     */
    fun focusPath(at: VPath, record: Boolean = true) {
        val s = _state.value
        val cwd = s.cwd ?: return
        val next = at.takeIf { it != cwd && TreeBranch.under(cwd, it) }
        if (next == s.active) return
        if (record) {
            // Without the rows or the branches: restoring a focus keeps what is on screen, so a
            // copy of either would only be memory held for every tap.
            back.addLast(s.copy(entries = emptyList(), branches = emptyMap()))
            forward.clear()
        }
        _state.update { it.copy(active = next, canGoBack = back.isNotEmpty(), canGoForward = forward.isNotEmpty()) }
    }

    /**
     * Select [node] and ask the listing to scroll it into the middle.
     *
     * The scroll is a request, not an action: this controller does not know how tall a row is
     * or how many fit, so it says which file and the list works out where.
     */
    fun revealOnly(node: VNode) {
        anchor = node.path
        choose(setOf(node.path), listOf(node))
        _state.update { it.copy(revealTarget = node.path) }
    }

    /** Called by the list once it has scrolled, so a later listing does not scroll again. */
    fun revealHandled() = _state.update {
        if (it.revealTarget == null && !it.revealIfHidden) it else it.copy(revealTarget = null, revealIfHidden = false)
    }
    /**
     * The last row picked deliberately, which a range extends FROM.
     *
     * Not the lowest selected index: using that would make a second range run from the wrong
     * end as soon as somebody had selected upwards.
     */
    private var anchor: VPath? = null

    /** Shift-click. See `RangeSelect.kt` for the arithmetic and why it is separate. */
    fun extendSelectionTo(node: VNode) {
        // In a pick the same range, from the pick's anchor over the pick's list.
        if (pickTook(node) {
                val rows = rowsInOrder(_state.value)
                pickTo(rangeSelect(rows.map { it.path }, pick.chosen(), pick.anchor(), node.path), rows, node)
            }
        ) return
        val s = _state.value
        val rows = rowsInOrder(s)
        val next = rangeSelect(
            visible = rows.map { it.path },
            current = s.selected,
            anchor = anchor,
            target = node.path,
        )
        anchor = node.path
        choose(TreeSelection.absorb(next), rows)
    }

    /**
     * A finger sliding across a gallery after a hold, from [start] to [current], measured
     * against [before] - the selection when the hold began, read from [selectionNow]. See
     * `dragSelect` for the arithmetic.
     */
    fun dragSelectTo(before: Set<VPath>, start: VPath, current: VPath, adding: Boolean = start !in before) {
        val rows = rowsInOrder(_state.value)
        val next = dragSelect(rows.map { it.path }, before, start, current, adding)
        if (next == selectionNow()) return
        if (pick.live()) {
            pickTo(next, rows, rows.firstOrNull { it.path == current })
            return
        }
        anchor = current
        choose(next, rows)
    }

    fun clearSelection() {
        anchor = null
        choose(emptySet())
    }

    fun selectAll() {
        val rows = rowsInOrder(_state.value)
        if (pick.live()) {
            pickTo(rows.mapTo(HashSet()) { it.path }, rows, null)
            return
        }
        // Absorbed, so a tree with folders open selects the folders once and not their
        // contents a second time.
        choose(TreeSelection.absorb(rows.mapTo(LinkedHashSet()) { it.path }), rows)
    }

    fun invertSelection() {
        val s = _state.value
        val rows = rowsInOrder(s)
        if (pick.live()) {
            pickTo(TreeSelection.invert(rows.map { it.path }, pick.chosen()), rows, null)
            return
        }
        choose(TreeSelection.invert(rows.map { it.path }, s.selected), rows)
    }

    fun selectedNodes(): List<VNode> {
        val s = _state.value
        return TreeSelection.resolve(
            s.selected,
            s.entries,
            s.branches.values.asSequence().flatten().asIterable(),
            s.search.hits.map { it.node },
            picked.values,
            // A selection put back by Back on a category, where nothing was picked this time.
            _shown.value,
        )
    }

    // ── listing presentation ──

    /** Re-applies sort and the hidden-file filter to the cached listing. No disk access. */
    fun reapply() = _state.update { render(it) }

    private fun render(s: PaneState): PaneState {
        val showHidden = prefs.showHidden.value
        val spec: SortSpec = prefs.sort.value
        val filtered = if (showHidden) raw else raw.filterNot { it.hidden || it.name.startsWith(".") }
        // The one place the sort is applied, so the one place the tail can hold rows out of
        // it. Everything downstream reads `entries` and needs no knowledge of either.
        return s.copy(entries = freshTail.applyTo(filtered.sortedBy(spec)), total = raw.size)
    }

    /** Type-to-jump (SEARCH.md §5.6): the index of the first row starting with [prefix]. */
    fun jumpIndex(prefix: String): Int {
        if (prefix.isEmpty()) return -1
        val p = prefix.lowercase()
        return _state.value.visible.indexOfFirst { it.name.lowercase().startsWith(p) }
    }

    // ── search ──

    fun openSearch(open: Boolean) = _state.update {
        if (open) it.copy(search = it.search.copy(open = true))
        else {
            searchJob?.cancel()
            confirmJob?.cancel()
            // Closing search ends any detour it caused. Leaving one running would keep a crawl
            // bent toward a query nobody is looking at any more.
            onSearched("")
            // The probes belong to this search. A share that was asleep five minutes ago is
            // asked again next time rather than remembered as down.
            probes.forEach { p -> p.cancel() }
            probes.clear()
            probed.clear()
            it.copy(search = SearchUi(reach = it.search.reach))
        }
    }

    fun setReach(reach: Reach) {
        _state.update { it.copy(search = it.search.copy(reach = reach)) }
        if (_state.value.search.active) runSearch(_state.value.search.query)
    }

    /**
     * Search by reading the filesystem and nothing else.
     *
     * Offered because an index can be cold and a user cannot see that it is. Pressing this is
     * the difference between "Filet says it is not there" and "Filet has looked".
     */
    /** The search bar's hidden-folders switch. Saved, and the search re-asked. SEARCH.md §14.5. */
    fun setSearchHidden(on: Boolean) {
        prefs.setSearchHidden(on)
        if (_state.value.search.active) runSearch(_state.value.search.query)
    }

    fun setNativeOnly(on: Boolean) {
        _state.update { it.copy(search = it.search.copy(nativeOnly = on)) }
        if (_state.value.search.active) runSearch(_state.value.search.query)
    }

    fun setQuery(q: String) {
        _state.update { it.copy(search = it.search.copy(query = q)) }
        runSearch(q)
    }

    /**
     * @param keepOrder true for a re-run against a growing index: the rows already drawn keep
     *   their places and only new ones append. False for a query the person just typed, which
     *   should be ranked fresh - that is a different list, not a longer one.
     */
    private fun runSearch(q: String, keepOrder: Boolean = false) {
        searchJob?.cancel()
        if (q.isBlank()) {
            onSearched("")
            _state.update { it.copy(search = it.search.copy(hits = emptyList(), running = false, painted = false)) }
            return
        }
        val s = _state.value
        // A filesystem search is only an answer a FOLDER pane can use. Searching from
        // Settings used to walk the device and hand back files, which is not a setting and
        // not something that pane can show. See searchPlan.
        if (searchPlan(s.kind) != SearchMode.FILESYSTEM) {
            // The query stays in the state; each special screen narrows its own rows from it.
            _state.update { it.copy(search = it.search.copy(hits = emptyList(), running = false, painted = true)) }
            return
        }
        // Resolved, not taken as given, and for two reasons now: Home has no cwd, so a
        // tree-shaped reach there is an impossible search rather than a narrow one; and a reach
        // naming a card that has been pulled or a share that has been deleted has to narrow
        // visibly instead of retrieving from a volume id that no longer resolves. Both used to
        // be answered with an empty list and no explanation.
        // Not named `scope`: this class already has one, and it is the CoroutineScope.
        // `target`, not `cwd`: in a tree the pane stays at its drive, and "This folder" is the
        // one focused in it.
        val searchReach = ReachLadder.resolve(s.search.reach, s.target, reachDrives)
        probeShares(ResultGroups.sharesIn(searchReach, reachDrives).map { it.key })
        // Read once. Whether a walk can serve this is a property of the QUERY - `from:` is
        // recorded in the index and written nowhere on the filesystem - and parsing it three
        // times to ask the same question three ways is how the answers drift apart.
        val provenance = dev.niccc2007.filet.index.QueryParser.parse(q).needsProvenance
        val req = SearchRequest(
            query = q,
            reach = searchReach,
            origin = s.target,
            // Not `rootsForDevice`: that list is what `jumpTo` resolves a volume from and it
            // includes the superuser volumes, which a search must not walk unless they were
            // asked for by name. See ReachLadder.everywhereRoots.
            roots = if (reachDrives.isEmpty()) rootsForDevice else ReachLadder.everywhereRoots(reachDrives),
            // The search bar's own switch, not the browser's. Whether to SEE dot-folders while
            // browsing is not whether a thumbnail cache belongs in an answer. SEARCH.md §14.5.
            showHidden = prefs.searchHidden.value,
            onWalked = ::noteWalked,
        )
        // Every source the plan names, not the first that claims the request.
        //
        // Bug identified: this was `firstOrNull { it.handles(req) }`. The index claims
        // SUBFOLDERS, so a search under a folder the crawl had not reached was answered by the
        // index, found nothing, and stopped - while the walk that would have found it never
        // ran. It presented as a file in a subfolder of Download being invisible with Subfolders
        // selected and found immediately from inside that subfolder - which worked only
        // because the index declines a single-folder search.
        val wanted = sourcePlan(
            indexUsable = searchSources.any { it.handles(req.copy(reach = Reach.Everywhere)) },
            reach = searchReach,
            nativeOnly = s.search.nativeOnly,
            provenance = provenance,
        )
        // Bug identified here, which presented as the Native search toggle changing nothing: the source for
        // a planned kind was picked by POSITION in the list and then called without checking it
        // accepted the request. The index declines a single-folder scope, so with Folder
        // selected the plan asked for an index, was handed one by position, and called it with
        // a request it had already refused - and turning the toggle on removed a source that
        // was never legitimately contributing, so the list did not move.
        //
        // A source now declares its own kind, and it is used only if it also accepts THIS
        // request rather than a differently-scoped one invented to test it with.
        val planned = wanted.mapNotNull { kind ->
            searchSources.firstOrNull { it.kind == kind && it.handles(req) }
        }.distinct()
        // The index answers; the walk reads only what the index does not cover. SEARCH.md §14.2,
        // decision 15. Without the index in the plan - the Native search toggle, a cold index -
        // the walk is the answer and reads everything, as it always did.
        val indexAnswers = planned.any { it.kind == SourceKind.INDEX }
        val walkReq = if (indexAnswers) req.copy(roots = walkGaps(req.roots, covered(), searchReach)) else req
        val sources = planned.filterNot { src ->
            src.kind == SourceKind.WALK && indexAnswers && spansDrives(searchReach) && walkReq.roots.isEmpty()
        }
        _state.update {
            it.copy(search = it.search.copy(
                origin = originLine(sources.map { src -> src.kind }),
                nativeMatters = nativeToggleMatters(
                    indexUsable = searchSources.any { src -> src.handles(req.copy(reach = Reach.Everywhere)) },
                    reach = searchReach,
                    provenance = provenance,
                ),
            ))
        }
        if (sources.isEmpty()) {
            _state.update { it.copy(search = it.search.copy(hits = emptyList(), running = false, painted = true)) }
            return
        }
        // Started here rather than from publish, and at most once per open search: it keeps its
        // own clock, and restarting it on every publish is what stopped that clock working.
        watchConfirmations()
        searchJob = scope.launch {
            // Debounce 120 ms: 300 feels laggy, under 100 wastes queries (SEARCH.md §5.5).
            delay(120)
            // After the debounce, so a crawl is not re-steered on every keystroke. No-op
            // unless something is actually crawling - see FileIndex.steerCrawl.
            onSearched(q)
            if (!keepOrder) checked.clear()
            _state.update {
                it.copy(search = it.search.copy(running = true, painted = false, walked = emptyMap()))
            }

            val collected = ArrayList<SearchHit>()
            // A re-run starts as though it had already painted, which is what makes every one of
            // its publishes stable. Without this the first publish of a re-run re-sorts the whole
            // set by score and the list jumps - the exact instability this has to avoid.
            var painted = keepOrder
            var published = 0
            val firstPaintAt = System.currentTimeMillis() + FIRST_PAINT_MS

            // Publishing runs on its own clock. SEARCH.md §14.8.
            //
            // Bug identified here: rows were published when one arrived and the count was a
            // multiple of 24. A slow trickle - a walker reading a share - therefore sat unseen for
            // as long as it took 24 more rows to turn up, and the first paint itself waited for a
            // row to arrive after its deadline. The list stalled while results existed, with
            // nothing on screen saying why. A tick publishes whatever has arrived, whenever it
            // arrived.
            val ticker = launch {
                while (true) {
                    delay(if (painted) PUBLISH_EVERY_MS else FIRST_PAINT_POLL_MS)
                    if (collected.size == published) continue
                    if (!painted) {
                        if (System.currentTimeMillis() < firstPaintAt) continue
                        painted = true
                        published = collected.size
                        publish(ArrayList(collected), stable = false)
                    } else {
                        // After first paint, later arrivals are appended in arrival order so rows
                        // never move under a thumb that is already reaching for one (§5.3).
                        published = collected.size
                        publish(ArrayList(collected), stable = true)
                    }
                }
            }
            // Merged as they arrive rather than one source after the other: the index answers
            // before the finger leaves the key, and a user who is going to be shown a result
            // should not wait for a walk to come up empty first. Duplicates are removed in
            // `publish`, which is keyed by path.
            merge(*sources.map { src -> src.search(if (src.kind == SourceKind.WALK) walkReq else req) }.toTypedArray())
                .collect { hit -> collected += hit }
            ticker.cancel()
            publish(collected, stable = painted)
            _state.update { it.copy(search = it.search.copy(running = false, painted = true)) }
        }
    }

    /**
     * @param stable once true, the already-painted prefix keeps its order and only the tail
     *   is ranked. Re-sorting the whole list on every arrival is the reshuffle-under-the-thumb
     *   failure SEARCH.md §5.3 exists to prevent.
     */
    /**
     * The one place search results reach the screen - and therefore the one place that has to
     * guarantee **one row per path**.
     *
     * A whole-device search can legitimately see the same file twice: volume roots overlap
     * (`/storage/emulated/0` and `/storage/self/primary` are the same bytes), and the index
     * can hold two node ids that resolve to one path. The list is keyed by path, and a
     * LazyColumn with a repeated key does not degrade - it throws, and the app dies mid-scroll.
     * Deduping here rather than at the key means the count under the search box is also right.
     */
    /**
     * Re-read the state of the results that are on screen, while a pass is running.
     *
     * Bug identified: a result's state was decided once, at the moment it was emitted, and
     * then frozen. A row found before the crawl reached it stayed labelled as being checked
     * for as long as the results were up, however long the pass ran - so the badge described
     * one instant rather than resolving while it was being watched.
     *
     * Reported as exactly that: the confirming badges never cleared in order as the pass went
     * past them.
     *
     * A page is a few dozen rows and the tick is slow, so this costs nothing next to the crawl
     * it is watching. It stops on its own when the pass ends, because the state then answers
     * AVAILABLE for everything and there is nothing left to move.
     */
    private var confirmJob: Job? = null

    /** Paths whose check has run or is running in this search, so none is checked twice. */
    private val checked = HashSet<String>()

    /** Paths whose check found nothing. Kept out of every later publish of this search. */
    private val gone = HashSet<String>()

    /**
     * Check the rows still marked Confirming. SEARCH.md §14.3.
     *
     * In rank order, a chunk at a time with the chunk's checks in parallel, so the rows at the
     * top - the ones on screen when the list first paints - come back first. One state update
     * per chunk rather than per row: a page of two hundred results flipping one at a time is two
     * hundred recompositions for a change nobody could watch happening.
     */
    private fun checkConfirming() {
        val pending = _state.value.search.hits
            .filter { it.state == HitState.CONFIRMING && checked.add(it.key) }
        if (pending.isEmpty()) return
        _state.update { it.copy(search = it.search.copy(checking = it.search.checking + pending.size)) }
        scope.launch {
            for (chunk in pending.chunked(CHECK_CHUNK)) {
                val asked = chunk.map { h ->
                    async { Triple(h.key, h.node.path, runCatching { vfs.stat(h.node.path) }) }
                }.awaitAll()
                // Bug identified here: a check that threw was read as "not there", so every row
                // from a share that was asleep or off the network left the list as though its
                // file had been deleted. Unreachable is not gone. Those rows stay Confirming -
                // seen, never openable - which is what the share's header already says.
                val results = asked.filter { it.third.isSuccess }.map { (key, path, r) -> Triple(key, path, r.getOrNull()) }
                for ((_, path, live) in results) if (live == null) onGone(path)
                _state.update { st ->
                    val byKey = results.associate { (key, _, live) -> key to live }
                    for ((key, _, live) in results) if (live == null) gone += key
                    val hits = st.search.hits.mapNotNull { h ->
                        if (h.key !in byKey) {
                            h
                        } else {
                            val live = byKey[h.key]
                            when (afterCheck(exists = live != null)) {
                                null -> null
                                else -> h.copy(state = HitState.AVAILABLE, node = live ?: h.node)
                            }
                        }
                    }
                    st.copy(
                        search = st.search.copy(
                            hits = hits,
                            checking = (st.search.checking - chunk.size).coerceAtLeast(0),
                        ),
                    )
                }
            }
        }
    }

    /** Record one row's check that happened outside the checker - a tap on it. */
    private fun settle(key: String, live: VNode?) {
        checked += key
        if (live == null) gone += key
        _state.update { st ->
            val hits = st.search.hits.mapNotNull { h ->
                when {
                    h.key != key -> h
                    live == null -> null
                    else -> h.copy(state = HitState.AVAILABLE, node = live)
                }
            }
            st.copy(search = st.search.copy(hits = hits))
        }
    }

    /**
     * A walker's folder count, throttled.
     *
     * Called for every folder a walker reads, which on local storage is hundreds a second; a
     * state update per call would cost more than the walk. One per root per tick is plenty for a
     * number nobody reads faster than that.
     */
    private fun noteWalked(root: VPath, folders: Int) {
        val now = System.currentTimeMillis()
        val last = walkedAt[root] ?: 0L
        if (now - last < PUBLISH_EVERY_MS && folders > 1) return
        walkedAt[root] = now
        _state.update { st ->
            if (!st.search.running) st
            else st.copy(search = st.search.copy(walked = st.search.walked + (root to folders)))
        }
    }

    private val walkedAt = java.util.concurrent.ConcurrentHashMap<VPath, Long>()

    private fun watchConfirmations() {
        // Already watching this search. Restarting would reset the tick, which is the fault.
        if (confirmJob?.isActive == true) return
        confirmJob = scope.launch {
            // What the answer on screen was built against, so a change can be an EDGE rather
            // than a level. Re-asking on a level would re-ask on every tick of a running crawl.
            var seen: IndexStatus? = indexStatus()
            var sinceAsk = 0L
            while (true) {
                delay(CONFIRM_TICK_MS)
                val st = _state.value.search
                if (!st.active) return@launch
                val status = indexStatus() ?: return@launch

                // Bug identified: this used to settle the badges and `return@launch`, so a query
                // typed while a pass was running never saw the rows that pass went on to write.
                // Retyping worked, which is what made it look like a display fault - it was not,
                // the question was simply never asked again.
                val rerun = SearchRefresh.shouldRerun(seen, status)
                seen = status

                // While a pass is running, ask again every so often rather than waiting for it to
                // end. A crawl over a whole phone takes minutes, and a query typed at the start of
                // one otherwise shows its first answer and nothing else until it finishes.
                //
                // Safe because of HOW the results merge, not because of how rarely this fires:
                // `keepOrder` publishes stably, so rows already on screen hold their positions and
                // only new ones append. A re-run can lengthen the list; it cannot reorder it.
                if (status.running) {
                    sinceAsk += CONFIRM_TICK_MS
                    if (sinceAsk >= RERUN_WHILE_CRAWLING_MS) {
                        sinceAsk = 0
                        rerunActiveSearch()
                    }
                } else {
                    sinceAsk = 0
                }

                // Ask once more when a pass ends, and keep watching afterwards in case a later
                // pass adds more. The re-run publishes stably, so rows already on screen keep
                // their places and new ones append.
                //
                // The badges are no longer this loop's business. Settling them on the crawl is
                // what kept rows labelled Confirming for minutes after they had been checked; a
                // row's label is now its own check's answer - see checkConfirming.
                if (SearchRefresh.shouldSettleBadges(status) && rerun) rerunActiveSearch()
            }
        }
    }

    /**
     * Run the open query again without disturbing what is already drawn.
     *
     * `runSearch` re-publishes from an empty collection, and `publish` is stable once something
     * has been painted - so the rows on screen keep their order and anything new appends. That is
     * the difference between more responsive and less stable, and it is the whole requirement.
     */
    private fun rerunActiveSearch() {
        val st = _state.value.search
        if (!st.active || st.query.isBlank()) return
        runSearch(st.query, keepOrder = true)
    }

    private fun publish(all: List<SearchHit>, stable: Boolean) {
        val shown = _state.value.search.hits
        val ranked = if (!stable || shown.isEmpty()) {
            all.distinctBestByKey().sortedByDescending { it.score }
        } else {
            // After first paint, later arrivals are appended in arrival order so rows never
            // move under a thumb already reaching for one (SEARCH.md §5.3).
            val keys = shown.mapTo(HashSet()) { it.key }
            shown + all.distinctBestByKey().filterNot { it.key in keys }
        }
        // A row its check already passed stays passed. The index re-emits the same path as
        // Confirming on every re-run, and without this a list would flicker back to unchecked
        // each time a pass wrote a few rows - or, since the checker skips paths it has done,
        // stay unchecked for good. A row its check removed stays removed for the same reason.
        val passed = shown.filter { it.state == HitState.AVAILABLE }.associateBy { it.key }
        val next = ranked.mapNotNull { h ->
            when {
                h.key in gone -> null
                h.state == HitState.CONFIRMING && h.key in passed -> h.copy(state = HitState.AVAILABLE, node = passed.getValue(h.key).node)
                else -> h
            }
        }
        _state.update { it.copy(search = it.search.copy(hits = next, painted = true)) }
        checkConfirming()
        // NOT watchConfirmations() any more. Restarting it here reset the tick on every publish -
        // several times per search - so its clock never ran and the end-of-pass edge could fall
        // in the gap between a cancel and the next tick. It is started once, by runSearch.
    }

    /** Keeps the best-scoring hit per path, in first-seen order. */
    private fun List<SearchHit>.distinctBestByKey(): List<SearchHit> {
        val best = LinkedHashMap<String, SearchHit>(size)
        for (hit in this) {
            val prev = best[hit.key]
            if (prev == null || hit.score > prev.score) best[hit.key] = hit
        }
        return best.values.toList()
    }

    /** Volume roots, resolved once and cached: `vfs.roots()` is a syscall per volume. */
    var rootsForDevice: List<VPath> = emptyList()

    /**
     * The same volumes with their labels and their kind, for the reach ladder.
     *
     * Set beside [rootsForDevice] from the one place that calls `vfs.roots()`, rather than
     * derived here: naming a drive means asking the saved remote what it is called, and a pane
     * is not the thing that knows that.
     */
    var reachDrives: List<ReachDrive> = emptyList()

    private val probed = HashSet<dev.niccc2007.filet.index.VolumeKey>()
    private val probes = ArrayList<kotlinx.coroutines.Job>()

    /**
     * Ask each share in reach whether it answers, once per open search.
     *
     * Once, not per keystroke: whether a NAS is awake does not change between "rep" and
     * "repo", and a listing per letter typed is a round trip per letter over Wi-Fi. Not tied to
     * the search job either, so retyping does not cancel a probe halfway and start it again.
     *
     * A listing of the share's root rather than a stat of it, because a stat of a share's root
     * is answered locally by the provider without touching the network - it would report every
     * share as present, which is the failure this exists to prevent.
     */
    private fun probeShares(keys: List<dev.niccc2007.filet.index.VolumeKey>) {
        for (key in keys) {
            if (!probed.add(key)) continue
            probes += scope.launch {
                val answered = kotlinx.coroutines.withTimeoutOrNull(PROBE_MS) {
                    runCatching { vfs.list(key.root) }.isSuccess
                } == true
                _state.update { st ->
                    val now = if (answered) st.search.unreachable - key else st.search.unreachable + key
                    st.copy(search = st.search.copy(unreachable = now))
                }
            }
        }
    }

    fun setError(msg: String?) = _state.update { it.copy(error = msg) }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun snapshotLocation(): String? = _state.value.cwd?.toString()

    private fun Throwable.readable(): String = when (this) {
        is VfsException.NotFound -> "That item no longer exists."
        is VfsException.AlreadyExists -> "Something with that name is already here."
        is VfsException.AccessDenied -> "Permission denied. Grant all-files access in Settings."
        is VfsException.NotADirectory -> "That is not a folder."
        is VfsException.IsADirectory -> "That is a folder."
        is VfsException.Unsupported -> message ?: "Not supported."
        else -> message ?: this::class.simpleName ?: "Something went wrong."
    }
}

/**
 * How often the state of the results on screen is re-read while an index pass is running.
 *
 * One and a half seconds: fast enough that badges clear in step with a crawl somebody is
 * watching, slow enough that a page of rows costs nothing beside the crawl itself.
 */
private const val CONFIRM_TICK_MS = 1_500L

/** How often an arriving trickle of results is published. Under what an eye notices. */
private const val PUBLISH_EVERY_MS = 100L

/**
 * How long the first paint collects before showing anything, so the first rows on screen are
 * ranked against each other rather than shown in whatever order the sources answered.
 */
private const val FIRST_PAINT_MS = 60L

/** How often the first paint looks to see whether its moment has come. */
private const val FIRST_PAINT_POLL_MS = 20L

/** Rows checked together, which is also roughly how many a phone shows at once. */
private const val CHECK_CHUNK = 16

/**
 * How often an open search re-asks the index while a crawl is running.
 *
 * Long enough that it is not a query per tick, short enough that a person watching results arrive
 * sees them arrive. The merge is what keeps it stable, so this is a cost decision rather than a
 * correctness one - an FTS match on this index measured 1.8 ms.
 */
private const val RERUN_WHILE_CRAWLING_MS = 4_500L

/**
 * How long a share gets to answer before its header says unreachable.
 *
 * Shorter than a connection's own timeout on purpose. This is not deciding whether the share is
 * usable - opening it still waits the full connect - only whether the search should be honest
 * that it has heard nothing yet, and four seconds of silence is already an answer worth showing.
 */
private const val PROBE_MS = 4_000L

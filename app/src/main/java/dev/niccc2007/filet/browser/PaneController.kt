package dev.niccc2007.filet.browser

import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.data.SortSpec
import dev.niccc2007.filet.index.SearchHit
import dev.niccc2007.filet.index.SearchRequest
import dev.niccc2007.filet.index.SearchScope
import dev.niccc2007.filet.index.SearchSource
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a pane is currently showing. Not every pane shows a directory. */
enum class PaneKind { HOME, FOLDER, ABOUT, BOOKMARKS, RECENT, SETTINGS, ACTIVITY, SCRIPTS, NEARBY, REMOTES, SHORTCUTS, HISTORY }

data class SearchUi(
    val open: Boolean = false,
    val query: String = "",
    val scope: SearchScope = SearchScope.FOLDER,
    val running: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val painted: Boolean = false,
) {
    val active: Boolean get() = open && query.isNotBlank()
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
     * Set by "go to containing folder" and cleared by the list the moment it has acted. It is
     * state rather than a delay because the listing takes as long as it takes - a fixed wait
     * is either too short on a big folder or a stall on a small one, and the previous version
     * guessed 160ms and then never scrolled at all.
     */
    val revealTarget: VPath? = null,
) {
    val selecting: Boolean get() = selected.isNotEmpty()
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
    /**
     * Told what was searched for, so a crawl that is still running can be bent toward it.
     *
     * A function rather than the index itself: the pane has no other business with the index,
     * and handing it one would be handing it every other thing an index can do.
     */
    private val onSearched: (String) -> Unit = {},
    private val onOpened: (VNode) -> Unit = {},
    /**
     * The world revision, bumped by every operation that writes to the filesystem.
     *
     * A function rather than a value because the pane outlives any single reading of it, and a
     * captured Int would be exactly the stale-capture bug that broke pinch-to-zoom.
     */
    private val worldRevision: () -> Int = { 0 },
) {
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

    fun navigateTo(path: VPath, push: Boolean = true) {
        listJob?.cancel()
        val prev = _state.value
        if (push && prev.kind != PaneKind.FOLDER || push && prev.cwd != path) {
            back.addLast(prev.copy(entries = emptyList()))
            forward.clear()
        }
        _state.value = prev.copy(
            kind = PaneKind.FOLDER,
            cwd = path,
            title = friendlyTitle(path),
            loading = true,
            error = null,
            selected = emptySet(),
            editingPath = false,
            canGoBack = back.isNotEmpty(),
            canGoForward = forward.isNotEmpty(),
            search = _state.value.search.copy(hits = emptyList(), painted = false),
        )
        // Read BEFORE the listing starts, not after it finishes: an operation that lands while
        // this read is in flight must leave the pane behind, or its change is the one that gets
        // lost. Stamping with the revision at the end would mark the pane current for a change
        // it never saw.
        val stamp = worldRevision()
        listJob = scope.launch {
            runCatching { vfs.list(path) }
                .onSuccess { list ->
                    raw = list
                    listedAt = stamp
                    _state.update { render(it.copy(loading = false)) }
                    // A search that was open stays open across navigation; re-run it here
                    // rather than leaving stale hits from the previous folder on screen.
                    if (_state.value.search.active) runSearch(_state.value.search.query)
                }
                .onFailure { e ->
                    // The previous listing stays on screen: an error with an empty list behind
                    // it is indistinguishable from an empty folder.
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

    private fun restore(s: PaneState) {
        val target = s.copy(canGoBack = back.isNotEmpty(), canGoForward = forward.isNotEmpty())
        if (target.kind == PaneKind.FOLDER && target.cwd != null) {
            _state.value = target.copy(loading = true)
            listJob?.cancel()
            val stamp = worldRevision()
            listJob = scope.launch {
                runCatching { vfs.list(target.cwd) }
                    .onSuccess {
                        raw = it
                        listedAt = stamp
                        _state.update { s2 -> render(s2.copy(loading = false)) }
                    }
                    .onFailure { e -> _state.update { s2 -> s2.copy(loading = false, error = e.readable()) } }
            }
        } else {
            _state.value = target
        }
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
        // MountBoundary, not VPath.parent: inside an archive or an APK the `!` marks where the
        // real filesystem stops, and a plain string walk climbs straight through it into a
        // path in a scheme that cannot read it. See MountBoundary.
        val parent = _state.value.cwd?.let { dev.niccc2007.filet.vfs.MountBoundary.up(it) } ?: return false
        navigateTo(parent, push = push)
        return true
    }

    fun refresh() {
        val s = _state.value
        if (s.kind == PaneKind.FOLDER && s.cwd != null) navigateTo(s.cwd, push = false)
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
        )
        if (stale) navigateTo(s.cwd ?: return, push = false)
    }

    fun open(node: VNode) {
        val kind = FileKind.of(node)
        when {
            node.isDir -> navigateTo(node.path)
            kind.isContainer -> onOpened(node)      // routed by the ViewModel: archive or APK mount
            else -> onOpened(node)
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

    fun toggleSelect(node: VNode) = _state.update {
        anchor = node.path
        val next = if (node.path in it.selected) it.selected - node.path else it.selected + node.path
        it.copy(selected = next)
    }

    fun selectOnly(node: VNode) = _state.update { anchor = node.path; it.copy(selected = setOf(node.path)) }

    /**
     * Select [node] and ask the listing to scroll it into the middle.
     *
     * The scroll is a request, not an action: this controller does not know how tall a row is
     * or how many fit, so it says which file and the list works out where.
     */
    fun revealOnly(node: VNode) = _state.update {
        anchor = node.path
        it.copy(selected = setOf(node.path), revealTarget = node.path)
    }

    /** Called by the list once it has scrolled, so a later listing does not scroll again. */
    fun revealHandled() = _state.update { if (it.revealTarget == null) it else it.copy(revealTarget = null) }
    /**
     * The last row picked deliberately, which a range extends FROM.
     *
     * Not the lowest selected index: using that would make a second range run from the wrong
     * end as soon as somebody had selected upwards.
     */
    private var anchor: VPath? = null

    /** Shift-click. See `RangeSelect.kt` for the arithmetic and why it is separate. */
    fun extendSelectionTo(node: VNode) = _state.update { s ->
        val next = rangeSelect(
            visible = s.visible.map { it.path },
            current = s.selected,
            anchor = anchor,
            target = node.path,
        )
        anchor = node.path
        s.copy(selected = next)
    }

    fun clearSelection() = _state.update { anchor = null; it.copy(selected = emptySet()) }
    fun selectAll() = _state.update { s -> s.copy(selected = s.visible.map { it.path }.toSet()) }

    fun invertSelection() = _state.update { s ->
        s.copy(selected = s.visible.map { it.path }.filterNot { it in s.selected }.toSet())
    }

    fun selectedNodes(): List<VNode> {
        val s = _state.value
        val byPath = (s.entries + s.search.hits.map { it.node }).associateBy { it.path }
        return s.selected.mapNotNull { byPath[it] }
    }

    // ── listing presentation ──

    /** Re-applies sort and the hidden-file filter to the cached listing. No disk access. */
    fun reapply() = _state.update { render(it) }

    private fun render(s: PaneState): PaneState {
        val showHidden = prefs.showHidden.value
        val spec: SortSpec = prefs.sort.value
        val filtered = if (showHidden) raw else raw.filterNot { it.hidden || it.name.startsWith(".") }
        return s.copy(entries = filtered.sortedBy(spec), total = raw.size)
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
            // Closing search ends any detour it caused. Leaving one running would keep a crawl
            // bent toward a query nobody is looking at any more.
            onSearched("")
            it.copy(search = SearchUi(scope = it.search.scope))
        }
    }

    fun setScope(scope: SearchScope) {
        _state.update { it.copy(search = it.search.copy(scope = scope)) }
        if (_state.value.search.active) runSearch(_state.value.search.query)
    }

    fun setQuery(q: String) {
        _state.update { it.copy(search = it.search.copy(query = q)) }
        runSearch(q)
    }

    private fun runSearch(q: String) {
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
        val req = SearchRequest(
            query = q,
            scope = s.search.scope,
            origin = s.cwd,
            roots = rootsForDevice,
            showHidden = prefs.showHidden.value,
        )
        val source = searchSources.firstOrNull { it.handles(req) } ?: return
        searchJob = scope.launch {
            // Debounce 120 ms: 300 feels laggy, under 100 wastes queries (SEARCH.md §5.5).
            delay(120)
            // After the debounce, so a crawl is not re-steered on every keystroke. No-op
            // unless something is actually crawling - see FileIndex.steerCrawl.
            onSearched(q)
            _state.update { it.copy(search = it.search.copy(running = true, painted = false)) }

            val collected = ArrayList<SearchHit>()
            var painted = false
            val firstPaintAt = System.currentTimeMillis() + 90
            source.search(req).collect { hit ->
                collected += hit
                val now = System.currentTimeMillis()
                if (!painted && now >= firstPaintAt) {
                    painted = true
                    publish(collected, stable = false)
                } else if (painted && collected.size % 24 == 0) {
                    // After first paint, later arrivals are appended in arrival order so rows
                    // never move under a thumb that is already reaching for one (SEARCH.md §5.3).
                    publish(collected, stable = true)
                }
            }
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
    private fun publish(all: List<SearchHit>, stable: Boolean) {
        val shown = _state.value.search.hits
        val next = if (!stable || shown.isEmpty()) {
            all.distinctBestByKey().sortedByDescending { it.score }
        } else {
            // After first paint, later arrivals are appended in arrival order so rows never
            // move under a thumb already reaching for one (SEARCH.md §5.3).
            val keys = shown.mapTo(HashSet()) { it.key }
            shown + all.distinctBestByKey().filterNot { it.key in keys }
        }
        _state.update { it.copy(search = it.search.copy(hits = next, painted = true)) }
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

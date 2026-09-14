package dev.niccc2007.filet

import android.app.Application
import android.content.Context
import dev.niccc2007.filet.data.Bookmarks
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.data.Recents
import dev.niccc2007.filet.home.HomeFeed
import dev.niccc2007.filet.home.TrackedFolders
import dev.niccc2007.filet.index.FileIndex
import dev.niccc2007.filet.index.HotWatcher
import dev.niccc2007.filet.index.IndexCoordinator
import dev.niccc2007.filet.index.IndexSearchSource
import dev.niccc2007.filet.index.SearchSource
import dev.niccc2007.filet.index.SqliteIndex
import dev.niccc2007.filet.index.WalkSearchSource
import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.ops.FileOperations
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.AndroidStorage
import dev.niccc2007.filet.vfs.provider.ApkProvider
import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import dev.niccc2007.filet.vfs.provider.RootProvider
import dev.niccc2007.filet.vfs.provider.SafProvider
import dev.niccc2007.filet.vfs.provider.net.FtpProvider
import dev.niccc2007.filet.vfs.provider.net.NetConnections
import dev.niccc2007.filet.vfs.provider.net.PeerProvider
import dev.niccc2007.filet.vfs.provider.net.SftpProvider
import dev.niccc2007.filet.vfs.provider.net.SmbProvider
import dev.niccc2007.filet.vfs.provider.net.WebDavProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The object graph.
 *
 * Hand-rolled rather than Hilt: there are about a dozen singletons, they are all created at
 * startup, and an annotation processor would add build time and a compiler plugin to a
 * project whose whole argument is that the layers are explicit.
 *
 * Provider registration is the one list that matters. Everything above L0 resolves a scheme
 * through [vfs]; nothing else ever learns which provider answered.
 */
class FiletGraph(context: Context) {

    val app: Context = context.applicationContext

    /** Outlives any screen. Long work must not die because a rotation killed a ViewModel. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs = Prefs(app)
    /** The app layer's single, documented `java.io.File` bridge. See R3 in PLAN.md. */
    val files = dev.niccc2007.filet.data.AppFiles(app)
    val bookmarks = Bookmarks(prefs)
    val recents = Recents(prefs)
    val tracked = TrackedFolders(prefs, scope)
    val ledger = JobLedger()
    val registry = dev.niccc2007.filet.handlers.HandlerRegistry(prefs)

    val connections = NetConnections(app)

    /**
     * Provider registration - the one list that matters.
     *
     * Root is registered unconditionally but answers nothing without a granted shell, which
     * keeps PLAN.md R1 honest: the UI asks the provider whether it can work rather than
     * offering a scheme that silently fails.
     */
    private val providers: MutableList<FileSystemProvider> = mutableListOf(
        AndroidStorage.localProvider(app),
        SafProvider(app),
        ArchiveProvider(),
        ApkProvider(),
        RootProvider(),
        SmbProvider(connections),
        SftpProvider(connections),
        FtpProvider(connections),
        WebDavProvider(connections),
        PeerProvider { nearby.endpoints() },
    )

    val vfs = Vfs(providers.toList())
    val ops = FileOperations(vfs, ledger)
    val scripts = dev.niccc2007.filet.script.ScriptStore(app, prefs)
    val scriptEngine = dev.niccc2007.filet.script.ScriptEngine(vfs)
    val signingKeys = dev.niccc2007.filet.apk.SigningKeys(app, prefs)
    val apkTools = dev.niccc2007.filet.apk.ApkTools(app, vfs, ledger)
    val bridge = dev.niccc2007.filet.bridge.TrawlBridge(app, ledger)
    /** What Filet has put on the home screen, so it is manageable from inside the app. */
    val shortcuts = dev.niccc2007.filet.shortcuts.ShortcutStore(app, prefs)
    val nearby: dev.niccc2007.filet.nearby.NearbyManager by lazy {
        dev.niccc2007.filet.nearby.NearbyManager(app, vfs, prefs, scope)
    }
    val home = HomeFeed(vfs, tracked, scope)

    /**
     * The index is optional by construction (PLAN.md L1, SEARCH.md §7.1).
     *
     * If SQLite fails to open - a corrupt file, a device with no writable database dir - the
     * app keeps working and search falls back to walking. An index that can take the app down
     * with it is a liability, not a feature.
     */
    val index: FileIndex = runCatching {
        SqliteIndex(
            app, vfs,
            extractors = listOf(
                // What makes `pkg:` `class:` `perm:` `label:` and `inzip:` able to answer.
                // Supplied from here rather than built into the index because the parsers are
                // :app dependencies - L1 must not reach up for them (PLAN.md R3).
                dev.niccc2007.filet.apk.ApkFacts(vfs, apkTools),
                dev.niccc2007.filet.index.ArchiveFacts(vfs),
            ),
        )
    }.getOrElse { NullIndex() }

    val watcher = HotWatcher(vfs) { changed ->
        // The cheap thing first. This used to run a four-second crawl and only then refresh
        // the feed, so the update Home actually shows queued behind the one nobody sees -
        // which is most of why the feed "takes time to update". The feed coalesces its own
        // bursts, so calling it per event is fine.
        home.onChanged()
        scope.launch { runCatching { index.crawl(listOf(changed), budgetMs = 4_000) } }
    }

    val indexCoordinator = IndexCoordinator(app, index, prefs, ledger, scope)
        .also { it.attachWatcher(watcher) }

    /**
     * Ordered by preference: the index answers whole-device and provenance queries, the walk
     * answers everything else and everything the index cannot. Both feed the same reranker,
     * so the user never sees which one replied.
     */
    val searchSources: List<SearchSource> =
        listOf(IndexSearchSource(index, vfs), WalkSearchSource(vfs))

    fun onFirstScreen() {
        scope.launch {
            val roots = runCatching { vfs.roots().map { it.path } }.getOrElse { emptyList() }
            tracked.seedDefaults(roots)
            scripts.seedExamples()
            home.refresh()
            index.status.value.let { if (it.enabled) indexCoordinator.onStart(roots) }
            // Follow the tracked list rather than sampling it once at startup. A folder
            // tracked afterwards was never watched at all, so it only ever updated when Home
            // was reopened - the other half of "its not latest".
            scope.launch {
                tracked.paths.collect { paths ->
                    watcher.watch(paths)
                    home.refresh()
                }
            }
            // Provenance is what makes the Source chip real; wiring it here rather than in
            // the UI keeps Home ignorant of whether an index exists.
            home.originLookup = { path ->
                index.provenanceOf(path)?.origin ?: bridge.provenanceOf(path.toString())?.origin
            }
            // Pull whatever the companion app is doing into the one Activity surface.
            runCatching { bridge.pullJobs() }
        }
    }
}

/**
 * What the app uses when there is no index.
 *
 * Not a crash and not a silent lie: every method answers "nothing", the status says so, and
 * search falls through to the filesystem walk.
 */
private class NullIndex : FileIndex {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(
        dev.niccc2007.filet.index.IndexStatus(enabled = false, available = false)
    )
    override val status = state
    override suspend fun candidates(req: dev.niccc2007.filet.index.SearchRequest, parsed: dev.niccc2007.filet.index.ParsedQuery) = emptyList<dev.niccc2007.filet.index.IndexRow>()
    override suspend fun idFor(path: dev.niccc2007.filet.vfs.VPath): Long? = null
    override suspend fun pathFor(id: Long): dev.niccc2007.filet.vfs.VPath? = null
    override suspend fun recordOpen(path: dev.niccc2007.filet.vfs.VPath) = Unit
    override suspend fun setPinned(path: dev.niccc2007.filet.vfs.VPath, pinned: Boolean) = Unit
    override suspend fun putProvenance(path: dev.niccc2007.filet.vfs.VPath, record: dev.niccc2007.filet.index.Provenance) = Unit
    override suspend fun provenanceOf(path: dev.niccc2007.filet.vfs.VPath): dev.niccc2007.filet.index.Provenance? = null
    override suspend fun byOrigin(fragment: String, limit: Int) = emptyList<dev.niccc2007.filet.index.IndexRow>()
    override suspend fun putInner(path: dev.niccc2007.filet.vfs.VPath, kind: String, values: List<String>) = Unit
    override suspend fun innerOf(id: Long, kind: String) = emptyList<String>()
    override suspend fun duplicates(
        scope: dev.niccc2007.filet.vfs.VPath?,
        minSize: Long,
        limitGroups: Int,
    ) = emptyList<List<dev.niccc2007.filet.vfs.VPath>>()
    override suspend fun crawl(roots: List<dev.niccc2007.filet.vfs.VPath>, budgetMs: Long, onProgress: (Long) -> Unit) =
        dev.niccc2007.filet.index.CrawlResult(0, 0, 0, true, 0)
    /** Nothing is crawling, so there is nothing to steer and nothing to promise on screen. */
    override fun steerCrawl(query: String) = Unit
    override suspend fun forget(path: dev.niccc2007.filet.vfs.VPath) = Unit
    override suspend fun clear() = Unit
    override fun close() = Unit
}

class FiletApp : Application() {
    val graph: FiletGraph by lazy { FiletGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Installed before anything else touches the graph, so a crash *while building the
        // graph* is caught too. No analytics, ever: a file manager's stack traces carry
        // paths, and paths are the most private strings on the device.
        //
        // Skipped in the `:crash` process, which exists to show the report - it must not be
        // able to loop by reporting its own failure to report.
        if (!isCrashProcess()) dev.niccc2007.filet.crash.CrashReport.install(this)
    }

    private fun isCrashProcess(): Boolean {
        val mine = android.os.Process.myPid()
        val manager = getSystemService(android.app.ActivityManager::class.java) ?: return false
        val name = runCatching {
            manager.runningAppProcesses?.firstOrNull { it.pid == mine }?.processName
        }.getOrNull()
        return name?.endsWith(":crash") == true
    }

    companion object {
        fun graphOf(context: Context): FiletGraph =
            (context.applicationContext as FiletApp).graph
    }
}

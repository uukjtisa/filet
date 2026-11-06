package dev.niccc2007.filet

import android.app.Application
import android.content.Context
import android.content.Intent
import dev.niccc2007.filet.data.Bookmarks
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.data.Recents
import dev.niccc2007.filet.home.HomeFeed
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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
/** When the media sweep last ran, and how often it is allowed to. */
private const val MEDIA_SWEEP_AT = "media.sweptAt"

/**
 * Six hours.
 *
 * Long enough that launching the app repeatedly costs nothing, short enough that a file
 * dropped on the device by something else is picked up the same day. Files Filet writes
 * itself are announced as they are written and do not wait for this.
 */
private const val MEDIA_SWEEP_EVERY = 6L * 60 * 60 * 1000

class FiletGraph(context: Context) {

    val app: Context = context.applicationContext

    /** Outlives any screen. Long work must not die because a rotation killed a ViewModel. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs = Prefs(app)
    /** The app layer's single, documented `java.io.File` bridge. See R3 in PLAN.md. */
    val files = dev.niccc2007.filet.data.AppFiles(app)
    val bookmarks = Bookmarks(prefs)
    val playlists = dev.niccc2007.filet.playback.Playlists(prefs)
    /** Tags for list rows. Lazy: a phone that never opens a playlist never builds it. */
    val trackCards by lazy { dev.niccc2007.filet.playback.TrackCards(app, vfs) }

    /**
     * Beat maps, measured away from any screen.
     *
     * On the graph rather than in the player, which is the whole fix: the measurement used to
     * belong to the composition that started it, so leaving the player threw away everything
     * it had done. This scope outlives every screen.
     */
    val beatMaker by lazy {
        dev.niccc2007.filet.playback.BeatMaker(app, ledger, scope) {
            files.vpath(
                dev.niccc2007.filet.data.FiletStore.dir(
                    app, dev.niccc2007.filet.data.FiletStore.Area.BEATS,
                ),
            ).toString()
        }
    }
    val folderPeek by lazy { dev.niccc2007.filet.browser.FolderPeek(vfs) }
    val recents = Recents(prefs)
    val folderVisits = dev.niccc2007.filet.data.FolderVisits(prefs) { recents.items.value }
    val ledger = JobLedger(dev.niccc2007.filet.jobs.PrefsJobStore(app), keepDays = { prefs.jobsKeepDays.value })
    val registry = dev.niccc2007.filet.handlers.HandlerRegistry(prefs)

    /** filet.json and the two list files, both ways. See `ConfigSync`. */
    val configSync: dev.niccc2007.filet.data.ConfigSync by lazy {
        dev.niccc2007.filet.data.ConfigSync(
            app, prefs, scope, bookmarks, playlists,
            afterApply = { registry.reload() },
            report = { message ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    val connections = NetConnections(app)

    /**
     * Provider registration - the one list that matters.
     *
     * Root is registered unconditionally but answers nothing without a granted shell, which
     * keeps PLAN.md R1 honest: the UI asks the provider whether it can work rather than
     * offering a scheme that silently fails.
     */
    /**
     * Where staged copies of remote archives live: everything under Filet's cache. Changing an
     * archive there would change the copy and leave the real one as it was, so the browser's
     * writes and the editor's save both refuse it.
     */
    val stagedArchiveDirs: () -> List<String> = {
        listOf(dev.niccc2007.filet.data.FiletStore.cacheRoot(app).absolutePath, app.cacheDir.absolutePath)
    }

    private val providers: MutableList<FileSystemProvider> = mutableListOf(
        AndroidStorage.localProvider(app),
        SafProvider(app),
        ArchiveProvider(stagedUnder = { stagedArchiveDirs() }),
        ApkProvider(),
        RootProvider(),
        SmbProvider(connections),
        SftpProvider(connections),
        FtpProvider(connections),
        WebDavProvider(connections),
        PeerProvider { nearby.endpoints() },
    )

    val vfs = Vfs(providers.toList())
    val ops = FileOperations(vfs, ledger, relaunch = { block -> scope.launch { block() } }) { touched ->
        // Everything Filet writes or removes is announced to the media index. Without it a
        // file is on the disk and invisible to every gallery on the phone.
        dev.niccc2007.filet.media.MediaAnnounce.announce(app, touched)
    }

    // Extraction is its own object because it is two operations - work out what will happen,
    // then carry out exactly that - and the preview reads the first without the second.
    val extractOps = dev.niccc2007.filet.ops.ExtractOperations(vfs, ledger, relaunch = { block -> scope.launch { block() } }) { touched ->
        dev.niccc2007.filet.media.MediaAnnounce.announce(app, touched)
    }
    val scripts = dev.niccc2007.filet.script.ScriptStore(app, prefs)
    val scriptEngine = dev.niccc2007.filet.script.ScriptEngine(vfs)
    val signingKeys = dev.niccc2007.filet.apk.SigningKeys(app, prefs)
    /** Local copies of remote files, for the operations that cannot take a stream. */
    val staging = dev.niccc2007.filet.ops.RemoteStaging(vfs, ledger) {
        dev.niccc2007.filet.data.FiletStore.dir(app, dev.niccc2007.filet.data.FiletStore.Area.REMOTE)
    }

    /** Reads and writes a file's own metadata. The screen that reaches it is MetadataScreen. */
    val metadata = dev.niccc2007.filet.metadata.MetadataStore(vfs)

    /**
     * The music and video player, owned here so it outlives every screen.
     *
     * In the graph rather than in a view model, and that is the fix for the reported fault:
     * state held by a composition dies with the composition, so closing the player stopped the
     * music and there was no way to put a queue in the background. Here it is a process-wide
     * thing that screens, the strip above the bottom bar and the notification all read.
     *
     * Not `by lazy` would mean building a player on every cold start for the vast majority of
     * launches that never play anything, so it is - the first `open` is what brings it up.
     */
    val playback: dev.niccc2007.filet.playback.Playback by lazy {
        dev.niccc2007.filet.playback.Playback(
            app = app,
            scope = scope,
            resolve = { id -> dev.niccc2007.filet.playback.TrackMedia.uriFor(vfs, files, id) },
            readTags = { id, uri ->
                dev.niccc2007.filet.playback.TrackMedia.tagsFor(app, vfs, id, uri)
            },
            onServiceWanted = { wanted ->
                dev.niccc2007.filet.playback.PlaybackService.reconcile(app, wanted)
            },
            // Read at the moment a track opens, not captured here: turning it off should
            // affect the next thing opened, and a captured boolean would hold whatever it
            // was when the player was first built.
            resumeWanted = { prefs.resumePlayback.value },
        ).also { player ->
            // The setting follows the player for as long as the process lives, rather than
            // being read when a track starts. Turning it on mid-track has to take effect on
            // the track that is running - that is the whole situation it gets turned on in.
            scope.launch { prefs.uninterrupted.collect { player.uninterrupted = it } }
            // Same shape, same reason: the decoder has to be told, and the setting
            // outlives every screen that can change it.
            scope.launch {
                prefs.pitchMode.collect {
                    player.setPitchMode(dev.niccc2007.filet.playback.PitchMode.of(it))
                }
            }
        }
    }

    init {
        // Mirrored rather than read, because `RootProvider` sits below the settings layer and
        // is kept there deliberately (PLAN.md R3). One direction of dependency, and a test can
        // set the flag without a Context.
        scope.launch { prefs.rootVolumes.collect { RootProvider.showVolumes = it } }
    }

    /**
     * Finding servers on this network that are not Filet. See `ServerScan`.
     *
     * Process-wide for the same reason the beacon is: a sweep takes a few seconds and somebody
     * who starts one and taps into a folder should not have thrown it away.
     */
    private val serverScanLazy = lazy { dev.niccc2007.filet.remotes.ServerScan(app, scope) }
    val serverScan by serverScanLazy

    /** Local copies for the readers that need a seekable file. See `LocalCopy`. */
    val localCopy = dev.niccc2007.filet.ops.LocalCopy(app, vfs, staging)

    val apkTools = dev.niccc2007.filet.apk.ApkTools(app, vfs, ledger, staging)
    val bridge = dev.niccc2007.filet.bridge.TrawlBridge(app, ledger)
    /** What Filet has put on the home screen, so it is manageable from inside the app. */
    val shortcuts = dev.niccc2007.filet.shortcuts.ShortcutStore(app, prefs)
    val nearby: dev.niccc2007.filet.nearby.NearbyManager by lazy {
        dev.niccc2007.filet.nearby.NearbyManager(app, vfs, prefs, scope)
    }
    init { dev.niccc2007.filet.data.Diag.start(app) }

    val home = HomeFeed(app, scope).also { feed ->
        // The New files list re-reads once Filet's own writes are in the media index, which is
        // when "after our own writes land" is actually true - a re-read at the moment the copy
        // finishes would race the scanner and miss them.
        dev.niccc2007.filet.media.MediaAnnounce.onScanned = { feed.changed() }
    }

    /**
     * The folders on screen, which are what the watcher watches. Set by the browser as panes
     * move; see `BrowserViewModel.followVisibleFolders`.
     */
    val watchedFolders = kotlinx.coroutines.flow.MutableStateFlow<Set<dev.niccc2007.filet.vfs.VPath>>(emptySet())

    /** Told when a watched folder changed, so the pane showing it can re-list. */
    @Volatile
    var onFolderChanged: ((dev.niccc2007.filet.vfs.VPath) -> Unit)? = null

    /**
     * Hosting this phone over WebDAV, so it appears in Windows Explorer.
     *
     * Lazy for the same reason [nearby] is: it opens a listening socket when it is started and
     * there is no reason for the object to exist in a session where it never is. The scope is
     * resolved when the session starts rather than held, so changing it takes effect on the
     * next start instead of moving the root under a mounted drive.
     */
    val webdav: dev.niccc2007.filet.webdav.WebDavServer by lazy {
        // The endpoint is read back out of Prefs the moment the store is attached, below, so a
        // mapped drive on a PC keeps resolving across restarts.
        dev.niccc2007.filet.webdav.WebDavServer(
            vfs = vfs,
            rootFor = { scopeChoice ->
                when (scopeChoice) {
                    dev.niccc2007.filet.webdav.DavScope.SHARED_FOLDER ->
                        dev.niccc2007.filet.vfs.VPath.of("local", "/storage/emulated/0/Filet/Shared")
                    dev.niccc2007.filet.webdav.DavScope.WHOLE_PHONE ->
                        dev.niccc2007.filet.vfs.VPath.of("local", "/storage/emulated/0")
                }
            },
            onEvent = { davState.value = it },
            // Everything the index already knows, flattened into the views. One broad query
            // rather than a query per view: the filtering is a pure function and running it
            // eight times over the same rows costs nothing next to eight trips to SQLite.
            indexed = {
                // Reused for a few seconds rather than rebuilt per request - see
                // DavViews.SNAPSHOT_MS for why, and what the staleness costs.
                val now = System.currentTimeMillis()
                val cached = viewSnapshot
                if (cached != null &&
                    dev.niccc2007.filet.webdav.DavViews.snapshotFresh(viewSnapshotAt, now)
                ) {
                    cached
                } else kotlinx.coroutines.runBlocking {
                    runCatching {
                        index.candidates(
                            dev.niccc2007.filet.index.SearchRequest(
                                query = "",
                                reach = dev.niccc2007.filet.index.Reach.Everywhere,
                                origin = null,
                                limit = 4000,
                            ),
                            dev.niccc2007.filet.index.QueryParser.parse(""),
                        )
                            .filterNot { it.isDir }
                            .mapNotNull { row ->
                                val p = index.pathFor(row.id) ?: return@mapNotNull null
                                dev.niccc2007.filet.webdav.DavViews.Entry(
                                    p, row.name, row.size, row.mtime,
                                )
                            }
                    }.getOrDefault(emptyList())
                        .also { viewSnapshot = it; viewSnapshotAt = System.currentTimeMillis() }
                }
            },
        ).also {
            // Setting the store loads the endpoint back out of Prefs and keeps it written from
            // then on. Without it the code, the port and the hosted folder were in-memory only:
            // settable, and gone on the next launch - so the URL a PC had mapped moved and the
            // saved network drive stopped resolving.
            it.store = dev.niccc2007.filet.data.PrefsDavStore(prefs)
            it.beacon = davBeacon
            it.onRemoteWrite = { touched ->
                remoteWrites.value = remoteWrites.value + 1
                // The reason a file sent to this phone over a network drive was on the disk
                // and absent from the gallery: nothing had told the media index about it.
                dev.niccc2007.filet.media.MediaAnnounce.announce(app, touched)
            }
        }
    }

    /**
     * Announces this phone's share while it hosts, and listens for other phones doing the same.
     *
     * On the graph rather than inside the server because both halves are needed in places the
     * server is not: the Remotes screen scans with it without hosting anything.
     */
    val davBeacon: dev.niccc2007.filet.webdav.DavBeacon by lazy {
        dev.niccc2007.filet.webdav.DavBeacon(app)
    }

    /**
     * The last pull of the index behind the hosting views, and when it was taken.
     *
     * Volatile rather than synchronised: the server answers on four threads, and two of them
     * racing here costs one duplicate query, not a wrong answer. A lock would serialise every
     * listing behind whichever thread was rebuilding.
     */
    @Volatile private var viewSnapshot: List<dev.niccc2007.filet.webdav.DavViews.Entry>? = null
    @Volatile private var viewSnapshotAt: Long = 0L

    /** The hosting card reads this; the server writes it. */
    val davState = kotlinx.coroutines.flow.MutableStateFlow(dev.niccc2007.filet.webdav.DavState())

    /**
     * Bumped whenever a mounted desktop or phone changes a file on this one.
     *
     * Separate from [davState], which changes on every request including reads - hanging a
     * re-listing off that would re-read folders while somebody merely browses the share.
     */
    val remoteWrites = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /**
     * Keep the launcher's long-press menu honest about whether sharing is on.
     *
     * One collector rather than a call beside each state write. The share's state is set in
     * seventeen places, and a menu entry that says "Start sharing" while a share is running is
     * exactly the kind of staleness that survives for months because nobody long-presses their
     * own app icon to check.
     *
     * `distinctUntilChanged` on the flag alone: the state also carries client lists and byte
     * counts that change constantly, and republishing the menu on every request would hit the
     * platform's shortcut rate limit and then silently stop updating at all.
     */
    /**
     * Notices when this device moves to a different network, so nothing has to be retried by hand.
     *
     * Not lazy and not tied to the UI: a share can be up while the app is in the background, which
     * is exactly when a phone moves between networks.
     */
    val netWatch = dev.niccc2007.filet.webdav.NetworkWatch(app).also { it.start() }

    /**
     * Everything that has to happen again when the network changes.
     *
     * One collector rather than a call in each of three places, because the three are one event
     * and were previously handled by the person: stop and start hosting, then open a screen until
     * the other device reappeared.
     *
     *  - **Announce again.** A registration is bound to the interface it was made on, so after a
     *    move the record names an address that no longer exists.
     *  - **Listen again.** A discovery listener holds the view it built on the old interface, and
     *    mDNS does not re-announce on request.
     *  - **Re-read what was shown.** The hosting card prints the address a PC should mount, and
     *    that address has just changed.
     *
     * Zero means nothing has been seen yet, which is not a move. Every later value is acted on,
     * including the first sighting at startup - by then there is nothing advertised to re-announce
     * and no scan to restart, so it costs nothing and needs no special case.
     */
    private val networkMoves = scope.launch {
        netWatch.moves.filter { it > 0L }.collect {
            runCatching { davBeacon.readvertiseAll() }
            runCatching { davBeacon.restartScan(forget = true) }
            // Peer discovery has the same staleness for the same reason - a scan that was running,
            // and only that. Starting one here put Android 17's device picker over whatever was
            // on screen, with Nearby never opened (issue #38).
            runCatching { nearby.restartScan() }
            // And the server scan, which was missing from this list - which is why its rows
            // outlived the networks they were found on. Only when it already exists: it is lazy,
            // and building it here to reset a list nobody has ever filled would be work done
            // because the Wi-Fi changed.
            if (serverScanLazy.isInitialized()) {
                runCatching { serverScanLazy.value.onNetworkMoved() }
            }
            // Only when something is hosting: the server is lazy, and building it here to ask for
            // a state nobody is showing would open its socket as a side effect of a Wi-Fi change.
            if (davState.value.running) runCatching { davState.value = webdav.state() }
        }
    }

    private val launcherMenu = scope.launch {
        nearby.state
            .map { it.running }
            .distinctUntilChanged()
            .collect { running ->
                runCatching {
                    dev.niccc2007.filet.shortcuts.LauncherShortcuts.publish(app, running)
                }
            }
    }

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
            // The index is derived, so it lives with the rest of the derived files and moves
            // with the one setting that governs them. A path rather than a File: R3 counts a
            // storage handle in a signature. See `FiletStore.dirPath`.
            dbDir = dev.niccc2007.filet.data.FiletStore.dirPath(
                app, dev.niccc2007.filet.data.FiletStore.Area.INDEX,
            ),
            // What Filet remembers, as opposed to what it worked out. A different folder and a
            // different file because it obeys different rules: `Area.MEMORY` is not disposable,
            // so the cache setting never moves it and no Clear button ever offers it. Nothing
            // else on the phone records when a file first appeared here, so losing it is
            // permanent in a way losing the index is not.
            memoryDir = dev.niccc2007.filet.data.FiletStore.dirPath(
                app, dev.niccc2007.filet.data.FiletStore.Area.MEMORY,
            ),
        )
    }.getOrElse { NullIndex() }

    /** How many Activities are started. Zero means nothing of Filet is on screen. */
    @Volatile
    private var activitiesStarted = 0

    /** How long a folder has to go quiet before its index pass runs. See [watcher]. */
    private val watchSettleMs = 1_500L

    private val watchPending = java.util.concurrent.ConcurrentHashMap<
        dev.niccc2007.filet.vfs.VPath,
        Pair<kotlinx.coroutines.Job, MutableSet<String?>>,
        >()

    /**
     * An inotify event in a folder that is on screen.
     *
     * Bug identified here, and it is most of issue #13. Every event started its own
     * four-second index pass: no debounce, no suppression of Filet's own writes, and no gate on
     * whether anybody was looking. And on Android 11+ shared storage is a FUSE mount served BY
     * MediaProvider, so every folder walk is MediaProvider CPU - which is why the battery
     * showed up against the system's media picker rather than against Filet.
     *
     * The folder is coalesced over a 1.5 s quiet window, so a burst is one pass. A path Filet
     * itself just wrote is dropped, because the operation that wrote it already refreshed what
     * it touched. And the index pass only runs while the app is visible.
     *
     * What it watches changed again when tracked folders were retired: it used to watch those
     * and feed the New files list, and that list now reads the media index (see `HomeFeed`).
     * Its job is the folders on screen - the pane showing a changed folder re-lists - and it
     * must not be wired back into the feed: the index already knows, at no cost.
     */
    val watcher = HotWatcher(vfs) { changed, name ->
        if (name != null &&
            dev.niccc2007.filet.media.MediaAnnounce.wroteRecently(changed.child(name))
        ) {
            return@HotWatcher
        }
        val names = watchPending.remove(changed)?.let { (job, seen) -> job.cancel(); seen }
            ?: HashSet()
        names += name
        watchPending[changed] = scope.launch {
            kotlinx.coroutines.delay(watchSettleMs)
            watchPending.remove(changed)
            onFolderChanged?.invoke(changed)
            // The same gates as walking into a folder: an index, kept up to date on its own, that
            // already holds something. Manual means nothing writes the index unasked - this
            // included - and on an index holding nothing there is no volume for the folder to
            // belong to.
            val st = index.status.value
            if (dev.niccc2007.filet.media.MediaAnnounce.visible() &&
                st.enabled && st.available && prefs.indexEnabled.value && prefs.indexAuto.value
            ) {
                runCatching { index.crawl(listOf(changed), budgetMs = 4_000) }
            }
        } to names
    }

    val indexCoordinator = IndexCoordinator(app, index, prefs, ledger, scope)
        .also { it.attachWatcher(watcher) }
        .also { c ->
            // SEARCH.md §13.6: a share reaches the crawl only when somebody opted it in. The uuid
            // is the provider's own root, built by RemoteIdentity rather than by hand, so it
            // matches the volume row exactly.
            c.indexedShares = {
                dev.niccc2007.filet.vfs.provider.net.RemoteIdentity.indexedUuids(connections.all())
            }
            c.seedFromMedia = { roots -> mediaWatch.seed(roots) }
        }

    /** SEARCH.md §14.6: the index kept current by the media store's own change record. */
    val mediaWatch: dev.niccc2007.filet.index.MediaWatch by lazy {
        dev.niccc2007.filet.index.MediaWatch(app, index, prefs, scope) {
            scope.launch { indexCoordinator.onOpen(runCatching { vfs.roots().map { it.path } }.getOrElse { emptyList() }) }
        }
    }

    /**
     * The drives the index has walked completely - the ones a walk beside it leaves alone.
     * SEARCH.md §14.2, decision 15. Re-read whenever a pass ends, which is the only time it can
     * change; read on every keystroke, it would be a query per letter typed.
     */
    @Volatile var coveredVolumes: Set<String> = emptySet()
        private set

    init {
        scope.launch {
            index.status
                .map { it.running to it.lastRunAt }
                .distinctUntilChanged()
                .collect { (running, _) ->
                    if (!running) coveredVolumes = runCatching { index.completeVolumes() }.getOrDefault(emptySet())
                }
        }
    }

    /** A result whose check found nothing there, forgotten so it is not offered again. */
    fun forgetGone(path: dev.niccc2007.filet.vfs.VPath) {
        scope.launch { runCatching { index.forget(path) } }
    }

    /**
     * Folders a search walk read on a share, on their way into the index. SEARCH.md §14.7.
     *
     * A queue rather than a write on the walker's coroutine: a walk over a NAS lists hundreds of
     * folders, and a transaction per folder on the walker would slow the search the index is
     * meant to speed up. Dropped rather than waited on when it backs up - a listing not written
     * is a folder the next walk writes instead.
     */
    private val shareListings = kotlinx.coroutines.channels.Channel<Pair<dev.niccc2007.filet.vfs.VPath, List<dev.niccc2007.filet.vfs.VNode>>>(
        capacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    init {
        scope.launch {
            for ((dir, children) in shareListings) {
                // The share's own root, built by RemoteIdentity - never the folder the walk began
                // at. A Folder or Subtree search walks from where it stands, and registering that
                // as a volume is the defect CrawlScope exists for.
                val root = connections.all()
                    .map(dev.niccc2007.filet.vfs.provider.net.RemoteIdentity::rootOf)
                    .firstOrNull { dev.niccc2007.filet.index.VolumeOwner.owns(it.toString(), dir.toString()) }
                    ?: continue
                runCatching {
                    index.putListing(root, dir, children) {
                        connections.all().any { dev.niccc2007.filet.vfs.provider.net.RemoteIdentity.rootOf(it) == root }
                    }
                }
            }
        }
    }

    init {
        // SEARCH.md §13.7. A share deleted, re-pointed at another share, or switched off
        // indexing loses its rows here - the store reports it, the index owns the delete.
        connections.onRowsLost = { uuid -> scope.launch { runCatching { index.forgetVolumes { it != uuid } } } }
        // And the leak from before the sweep existed: volume rows for shares deleted long
        // ago. Local volumes are never this sweep's to judge - see RemoteIdentity.isOrphan.
        scope.launch {
            runCatching {
                val saved = connections.all()
                index.forgetVolumes { uuid -> !dev.niccc2007.filet.vfs.provider.net.RemoteIdentity.isOrphan(uuid, saved) }
            }
        }
    }

    /**
     * Ordered by preference: the index answers whole-device and provenance queries, the walk
     * answers everything else and everything the index cannot. Both feed the same reranker,
     * so the user never sees which one replied.
     */
    val searchSources: List<SearchSource> =
        listOf(
            IndexSearchSource(index, vfs),
            WalkSearchSource(vfs) { dir, children ->
                if (dir.scheme in dev.niccc2007.filet.index.VolumeKey.REMOTE_SCHEMES) shareListings.trySend(dir to children)
            },
        )

    fun onFirstScreen() {
        scope.launch {
            dev.niccc2007.filet.log.FiletLog.i("launch", "onFirstScreen begins")
            val roots = dev.niccc2007.filet.log.FiletLog.span("launch", "vfs.roots") {
                runCatching { vfs.roots().map { it.path } }.getOrElse { emptyList() }
            }
            // Once, and harmless after: the retired tracked-folder list and its first-seen
            // store, so a phone that used them does not carry state nothing reads.
            dev.niccc2007.filet.home.HomeFeed.forgetRetired(prefs)
            // Read live, so blocking a folder takes effect on the next page rather than on the
            // next launch.
            home.blockedFolders = { prefs.blockedFolders.value }
            home.feedPrefixes = { prefs.feedPrefixes.value }
            // Filet's own folders and files, always. Not a stored preference, because it is not
            // a judgement anybody made - a file manager listing its own cache writes under
            // "what is new on your phone" is the app talking about itself. See
            // `FeedExclusions.ownPaths`, and note that Shared and Received are excluded
            // from the exclusion: files that arrived from elsewhere are the point of the tab.
            home.ownPaths = {
                dev.niccc2007.filet.home.FeedExclusions.ownPaths(
                    dev.niccc2007.filet.data.FiletStore.rootPath(app),
                )
            }
            home.order = { prefs.newFilesOrder.value }
            dev.niccc2007.filet.log.FiletLog.span("launch", "config.start") { configSync.start() }
            // One query per page, never one per row: see HomeFeed.corrected.
            home.firstSeenLookup = { paths -> index.firstSeenOf(paths) }
            dev.niccc2007.filet.log.FiletLog.span("launch", "scripts.seedExamples") { scripts.seedExamples() }
            dev.niccc2007.filet.log.FiletLog.span("launch", "home.refresh") { home.refresh() }
            index.status.value.let {
                if (it.enabled) dev.niccc2007.filet.log.FiletLog.span("launch", "index.onStart") { indexCoordinator.onStart(roots) }
            }
            // Follow the folders on screen. Gated on the app being on screen: inotify itself is
            // cheap, but what it triggers is not, and this process routinely lives for days
            // behind the playback and sharing services. Part of issue #13.
            scope.launch {
                watchedFolders.collect { paths ->
                    if (activitiesStarted > 0) watcher.watch(paths) else watcher.stopAll()
                }
            }
            (app as? android.app.Application)?.registerActivityLifecycleCallbacks(
                object : android.app.Application.ActivityLifecycleCallbacks {
                    override fun onActivityStarted(a: android.app.Activity) {
                        if (activitiesStarted++ == 0) {
                            watcher.watch(watchedFolders.value)
                            mediaWatch.onForeground()
                            configSync.onForeground()
                        }
                    }

                    override fun onActivityStopped(a: android.app.Activity) {
                        // Last one out stops the watches. A foreground service keeps the
                        // process alive, which is exactly the case this exists for.
                        if (--activitiesStarted <= 0) {
                            activitiesStarted = 0
                            watcher.stopAll()
                            // Not stopped with the watches: it keeps going for the minutes in
                            // Settings, and then lets go. SEARCH.md §14.6.
                            mediaWatch.onBackground()
                            configSync.onBackground()
                        }
                    }

                    override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = Unit
                    override fun onActivityResumed(a: android.app.Activity) = Unit
                    override fun onActivityPaused(a: android.app.Activity) = Unit
                    override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) = Unit
                    override fun onActivityDestroyed(a: android.app.Activity) = Unit
                },
            )
            // Provenance is what makes the Source chip real; wiring it here rather than in
            // the UI keeps Home ignorant of whether an index exists.
            home.originLookup = { path ->
                index.provenanceOf(path)?.origin ?: bridge.provenanceOf(path.toString())?.origin
            }
            // Pull whatever the companion app is doing into the one Activity surface.
            runCatching { bridge.pullJobs() }
            registerMissingMedia(roots)
        }
    }

    /**
     * Hand the gallery whatever it has never been told about.
     *
     * If Filet tracks a file, Filet registers it - which has to cover what is already on the
     * disk and not only what arrives next. A file written straight to storage is invisible to
     * every gallery until an app announces it, and nothing background-scans any more.
     *
     * Throttled, and capped inside the sweep: it is a set difference against what MediaStore
     * already holds, so a device in step does nothing at all, and one that has never been
     * scanned catches up over a few launches rather than in one storm.
     */
    private suspend fun registerMissingMedia(roots: List<dev.niccc2007.filet.vfs.VPath>) {
        val last = prefs.getLong(MEDIA_SWEEP_AT, 0L)
        val now = System.currentTimeMillis()
        if (now - last < MEDIA_SWEEP_EVERY) return
        prefs.putLong(MEDIA_SWEEP_AT, now)
        runCatching {
            val where = (roots + nearby.receivedFolder).distinct()
            dev.niccc2007.filet.media.MediaCatchUp.sweep(app, vfs, where)
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
    override suspend fun generationsFor(paths: List<dev.niccc2007.filet.vfs.VPath>) =
        emptyMap<dev.niccc2007.filet.vfs.VPath, Long>()

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
    override suspend fun crawl(
        roots: List<dev.niccc2007.filet.vfs.VPath>,
        budgetMs: Long,
        onProgress: (Long, dev.niccc2007.filet.vfs.VPath?) -> Unit,
    ) =
        dev.niccc2007.filet.index.CrawlResult(0, 0, 0, true, 0)
    /** Nothing is crawling, so there is nothing to steer and nothing to promise on screen. */
    override fun steerCrawl(query: String) = Unit
    override suspend fun forget(path: dev.niccc2007.filet.vfs.VPath) = Unit
    override suspend fun clear() = Unit
    override fun close() = Unit
}

class FiletApp : Application() {
    // Timed because the graph is built on whatever thread first asks for it - the main thread,
    // during onCreate - so every constructor in it is on the path to the first frame.
    val graph: FiletGraph by lazy {
        dev.niccc2007.filet.log.FiletLog.span("launch", "graph build") { FiletGraph(this).also { graphBuilt = true } }
    }

    /**
     * Whether the graph has been built yet.
     *
     * So a broadcast arriving while Filet is in the background can decide whether it has anything
     * to tell. Touching `graph` to answer that would build the whole thing - index, server,
     * watcher - as a side effect of some other app being installed.
     */
    @Volatile
    private var graphBuilt = false

    override fun onCreate() {
        super.onCreate()
        // First, and before the graph: this is the earliest point in the process, so a session
        // opened here covers everything that follows - including the graph construction that is
        // itself under suspicion for the slow cold start. Reading one boolean out of
        // SharedPreferences is the only work done ahead of it.
        //
        // Not in the crash process, which has its own job and must not write into the log of the
        // run it is reporting on.
        // Before the first preference is read. See `restoreSettingsIfLost`.
        val restored = if (isCrashProcess()) 0 else restoreSettingsIfLost()
        if (!isCrashProcess()) {
            val verbose = runCatching {
                getSharedPreferences("filet", MODE_PRIVATE)
                    .getBoolean(dev.niccc2007.filet.log.FiletLog.PREF_VERBOSE, true)
            }.getOrDefault(true)
            dev.niccc2007.filet.log.FiletLog.open(this, verbose)
            dev.niccc2007.filet.log.FiletLog.i("launch", "process start")
            // Reported AFTER the session is open, not when it happened. The restore has to run
            // before the first preference read and the logger is not up that early, so saying
            // it here is the difference between a recorded event and a silent one.
            if (restored > 0) {
                dev.niccc2007.filet.log.FiletLog.i(
                    "settings", "restored " + restored + " preference store(s) from the Filet folder",
                )
            }
        }
        // Installed before anything else touches the graph, so a crash *while building the
        // graph* is caught too. No analytics, ever: a file manager's stack traces carry
        // paths, and paths are the most private strings on the device.
        //
        // Skipped in the `:crash` process, which exists to show the report - it must not be
        // able to loop by reporting its own failure to report.
        if (!isCrashProcess()) dev.niccc2007.filet.crash.CrashReport.install(this)
        if (!isCrashProcess()) registerSplitInstallReceiver()
        if (!isCrashProcess()) watchInstalledApps()
        if (!isCrashProcess()) moveIntoTheStore()
        // Leave a note in the folder saying what it is.
        //
        // The reminder was meant for uninstall time, which Android does not allow - nothing can
        // be added to the system's uninstall dialogue. This is the version that still works
        // when the app is gone: whoever finds the folder later finds it explaining itself. See
        // `FiletStore.folderNote`.
        if (!isCrashProcess()) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { dev.niccc2007.filet.data.FiletStore.describeFolder(this@FiletApp) }
            }
        }
        // A line a minute about heat, CPU and dropped frames, while detailed logging is on.
        //
        // Started here rather than from a screen, because the question it answers - what was the
        // phone doing while it got hot - is usually about a minute nobody was looking at. It
        // checks the detailed-logging switch itself, so turning that on mid-run starts the
        // record without anything being restarted. Not in `:crash`, which exists to show one
        // screen and then die.
        if (!isCrashProcess()) {
            // Its own scope, deliberately. `graph.scope` would do, and reading it here would
            // build the entire object graph before the first frame - the cold-start cost the
            // note on `moveIntoTheStore` is about.
            runCatching {
                dev.niccc2007.filet.log.Heartbeat.start(
                    this,
                    CoroutineScope(SupervisorJob() + Dispatchers.IO),
                )
            }
        }
    }

    /**
     * The one-time move of the old scattered working files into the visible folder.
     *
     * Off the main thread and after everything else in `onCreate`, because it walks directories
     * and the first frame must not wait for it. Nothing in the app depends on it having
     * finished: a file not yet moved is read from neither place and measured again, which is a
     * cache miss rather than a fault.
     *
     * Gated on a flag AND idempotent - see `FiletStore.migrate` for why both.
     */
    /**
     * Put the configuration back when the app has lost it and a copy is reachable.
     *
     * ## Why it runs before anything else
     *
     * Copying preference files underneath a running app does nothing visible: `Prefs` builds
     * its StateFlows from the stored values when it is constructed, so a restore after that
     * point is not seen until the process restarts. Called from the top of `onCreate` nothing
     * has read anything yet, so a restore simply becomes the state the app starts in. It is
     * ahead of the logger for the same reason, which is why its result is reported afterwards
     * rather than where it happens.
     *
     * Silent, and that is deliberate: there is nothing to decide. The app has no settings, a
     * snapshot of its settings is sitting in the Filet folder, and the alternative to using it
     * is starting empty. A prompt here would be asking somebody to confirm that they would
     * like their playlists back.
     *
     * Only when the preference store is genuinely ABSENT. Not "looks sparse", not "seems
     * older" - absent. Restoring over settings somebody is actually using would be the
     * opposite failure, and it is the worse one, because it destroys current state to recover
     * old state.
     *
     * This covers a cleared app whose All-files access survived. A fresh install has no
     * permission yet and cannot read shared storage at all, so that case is offered in
     * Settings instead, with a restart - see `SettingsHome.restore`.
     */
    private fun restoreSettingsIfLost(): Int = runCatching {
        if (dev.niccc2007.filet.data.SettingsHome.stores(this).isNotEmpty()) return@runCatching 0
        if (!dev.niccc2007.filet.data.FiletStore.reachable()) return@runCatching 0
        dev.niccc2007.filet.data.SettingsHome.restore(this)
    }.getOrDefault(0)

    /**
     * Write the configuration out, off the main thread.
     *
     * Called when the app goes to the background, which is both the moment somebody has
     * finished changing things and a moment nothing is waiting on the main thread.
     */
    fun keepSettingsSafe() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                // Connections go in as their own export, never as the encrypted store: its key
                // is destroyed by an uninstall, so the bytes would restore as undecryptable
                // ciphertext. Without secrets, so the hosts, ports, shares and usernames all
                // survive and only the passwords have to be retyped - shared storage is
                // readable by anything holding storage permission, and a saved password there
                // would be a worse problem than the one this solves.
                val remotes = runCatching {
                    org.json.JSONArray(graph.connections.exportAll(plainSecrets = false)).toString()
                }.getOrNull()
                dev.niccc2007.filet.data.SettingsHome.snapshot(this@FiletApp, remotes)
            }
        }
    }

    /** So rotation does not re-run the restore. See [restoreConnectionsIfLost]. */
    private val remotesRestoreTried = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Put the saved remotes back, once there is a graph to put them in.
     *
     * A second restore phase, and it has to be: decoding a connection needs the connection
     * store, which belongs to the object graph, so this cannot happen in the window where the
     * raw preference files go back. Only when there are none - importing over connections
     * somebody is already using would be the opposite fault, and the worse one.
     *
     * Called from the Activity, NOT from `onCreate`. `graph` is a `by lazy`, so touching it
     * here would build the entire object graph before the first frame, which is the cold-start
     * cost `moveIntoTheStore` is written to avoid. By the time an Activity exists the graph is
     * being built anyway, so this costs nothing.
     *
     * Once per process, and the guard lives here rather than at the call site: the Activity is
     * recreated on every rotation and theme change, and the caller cannot be expected to
     * remember that.
     */
    fun restoreConnectionsIfLost() {
        if (!remotesRestoreTried.compareAndSet(false, true)) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                if (graph.connections.all().isNotEmpty()) return@runCatching
                val raw = dev.niccc2007.filet.data.SettingsHome.pendingConnections(this@FiletApp)
                    ?: return@runCatching
                val array = org.json.JSONArray(raw)
                val entries = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                if (entries.isEmpty()) return@runCatching
                val put = graph.connections.importAll(entries)
                dev.niccc2007.filet.log.FiletLog.i(
                    "settings", "restored " + put + " saved connection(s), without passwords",
                )
            }
        }
    }

    private fun moveIntoTheStore() {
        // Deliberately NOT through the graph.
        //
        // `graph` is a `by lazy`, so reading it here would build the whole object graph on the
        // main thread inside `onCreate` - which is the cold-start cost the comment at the top
        // of this method warns about, paid to find out whether a flag is set. One boolean out
        // of SharedPreferences costs nothing and reaches the same answer; `Prefs` reads the
        // same file under the same key.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val sp = getSharedPreferences(
                    dev.niccc2007.filet.data.PrefKeys.FILE, MODE_PRIVATE,
                )
                if (sp.getBoolean(dev.niccc2007.filet.data.PrefKeys.STORE_MIGRATED, false)) {
                    return@runCatching
                }
                val moved = dev.niccc2007.filet.data.FiletStore.migrate(this@FiletApp)
                sp.edit().putBoolean(dev.niccc2007.filet.data.PrefKeys.STORE_MIGRATED, true).apply()
                dev.niccc2007.filet.log.FiletLog.i(
                    "store", "moved " + moved + " file(s) into the Filet folder",
                )
            }
        }
    }

    /**
     * Listen for the verdict on a split-bundle install.
     *
     * Without this the session commits and **nothing happens**: the platform answers
     * `STATUS_PENDING_USER_ACTION` by handing back an intent that has to be started, and a
     * commit whose status nobody reads is a confirmation screen that never appears. Registered
     * on the application rather than an activity because the answer can arrive while the
     * browser is not on screen, and a missed one leaves a staged session occupying its own size
     * in storage until the platform collects it.
     *
     * NOT_EXPORTED because it is our own broadcast to ourselves; an exported receiver here
     * would let any app on the device drive Filet's installer status handling.
     */
    /**
     * Drop the cached opener list when an app is installed or removed.
     *
     * The list of apps that can open a file changes for exactly one reason, and this is it.
     * Without this the cache would rely on its own timeout, so an app installed a minute ago
     * would be missing from the picker with no way to make it appear.
     */
    private fun watchInstalledApps() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                dev.niccc2007.filet.handlers.OpenerCatalog.invalidate()
                // The other thing a package change invalidates: whether the companion app is
                // installed, which is cached for the life of the process precisely because it
                // cannot change without one of these arriving.
                if (graphBuilt) runCatching { graph.bridge.forgetPeer() }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        runCatching { registerReceiver(receiver, filter) }
    }

    private fun registerSplitInstallReceiver() {
        val receiver = dev.niccc2007.filet.apk.SplitInstallReceiver { message ->
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    receiver,
                    dev.niccc2007.filet.apk.SplitInstallReceiver.filter(),
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                registerReceiver(receiver, dev.niccc2007.filet.apk.SplitInstallReceiver.filter())
            }
        }
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

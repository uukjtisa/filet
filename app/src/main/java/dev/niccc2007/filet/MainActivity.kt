package dev.niccc2007.filet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.niccc2007.filet.browser.BrowserScreen
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletDialogs
import dev.niccc2007.filet.handlers.HandlerHost
import dev.niccc2007.filet.shortcuts.ShortcutRoute
import dev.niccc2007.filet.shortcuts.ShortcutRouterActivity
import dev.niccc2007.filet.shortcuts.shortcutRoute
import dev.niccc2007.filet.signet.OnboardingScreen
import dev.niccc2007.filet.signet.markOnboardingComplete
import dev.niccc2007.filet.signet.onboardingComplete
import dev.niccc2007.filet.ui.theme.FiletTheme
import dev.niccc2007.filet.update.UpdateNotifier
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.provider.StorageAccess
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var vm: BrowserViewModel? = null

    /**
     * SAF tree grants. Persisted immediately: a tree permission that is not taken with
     * `takePersistableUriPermission` evaporates on the next process death, and the user
     * experiences that as the app forgetting their SD card.
     */
    // Answers go to the ViewModel, which outlives this Activity and knows who asked.
    private val forResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vm?.deliverResult(r.resultCode, r.data)
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        vm?.onFolderGranted(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = FiletApp.graphOf(this)
        // Here rather than in `Application.onCreate`: putting the saved remotes back needs the
        // connection store, and touching the graph there would build the whole thing before the
        // first frame. The graph is being built on this line anyway.
        (application as? FiletApp)?.restoreConnectionsIfLost()

        setContent {
            val theme by graph.prefs.theme.collectAsState()
            val accent by graph.prefs.accent.collectAsState()
            // The folder-total lookup the rows read, when "Folder total" is on the line under the
            // name or in the column beside it.
            //
            // Reading is free and measuring is asked for separately - see `FolderBytes`. The
            // read returns what is already known; the request puts the folder in a queue that
            // walks ONE at a time, which is what makes a real folder size affordable from a
            // list. Provided once here rather than threaded through every list, grid and pane
            // signature to deliver one figure.
            val folderBytes = remember {
                val measure = dev.niccc2007.filet.browser.FolderMeasure.of(graph)
                object : dev.niccc2007.filet.browser.FolderBytes {
                    override suspend fun of(path: dev.niccc2007.filet.vfs.VPath) =
                        runCatching { measure.total(path) }
                            .getOrDefault(dev.niccc2007.filet.browser.FolderTotal.Unknown)

                    override fun measure(path: dev.niccc2007.filet.vfs.VPath) {
                        runCatching { measure.measure(path) }
                    }
                }
            }
            // ONE clock for every pending figure on screen, both panes included, so they breathe
            // together instead of each row starting its own animation when it is composed. See
            // `RowPulse` for why per-row clocks cost a frame budget and read as noise.
            val rowPulse = dev.niccc2007.filet.ui.rememberRowPulse()
            CompositionLocalProvider(
                dev.niccc2007.filet.browser.LocalFolderBytes provides folderBytes,
                dev.niccc2007.filet.ui.LocalRowPulse provides rowPulse,
            ) {
            FiletTheme(theme = theme, accent = accent) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val ctx = LocalContext.current
                    var onboarded by remember { mutableStateOf(onboardingComplete(ctx)) }

                    if (!onboarded) {
                        OnboardingScreen(
                            onFinish = {
                                // Both Skip and Done land here, and both mean "do not show
                                // this again". Without the write, onboarding reappears on
                                // every launch.
                                markOnboardingComplete(ctx)
                                onboarded = true
                                graph.onFirstScreen()
                            }
                        )
                        return@Surface
                    }

                    // Re-check on every resume: a special permission is revocable from
                    // Settings while we are backgrounded, so a cached boolean lies.
                    var allFiles by remember { mutableStateOf(StorageAccess.hasAllFiles()) }
                    var browserRef by remember { mutableStateOf<BrowserViewModel?>(null) }
                    val owner = LocalLifecycleOwner.current
                    DisposableEffect(owner) {
                        val obs = LifecycleEventObserver { _, e ->
                            if (e == Lifecycle.Event.ON_RESUME) {
                                allFiles = StorageAccess.hasAllFiles()
                                // A tab restored before access was granted has nothing in it.
                                // Re-read those, and only those.
                                browserRef?.refreshStalePanes()
                                // And ask about updates HERE rather than once per Activity.
                                //
                                // Filet's process outlives its screen - a foreground service
                                // while music plays or a share is hosted - so
                                // coming back from recents resumes this same Activity and a
                                // one-shot effect below never runs again. That is why a new
                                // version could sit on GitHub for days without the app ever
                                // mentioning it. The throttle lives in `shouldCheck`, where
                                // it can be reasoned about, rather than in how often this
                                // happens to be called.
                                browserRef?.checkForUpdatesQuietly()
                            }
                        }
                        owner.lifecycle.addObserver(obs)
                        onDispose { owner.lifecycle.removeObserver(obs) }
                    }

                    val browser: BrowserViewModel = viewModel(factory = factoryFor(graph))
                    wire(browser)
                    // The observer above is created before the ViewModel exists, so it reaches
                    // it through this rather than capturing it.
                    browserRef = browser
                    LaunchedEffect(allFiles) { if (allFiles) browser.refreshStalePanes() }

                    Column(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.statusBars),
                    ) {
                        // The app is usable without all-files access - PLAN.md L0 makes SAF a
                        // first-class provider rather than a fallback. So this is a banner,
                        // not a wall. A wall would be easier and would also be a lie.
                        if (!allFiles) PermissionBanner { browser.requestAllFiles() }
                        HandlerHost(browser) { BrowserScreen(browser) }
                    }
                    FiletDialogs(browser)
                    // Everything that appears without being asked for: the first-run welcome
                    // and the update offer, one at a time through `Notices`.
                    //
                    // Bug identified here: this was never composed. Both ends of the chain
                    // were built and tested - `FirstRun` decides whether it is a first run,
                    // `checkForUpdatesQuietly` offers UPDATE to the queue, `Notices` ranks
                    // them - and nothing drew the result, so every unsolicited dialogue in
                    // the app silently did nothing. It presented as the update popup having
                    // stopped appearing, and the version arithmetic (a 0.1.20 build against a
                    // 0.1.19 release declines forever, correctly) hid it for longer: on a dev
                    // install the queue was usually empty anyway, so an empty screen looked
                    // like the right answer.
                    dev.niccc2007.filet.notice.NoticeHost(browser)

                    DisposableEffect(Unit) {
                        graph.onFirstScreen()
                        handleIncoming(intent, browser)
                        // The first resume has already happened by the time the observer
                        // above has a ViewModel to call, so the first visit asks here.
                        browser.checkForUpdatesQuietly()
                        // And whether this launch is a genuinely new install. Here rather than
                        // in `FiletApp`, because it queues a dialogue and a dialogue needs a
                        // screen to be drawn on - see `FirstRun.check`.
                        dev.niccc2007.filet.notice.FirstRun.check(
                            this@MainActivity,
                            dev.niccc2007.filet.BuildConfig.DEBUG,
                        )
                        onDispose { }
                    }

                }
            }
            }
        }
    }

    /**
     * Discovery runs for as long as Filet is on screen, not only on the Remotes screen.
     *
     * This is what makes a saved remote follow its device to a new address without being asked.
     * Learning happens from an announcement, and announcements were only being listened for
     * while the one screen that starts a scan was open - so changing network produced a remote
     * that no longer worked and a screen offering to add it again as a stranger.
     *
     * Bounded by the foreground on purpose. A scan holds a multicast lock, and holding one while
     * the app is not visible is a battery cost nobody asked for; the addresses are learned the
     * next time Filet is opened, which is also the next time they could matter.
     */
    override fun onStart() {
        super.onStart()
        FiletApp.graphOf(this).davBeacon.startScan()
    }

    override fun onStop() {
        super.onStop()
        FiletApp.graphOf(this).davBeacon.stopScan()
    }

    /**
     * Frame timing, for the "it feels laggy" half of the diagnostics.
     *
     * Bug identified here: [FrameWatch] was built, it gates itself on detailed logging, the
     * diagnostics dump has a `frames` section that reads it - and nothing ever attached it, so
     * that section said "no frames measured" on every report ever produced. The one number
     * that distinguishes "the phone is busy" from "this screen is dropping frames" was
     * unobtainable.
     *
     * Attached on resume rather than on start: a window behind another Activity is not
     * producing frames anybody is looking at, and metrics from it would dilute the only
     * statistic that matters here.
     */
    override fun onResume() {
        super.onResume()
        dev.niccc2007.filet.log.FrameWatch.attach(this)
    }

    override fun onPause() {
        super.onPause()
        dev.niccc2007.filet.log.FrameWatch.detach(this)
        // The tabs as they are this moment. A scroll or a walk in the last half second is still
        // waiting on its pause, and leaving the screen is when the process may be swiped away.
        vm?.saveTabsNow()
        // Leaving the app is both the moment somebody has finished changing things and a
        // moment nothing is waiting on the main thread. The write itself is off-thread and
        // refuses to replace a fuller snapshot with an emptier one - see `SettingsHome`.
        (application as? FiletApp)?.keepSettingsSafe()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm?.let { handleIncoming(intent, it) }
    }

    private fun wire(browser: BrowserViewModel) {
        vm = browser
        browser.onIntent = { i -> runCatching { startActivity(i) }.onFailure { browser.toast("Nothing on this device can do that.") } }
        browser.onIntentForResult = { i ->
            runCatching { forResult.launch(i) }.onFailure {
                // Never reached the other side, so nothing will answer: answer for it.
                browser.deliverResult(RESULT_CANCELED, null)
                browser.toast("Nothing on this device can do that.")
            }
        }
        browser.onPickFolder = { hint ->
            runCatching { pickFolder.launch(hint) }
                .onFailure { browser.toast("No folder picker on this device.") }
        }
        browser.onExit = { finish() }
        browser.onStopFilet = { finishAndRemoveTask() }
        browser.onShare = { nodes -> share(nodes, browser) }
        browser.onCheckUpdates = if (BuildConfig.UPDATER_ENABLED) {
            { browser.openUrl("https://github.com/uukjtisa/filet/releases/latest") }
        } else null

        // Shares that asked to come up with the app. A no-op unless one was ticked, and it is
        // ticked per share - nothing on a fresh install opens a socket by itself.
        //
        // Here rather than in the Application, because it needs the foreground service, and a
        // service started before an Activity exists has nothing to put in its notification tap.
        browser.startAutoHosting()
    }

    /**
     * Inbound routing (PLAN.md L2).
     *
     * VIEW hands us a file to open; SEND hands us a file to put somewhere. They are different
     * verbs and are deliberately not collapsed: one opens a viewer, the other starts a paste.
     */
    private fun handleIncoming(intent: Intent?, browser: BrowserViewModel) {
        // An update notification. Checked before the shortcut routing because it carries
        // neither a target nor an action and would otherwise fall through to "open the app and
        // do nothing", which is the shape of the bug that took two reports to find below.
        if (intent?.hasExtra(UpdateNotifier.EXTRA_UPDATE) == true) {
            browser.openUpdateFromNotification(intent.getStringExtra(UpdateNotifier.EXTRA_UPDATE))
            return
        }

        // A job's notification: straight to the Activity tab, where the job is, rather than to
        // whichever tab was last open.
        if (intent?.getBooleanExtra(dev.niccc2007.filet.jobs.EXTRA_OPEN_ACTIVITY, false) == true) {
            browser.openActivity()
            return
        }

        // A pinned shortcut or a widget row has already resolved an ID to a path, so it
        // arrives as a Filet target rather than as a content URI. An action shortcut carries
        // no path at all.
        //
        // This used to read the target extra and nothing else, which is why every action
        // shortcut - Search, Start sharing, Index now, Recent - launched the app onto
        // whichever tab was last open and then did nothing. Reported twice before it was
        // found, because the icon looked right and the app did open.
        when (val route = shortcutRoute(
            target = intent?.getStringExtra(ShortcutRouterActivity.EXTRA_TARGET),
            action = intent?.getStringExtra(ShortcutRouterActivity.EXTRA_ACTION),
            handler = intent?.getStringExtra(ShortcutRouterActivity.EXTRA_HANDLER),
        )) {
            is ShortcutRoute.OpenTarget -> {
                browser.openResolvedTarget(route.raw, route.handler)
                return
            }
            is ShortcutRoute.RunAction -> {
                browser.runShortcutAction(route.action)
                return
            }
            is ShortcutRoute.Nothing -> Unit
        }
        // Which named viewer the other app picked - "Filet Video Player" and the rest are aliases
        // of this activity - or null for plain "Filet", which follows the routing table.
        val viewer = dev.niccc2007.filet.open.InboundViewer.of(intent?.component?.className)
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let {
                browser.openIncoming(it, intent.type, viewer = viewer?.handler)
            }
            // EDIT is VIEW with an opinion about which handler. Routed to the same place so
            // there is one path for an incoming file, with a flag rather than a second copy
            // of the staging, the stat and the failure wording.
            Intent.ACTION_EDIT -> intent.data?.let {
                browser.openIncoming(it, intent.type, edit = true)
            }
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) browser.acceptIncoming(listOf(uri))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) browser.acceptIncoming(uris)
            }
        }
    }

    /**
     * Share the selection.
     *
     * The Activity owns the chooser; the ViewModel owns turning a VFS path into a grantable
     * URI. Splitting it that way is what keeps `java.io.File` out of the UI layer entirely.
     */
    private fun share(nodes: List<VNode>, browser: BrowserViewModel) {
        lifecycleScope.launch {
            val uris = browser.shareUrisFor(nodes)
            if (uris.isEmpty()) { browser.toast("These files cannot be shared from here."); return@launch }
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            send.type = "*/*"
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(Intent.createChooser(send, "Share")) }
                .onFailure { browser.toast("Nothing can receive these.") }
        }
    }

    private fun factoryFor(graph: FiletGraph) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BrowserViewModel(graph) as T
    }
}

@Composable
private fun PermissionBanner(onGrant: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "All-files access is off — most folders will look empty.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            TextButton(onClick = onGrant, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Grant it", fontSize = 12.sp)
            }
        }
    }
}

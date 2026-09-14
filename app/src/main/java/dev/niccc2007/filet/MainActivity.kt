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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
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
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.provider.StorageAccess

class MainActivity : ComponentActivity() {

    private var vm: BrowserViewModel? = null

    /**
     * SAF tree grants. Persisted immediately: a tree permission that is not taken with
     * `takePersistableUriPermission` evaporates on the next process death, and the user
     * experiences that as the app forgetting their SD card.
     */
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

        setContent {
            val theme by graph.prefs.theme.collectAsState()
            val accent by graph.prefs.accent.collectAsState()

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

                    DisposableEffect(Unit) {
                        graph.onFirstScreen()
                        handleIncoming(intent, browser)
                        onDispose { }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm?.let { handleIncoming(intent, it) }
    }

    private fun wire(browser: BrowserViewModel) {
        vm = browser
        browser.onIntent = { i -> runCatching { startActivity(i) }.onFailure { browser.toast("Nothing on this device can do that.") } }
        browser.onPickFolder = { runCatching { pickFolder.launch(null) }.onFailure { browser.toast("No folder picker on this device.") } }
        browser.onExit = { finish() }
        browser.onShare = { nodes -> share(nodes, browser) }
        browser.onCheckUpdates = if (BuildConfig.UPDATER_ENABLED) {
            { browser.openUrl("https://github.com/uukjtisa/filet/releases/latest") }
        } else null
    }

    /**
     * Inbound routing (PLAN.md L2).
     *
     * VIEW hands us a file to open; SEND hands us a file to put somewhere. They are different
     * verbs and are deliberately not collapsed: one opens a viewer, the other starts a paste.
     */
    private fun handleIncoming(intent: Intent?, browser: BrowserViewModel) {
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
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { browser.openIncoming(it, intent.type) }
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

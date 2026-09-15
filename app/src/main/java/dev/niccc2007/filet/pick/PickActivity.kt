package dev.niccc2007.filet.pick

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.browser.BrowserScreen
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletDialogs
import dev.niccc2007.filet.ui.theme.FiletTheme
import kotlinx.coroutines.launch

/**
 * Filet answering "choose a file" for another app.
 *
 * The manifest has advertised an `ACTION_GET_CONTENT` filter since the first release, so Filet
 * has been offered in every chooser on the device - and nothing ever called `setResult`. Somebody
 * picked Filet, browsed, tapped a file, and the app that asked got nothing. That is rule R1 in
 * the one place R1 cannot see it: a dead control inside somebody ELSE's app, where the blame
 * lands on them.
 *
 * ## Why this is its own activity
 *
 * `MainActivity` is `launchMode="singleTask"`, and a singleTask activity does not run in the
 * caller's task - so `setResult` has nowhere to go and the caller is handed `RESULT_CANCELED`
 * the moment it starts. Teaching `MainActivity` to return a result would have produced a picker
 * that still returned nothing, with the reason nowhere in the change. This activity is
 * deliberately plain `standard` launch mode, and `tools/check-picker.mjs` fails the build if it
 * ever stops being.
 *
 * ## What it does not claim
 *
 * `ACTION_OPEN_DOCUMENT`, `ACTION_CREATE_DOCUMENT` and `ACTION_OPEN_DOCUMENT_TREE` are served by
 * a `DocumentsProvider`, not by an activity; no intent filter puts an app in that picker. Filet
 * answers the two actions an activity can answer and says so rather than appearing to cover all
 * of them.
 */
class PickActivity : ComponentActivity() {

    private lateinit var request: PickRequest

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        request = PickRequest.of(
            type = intent?.type,
            extraMimeTypes = intent?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
            allowMultiple = intent?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true,
            localOnly = intent?.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false) == true,
            openable = intent?.categories?.contains(Intent.CATEGORY_OPENABLE) == true,
        )

        // Cancel is the default answer, set before anything else can go wrong. A picker that
        // is killed, backed out of or crashed must look like a cancel to the caller rather
        // than leaving it waiting on a result that never arrives.
        setResult(RESULT_CANCELED)

        val graph = FiletApp.graphOf(this)
        val caller = callerLabel()

        setContent {
            val theme by graph.prefs.theme.collectAsState()
            val accent by graph.prefs.accent.collectAsState()
            FiletTheme(theme = theme, accent = accent) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val browser: BrowserViewModel = viewModel(factory = factoryFor(graph))
                    browser.pickRequest = request
                    browser.pickCaller = caller
                    browser.onExit = { cancel() }
                    browser.onPickCancel = { cancel() }
                    browser.onPickConfirm = { confirm(browser) }

                    // No bar of its own. The notice and the confirm are drawn by the browser,
                    // below its tabs and among its own actions - a strip laid over the top of
                    // the whole app read as a system dialog wrapping Filet rather than as
                    // Filet doing the job, which is The fault: and is right.
                    Column(
                        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
                    ) {
                        BrowserScreen(browser)
                    }
                    FiletDialogs(browser)
                }
            }
        }
    }

    /**
     * Hand the picked files back.
     *
     * Everything that can refuse does so BEFORE the activity closes, because a refusal the
     * caller discovers is indistinguishable from the user changing their mind.
     */
    private fun confirm(browser: BrowserViewModel) {
        lifecycleScope.launch {
            val nodes = browser.pickSelection()
            PickRules.refusal(request, nodes.size, nodes.any { it.isDir })?.let {
                browser.toast(it)
                return@launch
            }

            // The same route everything leaving the process takes: a FileProvider grant, and a
            // cached copy first for anything with no real path - a member inside an archive,
            // or a file on a network share. That is the part the system picker cannot do.
            val uris = browser.shareUrisFor(nodes)
            if (uris.isEmpty()) {
                browser.toast("That file cannot be handed to another app from here.")
                return@launch
            }
            if (uris.size < nodes.size) {
                // Honest rather than silently short: a caller that asked for four and gets two
                // has no way to know two were dropped.
                browser.toast("${nodes.size - uris.size} of these could not be handed over")
            }

            val answer = PickAnswer.of(uris.map { it.toString() }, request)
            val data = Intent()
            answer.primary?.let { data.data = Uri.parse(it) }
            if (answer.useClipData) {
                val clip = ClipData.newRawUri("files", Uri.parse(answer.uris[0]))
                for (extra in answer.uris.drop(1)) clip.addItem(ClipData.Item(Uri.parse(extra)))
                data.clipData = clip
            }
            // Without this the caller holds URIs it is not allowed to open, which fails at
            // `openInputStream` rather than here.
            data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setResult(RESULT_OK, data)
            finish()
        }
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    /** The asking app's name, for the bar. Its package if it has no readable label. */
    private fun callerLabel(): String? {
        val pkg = callingPackage ?: referrerPackage() ?: return null
        return runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /**
     * `callingPackage` is null unless the caller used `startActivityForResult`, which the
     * chooser does not always preserve. The referrer is the fallback, and both being absent
     * only costs the bar a name.
     */
    private fun referrerPackage(): String? = runCatching { referrer?.host }.getOrNull()

    private fun factoryFor(graph: dev.niccc2007.filet.FiletGraph) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BrowserViewModel(graph) as T
    }
}

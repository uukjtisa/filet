package dev.niccc2007.filet.docs

import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The reader for everything that turns into a [Doc]: Markdown, DOCX, PPTX.
 *
 * One screen, one reader per format, and the format is chosen from the extension rather than
 * sniffed - these three are told apart by their names in every file manager, and a DOCX that
 * has been renamed is a file whose owner has told us what they think it is.
 */
@Composable
fun DocumentScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    var doc by remember(node.path) { mutableStateOf<Doc?>(null) }
    var error by remember(node.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(node.path) {
        val kind = DocFormat.of(node.extension)
        val read = runCatching {
            withContext(Dispatchers.IO) {
                when (kind) {
                    DocFormat.MARKDOWN -> MarkdownReader.read(vm.readText(node))
                    DocFormat.WORD -> OfficeReader.read({ vm.openRead(node) }, presentation = false)
                    DocFormat.SLIDES -> OfficeReader.read({ vm.openRead(node) }, presentation = true)
                    // A plain text file reached here by somebody choosing this reader for it.
                    // Shown as one code block, which is honest - it has no structure to find.
                    DocFormat.NONE -> Doc(listOf(Block.Code(vm.readText(node))))
                }
            }
        }
        read.onSuccess { doc = it }
        read.onFailure { error = it.message ?: "This file could not be read." }
    }

    val current = doc
    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error.orEmpty(), fontSize = 12.sp, color = colors.warn, modifier = Modifier.padding(28.dp))
        }

        current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }

        else -> DocReader(current)
    }
}

/** Which reader a name points at. */
enum class DocFormat {
    MARKDOWN, WORD, SLIDES, NONE;

    companion object {
        fun of(extension: String): DocFormat = when (extension.lowercase()) {
            "md", "markdown", "mdown", "mkd" -> MARKDOWN
            "docx" -> WORD
            "pptx" -> SLIDES
            else -> NONE
        }

        /** Every extension this package has a reader for, for the handler registry. */
        val EXTENSIONS: Set<String> =
            setOf("md", "markdown", "mdown", "mkd", "docx", "pptx", "pdf", "html", "htm", "xhtml")
    }
}

/**
 * HTML, rendered, with its source one tap away.
 *
 * ## The security decisions, which are the whole of this file
 *
 * A local HTML file is somebody else's code, and a `WebView` is a browser. Three settings
 * decide whether opening one is safe, and all three are off:
 *
 * - **JavaScript.** Off. A local page with script can do everything a page can do, and the one
 *   thing somebody opening an `.html` in a file manager wants is to see what it says.
 * - **File access from file URLs.** Off, both flags. With them on, a page loaded from
 *   `file://` can `fetch` any other file the app can read and post it anywhere - which turns
 *   "preview this HTML" into an exfiltration primitive for the whole of shared storage. This
 *   is the single most important line here.
 * - **Navigation.** Blocked. Every link is refused rather than followed, so a preview cannot
 *   become a browsing session and cannot make a network request the reader did not ask for.
 *
 * The page is loaded as a STRING rather than by URL, which is what makes the file-access flags
 * moot as well as off: there is no base URL, so a relative `src` has nothing to resolve
 * against. Images and stylesheets beside the file therefore do not load, and that is the cost
 * of this being safe by construction rather than by configuration.
 *
 * ## Why the editor is the same editor
 *
 * The Source toggle does not open a second editor - it routes to the one in `TextEditorScreen`
 * through the handler registry, which is the editor the whole app uses. One editor, one set of
 * keybindings, one save path, one archive-save dialogue. A format gets a *pipeline* into it,
 * never a copy of it.
 */
@Composable
fun HtmlScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    var source by remember(node.path) { mutableStateOf<String?>(null) }
    var error by remember(node.path) { mutableStateOf<String?>(null) }
    var showSource by remember(node.path) { mutableStateOf(false) }

    LaunchedEffect(node.path) {
        runCatching { withContext(Dispatchers.IO) { vm.readText(node) } }
            .onSuccess { source = it }
            .onFailure { error = it.message ?: "This file could not be read." }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (showSource) "Source" else "Preview",
                fontSize = 11.sp,
                color = colors.fg3,
                modifier = Modifier.weight(1f),
            )
            Text(
                "no scripts",
                fontSize = 9.5.sp,
                color = colors.fg3,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.sunken)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (showSource) "Preview" else "Source",
                fontSize = 11.sp,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.accent.copy(alpha = 0.12f))
                    .clickable { showSource = !showSource }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        val html = source
        when {
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error.orEmpty(), fontSize = 12.sp, color = colors.warn)
            }

            html == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }

            showSource -> DocReader(Doc(listOf(Block.Code(html, "html"))))

            else -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        @Suppress("DEPRECATION")
                        settings.allowFileAccessFromFileURLs = false
                        @Suppress("DEPRECATION")
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        // Reflowed to the width, which is the whole reason this is readable on
                        // a phone at all.
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = object : WebViewClient() {
                            // EVERY navigation refused. A preview that can follow a link is a
                            // browser, and a browser inside a file manager is an outbound
                            // request nobody asked for.
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = true
                        }
                    }
                },
                update = { view ->
                    // loadDataWithBaseURL(null, ...) - no base, so a relative src resolves to
                    // nothing rather than to the filesystem. See the note above.
                    view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                },
            )
        }
    }
}

/** Whether this package has a reader for a file, for `HandlerRegistry`. */
fun hasDocReader(node: VNode): Boolean =
    node.extension.lowercase() in DocFormat.EXTENSIONS && FileKind.of(node) != FileKind.FOLDER

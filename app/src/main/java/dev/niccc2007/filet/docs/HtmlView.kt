package dev.niccc2007.filet.docs

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.compose.runtime.key
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgKv
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.types.FileTypes
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking


/**
 * The made-up origin a local page is served from, and the rules that keep it inside its folder.
 *
 * Pure, with no Android type in it, because every function here is a security decision and each
 * one can be wrong in a way that only a test with a hostile path will find.
 */
object HtmlOrigin {

    /**
     * Under `.invalid`, which RFC 2606 reserves so that it can never resolve. If interception
     * were ever bypassed, a request for this host fails at DNS instead of reaching somebody's
     * server.
     */
    const val DOMAIN = "filet.invalid"

    /**
     * One origin per FOLDER, named by a hash of its full path.
     *
     * Decision: per folder, not one shared origin. A shared origin would give every local page
     * the same `localStorage`, cookies and same-origin access - so a page in Downloads could
     * read whatever a page somewhere else had stored, and could frame any other folder's files
     * as its own. Per folder, the browser's own same-origin rule is the folder boundary.
     */
    fun host(folder: VPath): String = "d" + sha256(folder.toString()).take(20) + "." + DOMAIN

    private fun base(folder: VPath) = "https://" + host(folder) + "/"

    /** The address a document is opened at. */
    fun entryUrl(doc: VPath): String = base(doc.parent ?: doc) + encodeSegment(doc.name)

    /** Whether [url] addresses this folder's origin at all, found or not. */
    fun isOurs(folder: VPath, url: String): Boolean {
        val b = base(folder)
        return url.startsWith(b, ignoreCase = true) || url.equals(b.dropLast(1), ignoreCase = true)
    }

    /**
     * The file a request for [url] means, or null when it means nothing this page may read.
     *
     * Null for another origin, for the folder itself, for a segment that decodes to `.` or
     * `..` or contains a separator or a NUL, and for anything malformed. The browser has already
     * collapsed literal `../` in the URL, but a percent-encoded `%2e%2e` or `%2f` arrives
     * intact and decodes to exactly the traversal the collapsing was meant to prevent - which
     * is why the check runs AFTER decoding, on every segment.
     */
    fun resolve(folder: VPath, url: String): VPath? {
        val b = base(folder)
        if (!url.startsWith(b, ignoreCase = true)) return null
        val path = url.substring(b.length).substringBefore('#').substringBefore('?')
        var at = folder
        for (raw in path.split('/')) {
            if (raw.isEmpty()) continue
            val seg = decode(raw) ?: return null
            if (seg == "." || seg == ".." || '/' in seg || '\\' in seg || '\u0000' in seg) return null
            at = runCatching { at.child(seg) }.getOrNull() ?: return null
        }
        // Belt and braces: `child` already refuses traversal, and this says so a second time
        // in the terms the boundary is actually stated in.
        return at.takeIf { it != folder && folder.contains(it) }
    }

    /** What happens to a navigation of the whole viewer. */
    enum class Nav {
        /** Another file in this folder, or a blank frame: the viewer loads it. */
        LOAD,

        /** A website, a mail address or a phone number: offered to the system, never loaded here. */
        HAND_OFF,

        /** Anything else - `file:`, `content:`, `intent:`, `javascript:`, another app's scheme. */
        REFUSE,
    }

    fun navigation(folder: VPath, url: String): Nav {
        if (isOurs(folder, url)) return Nav.LOAD
        // Another folder's origin. Never this page's to load, and meaningless to a browser.
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
        if (host == DOMAIN || host.endsWith(".$DOMAIN")) return Nav.REFUSE
        return when (url.substringBefore(':', "").lowercase()) {
            "about" -> Nav.LOAD
            "http", "https", "mailto", "tel" -> Nav.HAND_OFF
            else -> Nav.REFUSE
        }
    }

    /**
     * Requests the WebView answers itself without ever touching the network: inline `data:`,
     * in-memory `blob:` and `about:blank`. Refusing these would break pages for no gain.
     */
    fun isSelfContained(url: String): Boolean =
        url.substringBefore(':', "").lowercase() in setOf("data", "blob", "about")

    /**
     * The type a file is served as.
     *
     * The file type table rather than the platform's `MimeTypeMap`, for two reasons: that map
     * has no entry for `.mjs` on several Android versions, and a module script served with the
     * wrong type is refused outright by the browser's strict MIME check - the page loads and
     * none of its script runs, which is exactly the fault this viewer is meant not to have.
     */
    fun mimeOf(name: String): String =
        FileTypes.mime(FileTypes.extensionOf(name)) ?: "application/octet-stream"

    /** Text types are declared UTF-8, which is what the viewer has always read pages as. */
    fun isText(mime: String): Boolean =
        mime.startsWith("text/") || mime.endsWith("+xml") || mime == "application/json" ||
            mime == "application/xml"

    /** Pages larger than this are served as they are rather than held whole to be rewritten. */
    const val REWRITE_CAP = 4 * 1024 * 1024

    /** What a page is given when it declares no viewport. */
    const val READER_VIEWPORT = "width=device-width"

    /**
     * The page's viewport, decided in the reader's favour.
     *
     * - **`user-scalable=no` and `maximum-scale` are removed.** A page locking its zoom is its
     *   author deciding for the reader; in a file manager's viewer the reader wins. Chromium has
     *   no setting to override the meta, so the meta itself is changed - the only lever there is.
     * - **A page with no viewport is given `width=device-width`.** Without one, WebView lays the
     *   page out 980px wide and shrinks it, so the text of a plain local document - the common
     *   case here - is unreadable until pinched, and pinching only magnifies, it never rewraps.
     *   With it, the text wraps to the phone's width. `initial-scale` is deliberately not set, so
     *   Chromium can still shrink an overflowing fixed-width layout to fit.
     * - **A page that declares a fixed width keeps it.** It cannot reflow - the author fixed the
     *   width - so it is magnified and panned, with text autosizing enlarging its body text.
     *
     * Decision: both reflow and magnify, by page. Pinch is ALWAYS magnify-and-pan in Chromium;
     * reflow comes from the layout width, which is what this sets. Known gap: a script that
     * rewrites the viewport after load can lock zoom again.
     */
    fun forReader(html: String): String {
        val meta = VIEWPORT_META.find(html)
        if (meta != null) {
            val tag = meta.value
            val content = CONTENT_ATTR.find(tag) ?: return html
            val raw = content.groupValues[2].ifEmpty { content.groupValues[3] }
            val kept = raw.split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot {
                    val k = it.substringBefore('=').trim().lowercase()
                    k == "user-scalable" || k == "maximum-scale"
                }
            val fixed = (kept + "user-scalable=yes").joinToString(", ")
            val newTag = tag.replaceRange(content.range, "content=\"$fixed\"")
            return html.replaceRange(meta.range, newTag)
        }
        val inject = "<meta name=\"viewport\" content=\"$READER_VIEWPORT\">"
        HEAD_OPEN.find(html)?.let { return html.substring(0, it.range.last + 1) + inject + html.substring(it.range.last + 1) }
        // Never before the doctype: anything ahead of it drops the page into quirks mode.
        DOCTYPE.find(html)?.let { return html.substring(0, it.range.last + 1) + inject + html.substring(it.range.last + 1) }
        return inject + html
    }

    private val VIEWPORT_META = Regex("""<meta\b[^>]*\bname\s*=\s*["']?viewport["']?[^>]*>""", RegexOption.IGNORE_CASE)
    private val CONTENT_ATTR = Regex("""\bcontent\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val HEAD_OPEN = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val DOCTYPE = Regex("""^\s*<!doctype[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * Sent with every served file.
     *
     * `'self'` everywhere, plus inline and eval because ordinary pages use both and this
     * policy is not about the page's own code - it is about where things may go. `connect-src`
     * is the line that closes WebSocket, which request interception cannot see, and
     * `form-action` stops a form posting to a site.
     */
    const val POLICY =
        "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; " +
            "connect-src 'self' data: blob:; form-action 'self'; base-uri 'self'"

    /** Percent-encode one path segment: unreserved characters stay, everything else is UTF-8 bytes. */
    fun encodeSegment(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~") append(ch)
            else append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
        }
    }

    /** Decode one path segment, or null when it is malformed. `+` is a plus: this is a path. */
    fun decode(s: String): String? {
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write(hi * 16 + lo)
                i += 3
            } else {
                out.write(ch.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private const val HEX = "0123456789ABCDEF"

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * HTML, rendered with its scripts running, and its source one tap away.
 *
 * READ THIS BEFORE LOOSENING ANYTHING IN THIS FILE. It is the contract for what a local page
 * is allowed to do, and every line of it is load-bearing.
 *
 * ## What a page opened here CAN do
 *
 * - Run its own JavaScript, inline or from files beside it. DOM storage works, so a page that
 *   keeps state in `localStorage` keeps it.
 * - Load anything inside its own folder or a folder below it: stylesheets, scripts (classic and
 *   module), images, fonts, JSON, media, and other pages it links to. The page and its assets
 *   are served by the app from a made-up `https` origin, one per folder - see [HtmlOrigin].
 * - Read those same files with `fetch` or `XMLHttpRequest`, because to a page "a file beside
 *   me" and "my own asset" are the same request and cannot be told apart.
 * - Show `alert`, `confirm` and `prompt`.
 * - Ask to open a link to a website. A tapped link to `http`, `https`, `mailto` or `tel` raises
 *   a dialogue showing the full address; only a "yes" hands it to the system browser.
 *
 * ## What it CANNOT do
 *
 * - **Reach the network.** Every request that is not for its own origin is answered by the app
 *   with a 403 before it leaves the device, and a Content-Security-Policy on every served file
 *   refuses WebSocket and form posts to anywhere else. This is the boundary that makes the
 *   folder read above safe: a page can read the files near it, and has nowhere to send them.
 * - **Read anything outside its folder.** A request is resolved segment by segment beneath the
 *   document's folder and refused if any segment is `.`, `..`, or contains a separator after
 *   decoding. The folder above, sibling folders, and the rest of storage are not addressable.
 * - **Use `file://` or `content://` at all.** File and content access are off, and so are both
 *   "access from file URLs" flags. Those two flags are the setting that turns "this page's
 *   scripts run" into "this page can read every file the app can reach"; nothing here needs
 *   them, because nothing is loaded from a `file://` URL.
 * - **Navigate the viewer away.** A link to a website is never loaded in the viewer. One that a
 *   script follows without a tap is refused silently, so a page cannot spam the dialogue or
 *   smuggle its contents out in an address the reader never chose to open.
 * - **Talk to the app.** There is no `addJavascriptInterface` and there must never be one: an
 *   injected bridge is a way for any HTML file on the phone to call into this process.
 * - Ask for location, the camera or microphone (refused), open a file chooser (refused), or
 *   open a new window (multiple windows off).
 *
 * ## What was considered and not done
 *
 * - **Letting a page load from a CDN.** Many saved pages pull a library from the web. Allowing
 *   it means allowing every outbound request a page makes, and that undoes the boundary above:
 *   a page that can read its folder and also reach the network can upload the folder. A page
 *   that needs a library from the web shows without it - which is the price, and it is stated
 *   in the bar as "offline".
 * - **`androidx.webkit.WebViewAssetLoader`.** It does the same job as [PageClient] for the
 *   app's own assets and files directory, and neither is where a user's HTML lives; its
 *   path handler would have to be written against the VFS anyway. The interception here is a
 *   page of code with no dependency, and it reads through the VFS - so a page inside an archive
 *   or on a share loads its assets from inside that archive or share like any other.
 *
 * ## Known gaps, stated so they are not rediscovered as surprises
 *
 * - WebRTC is not covered by request interception or by CSP; a page could open a peer
 *   connection. WebView offers no switch for it per view.
 * - A service worker's script is fetched outside this client. Its host is under `.invalid`,
 *   which no resolver answers, so registration fails rather than escapes - a fact about DNS,
 *   not a setting.
 * - Media is served whole, without range requests, so seeking in a long video may restart it.
 *
 * ## Why the editor is the same editor
 *
 * The Source toggle shows the text read-only; editing goes to `TextEditorScreen` through the
 * handler registry, which is the editor the whole app uses. A format gets a pipeline into it,
 * never a copy of it. The other way round, the editor's Render shows its unsaved text through
 * [HtmlPage], this screen's own page, so a preview is what opening the file shows: scripts,
 * sandbox and all.
 */
@Composable
fun HtmlScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    var source by remember(node.path) { mutableStateOf<String?>(null) }
    var error by remember(node.path) { mutableStateOf<String?>(null) }
    var showSource by remember(node.path) { mutableStateOf(false) }

    // The source is only read when it is asked for: the preview no longer needs the text,
    // since the page is served by request like any of its assets.
    LaunchedEffect(node.path, showSource) {
        if (!showSource || source != null) return@LaunchedEffect
        runCatching { vm.readText(node) }
            .onSuccess { source = it }
            .onFailure { error = it.message ?: "This file could not be read." }
    }

    Column(Modifier.fillMaxSize()) {
        // Bug identified here: the page had a strip of its own instead of `ViewerBar` - no name,
        // no Close, and drawn under the clock now that viewers sit behind the system bars. The
        // strip's two facts stay, as the bar's actions.
        dev.niccc2007.filet.handlers.ViewerBar(
            title = node.name,
            subtitle = node.path.parent?.path.orEmpty(),
            onClose = { vm.closeHandler() },
        ) {
            // Scripts run; the web does not. This is the one fact about the preview that
            // explains a page that looks incomplete, so it is on screen rather than in a menu.
            Text(
                "offline",
                fontSize = 9.5.sp,
                color = colors.fg3,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.sunken)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(4.dp))
            // A mode, drawn lit while the source is showing.
            dev.niccc2007.filet.handlers.ViewerAction(
                dev.niccc2007.filet.browser.FiletIcons.Code,
                if (showSource) "Show the page" else "Show the source",
                active = showSource,
            ) { showSource = !showSource }
        }
        val html = source
        // The page stays alive under the source rather than being torn down for it. Bug
        // identified in the first version: toggling Source destroyed the WebView, so coming back
        // reloaded the page and lost its zoom, its scroll position and whatever its scripts had
        // built. INVISIBLE rather than removed keeps all three and takes no touches.
        Box(Modifier.fillMaxSize()) {
            HtmlPage(vm.vfs, node.path, Modifier.fillMaxSize(), hidden = showSource)
            if (showSource) {
                Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                    when {
                        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(error.orEmpty(), fontSize = 12.sp, color = colors.warn)
                        }

                        html == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        }

                        else -> DocReader(Doc(listOf(Block.Code(html, "html"))))
                    }
                }
            }
        }
    }

}

/** The extensions [HtmlScreen] renders, which are the ones the editor offers a page for. */
val HTML_EXTENSIONS: Set<String> = setOf("html", "htm", "xhtml")

/**
 * One local page in its sandbox: the WebView, the folder it is served from, and the question a
 * link away from it asks. Everything in the note on [HtmlScreen] holds for it - it IS that
 * screen's page, shared with the editor's Render so the two cannot drift apart.
 *
 * The WebView goes when this leaves the composition: stopped and destroyed, so a page's timers
 * and media do not keep running behind whatever replaced it.
 *
 * @param buffer the text to show for [file] in place of what is stored - the editor's unsaved
 *   edit. Only the request for [file] itself is answered from it; every other request is the
 *   folder's, as ever. Null serves the file as stored.
 * @param hidden alive but not shown and not touched, for the viewer's Source toggle.
 */
@Composable
fun HtmlPage(vfs: Vfs, file: VPath, modifier: Modifier = Modifier, buffer: String? = null, hidden: Boolean = false) {
    val context = LocalContext.current
    // The outside address a tapped link asked to open, waiting on the reader's answer.
    val leaving = remember(file) { mutableStateOf<String?>(null) }
    val folder = remember(file) { file.parent ?: file }
    // Keyed on the file: a different file is a new WebView, which is what resets the zoom.
    // Without the key the factory ran once per slot and a second file opened in the same place
    // would have kept showing the first. And on the buffer: a new edit is a new page.
    key(file, buffer) {
        AndroidView(
            modifier = modifier,
            // Loaded once, here. Bug identified in the version this replaces: the load was in
            // `update`, which runs on every recomposition, so any recomposition of the screen
            // reloaded the page and threw away its script state.
            factory = { ctx ->
                pageView(ctx, vfs, folder, leaving, file, buffer).also { it.loadUrl(HtmlOrigin.entryUrl(file)) }
            },
            update = { view -> view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE },
            onRelease = { view ->
                view.stopLoading()
                view.destroy()
            },
        )
    }

    leaving.value?.let { url ->
        Dlg(onDismiss = { leaving.value = null }) {
            DlgHeader(
                FiletIcons.Link,
                "Open outside Filet?",
                sub = "This page links away from the file.",
                onClose = { leaving.value = null },
            )
            DlgBody {
                DlgKv("Address", url, mono = true)
                DlgCaption("It opens in your browser, not here. The whole address is shown above, because anything a page wants to send goes in it.")
            }
            DlgFooter {
                DlgSpacer()
                DlgBtn("Cancel") { leaving.value = null }
                DlgBtn("Open", kind = BtnKind.PRIMARY) {
                    leaving.value = null
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The WebView, configured. Every setting that matters is stated even where it is the default,
 * because a default can change between WebView releases and this list is the contract.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun pageView(
    context: Context,
    vfs: Vfs,
    folder: VPath,
    leaving: MutableState<String?>,
    entry: VPath,
    buffer: String?,
): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.setGeolocationEnabled(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // A page is read fresh every time: one edited in the text editor and reopened must
        // show the edit, not a cached copy of the last visit.
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        // Pinch and double-tap zoom. The +/- buttons are off: they float over the content and
        // duplicate a gesture every phone already has.
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // Wide viewport ON, because it is what makes WebView honour a page's viewport meta at
        // all; off, every page is laid out at the view's width whatever it declares, and a page
        // built for a fixed 900px layout is crushed into a phone column. Overview mode then fits
        // a page wider than the screen to the screen's width on open.
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        // Text autosizing enlarges body text in a layout wider than the screen, which is the
        // one case where reflow is impossible - the author fixed the width. Pages that declare
        // device width, or that `HtmlOrigin.forReader` gives it to, reflow and need no help.
        settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
        webViewClient = PageClient(vfs, folder, leaving, entry, buffer)
        webChromeClient = PageChrome()
    }

/**
 * Serves the page's folder, refuses everything else, and decides what a navigation may do.
 *
 * [buffer], when there is one, is served for [entry] in place of the stored file - see
 * [HtmlPage]. It changes what one file reads as, never which files can be read.
 */
private class PageClient(
    private val vfs: Vfs,
    private val folder: VPath,
    private val leaving: MutableState<String?>,
    private val entry: VPath,
    private val buffer: String?,
) : WebViewClient() {

    /**
     * Called on a WebView background thread for every request the page makes, so blocking on
     * the VFS here is blocking that thread, not the UI. The stream returned is read and closed
     * by the WebView.
     */
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return refuse(403, "Forbidden")
        if (HtmlOrigin.isSelfContained(url)) return null
        if (!HtmlOrigin.isOurs(folder, url)) return refuse(403, "Forbidden")
        val path = HtmlOrigin.resolve(folder, url) ?: return refuse(404, "Not Found")
        val stream = if (buffer != null && path == entry) {
            ByteArrayInputStream(buffer.toByteArray(Charsets.UTF_8))
        } else {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    val node = vfs.stat(path)
                    if (node == null || node.isDir) null else vfs.openRead(path)
                }
            }.getOrNull() ?: return refuse(404, "Not Found")
        }
        val mime = HtmlOrigin.mimeOf(path.name)
        return WebResourceResponse(
            mime,
            if (HtmlOrigin.isText(mime)) "utf-8" else null,
            200,
            "OK",
            headers(),
            if (mime == "text/html") readerCopy(stream) else stream,
        )
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val req = request ?: return true
        val url = req.url.toString()
        return when (HtmlOrigin.navigation(folder, url)) {
            HtmlOrigin.Nav.LOAD -> false
            HtmlOrigin.Nav.HAND_OFF -> {
                // Only a tap asks. A script-driven redirect is refused without a word.
                if (req.isForMainFrame && req.hasGesture()) leaving.value = url
                true
            }
            HtmlOrigin.Nav.REFUSE -> true
        }
    }

    /**
     * The page with its viewport handed to the reader - see [HtmlOrigin.forReader]. A page past
     * [HtmlOrigin.REWRITE_CAP] is served untouched: rewriting means holding it whole, and a
     * multi-megabyte HTML file is a data dump, not a layout anyone pinches.
     */
    private fun readerCopy(stream: InputStream): InputStream {
        val head = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (head.size() <= HtmlOrigin.REWRITE_CAP) {
            val n = stream.read(buf)
            if (n < 0) break
            head.write(buf, 0, n)
        }
        // Too big: what was read goes first, the rest streams on, and the WebView closes both.
        if (head.size() > HtmlOrigin.REWRITE_CAP) {
            return java.io.SequenceInputStream(ByteArrayInputStream(head.toByteArray()), stream)
        }
        stream.close()
        val page = HtmlOrigin.forReader(String(head.toByteArray(), Charsets.UTF_8))
        return ByteArrayInputStream(page.toByteArray(Charsets.UTF_8))
    }

    private fun headers(): Map<String, String> = mapOf(
        "Content-Security-Policy" to HtmlOrigin.POLICY,
        "Cache-Control" to "no-store",
        // Prefetching a host name is a DNS lookup the page can steer, and a lookup is a message.
        "X-DNS-Prefetch-Control" to "off",
    )

    private fun refuse(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", code, reason, headers(), empty())

    private fun empty(): InputStream = ByteArrayInputStream(ByteArray(0))
}

/** Every permission a page can ask for, refused. */
private class PageChrome : WebChromeClient() {
    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        callback?.invoke(origin, false, false)
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.deny()
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        filePathCallback?.onReceiveValue(null)
        return true
    }
}

package dev.niccc2007.filet.handlers

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.docs.MarkdownPageActions
import dev.niccc2007.filet.docs.ReadingSpot
import dev.niccc2007.filet.docs.ReadingSpots
import dev.niccc2007.filet.docs.SaveSpotOnLeave
import dev.niccc2007.filet.docs.SpotCard
import dev.niccc2007.filet.docs.SpotKind
import dev.niccc2007.filet.docs.rememberSavedSpot
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Above this, a text editor is the wrong tool and the hex viewer is the right one. */
private const val MAX_EDIT_BYTES = 12L * 1024 * 1024

/**
 * The code editor, on sora-editor (LGPL-2.1, linked as an unmodified binary dependency).
 *
 * sora is here for the parts that are genuinely hard: virtualised rendering of a 40,000-line
 * file, selection handles, undo history, incremental search. The syntax colouring is Filet's
 * own ([FiletLanguage]) because the alternative is shipping and maintaining a TextMate
 * grammar per file type to produce four colours.
 *
 * ## Markdown and HTML: one switch between the page and its source
 *
 * A Markdown file has a rendered form, and the renderer existed - the document reader's - with
 * nothing in the editor reaching it. So editing a README was writing
 * blind: save, close, open it in the reader, look, come back, and every one of those trips
 * cost the undo history. Now one button flips between them, in this screen.
 *
 * Rendered from the editor's BUFFER, never from the file on disk - the point is to see the
 * edit before it is saved. And the editor stays composed underneath the page, hidden, so
 * flipping back finds the same editor: unsaved text, caret and undo history all intact.
 * Rebuilding it from a string on every flip would keep the words and lose everything else.
 *
 * HTML gets the same switch, and its page is the HTML viewer's own (`HtmlPage`): scripts run,
 * nothing leaves the phone, and the page reads only its own folder. The buffer is served in
 * place of the file and every asset beside it comes from the folder as usual. The page is torn
 * down on the way back to the source, so its timers do not run on behind the editor.
 *
 * @param startRendered open on the page rather than the source - how the document reader hands
 *   a Markdown file over, so there is one screen with one switch rather than a reader and an
 *   editor each pointing at the other.
 */
@Composable
fun TextEditorScreen(vm: BrowserViewModel, node: VNode, startRendered: Boolean = false) {
    val colors = Filet.colors
    val scheme = MaterialTheme.colorScheme
    var loading by remember(node.path) { mutableStateOf(true) }
    var error by remember(node.path) { mutableStateOf<String?>(null) }
    var text by remember(node.path) { mutableStateOf("") }
    var dirty by remember(node.path) { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editor by remember(node.path) { mutableStateOf<CodeEditor?>(null) }

    val markdown = remember(node.path) {
        dev.niccc2007.filet.docs.DocFormat.of(node.extension) == dev.niccc2007.filet.docs.DocFormat.MARKDOWN
    }
    val webPage = remember(node.path) { node.extension in dev.niccc2007.filet.docs.HTML_EXTENSIONS }
    // The kinds with a page to show.
    val renders = markdown || webPage
    var rendering by remember(node.path) { mutableStateOf(startRendered && renders) }
    // The text the page is drawn from: the buffer as it was at the flip.
    var renderSource by remember(node.path) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val page = dev.niccc2007.filet.docs.rememberDocPageState(node.path)
    val scope = rememberCoroutineScope()
    // A link on the page to another file opens it over this one, as a tap on it in a folder
    // would, and Back returns here; a link to a folder goes to the folder, which means leaving
    // the viewers for the browser. See BrowserViewModel.followLink.
    val place = remember(node.path) {
        dev.niccc2007.filet.docs.DocPlace(
            file = node.path,
            open = { path -> vm.followLink(path) },
            say = { vm.toast(it) },
            stamp = node.mtime,
        )
    }

    // The source's own reading spot, a line. Offered the first time the source is on screen -
    // a Markdown file handed over from the reader opens on its page, which has its own - and
    // only that once, however often the switch is flipped.
    val where = remember(node.path) { EditorWhere() }
    val savedLine = rememberSavedSpot(node.path.toString(), SpotKind.LINE)
    var lineCard by remember(node.path) { mutableStateOf<ReadingSpot?>(null) }
    var lineOffered by remember(node.path) { mutableStateOf(false) }
    LaunchedEffect(savedLine, rendering, loading) {
        if (savedLine != null && !rendering && !loading && !lineOffered) {
            lineOffered = true
            lineCard = savedLine
        }
    }
    SaveSpotOnLeave(node.path) {
        if (loading || error != null) return@SaveSpotOnLeave
        val path = node.path.toString()
        ReadingSpots.of(context).left(path, SpotKind.LINE, top = where.first <= 0, end = where.last >= where.count - 1) {
            ReadingSpot(path, SpotKind.LINE, item = where.first, label = "line ${where.first + 1}", stamp = node.mtime)
        }
    }

    /** Page to source or back. The keyboard goes with the source: there is nothing to type into. */
    fun flip() {
        if (rendering) {
            rendering = false
            return
        }
        val ed = editor
        renderSource = ed?.text?.toString() ?: text
        if (ed != null) {
            ed.clearFocus()
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(ed.windowToken, 0)
        }
        findOpen = false
        rendering = true
    }

    LaunchedEffect(node.path) {
        loading = true
        val result = runCatching {
            if (node.size > MAX_EDIT_BYTES) throw IllegalStateException("too big")
            withContext(Dispatchers.IO) {
                vm.readText(node)
            }
        }
        result.onSuccess {
            text = it
            loading = false
            if (rendering) renderSource = it
        }
            .onFailure {
                error = if (node.size > MAX_EDIT_BYTES)
                    "This file is ${humanSize(node.size)} — too large to edit safely. Open it in the hex viewer instead."
                else it.message ?: "Could not read this file."
                loading = false
            }
    }

    Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = node.name,
            subtitle = buildString {
                append(node.path.parent?.path ?: "")
                if (dirty) append("  ·  unsaved")
                // The one fact that explains a page that looks incomplete - see `HtmlScreen`.
                if (rendering && webPage) append("  ·  offline")
            },
            onClose = { vm.closeHandler() },
        ) {
            // The one switch, named for where it goes. Only for Markdown and HTML: nothing else
            // in the editor has a page to show.
            if (renders) {
                ViewerAction(
                    if (rendering) FiletIcons.Rename else FiletIcons.Eye,
                    if (rendering) "Edit" else "Render",
                    enabled = !loading && error == null,
                ) { flip() }
            }
            // The tools that act on the text hide while the page is up: they would act on an
            // editor nobody can see, and the page's own controls take their place. Save stays -
            // an edit made a moment ago is still unsaved.
            if (rendering) {
                if (markdown) MarkdownPageActions(page)
            } else {
                ViewerAction(FiletIcons.Search, "Find", enabled = !loading) { findOpen = !findOpen }
                ViewerAction(FiletIcons.Rows, if (wrap) "No wrap" else "Wrap", enabled = !loading) {
                    wrap = !wrap
                    editor?.setWordwrap(wrap)
                }
                ViewerAction(FiletIcons.Back, "Undo", enabled = editor?.canUndo() == true) { editor?.undo() }
                ViewerAction(FiletIcons.Refresh, "Redo", enabled = editor?.canRedo() == true) { editor?.redo() }
            }
            ViewerAction(FiletIcons.Check, "Save", enabled = dirty && !loading) {
                editor?.let { ed -> vm.saveText(node, ed.text.toString()) { dirty = false } }
            }
        }

        if (findOpen && !loading && !rendering) {
            Row(
                Modifier.fillMaxWidth().background(colors.high).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        val ed = editor
                        if (ed != null) {
                            if (it.isEmpty()) ed.searcher.stopSearch()
                            else ed.searcher.search(it, EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_NORMAL, true))
                        }
                    },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = scheme.onSurface, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.weight(1f),
                )
                ViewerAction(FiletIcons.Up, "Previous") { editor?.searcher?.gotoPrevious() }
                ViewerAction(FiletIcons.Download, "Next") { editor?.searcher?.gotoNext() }
                ViewerAction(FiletIcons.Close, "Close find") { findOpen = false; query = ""; editor?.searcher?.stopSearch() }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
            }
            error != null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text(error!!, fontSize = 13.sp, color = colors.fg2)
            }
            else -> Box(Modifier.fillMaxSize()) {
                AndroidView(
                    // Two fixes, both about where the text can actually be seen.
                    //
                    // `imePadding` is the one that matters most: the editor filled the window, so the
                    // keyboard covered its bottom third and a caret down there was behind it. Nothing
                    // scrolled it into view because as far as the editor was concerned it WAS in
                    // view - the window had not changed size. Inset by the keyboard, the editor is
                    // the part of the screen that is actually visible, and its own scrolling does the
                    // rest.
                    //
                    // The end padding is for wrapped lines clipping at the right edge. A glyph whose
                    // ink is wider than the advance width it was measured by paints outside the box
                    // the wrap was computed for - the same fault as an italic terminal overhanging its
                    // own box. A few pixels of reserve costs nothing and is simpler than fighting the
                    // measurement.
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .padding(end = 4.dp)
                        // Hidden, not removed, while the page is up - see the class note.
                        .graphicsLayer { alpha = if (rendering) 0f else 1f },
                    factory = { ctx ->
                        CodeEditor(ctx).apply {
                            setEditorLanguage(FiletLanguage(Syntax.forExtension(node.extension)))
                            colorScheme = filetScheme(
                                bg = scheme.background.toArgb(),
                                fg = scheme.onSurface.toArgb(),
                                accent = colors.accent.toArgb(),
                                comment = colors.fg3.toArgb(),
                                literal = colors.good.toArgb(),
                                annotation = colors.warn.toArgb(),
                                lineNumber = colors.fg3.toArgb(),
                                selection = colors.sel.toArgb(),
                                currentLine = colors.hover.toArgb(),
                            )
                            setTypefaceText(Typeface.MONOSPACE)
                            setTextSize(13f)
                            setLineNumberEnabled(true)
                            setTabWidth(4)
                            setWordwrap(wrap)
                            // Scroll past the last line, always, with a ceiling.
                            //
                            // Without it the content ends at the last line, so a caret on that line
                            // cannot be scrolled any higher - which is exactly the case where the
                            // keyboard is in the way and scrolling is what you want. A factor rather
                            // than a fixed number of lines, so it is the same gesture on any screen,
                            // and well under 1 so the file cannot be scrolled off into nothing.
                            setVerticalExtraSpaceFactor(EXTRA_SCROLL)
                            setText(text)
                            // The dirty flag drives the save button, so it must come from the
                            // editor's own content events, not from a guess about focus.
                            subscribeAlways(io.github.rosemoe.sora.event.ContentChangeEvent::class.java) {
                                dirty = true
                            }
                            // Kept as it moves, so the spot can be saved after the editor is gone.
                            // From where the scroll ENDS: a fling announces itself once, at its
                            // start, with its end in the event, and the screen read at that moment
                            // is where the finger let go rather than where the text came to rest.
                            subscribeAlways(io.github.rosemoe.sora.event.ScrollEvent::class.java) { e ->
                                val row = rowHeight.coerceAtLeast(1)
                                val last = (layout.rowCount - 1).coerceAtLeast(0)
                                fun lineAt(y: Int) = runCatching { layout.getLineNumberForRow((y / row).coerceIn(0, last)) }.getOrDefault(0)
                                where.first = lineAt(e.endY)
                                where.last = lineAt(e.endY + height)
                                where.count = lineCount
                            }
                            editor = this
                        }
                    },
                    onRelease = { it.release(); editor = null },
                )
                // Under the page when it is up, which covers it: the card is the source's.
                SpotCard(lineCard, Modifier.align(Alignment.TopEnd)) { s ->
                    editor?.let { ed -> ed.scrollToLine(ReadingSpots.goal(s, node.mtime, ed.lineCount).item) }
                }
                if (rendering) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(scheme.background)
                            // Takes the page's touches, so a tap on its empty space cannot reach
                            // the hidden editor beneath and bring the keyboard up over a page.
                            .pointerInput(Unit) {
                                awaitPointerEventScope { while (true) awaitPointerEvent() }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        renderSource?.let {
                            if (markdown) {
                                dev.niccc2007.filet.docs.MarkdownPage(it, key = node.path, place = place, page = page)
                            } else {
                                dev.niccc2007.filet.docs.HtmlPage(vm.vfs, node.path, Modifier.fillMaxSize(), buffer = it)
                            }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(node.path) {
        onDispose { editor?.let { runCatching { it.release() } }; editor = null }
    }
}

/** Where the editor's screen is, in lines. Written as it scrolls; read when the screen is left. */
private class EditorWhere {
    var first = 0
    var last = 0
    var count = 0
}

/**
 * Scroll so [line] is the first on screen.
 *
 * sora reports a character's offset at the BOTTOM of its row, so a row's top is one row up. The
 * first row of the line, so a wrapped line starts at its start.
 */
private fun CodeEditor.scrollToLine(line: Int) {
    val l = line.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
    val y = (layout.getCharLayoutOffset(l, 0)[0] - rowHeight).toInt().coerceIn(0, scrollMaxY.coerceAtLeast(0))
    scroller.forceFinished(true)
    scroller.startScroll(offsetX, offsetY, 0, y - offsetY)
    invalidate()
}

/**
 * Map Filet's palette onto sora's colour ids.
 *
 * Explicit rather than one of sora's bundled schemes: an editor that does not match the app
 * it is embedded in looks like a different program, and the whole point of the palette
 * setting is that the user picked it.
 */
private fun filetScheme(
    bg: Int, fg: Int, accent: Int, comment: Int, literal: Int,
    annotation: Int, lineNumber: Int, selection: Int, currentLine: Int,
): EditorColorScheme = EditorColorScheme().apply {
    setColor(EditorColorScheme.WHOLE_BACKGROUND, bg)
    setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, bg)
    setColor(EditorColorScheme.TEXT_NORMAL, fg)
    setColor(EditorColorScheme.KEYWORD, accent)
    setColor(EditorColorScheme.COMMENT, comment)
    setColor(EditorColorScheme.LITERAL, literal)
    setColor(EditorColorScheme.OPERATOR, fg)
    setColor(EditorColorScheme.ANNOTATION, annotation)
    setColor(EditorColorScheme.HTML_TAG, accent)
    setColor(EditorColorScheme.ATTRIBUTE_NAME, annotation)
    setColor(EditorColorScheme.ATTRIBUTE_VALUE, literal)
    setColor(EditorColorScheme.IDENTIFIER_NAME, fg)
    setColor(EditorColorScheme.IDENTIFIER_VAR, fg)
    setColor(EditorColorScheme.FUNCTION_NAME, fg)
    setColor(EditorColorScheme.LINE_NUMBER, lineNumber)
    setColor(EditorColorScheme.LINE_NUMBER_CURRENT, accent)
    setColor(EditorColorScheme.LINE_DIVIDER, lineNumber)
    setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, selection)
    setColor(EditorColorScheme.CURRENT_LINE, currentLine)
    setColor(EditorColorScheme.SELECTION_INSERT, accent)
    setColor(EditorColorScheme.SELECTION_HANDLE, accent)
    setColor(EditorColorScheme.BLOCK_LINE, lineNumber)
    setColor(EditorColorScheme.BLOCK_LINE_CURRENT, accent)
    setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, selection)
    setColor(EditorColorScheme.SCROLL_BAR_THUMB, lineNumber)
    setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, accent)
}

/**
 * How much empty space is scrollable past the last line, as a fraction of the viewport.
 *
 * Enough to lift the final line clear of a keyboard, which is the whole reason it exists. Kept
 * well below 1 so a short file cannot be scrolled until it is off the screen entirely - scroll
 * past the end is a convenience, and an unbounded one is its own annoyance.
 */
private const val EXTRA_SCROLL = 0.6f

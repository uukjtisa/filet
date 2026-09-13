package dev.niccc2007.filet.handlers

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
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
 */
@Composable
fun TextEditorScreen(vm: BrowserViewModel, node: VNode) {
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

    LaunchedEffect(node.path) {
        loading = true
        val result = runCatching {
            if (node.size > MAX_EDIT_BYTES) throw IllegalStateException("too big")
            withContext(Dispatchers.IO) {
                vm.readText(node)
            }
        }
        result.onSuccess { text = it; loading = false }
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
            },
            onClose = { vm.closeHandler() },
        ) {
            ViewerAction(FiletIcons.Search, "Find", enabled = !loading) { findOpen = !findOpen }
            ViewerAction(FiletIcons.Rows, if (wrap) "No wrap" else "Wrap", enabled = !loading) {
                wrap = !wrap
                editor?.setWordwrap(wrap)
            }
            ViewerAction(FiletIcons.Back, "Undo", enabled = editor?.canUndo() == true) { editor?.undo() }
            ViewerAction(FiletIcons.Refresh, "Redo", enabled = editor?.canRedo() == true) { editor?.redo() }
            ViewerAction(FiletIcons.Check, "Save", enabled = dirty && !loading) {
                editor?.let { ed -> vm.saveText(node, ed.text.toString()) { dirty = false } }
            }
        }

        if (findOpen && !loading) {
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
            else -> AndroidView(
                modifier = Modifier.fillMaxSize(),
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
                        setText(text)
                        // The dirty flag drives the save button, so it must come from the
                        // editor's own content events, not from a guess about focus.
                        subscribeAlways(io.github.rosemoe.sora.event.ContentChangeEvent::class.java) {
                            dirty = true
                        }
                        editor = this
                    }
                },
                onRelease = { it.release(); editor = null },
            )
        }
    }

    DisposableEffect(node.path) {
        onDispose { editor?.let { runCatching { it.release() } }; editor = null }
    }
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

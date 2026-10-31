package dev.niccc2007.filet.docs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.handlers.HandlerId
import dev.niccc2007.filet.handlers.ViewerAction
import dev.niccc2007.filet.handlers.ViewerBar
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
    // A sheet is not a document. It scrolls in two directions, it keeps its headers pinned, and
    // a cell has a value, a display form and possibly a formula - none of which a `Doc` block
    // can carry. So it gets its own reader and its own screen, routed here because this is the
    // one entry point the handler registry knows about.
    if (DocFormat.of(node.extension) == DocFormat.SHEET) {
        SheetScreen(vm, node)
        return
    }
    // Markdown is read in the code editor, on its page, with one switch to the source - see
    // `TextEditorScreen`. This reader used to draw it and offer an Edit button to the editor,
    // which offered nothing back: two screens pointing one way, and the page always the file on
    // disk rather than the edit being made.
    if (DocFormat.of(node.extension) == DocFormat.MARKDOWN) {
        dev.niccc2007.filet.handlers.TextEditorScreen(vm, node, startRendered = true)
        return
    }
    val colors = Filet.colors
    val kind = DocFormat.of(node.extension)
    var doc by remember(node.path) { mutableStateOf<Doc?>(null) }
    var error by remember(node.path) { mutableStateOf<String?>(null) }
    var editing by remember(node.path) { mutableStateOf(false) }
    // Bumped when the text editor closes, so the reader shows what was saved.
    var generation by remember(node.path) { mutableStateOf(0) }

    LaunchedEffect(node.path, generation) {
        val read = runCatching {
            withContext(Dispatchers.IO) {
                when (kind) {
                    DocFormat.MARKDOWN -> MarkdownReader.read(vm.readText(node))
                    DocFormat.WORD -> OfficeReader.read({ vm.openRead(node) }, presentation = false)
                    DocFormat.SLIDES -> OfficeReader.read({ vm.openRead(node) }, presentation = true)
                    // A plain text file reached here by somebody choosing this reader for it.
                    // Shown as one code block, which is honest - it has no structure to find.
                    DocFormat.NONE -> Doc(listOf(Block.Code(vm.readText(node))))
                    // Unreachable: the early return above sends sheets to `SheetScreen`. Left
                    // as a throw rather than a silent empty document so that if the guard is
                    // ever moved, this fails loudly instead of showing a blank page.
                    DocFormat.SHEET -> error("A sheet is read by SheetReader, not here.")
                }
            }
        }
        read.onSuccess { doc = it; error = null }
        read.onFailure { error = it.message ?: "This file could not be read." }
    }

    if (editing) {
        OfficeEditScreen(vm, node, presentation = kind == DocFormat.SLIDES) {
            editing = false
            generation++
        }
        return
    }

    val writable = vm.canWriteHere(node.path.parent)
    val page = rememberDocPageState(node.path)
    // A Word link is a web address, almost always; one to a file next to it opens it here.
    val place = remember(node.path) {
        // A link opens its file over this one and Back returns here. It used to show the file
        // in its folder, which left the document for the browser to do it.
        DocPlace(file = node.path, open = { vm.followLink(it) }, say = { vm.toast(it) }, stamp = node.mtime)
    }
    Column(Modifier.fillMaxSize()) {
        ViewerBar(title = node.name, subtitle = node.path.parent?.path.orEmpty(), onClose = { vm.closeHandler() }) {
            when (kind) {
                // Text editing in place, labelled on its own screen for exactly what it changes.
                // Hidden where the folder is read-only rather than offered and refused.
                DocFormat.WORD, DocFormat.SLIDES ->
                    if (writable) ViewerAction(FiletIcons.Rename, "Edit text", enabled = doc != null) { editing = true }
                // Plain text is the one code editor's, not a second one's. Markdown never gets
                // here: it went to the editor above.
                DocFormat.NONE ->
                    ViewerAction(FiletIcons.Rename, "Edit") { vm.openWith(node, HandlerId.TEXT, remember = false) }
                DocFormat.MARKDOWN -> Unit
                DocFormat.SHEET -> Unit
            }
            if (doc != null) DocPageActions(page)
        }
        val current = doc
        when {
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error.orEmpty(), fontSize = 12.sp, color = colors.warn, modifier = Modifier.padding(28.dp))
            }

            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }

            // Keyed on the path so the scroll resets when a different file opens, rather than only
            // when the reader leaves composition - the same slot is reused between files.
            else -> DocReader(current, key = node.path, place = place, page = page)
        }
    }
}

/** Which reader a name points at. */
/**
 * Which reader a name points at - and the honest answer to "what can this app open".
 *
 * | Extension              | View                                            | Edit                                      |
 * |------------------------|-------------------------------------------------|-------------------------------------------|
 * | md markdown mdown mkd  | rendered (`MarkdownReader`), in the code editor | one switch to the source and back         |
 * | docx                   | headings, emphasis, lists, tables, pictures     | text of paragraphs, in place (`OfficeEditScreen`) |
 * | pptx                   | slides in deck order, pictures, tables, notes   | text of slide paragraphs, in place        |
 * | xlsx xlsm ods          | grid (`SheetScreen`)                            | no                                        |
 * | pdf                    | pages (`PdfScreen`)                             | no                                        |
 * | html htm xhtml         | rendered (`HtmlScreen`)                         | source, in the one code editor            |
 * | anything else here     | as one block of text                            | in the code editor                        |
 *
 * DOCX/PPTX editing is TEXT ONLY and says so on its screen: the words in existing paragraphs
 * change, and every byte of the archive outside those text nodes is kept. Not offered: new
 * paragraphs, formatting, tables, pictures, headers, footnotes, comments, speaker notes. Lifting
 * that ceiling means a document model that round-trips, which no GPL-compatible library on
 * Android offers at a size this app would accept; see `OfficeReader` on POI.
 *
 * Not read at all, and routed to another app: doc, ppt, xls (binary Office), odt, odp, rtf, epub.
 */
enum class DocFormat {
    MARKDOWN, WORD, SLIDES,

    /**
     * A spreadsheet, which does NOT become a [Doc] - see `SheetScreen`.
     *
     * In this enum rather than branched on separately at the call site, because this enum is
     * the registry of what the package can open and an exhaustive `when` over it is what forces
     * a new surface to decide. A special case outside it compiles today and is invisible to the
     * next person adding a format.
     */
    SHEET,
    NONE;

    companion object {
        fun of(extension: String): DocFormat = when (extension.lowercase()) {
            "md", "markdown", "mdown", "mkd" -> MARKDOWN
            "docx" -> WORD
            "pptx" -> SLIDES
            "xlsx", "xlsm", "ods" -> SHEET
            else -> NONE
        }

        /** Every extension this package has a reader for, for the handler registry. */
        val EXTENSIONS: Set<String> =
            setOf(
                "md", "markdown", "mdown", "mkd", "docx", "pptx",
                "xlsx", "xlsm", "ods", "pdf", "html", "htm", "xhtml",
            )
    }
}

/** Whether this package has a reader for a file, for `HandlerRegistry`. */
fun hasDocReader(node: VNode): Boolean =
    node.extension.lowercase() in DocFormat.EXTENSIONS && FileKind.of(node) != FileKind.FOLDER

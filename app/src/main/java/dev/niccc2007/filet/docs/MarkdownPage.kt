package dev.niccc2007.filet.docs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Markdown source drawn as its page.
 *
 * The one surface a rendered Markdown file is shown on, so whatever changes how a page reads
 * changes it everywhere it is read. It takes TEXT, not a file: the code editor hands it the
 * buffer as it stands, unsaved edits included, which is the whole reason for drawing the page
 * there at all.
 *
 * Parsed off the main thread, and again only when the text changes - a long README is a few
 * thousand lines through the reader, and doing that inside a frame is a visible stall on the
 * flip from source to page. The page already up STAYS up while the new one is read: clearing it
 * first flashed a spinner over the whole screen on every flip back from an edit, and lost the
 * place it was scrolled to.
 *
 * @param key what resets the reader's scroll and folds: the file, so moving between files starts
 *   each at the top.
 * @param place the file's address, for its relative pictures and links.
 * @param page the state the bar's Contents and Reading buttons act on - see [MarkdownPageActions].
 */
@Composable
fun MarkdownPage(
    source: String,
    key: Any?,
    modifier: Modifier = Modifier,
    place: DocPlace? = null,
    page: DocPageState? = null,
) {
    var shown by remember(key) { mutableStateOf<Doc?>(null) }
    LaunchedEffect(key, source) {
        shown = withContext(Dispatchers.Default) {
            runCatching { MarkdownReader.read(source) }.getOrNull()
                // Shown as its source rather than as nothing: a page that fails to parse still
                // has words on it.
                ?: Doc(listOf(Block.Code(source)))
        }
    }
    val doc = shown
    if (doc == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
        }
    } else {
        DocReader(doc, modifier, key = key, place = place, page = page)
    }
}

/**
 * The page's own controls, for the bar of whatever screen is showing a [MarkdownPage]: Contents
 * and the reading settings, the same pair every document reader's bar carries.
 */
@Composable
fun RowScope.MarkdownPageActions(page: DocPageState) {
    DocPageActions(page)
}

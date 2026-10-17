package dev.niccc2007.filet.docs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A PDF, as a scroll of pages.
 *
 * ## Why a scroll and not a pager
 *
 * A pager is what a PDF viewer on a desktop does, because a desktop shows a whole page at
 * once. On a phone a page is taller than the screen whatever is done to it, so a pager means
 * swiping sideways between things that each need scrolling vertically - two axes for one
 * document. One continuous scroll is how every phone reader that people actually use behaves.
 *
 * ## Rendered as it is read
 *
 * A page at a readable width is several megabytes as a bitmap, so pages are rendered when they
 * come into view and dropped when they leave - see [PdfDoc.KEEP]. The placeholder keeps the
 * page's real aspect so the scroll does not jump as pages arrive, which is the difference
 * between a list that loads and a list that fights you.
 */
@Composable
fun PdfScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    var doc by remember(node.path) { mutableStateOf<PdfDoc?>(null) }
    var error by remember(node.path) { mutableStateOf<String?>(null) }
    var loading by remember(node.path) { mutableStateOf(true) }

    // PdfRenderer needs a seekable descriptor, so an entry in an archive or a file on a share
    // has to be a real local file first. See `BrowserViewModel.localFileFor`.
    LaunchedEffect(node.path) {
        val opened = withContext(Dispatchers.IO) { PdfDoc.open(vm.localCopy, node) }
        if (opened == null) {
            error = "Android could not read this as a PDF. It may be damaged, encrypted, or " +
                "somewhere Filet cannot copy it out of."
        }
        doc = opened
        loading = false
    }

    // The descriptor is closed when the screen goes, not when the composition happens to be
    // collected: a leaked ParcelFileDescriptor holds the file open, and enough of them
    // exhausts the process's handles.
    DisposableEffect(node.path) {
        onDispose { doc?.close() }
    }

    val current = doc
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }

        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                error.orEmpty(),
                fontSize = 12.sp,
                color = colors.warn,
                modifier = Modifier.padding(28.dp),
            )
        }

        current != null -> PdfPages(current)
    }
}

@Composable
private fun PdfPages(doc: PdfDoc) {
    val colors = Filet.colors
    val state = rememberLazyListState()
    val density = LocalDensity.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    // Rendered at the screen's real pixel width, capped. Rendering at the dp width would be
    // a quarter of the resolution on a modern phone and read as a blurry scan.
    val pixels = remember(widthDp, density) {
        with(density) { (widthDp.dp.toPx()).toInt() }.coerceIn(320, PdfDoc.MAX_WIDTH)
    }
    val rendered = remember(doc) { mutableStateOf<Map<Int, android.graphics.Bitmap>>(emptyMap()) }

    // One effect, driven by what is on screen. Rendering inside each item's own effect means
    // a fling starts and cancels a hundred renders; this one looks at the settled range.
    LaunchedEffect(doc, pixels) {
        snapshotFlow {
            val visible = state.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) IntRange.EMPTY else visible.first().index..visible.last().index
        }.collect { range ->
            if (range.isEmpty()) return@collect
            val want = (range.first - PdfDoc.KEEP)..(range.last + PdfDoc.KEEP)
            // Dropped first, so the peak heap is one page rather than two sets of them.
            val keep = rendered.value.filterKeys { it in want }
            if (keep.size != rendered.value.size) rendered.value = keep
            for (i in want) {
                if (i < 0 || i >= doc.pages) continue
                if (rendered.value.containsKey(i)) continue
                val bitmap = withContext(Dispatchers.Default) { doc.render(i, pixels) } ?: continue
                rendered.value = rendered.value + (i to bitmap)
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = state) {
        items(doc.pages) { index ->
            val bitmap = rendered.value[index]
            val aspect = remember(doc, index) { doc.aspect(index) }
            Column {
                Spacer(Modifier.height(if (index == 0) 10.dp else 6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .aspectRatio(1f / aspect.coerceAtLeast(0.2f))
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.sunken),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Page ${index + 1}",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("${index + 1}", fontSize = 11.sp, color = colors.fg3)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 3.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Text(
                        "${index + 1} / ${doc.pages}",
                        fontSize = 9.5.sp,
                        color = colors.fg3,
                    )
                }
            }
        }
    }
}

package dev.niccc2007.filet.handlers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Audio and video, routed to the screen each one deserves.
 *
 * They used to share one composable and one set of chrome, which is why the music player was a
 * progress line under a filename and the video player was the stock 2010 controller. They have
 * almost nothing in common beyond needing a seekable source, so they are two screens now.
 */
@Composable
fun MediaScreen(vm: BrowserViewModel, node: VNode) {
    if (FileKind.of(node) == FileKind.VIDEO) VideoScreen(vm, node) else AudioScreen(vm, node)
}

/**
 * A hex viewer that never loads the whole file.
 *
 * Rows are decoded from a page cache on demand, so a 4 GB disk image opens instantly and
 * scrolls without a 4 GB allocation. This is also the honest fallback for anything the text
 * editor refuses.
 */
@Composable
fun HexViewerScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    val bytesPerRow = 16
    val rows = ((node.size + bytesPerRow - 1) / bytesPerRow).coerceAtLeast(0)
    val cache = remember(node.path) { HexPageCache(vm, node) }

    Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = node.name,
            subtitle = "${humanSize(node.size)}  ·  $rows rows",
            onClose = { vm.closeHandler() },
        ) {
            ViewerAction(FiletIcons.Code, "Open as text") { vm.openWith(node, HandlerId.TEXT, remember = false) }
            ViewerAction(FiletIcons.Info, "Properties") { vm.showProperties(node) }
        }
        LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            items(rows.toInt()) { row ->
                val offset = row.toLong() * bytesPerRow
                var line by remember(node.path, row) { mutableStateOf<Triple<String, String, String>?>(null) }
                LaunchedEffect(node.path, row) {
                    line = withContext(Dispatchers.IO) { cache.row(offset, bytesPerRow) }
                }
                val l = line
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)) {
                    Text(
                        "%08X".format(offset),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.fg3,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        l?.second ?: "",
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        l?.third ?: "",
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.fg2,
                    )
                }
            }
        }
    }
}

/**
 * 64 KB pages, one stream re-opened per page.
 *
 * The VFS deliberately offers no random access on every backend (an archive entry has none),
 * so seeking is `skip`, and caching whole pages is what keeps that from being quadratic.
 */
private class HexPageCache(private val vm: BrowserViewModel, private val node: VNode) {
    private val pages = LinkedHashMap<Long, ByteArray>(8, 0.75f, true)
    private val pageSize = 64 * 1024

    @Synchronized
    fun row(offset: Long, count: Int): Triple<String, String, String> {
        val pageIndex = offset / pageSize
        val page = pages.getOrPut(pageIndex) { readPage(pageIndex) }
        if (pages.size > 6) pages.remove(pages.keys.first())
        val start = (offset % pageSize).toInt()
        val hex = StringBuilder()
        val ascii = StringBuilder()
        for (i in 0 until count) {
            val idx = start + i
            if (idx >= page.size) break
            val b = page[idx].toInt() and 0xFF
            hex.append("%02X ".format(b))
            ascii.append(if (b in 32..126) b.toChar() else '.')
        }
        return Triple("", hex.toString().trimEnd(), ascii.toString())
    }

    private fun readPage(index: Long): ByteArray = runCatching {
        vm.openRead(node).use { input ->
            var skipped = 0L
            val target = index * pageSize
            while (skipped < target) {
                val n = input.skip(target - skipped)
                if (n <= 0) break
                skipped += n
            }
            val buf = ByteArray(pageSize)
            var read = 0
            while (read < pageSize) {
                val n = input.read(buf, read, pageSize - read)
                if (n < 0) break
                read += n
            }
            if (read == pageSize) buf else buf.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))
}

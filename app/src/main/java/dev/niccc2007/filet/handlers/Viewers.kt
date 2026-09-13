package dev.niccc2007.filet.handlers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Images, decoded through the VFS.
 *
 * Two passes: bounds-only first to learn the size, then a sampled decode. Decoding a 108 MP
 * phone photo at full size is roughly 400 MB of bitmap and an immediate OOM, and `inSampleSize`
 * is the only thing standing between this viewer and that.
 */
@Composable
fun ImageViewerScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    var bitmap by remember(node.path) { mutableStateOf<Bitmap?>(null) }
    var dims by remember(node.path) { mutableStateOf("") }
    var error by remember(node.path) { mutableStateOf<String?>(null) }
    var scale by remember(node.path) { mutableFloatStateOf(1f) }
    var offsetX by remember(node.path) { mutableFloatStateOf(0f) }
    var offsetY by remember(node.path) { mutableFloatStateOf(0f) }

    LaunchedEffect(node.path) {
        runCatching {
            withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                vm.openRead(node).use { BitmapFactory.decodeStream(it, null, bounds) }
                val w = bounds.outWidth
                val h = bounds.outHeight
                var sample = 1
                while (w / sample > 2400 || h / sample > 2400) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = vm.openRead(node).use { BitmapFactory.decodeStream(it, null, opts) }
                bmp to "${w} × ${h}"
            }
        }.onSuccess { (bmp, d) ->
            if (bmp == null) error = "This is not an image Android can decode."
            bitmap = bmp
            dims = d
        }.onFailure { error = it.message ?: "Could not read this image." }
    }

    DisposableEffect(node.path) { onDispose { bitmap?.recycle(); bitmap = null } }

    Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = node.name,
            subtitle = listOf(dims, humanSize(node.size)).filter { it.isNotEmpty() }.joinToString("  ·  "),
            onClose = { vm.closeHandler() },
        ) {
            ViewerAction(FiletIcons.Refresh, "Reset zoom") { scale = 1f; offsetX = 0f; offsetY = 0f }
            ViewerAction(FiletIcons.Share, "Share") { vm.shareOne(node) }
            ViewerAction(FiletIcons.Info, "Properties") { vm.showProperties(node) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(node.path) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 12f)
                        // Panning is only meaningful once zoomed in; at 1x it would let the
                        // image wander off screen with no way to find it again.
                        if (scale > 1.01f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f; offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            when {
                error != null -> Text(error!!, fontSize = 13.sp, color = colors.fg2)
                bmp == null -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                else -> Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = node.name,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offsetX; translationY = offsetY
                    },
                )
            }
        }
    }
}

/**
 * Audio and video.
 *
 * Video needs a surface and a seekable source, so it goes through `VideoView` against a real
 * path or a cached copy; audio runs on `MediaPlayer`. Neither can consume an `InputStream`,
 * which is why [BrowserViewModel.localUriFor] exists rather than a stream being passed in.
 */
@Composable
fun MediaScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    val isVideo = node.extension in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "ts", "m4v")
    var uri by remember(node.path) { mutableStateOf<Uri?>(null) }
    var error by remember(node.path) { mutableStateOf<String?>(null) }
    var meta by remember(node.path) { mutableStateOf("") }

    LaunchedEffect(node.path) {
        val u = runCatching { vm.localUriFor(node) }.getOrNull()
        if (u == null) {
            error = "This file has to be copied out before it can play. Copy it to local storage first."
        } else {
            uri = u
            meta = withContext(Dispatchers.IO) { readMeta(vm, node) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ViewerBar(
            title = node.name,
            subtitle = listOf(meta, humanSize(node.size)).filter { it.isNotEmpty() }.joinToString("  ·  "),
            onClose = { vm.closeHandler() },
        ) {
            ViewerAction(FiletIcons.Share, "Share") { vm.shareOne(node) }
            ViewerAction(FiletIcons.Link, "Open elsewhere") { vm.openExternally(node, force = true) }
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), Alignment.Center) {
            val u = uri
            when {
                error != null -> Text(error!!, fontSize = 13.sp, color = colors.fg2, modifier = Modifier.padding(24.dp))
                u == null -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                isVideo -> androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(u)
                            setMediaController(android.widget.MediaController(ctx).also { it.setAnchorView(this) })
                            setOnPreparedListener { it.start() }
                            setOnErrorListener { _, _, _ -> true }
                        }
                    },
                )
                else -> AudioPlayer(u, node)
            }
        }
    }
}

@Composable
private fun AudioPlayer(uri: Uri, node: VNode) {
    val colors = Filet.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val player = remember(uri) {
        runCatching { MediaPlayer.create(ctx, uri) }.getOrNull()
    }
    var position by remember(uri) { mutableFloatStateOf(0f) }
    var playing by remember(uri) { mutableStateOf(false) }

    DisposableEffect(uri) {
        player?.start()
        playing = player != null
        onDispose { runCatching { player?.release() } }
    }

    LaunchedEffect(uri, playing) {
        while (playing && player != null) {
            val dur = player.duration.coerceAtLeast(1)
            position = player.currentPosition.toFloat() / dur
            delay(250)
        }
    }

    if (player == null) {
        Text("Android cannot decode this audio file.", fontSize = 13.sp, color = colors.fg2)
        return
    }

    Column(
        Modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.Icon(
            FiletIcons.Audio, null, tint = colors.accent, modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text(node.name, fontSize = 14.sp, maxLines = 2)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(progress = { position }, modifier = Modifier.fillMaxWidth().height(3.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            "${clock(player.currentPosition)} / ${clock(player.duration)}",
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.fg3,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ViewerAction(FiletIcons.Back, "Back 10s") {
                player.seekTo(max(0, player.currentPosition - 10_000))
            }
            ViewerAction(if (playing) FiletIcons.Close else FiletIcons.Play, if (playing) "Pause" else "Play") {
                if (playing) player.pause() else player.start()
                playing = !playing
            }
            ViewerAction(FiletIcons.Play, "Forward 10s") {
                player.seekTo(min(player.duration, player.currentPosition + 10_000))
            }
        }
    }
}

private fun clock(ms: Int): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

private fun readMeta(vm: BrowserViewModel, node: VNode): String = runCatching {
    val path = vm.osPathOf(node) ?: return ""
    // MediaMetadataRetriever only became AutoCloseable at API 29 and this app floors at 26,
    // so the release is explicit rather than a `use` block.
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(path)
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        buildString {
            if (dur > 0) append(clock(dur.toInt()))
            if (w != null && h != null) { if (isNotEmpty()) append("  ·  "); append("$w × $h") }
        }
    } finally {
        runCatching { r.release() }
    }
}.getOrDefault("")

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

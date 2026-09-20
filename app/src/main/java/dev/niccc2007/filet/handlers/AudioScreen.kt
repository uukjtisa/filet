package dev.niccc2007.filet.handlers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The music player.
 *
 * What was here before was a progress line, a filename and three buttons, one of which was the
 * Close icon doing duty as Pause. The brief was a Spotify-like transport, and the parts of that
 * which actually matter are: the cover art is the screen, the track has a name and an artist
 * rather than a filename, the bar can be scrubbed, and the folder you opened it from is a
 * queue you can see and move around in.
 *
 * The queue arithmetic is in `PlayQueue.kt` and the bar behaviour is in `Transport.kt`, both
 * tested. This file owns the `MediaPlayer` and the tags.
 */

/** What repeat is doing. Off, the whole queue, or this one track. */
private enum class Repeat { OFF, ALL, ONE }

/** Tags as the file itself reports them, with the filename as the fallback for the title. */
private class Tags(
    val title: String,
    val artist: String?,
    val album: String?,
    val art: Bitmap?,
)

/**
 * The player handle, kept out of Compose state.
 *
 * A `MediaPlayer` in a `mutableStateOf` invites a recomposition to read a half-released one.
 * Holding it here means exactly one place creates and destroys it.
 */
private class AudioEngine {
    var player: MediaPlayer? = null

    fun release() {
        runCatching { player?.release() }
        player = null
    }
}

@Composable
fun AudioScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    val context = LocalContext.current
    val engine = remember(node.path) { AudioEngine() }

    var queue by remember(node.path) { mutableStateOf<PlayQueue?>(null) }
    var tags by remember(node.path) { mutableStateOf<Tags?>(null) }
    var duration by remember(node.path) { mutableLongStateOf(0L) }
    var position by remember(node.path) { mutableLongStateOf(0L) }
    var scrubTo by remember(node.path) { mutableStateOf<Long?>(null) }
    var playing by remember(node.path) { mutableStateOf(false) }
    var error by remember(node.path) { mutableStateOf<String?>(null) }
    var shuffle by remember(node.path) { mutableStateOf(false) }
    var repeat by remember(node.path) { mutableStateOf(Repeat.OFF) }
    var showQueue by remember(node.path) { mutableStateOf(false) }
    // Bumped to ask the loader to run again on the same track, which is what repeat-one and a
    // manual restart both need. A state change the effect key can see.
    var generation by remember(node.path) { mutableIntStateOf(0) }

    LaunchedEffect(node.path) {
        queue = audioQueue(vm.siblingsOf(node), node, vm.currentSort)
    }

    val track = queue?.current

    fun advance(delta: Int) {
        val q = queue ?: return
        queue = when {
            shuffle && q.size > 2 -> {
                // Any track but this one. Not a shuffled permutation: a folder queue has no
                // "end" to reach, and a next that can repeat the track you are on reads as broken.
                var i = Random.nextInt(q.size)
                while (i == q.index) i = Random.nextInt(q.size)
                q.at(i)
            }
            else -> q.stepped(delta)
        }
    }

    // Load whatever the queue is pointing at.
    LaunchedEffect(track?.path, generation) {
        engine.release()
        playing = false
        position = 0L
        duration = 0L
        tags = null
        error = null
        val t = track ?: return@LaunchedEffect

        val uri = runCatching { vm.localUriFor(t) }.getOrNull()
        if (uri == null) {
            error = "This track has to be copied to local storage before it can play."
            return@LaunchedEffect
        }

        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(context, uri)
                    prepare()
                }
            }.getOrNull()
        }
        if (prepared == null) {
            error = "Android cannot decode ${t.name}."
            return@LaunchedEffect
        }
        engine.player = prepared
        duration = prepared.duration.toLong().coerceAtLeast(0L)
        prepared.setOnCompletionListener {
            if (repeat == Repeat.ONE) generation++ else advance(1)
        }
        prepared.start()
        playing = true

        tags = withContext(Dispatchers.IO) { readTags(context, vm, t, uri) }
    }

    // The position ticker. 200 ms is smooth enough for a bar and cheap enough to ignore.
    LaunchedEffect(playing, track?.path, generation) {
        while (playing) {
            position = engine.player?.currentPosition?.toLong() ?: 0L
            delay(200)
        }
    }

    DisposableEffect(engine) { onDispose { engine.release() } }

    val art = tags?.art
    val shownPosition = scrubTo ?: position

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ViewerBar(
            title = tags?.title ?: node.name,
            subtitle = queue?.let { "${it.position} of ${it.size}" } ?: "",
            onClose = { vm.closeHandler() },
        ) {
            ViewerAction(FiletIcons.Rows, "Queue", enabled = (queue?.size ?: 0) > 1) {
                showQueue = !showQueue
            }
            ViewerAction(FiletIcons.Link, "Open in another app") {
                vm.openExternally(track ?: node, force = true)
            }
            ViewerAction(FiletIcons.Share, "Share") { vm.shareFromViewer(track ?: node) }
            ViewerAction(FiletIcons.Info, "Properties") { vm.showProperties(track ?: node) }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Centred, not stacked from the top. The art is capped by whichever of width and
            // height runs out first, so on a tall phone there is slack, and hanging the whole
            // player off the top leaves it in the middle of the screen with a hole under it.
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.raised),
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) {
                    Image(
                        art.asImageBitmap(), "Cover art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    // No embedded art is normal, not a failure, so the placeholder is a piece
                    // of the design rather than an error state.
                    Icon(
                        FiletIcons.Audio, null,
                        tint = colors.accent.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxSize(0.32f),
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            Text(
                tags?.title ?: (track?.name ?: node.name),
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(tags?.artist, tags?.album).joinToString("  ·  ").ifEmpty {
                    track?.path?.parent?.name ?: ""
                },
                fontSize = 12.5.sp,
                color = colors.fg2,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error!!, fontSize = 12.sp, color = colors.warn, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(16.dp))

            ScrubBar(
                positionMs = shownPosition,
                durationMs = duration,
                enabled = duration > 0,
                onScrub = { scrubTo = it },
                onScrubEnd = { target ->
                    scrubTo = null
                    engine.player?.seekTo(target.toInt())
                    position = target
                },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    clockOf(shownPosition),
                    fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = colors.fg3,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    remainingOf(shownPosition, duration),
                    fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = colors.fg3,
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    FiletIcons.Shuffle, "Shuffle", size = 38.dp,
                    active = shuffle, enabled = (queue?.size ?: 0) > 2,
                ) { shuffle = !shuffle }

                TransportButton(FiletIcons.Prev, "Previous", size = 46.dp) {
                    // Restart first, jump back second - the rule every player uses, and the one
                    // that makes a mis-tap during a track harmless.
                    if (position > 3_000L) {
                        engine.player?.seekTo(0)
                        position = 0L
                    } else {
                        advance(-1)
                    }
                }

                TransportButton(
                    if (playing) FiletIcons.Pause else FiletIcons.PlaySolid,
                    if (playing) "Pause" else "Play",
                    filled = true,
                    size = 64.dp,
                    enabled = engine.player != null,
                ) {
                    val p = engine.player ?: return@TransportButton
                    if (playing) p.pause() else p.start()
                    playing = !playing
                }

                TransportButton(FiletIcons.Next, "Next", size = 46.dp) { advance(1) }

                TransportButton(
                    FiletIcons.Repeat,
                    when (repeat) {
                        Repeat.OFF -> "Repeat off"
                        Repeat.ALL -> "Repeat queue"
                        Repeat.ONE -> "Repeat track"
                    },
                    size = 38.dp,
                    active = repeat != Repeat.OFF,
                ) {
                    repeat = when (repeat) {
                        Repeat.OFF -> Repeat.ALL
                        Repeat.ALL -> Repeat.ONE
                        Repeat.ONE -> Repeat.OFF
                    }
                }
            }
            if (repeat == Repeat.ONE) {
                Text("Repeating this track", fontSize = 9.5.sp, color = colors.accent)
            }
            Spacer(Modifier.height(14.dp))
        }

        AnimatedVisibility(visible = showQueue) {
            QueuePanel(
                queue = queue,
                onPick = { i -> queue = queue?.at(i) },
            )
        }
    }
}

/** The folder, as a list you can jump around in. Capped in height so it never eats the art. */
@Composable
private fun QueuePanel(queue: PlayQueue?, onPick: (Int) -> Unit) {
    val colors = Filet.colors
    val q = queue ?: return
    val state = rememberLazyListState()

    LaunchedEffect(q.index) {
        // Keep the playing row on screen as the queue advances, without yanking the list if
        // the user has scrolled somewhere else on purpose.
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && q.index !in visible.first().index..visible.last().index) {
            runCatching { state.animateScrollToItem(q.index) }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .background(colors.raised)
    ) {
        Text(
            "Up next  ·  ${q.size} tracks",
            fontSize = 9.5.sp,
            letterSpacing = 0.8.sp,
            color = colors.fg3,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(q.items, key = { _, n -> n.path.toString() }) { i, n ->
                val here = i == q.index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (here) colors.sel else Color.Transparent)
                        .clickable { onPick(i) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${i + 1}",
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = if (here) colors.accent else colors.fg3,
                        modifier = Modifier.width(26.dp),
                    )
                    Text(
                        n.name,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (here) colors.accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (here) {
                        Icon(
                            FiletIcons.PlaySolid, null,
                            tint = colors.accent, modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Title, artist, album and cover art, from the file's own tags.
 *
 * Blocking; the caller is on IO. A real path is preferred over the content URI because some
 * OEM extractors refuse a URI they did not mint, and the filename is the fallback title
 * because an untagged MP3 with a blank title reads as a broken player rather than a bare file.
 */
private fun readTags(context: Context, vm: BrowserViewModel, node: VNode, uri: Uri): Tags {
    val fallback = node.name.substringBeforeLast('.', node.name)
    val r = MediaMetadataRetriever()
    return try {
        val os = vm.osPathOf(node)
        if (os != null) r.setDataSource(os) else r.setDataSource(context, uri)
        Tags(
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: fallback,
            artist = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                ?.takeIf { it.isNotBlank() },
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() },
            art = r.embeddedPicture?.let { bytes ->
                // Sampled to roughly a phone's width. Album art is routinely 1500 square and
                // decoding that at full size for a 400 px box is 9 MB for nothing.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            },
        )
    } catch (_: Throwable) {
        // A tagless or malformed file is ordinary. It still plays.
        Tags(fallback, null, null, null)
    } finally {
        // MediaMetadataRetriever only became AutoCloseable at API 29 and this app floors at 26.
        runCatching { r.release() }
    }
}

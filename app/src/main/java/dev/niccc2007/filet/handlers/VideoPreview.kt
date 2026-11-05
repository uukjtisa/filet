package dev.niccc2007.filet.handlers

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.dialogs.PopMenu
import dev.niccc2007.filet.ui.dialogs.PopRow
import dev.niccc2007.filet.ui.dialogs.PopSeparator
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * A video before it plays, and after Back stops it: the picture under a play button, silent.
 * See [VideoStart] for when a video opens here and when it plays at once.
 *
 * ## Opened
 *
 * A glimpse of the whole video, the same one a held tile plays - [GlimpsePlan]'s six clips of two
 * seconds, one from each sixth - muted, round and round, over the video's poster. Its own small
 * player on a texture, not the video screen's: that one is made only once Play is pressed, so a
 * video looked at and left costs no decoder set up for sound, no sound effects on a session and
 * no audio focus. Nothing here asks for focus at all, so music playing elsewhere carries on
 * until Play.
 *
 * Over a network share the poster stays and nothing plays, as it does on a held tile: a glimpse
 * that pulls a video across the network a few seconds at a time is a stall dressed up as a
 * preview. Play still plays it.
 *
 * ## Stopped
 *
 * Back while the video plays comes here instead of leaving, with the picture it stopped on and
 * the time it carries on from. Play carries on from there; the menu has Play from the start.
 * Not the glimpse again: a glimpse of other parts of the video above a Play that goes back to
 * the middle of it would show one thing and do another.
 *
 * A tap anywhere on the picture plays, as the button does: the button is where the eye goes, the
 * picture is where the thumb already is. A swipe still moves through the run.
 *
 * @param stoppedAt where Back stopped it, or 0 for a video just opened.
 * @param still the frame it stopped on, read off the screen as it stopped; null to read it from
 *   the file instead.
 * @param onPlay play with sound, from this position.
 */
@Composable
internal fun VideoPreview(
    vm: BrowserViewModel,
    node: VNode,
    stoppedAt: Long,
    still: Bitmap?,
    onPlay: (fromMs: Long) -> Unit,
) {
    val grey by vm.prefs.viewerGrey.collectAsState()
    var menu by remember(node.path) { mutableStateOf(false) }
    dev.niccc2007.filet.ui.theme.DarkGround(ViewerGround.of(grey)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (stoppedAt > 0L) {
                StoppedStill(vm, node, stoppedAt, still)
            } else {
                RunPoster(node, video = false)
                GlimpseLoop(vm, node)
            }
            // The tap, on a layer of its own over the picture: the glimpse is a view of its own
            // under it, and a view can keep a touch from what is around it.
            Box(Modifier.matchParentSize().clickable(onClickLabel = "Play") { onPlay(stoppedAt) })
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PlayMark()
                if (stoppedAt > 0L) {
                    Spacer(Modifier.height(10.dp))
                    // On a dark disc of its own, like the mark: it sits on a photograph.
                    Text(
                        "From ${clockOf(stoppedAt)}",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                ViewerBar(
                    title = node.name,
                    subtitle = humanSize(node.size),
                    onClose = { vm.closeHandler() },
                ) {
                    // The arrows as well as the swipe, the way the playing screen has them.
                    LocalViewerRun.current?.let { runPage ->
                        if (runPage.hasPrevious) ViewerAction(FiletIcons.Prev, "Previous") { runPage.step(-1) }
                        if (runPage.hasNext) ViewerAction(FiletIcons.Next, "Next") { runPage.step(1) }
                    }
                    Box {
                        ViewerAction(FiletIcons.More, "More") { menu = true }
                        PopMenu(expanded = menu, onDismiss = { menu = false }) {
                            if (stoppedAt > 0L) {
                                PopRow(FiletIcons.Play, "Play from the start") { menu = false; onPlay(0L) }
                                PopSeparator()
                            }
                            if (LocalViewerRun.current != null) {
                                NextVideoRow(vm) { menu = false }
                                RunKindsRow(vm, node) { menu = false }
                                PopSeparator()
                            }
                            PopRow(FiletIcons.Link, "Open in another app") {
                                menu = false
                                vm.openExternally(node, force = true)
                            }
                            PopRow(FiletIcons.Share, "Share") { menu = false; vm.share(node) }
                            PopRow(FiletIcons.Info, "Properties") { menu = false; vm.showProperties(node) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Next video setting as a menu row, lit while a video stepped or swiped to plays at once.
 * The same preference as Settings > Video. See [VideoStart].
 */
@Composable
internal fun NextVideoRow(vm: BrowserViewModel, close: () -> Unit) {
    val plays by vm.prefs.videoNextPlays.collectAsState()
    PopRow(
        FiletIcons.Next,
        "Play the next video at once",
        detail = if (plays) {
            "A video you swipe or step to starts playing."
        } else {
            "A video you swipe or step to waits for a tap."
        },
        active = plays,
    ) {
        vm.prefs.setVideoNextPlays(!plays)
        close()
    }
}

/**
 * The frame Back stopped on: [still] when the screen gave one, else read from the file at [at],
 * with the poster under it until then.
 */
@Composable
private fun StoppedStill(vm: BrowserViewModel, node: VNode, at: Long, still: Bitmap?) {
    val context = LocalContext.current
    var frame by remember(node.path, at) { mutableStateOf(still) }
    if (frame == null) {
        LaunchedEffect(node.path, at) {
            if (vm.vfs.osPath(node.path) == null) return@LaunchedEffect
            val source = runCatching { vm.localUriFor(node) }.getOrNull() ?: return@LaunchedEffect
            frame = withContext(Dispatchers.IO) { frameAt(context, source, at) }
        }
    }
    val shown = frame
    if (shown == null) {
        RunPoster(node, video = false)
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(shown.asImageBitmap(), node.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

/** The frame at [atMs], exactly there rather than at the keyframe before it; null if it will not decode. */
private fun frameAt(context: android.content.Context, uri: Uri, atMs: Long): Bitmap? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(context, uri)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            r.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, STILL_PX, STILL_PX)
        } else {
            r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
        }
    } catch (e: RuntimeException) {
        null
    } finally {
        runCatching { r.release() }
    }
}

/**
 * The picture on [view] now, at up to [STILL_PX] on its long side, or null when it cannot be
 * read - no surface yet, or a copy refused or slower than a few frames.
 *
 * What Back shows: the frame that was on the screen, not the nearest one a decoder finds, so the
 * picture does not jump as the player goes.
 */
internal suspend fun captureStill(view: SurfaceView): Bitmap? {
    if (!view.holder.surface.isValid || view.width <= 0 || view.height <= 0) return null
    val scale = minOf(1f, STILL_PX.toFloat() / maxOf(view.width, view.height))
    val bitmap = Bitmap.createBitmap(
        (view.width * scale).toInt().coerceAtLeast(1),
        (view.height * scale).toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    return withTimeoutOrNull(STILL_WAIT_MS) {
        suspendCancellableCoroutine { c ->
            try {
                PixelCopy.request(view, bitmap, { result ->
                    if (c.isActive) c.resume(if (result == PixelCopy.SUCCESS) bitmap else null)
                }, Handler(Looper.getMainLooper()))
            } catch (e: IllegalArgumentException) {
                c.resume(null)
            }
        }
    }
}

/**
 * The glimpse: [GlimpsePlan]'s clips played muted from the original file, over whatever is under
 * it, with a bar in as many segments as there are clips. Local files only; see [VideoPreview].
 */
@Composable
private fun GlimpseLoop(vm: BrowserViewModel, node: VNode) {
    val context = LocalContext.current
    var uri by remember(node.path) { mutableStateOf<Uri?>(null) }
    var aspect by remember(node.path) { mutableFloatStateOf(0f) }
    var player by remember(node.path) { mutableStateOf<MediaPlayer?>(null) }
    var surface by remember(node.path) { mutableStateOf<Surface?>(null) }
    var plan by remember(node.path) { mutableStateOf<List<GlimpsePlan.Clip>>(emptyList()) }
    var clip by remember(node.path) { mutableIntStateOf(0) }
    var within by remember(node.path) { mutableFloatStateOf(0f) }
    var showing by remember(node.path) { mutableStateOf(false) }

    fun release() {
        player?.let { runCatching { it.release() } }
        surface?.let { runCatching { it.release() } }
        player = null
        surface = null
        showing = false
    }
    DisposableEffect(node.path) { onDispose { release() } }
    LaunchedEffect(node.path) {
        if (vm.vfs.osPath(node.path) != null) uri = runCatching { vm.localUriFor(node) }.getOrNull()
    }
    val source = uri ?: return

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = (if (aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize())
                .graphicsLayer { alpha = if (showing) 1f else 0f },
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                            val mp = MediaPlayer()
                            val out = Surface(texture)
                            runCatching {
                                mp.setDataSource(context, source)
                                mp.setSurface(out)
                                // Muted, and nothing asks for audio focus: a MediaPlayer takes
                                // none of its own, so whatever else is playing carries on.
                                mp.setVolume(0f, 0f)
                                mp.setOnVideoSizeChangedListener { _, vw, vh ->
                                    if (vw > 0 && vh > 0) aspect = vw.toFloat() / vh
                                }
                                mp.setOnInfoListener { _, what, _ ->
                                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) showing = true
                                    false
                                }
                                mp.setOnPreparedListener {
                                    val p = GlimpsePlan.of(it.duration.toLong())
                                    plan = p
                                    it.isLooping = GlimpsePlan.isWhole(p)
                                    p.firstOrNull()?.let { c -> it.seekTo(c.startMs, MediaPlayer.SEEK_CLOSEST_SYNC) }
                                    it.start()
                                    player = it
                                }
                                surface = out
                                mp.prepareAsync()
                            }.onFailure {
                                runCatching { mp.release() }
                                runCatching { out.release() }
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) = Unit

                        // The app went to the background, or the page went: a new texture comes
                        // with a new player when it is back.
                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            release()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            },
        )
        // The clips, in order, round and round, for as long as the preview is up.
        LaunchedEffect(player, plan) {
            val mp = player ?: return@LaunchedEffect
            if (plan.isEmpty()) return@LaunchedEffect
            if (GlimpsePlan.isWhole(plan)) {
                val total = plan[0].lengthMs.coerceAtLeast(1L)
                while (isActive) {
                    within = runCatching { mp.currentPosition.toFloat() / total }.getOrDefault(0f).coerceIn(0f, 1f)
                    delay(GLIMPSE_TICK_MS)
                }
                return@LaunchedEffect
            }
            var i = 0
            while (isActive) {
                val c = plan[i]
                clip = i
                if (i > 0 || within > 0f) runCatching { mp.seekTo(c.startMs, MediaPlayer.SEEK_CLOSEST_SYNC) }
                val began = System.currentTimeMillis()
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - began
                    within = (elapsed.toFloat() / c.lengthMs).coerceIn(0f, 1f)
                    if (elapsed >= c.lengthMs) break
                    delay(GLIMPSE_TICK_MS)
                }
                i = (i + 1) % plan.size
            }
        }
    }
    if (showing && plan.isNotEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            GlimpseBar(
                segments = plan.size,
                current = clip,
                within = within,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            )
        }
    }
}

/** The glimpse's progress: a segment per clip, those played full, the playing one filling. */
@Composable
private fun GlimpseBar(segments: Int, current: Int, within: Float, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until segments) {
            val fill = when {
                i < current -> 1f
                i == current -> within
                else -> 0f
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.32f)),
            ) {
                Box(Modifier.fillMaxWidth(fill).height(3.dp).background(Color.White))
            }
        }
    }
}

/** The stopped frame's longest edge: sharp on a phone, and one of them held at a time. */
private const val STILL_PX = 1600

/** How long Back waits for the screen's picture before reading the frame from the file instead. */
private const val STILL_WAIT_MS = 300L

private const val GLIMPSE_TICK_MS = 50L

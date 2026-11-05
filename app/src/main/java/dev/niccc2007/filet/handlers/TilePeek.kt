package dev.niccc2007.filet.handlers

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.browser.FileThumb
import dev.niccc2007.filet.browser.ListNameText
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** One thing the peek offers under its picture. */
class PeekAction(
    val icon: ImageVector,
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A held grid tile, lifted: the file larger, and what can be done with it underneath.
 *
 * What a grid's long press is for, now. It used to select, which is what a list's long press
 * does too - but a grid is where somebody is looking at pictures, and the first question about
 * a thumbnail the size of a stamp is what it actually shows. A photo comes up at the size of the
 * screen; a video plays a silent glimpse of itself (see [GlimpsePlan]); anything else shows its
 * icon and what it is. Select is the first action, so the old meaning is one tap further away,
 * not gone.
 *
 * The caller decides the [actions] - a folder grid can select, a category gallery has no
 * selection to start - so one popup serves both without either knowing about the other.
 */
@Composable
fun TilePeek(vm: BrowserViewModel, node: VNode, actions: List<PeekAction>, onDismiss: () -> Unit) {
    val colors = Filet.colors
    val lift = remember(node.path) { Animatable(0.9f) }
    LaunchedEffect(node.path) {
        lift.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // The whole window dismisses, so a tap anywhere off the card puts it away - the way a
        // peek is left everywhere it exists.
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val width = minOf(maxWidth * 0.86f, 380.dp)
            // Read here: the scopes inside are layout scopes too, and a DSL marker stops this one
            // being reached from them implicitly.
            val pictureMax = maxHeight * 0.58f
            Column(
                Modifier
                    .width(width)
                    .graphicsLayer {
                        scaleX = lift.value
                        scaleY = lift.value
                        alpha = ((lift.value - 0.9f) * 10f).coerceIn(0f, 1f)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val shape = RoundedCornerShape(18.dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = pictureMax)
                        .clip(shape)
                        .background(Color.Black)
                        // Taps on the picture are not taps off the card.
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    contentAlignment = Alignment.Center,
                ) {
                    when (FileKind.of(node)) {
                        FileKind.IMAGE -> PeekPicture(node)
                        FileKind.VIDEO -> PeekGlimpse(vm, node)
                        else -> PeekIcon(node)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.raised)
                        .border(1.dp, colors.lineSoft, RoundedCornerShape(14.dp)),
                ) {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp)) {
                        ListNameText(node.name, MaterialTheme.colorScheme.onSurface, 12.5f, lines = 2)
                        Text(humanSize(node.size), fontSize = 10.5.sp, color = colors.fg3)
                    }
                    for (a in actions) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onDismiss(); a.onClick() }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val ink = if (a.danger) colors.warn else MaterialTheme.colorScheme.onSurface
                            Text(a.label, fontSize = 13.sp, color = ink, modifier = Modifier.weight(1f))
                            Icon(a.icon, null, tint = if (a.danger) colors.warn else colors.fg2, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A photo at the size of the card, from the same cache the tiles use, one size up. */
@Composable
private fun PeekPicture(node: VNode) {
    val bitmap = rememberPeekBitmap(node)
    val b = bitmap
    if (b == null) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f))
    } else {
        Image(
            b.asImageBitmap(),
            node.name,
            Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height.coerceAtLeast(1)),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun rememberPeekBitmap(node: VNode): Bitmap? {
    val context = LocalContext.current
    val known = remember(node.path) { Thumbnails.peek(node.path, node.size, node.mtime, PEEK_PX)?.bitmap }
    var bitmap by remember(node.path) { mutableStateOf(known) }
    if (known == null) {
        LaunchedEffect(node.path) {
            val graph = runCatching { FiletApp.graphOf(context) }.getOrNull() ?: return@LaunchedEffect
            bitmap = runCatching {
                Thumbnails.load(context, graph.vfs, node.path, node.size, node.mtime, PEEK_PX)
            }.getOrNull()
        }
    }
    return bitmap
}

/** Anything that is not a picture: its icon, large, on the card's own ground. */
@Composable
private fun PeekIcon(node: VNode) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(1.6f).background(Filet.colors.sunken),
        contentAlignment = Alignment.Center,
    ) {
        FileThumb(node = node, size = 96.dp, fallbackTint = Filet.colors.fg2, glyphSize = 44.dp)
    }
}

/**
 * A video's glimpse: the clips [GlimpsePlan] picks, played muted from the original file, with a
 * bar in as many segments as there are clips.
 *
 * No file is made and nothing is decoded ahead: each clip is a seek to the nearest keyframe
 * (`SEEK_CLOSEST_SYNC`, the cheap seek) and one clip's length of ordinary playback. Over a network
 * share the still frame stays and nothing plays - a glimpse that has to pull a video across the
 * network a few seconds at a time is a stall dressed up as a preview.
 *
 * It plays while the peek is up and stops the moment it goes; the player is released with it.
 */
@Composable
private fun PeekGlimpse(vm: BrowserViewModel, node: VNode) {
    val context = LocalContext.current
    val still = rememberPeekBitmap(node)
    var aspect by remember(node.path) { mutableFloatStateOf(still?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (16f / 9f)) }
    LaunchedEffect(still) { still?.let { aspect = it.width.toFloat() / it.height.coerceAtLeast(1) } }
    var uri by remember(node.path) { mutableStateOf<android.net.Uri?>(null) }
    LaunchedEffect(node.path) {
        // Local files only: a share keeps its still. See above.
        if (vm.vfs.osPath(node.path) != null) uri = runCatching { vm.localUriFor(node) }.getOrNull()
    }
    var player by remember(node.path) { mutableStateOf<MediaPlayer?>(null) }
    var plan by remember(node.path) { mutableStateOf<List<GlimpsePlan.Clip>>(emptyList()) }
    var clip by remember(node.path) { mutableIntStateOf(0) }
    var within by remember(node.path) { mutableFloatStateOf(0f) }
    var showing by remember(node.path) { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth().aspectRatio(aspect), contentAlignment = Alignment.Center) {
        still?.let {
            Image(it.asImageBitmap(), node.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        val source = uri
        if (source != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (showing) 1f else 0f },
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                                val mp = MediaPlayer()
                                runCatching {
                                    mp.setDataSource(context, source)
                                    mp.setSurface(Surface(texture))
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
                                    mp.prepareAsync()
                                }.onFailure { runCatching { mp.release() } }
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) = Unit
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                player?.let { runCatching { it.release() } }
                                player = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                },
            )
        }
        // The clips, in order, round and round, for as long as the peek is up.
        LaunchedEffect(player, plan) {
            val mp = player ?: return@LaunchedEffect
            if (plan.isEmpty()) return@LaunchedEffect
            if (GlimpsePlan.isWhole(plan)) {
                val total = plan[0].lengthMs.coerceAtLeast(1L)
                while (isActive) {
                    within = runCatching { mp.currentPosition.toFloat() / total }.getOrDefault(0f).coerceIn(0f, 1f)
                    delay(TICK_MS)
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
                    delay(TICK_MS)
                }
                i = (i + 1) % plan.size
            }
        }
        if (plan.isNotEmpty()) {
            SegmentBar(
                segments = plan.size,
                current = clip,
                within = within,
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
            )
        }
    }
    DisposableEffect(node.path) {
        onDispose {
            player?.let { runCatching { it.release() } }
            player = null
        }
    }
}

/** The glimpse's progress: a segment per clip, those played full, the playing one filling. */
@Composable
private fun SegmentBar(segments: Int, current: Int, within: Float, modifier: Modifier = Modifier) {
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
                Box(
                    Modifier
                        .fillMaxWidth(fill)
                        .height(3.dp)
                        .background(Color.White),
                )
            }
        }
    }
}

/** A photo's peek, one size up from the tiles: sharp at the card's width on a phone. */
private const val PEEK_PX = 1080

private const val TICK_MS = 50L

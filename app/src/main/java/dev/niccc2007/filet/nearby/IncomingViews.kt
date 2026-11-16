package dev.niccc2007.filet.nearby

import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The full dialog: the send in the middle of the screen with each file previewed before anything
 * is saved - pictures shown, video and sound played (without sound at first when that is on), text
 * and Markdown readable. Any other file shows its kind. The strip and a sideways swipe move between
 * files. `.fd` in the incoming mock.
 *
 * Nothing seen here is saved: every look streams from the sending phone and is gone with the
 * dialog. Accept is what brings the files to Filet/Received.
 */
@Composable
internal fun FullDialog(
    offer: PushOffer,
    settings: IncomingSettings,
    thumbs: Map<String, ImageBitmap>,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onClose: () -> Unit,
    onView: (Int) -> Unit,
) {
    val colors = Filet.colors
    val peeked by rememberPeek(offer)
    val files = peeked?.map { it.file } ?: offer.files
    var index by remember(offer.id) { mutableIntStateOf(0) }
    val at = index.coerceIn(0, (files.size - 1).coerceAtLeast(0))
    var muted by remember(offer.id) { mutableStateOf(settings.muted) }
    // `.full{background:#000b}` - a tap on it leaves the send waiting, as the card's close does.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xBB000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
                // Taps on the dialog stay in it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            val f = files.getOrNull(at)
            val token = peeked?.getOrNull(at)?.token
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color.Black)
                    .swipeBetween(files.size, { index }, { index = it }),
            ) {
                if (f != null) {
                    Stage(
                        offer, f, token, thumbs[f.name], muted,
                        onView = { onView(at) },
                        modifier = Modifier.fillMaxSize(),
                        controls = true,
                    )
                }
                if (files.size > 1) Badge(IncomingRules.pager(at, files.size), Modifier.align(Alignment.TopStart).padding(8.dp)) {}
                if (f != null && IncomingRules.preview(f.name, f.dir).let { it == PreviewKind.VIDEO || it == PreviewKind.SOUND }) {
                    Badge(if (muted) "Muted" else "Sound on", Modifier.align(Alignment.TopEnd).padding(8.dp)) { muted = !muted }
                }
            }
            if (files.size > 1) {
                LazyRow(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(files) { i, file ->
                        PreviewTile(
                            file, thumbs[file.name],
                            Modifier
                                .size(46.dp)
                                .then(if (i == at) Modifier.border(2.dp, colors.accent, RoundedCornerShape(10.dp)) else Modifier)
                                .clickable { index = i },
                        )
                    }
                }
            }
            if (f != null) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, top = if (files.size > 1) 2.dp else 10.dp)) {
                    Text(
                        f.name + if (f.dir) ".zip" else "",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(IncomingRules.detail(offer.name, f) { humanSize(it) }, fontSize = 11.sp, color = colors.fg2, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton("Decline", primary = false, Modifier.weight(1f), onDecline)
                DialogButton(if (files.size == 1) "Accept" else "Accept all", primary = true, Modifier.weight(1f), onAccept)
            }
        }
    }
}

/**
 * The file viewed over the whole screen before it is accepted: Back returns to the app. Save is
 * Accept; Open in Filet brings Filet up on the same question; Decline turns the send away.
 * `.viewer` in the incoming mock.
 */
@Composable
internal fun FullViewer(
    offer: PushOffer,
    start: Int,
    settings: IncomingSettings,
    thumbs: Map<String, ImageBitmap>,
    onClose: () -> Unit,
    onSave: () -> Unit,
    /** Null inside Filet, where there is nothing to bring up. */
    onOpenFilet: (() -> Unit)?,
    onDecline: () -> Unit,
) {
    val colors = Filet.colors
    val peeked by rememberPeek(offer)
    val files = peeked?.map { it.file } ?: offer.files
    var index by remember(offer.id, start) { mutableIntStateOf(start) }
    val at = index.coerceIn(0, (files.size - 1).coerceAtLeast(0))
    var muted by remember(offer.id) { mutableStateOf(settings.muted) }
    val f = files.getOrNull(at)
    Box(Modifier.fillMaxSize().background(Color.Black).swipeBetween(files.size, { index }, { index = it })) {
        if (f != null) {
            Stage(
                offer, f, peeked?.getOrNull(at)?.token, thumbs[f.name], muted,
                onView = null,
                modifier = Modifier.fillMaxSize().padding(top = 60.dp, bottom = 90.dp),
                controls = true,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(FiletIcons.Back, "Back", tint = Color.White, modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClose).padding(4.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(f?.name.orEmpty(), color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("From ${offer.name} · not saved yet", color = colors.fg2, fontSize = 11.sp)
            }
            if (files.size > 1) Text("${at + 1} / ${files.size}", color = Color.White, fontSize = 12.sp)
            if (f != null && IncomingRules.preview(f.name, f.dir).let { it == PreviewKind.VIDEO || it == PreviewKind.SOUND }) {
                Icon(
                    if (muted) FiletIcons.VolumeOff else FiletIcons.Volume, if (muted) "Sound on" else "Mute",
                    tint = Color.White,
                    modifier = Modifier.padding(start = 10.dp).size(28.dp).clip(CircleShape).clickable { muted = !muted }.padding(4.dp),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .navigationBarsPadding()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            ViewerAction(FiletIcons.Download, "Save", onSave)
            if (onOpenFilet != null) ViewerAction(FiletIcons.Folder, "Open in Filet", onOpenFilet)
            ViewerAction(FiletIcons.Close, "Decline", onDecline)
        }
    }
}

/**
 * The file in a small window over the app in use, so a video keeps playing while the app beneath
 * is used. Dragged by its body, resized by its corner; ✕ closes it and the square opens it full
 * screen. `.float` in the incoming mock. The window itself is moved and sized by the overlay.
 */
@Composable
internal fun FloatViewer(
    offer: PushOffer,
    start: Int,
    settings: IncomingSettings,
    thumbs: Map<String, ImageBitmap>,
    onClose: () -> Unit,
    onFull: () -> Unit,
    onMove: (dx: Float, dy: Float) -> Unit,
    onResize: (dx: Float, dy: Float) -> Unit,
) {
    val peeked by rememberPeek(offer)
    val files = peeked?.map { it.file } ?: offer.files
    val f = files.getOrNull(start.coerceIn(0, (files.size - 1).coerceAtLeast(0)))
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
            .dragBy(onMove),
    ) {
        if (f != null) {
            Stage(
                offer, f, peeked?.getOrNull(start)?.token, thumbs[f.name], settings.muted,
                onView = null,
                modifier = Modifier.fillMaxSize(),
                controls = false,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(Brush.verticalGradient(listOf(Color(0xBB000000), Color.Transparent)))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(FiletIcons.Close, "Close", tint = Color.White, modifier = Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onClose).padding(3.dp))
            Text(f?.name.orEmpty(), color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
            Icon(FiletIcons.Expand, "Full screen", tint = Color.White, modifier = Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onFull).padding(3.dp))
        }
        // `.grip`: the corner that sizes the window.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(26.dp)
                .dragBy(onResize),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Box(Modifier.padding(4.dp).size(12.dp).border(2.dp, Color(0x88FFFFFF), RoundedCornerShape(bottomEnd = 4.dp)))
        }
    }
}

/** What one file shows before it is accepted. */
@Composable
private fun Stage(
    offer: PushOffer,
    f: PushFile,
    token: String?,
    thumb: ImageBitmap?,
    muted: Boolean,
    onView: (() -> Unit)?,
    modifier: Modifier,
    controls: Boolean,
) {
    val colors = Filet.colors
    val kind = IncomingRules.preview(f.name, f.dir)
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            token == null && kind != PreviewKind.NONE -> {
                // Still asking the other phone for its files: the thumbnail, if there is one.
                if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            kind == PreviewKind.PICTURE -> {
                val px = (LocalContext.current.resources.displayMetrics.widthPixels).coerceAtMost(2048)
                val big by produceState<ImageBitmap?>(null, offer.id, token) {
                    value = withContext(Dispatchers.IO) { IncomingPeek.picture(offer.base, offer.key, token!!, px) }
                }
                val shown = big ?: thumb
                if (shown != null) {
                    Image(
                        shown, f.name,
                        Modifier.fillMaxSize().then(if (onView != null) Modifier.clickable(onClick = onView) else Modifier),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            kind == PreviewKind.VIDEO || kind == PreviewKind.SOUND ->
                Playing(IncomingPeek.url(offer.base, offer.key, token!!), video = kind == PreviewKind.VIDEO, muted, controls, onView)
            kind == PreviewKind.TEXT -> {
                val text by produceState<String?>(null, offer.id, token) {
                    value = withContext(Dispatchers.IO) { IncomingPeek.text(offer.base, offer.key, token!!) }
                }
                TextPreview(text, IncomingRules.isMarkdown(f.name))
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(colors.raised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(kindIcon(f), null, tint = colors.fg2, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (f.dir) "A folder: it arrives as a zip" else "No preview for this kind of file",
                    color = colors.fg2, fontSize = 12.sp,
                )
            }
        }
    }
}

/** Video or sound streamed from the sending phone, looping; without sound while [muted]. */
@Composable
private fun Playing(url: String, video: Boolean, muted: Boolean, controls: Boolean, onView: (() -> Unit)?) {
    val context = LocalContext.current
    val colors = Filet.colors
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    var aspect by remember(url) { mutableFloatStateOf(16f / 9f) }
    var playing by remember(url) { mutableStateOf(true) }
    var position by remember(url) { mutableLongStateOf(0L) }
    var duration by remember(url) { mutableLongStateOf(0L) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    // Four times a second, and only while this is on screen: the track and the time.
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it > 0 } ?: 0
            playing = player.isPlaying
            delay(250)
        }
    }
    Box(
        Modifier.fillMaxSize().clickable { if (player.isPlaying) player.pause() else player.play() },
        contentAlignment = Alignment.Center,
    ) {
        if (video) {
            AndroidView(
                factory = { TextureView(it).also { v -> player.setVideoTextureView(v) } },
                modifier = Modifier.aspectRatio(aspect),
                onRelease = { v -> player.clearVideoTextureView(v) },
            )
        } else {
            Text("♫", fontSize = 44.sp, color = colors.accent)
        }
        if (controls) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (playing) FiletIcons.Pause else FiletIcons.Play, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
                    val share = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth(share).height(3.dp).background(colors.accent))
                }
                Spacer(Modifier.width(8.dp))
                Text("${clock(position)} / ${clock(duration)}", color = Color.White, fontSize = 11.sp)
                if (onView != null) {
                    Icon(
                        FiletIcons.Expand, "View", tint = Color.White,
                        modifier = Modifier.padding(start = 8.dp).size(20.dp).clickable(onClick = onView).padding(2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TextPreview(text: String?, markdown: Boolean) {
    val colors = Filet.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF121210))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        if (text == null) return@Column
        for (line in IncomingRules.previewLines(text, markdown)) {
            when (line.style) {
                PreviewLine.Style.HEADING -> Text(line.text, color = Color(0xFFD9D6CF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                PreviewLine.Style.BULLET -> Text("•  " + line.text, color = Color(0xFFD9D6CF), fontSize = 11.sp)
                PreviewLine.Style.PLAIN -> Text(line.text, color = Color(0xFFD9D6CF), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Read only - nothing is saved until Accept.", color = colors.fg3, fontSize = 10.5.sp)
    }
}

/** A tile in the dialog's strip: the picture, or the file's kind. */
@Composable
internal fun PreviewTile(f: PushFile, art: ImageBitmap?, modifier: Modifier) {
    val colors = Filet.colors
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(colors.high), contentAlignment = Alignment.Center) {
        if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(kindIcon(f), null, tint = colors.fg2, modifier = Modifier.size(18.dp))
        if (IncomingRules.isVideo(f.name)) Icon(FiletIcons.Play, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 11.sp,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xAA000000)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun DialogButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = Filet.colors
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(if (primary) colors.accent else colors.raised).clickable(onClick = onClick).padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 13.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            color = if (primary) Color(0xFF0E1418) else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(label, color = Filet.colors.fg2, fontSize = 12.sp)
    }
}

/** The offer's files with the tokens the other phone serves them by; null until it has answered. */
@Composable
private fun rememberPeek(offer: PushOffer) = produceState<List<IncomingPeek.Peeked>?>(null, offer.id) {
    value = withContext(Dispatchers.IO) { IncomingPeek.files(offer.base, offer.key) }
}

/** A sideways swipe past [SWIPE_PX] moves to the next or the previous file. */
private fun Modifier.swipeBetween(count: Int, index: () -> Int, set: (Int) -> Unit): Modifier =
    if (count < 2) this else composed {
        var dx by remember { mutableFloatStateOf(0f) }
        draggable(
            rememberDraggableState { d -> dx += d },
            Orientation.Horizontal,
            onDragStopped = {
                val step = when {
                    dx < -SWIPE_PX -> 1
                    dx > SWIPE_PX -> -1
                    else -> 0
                }
                dx = 0f
                if (step != 0) set((index() + step).coerceIn(0, count - 1))
            },
        )
    }

/** Drags reported as they happen, for the floating window's move and resize. */
private fun Modifier.dragBy(onDrag: (Float, Float) -> Unit): Modifier = composed {
    val latest = rememberUpdatedState(onDrag)
    pointerInput(Unit) {
        detectDragGestures { change, amount ->
            change.consume()
            latest.value(amount.x, amount.y)
        }
    }
}

/**
 * Swiped toward the edge it came from, it goes, and the send waits on the Share page as the
 * close button leaves it. A short drag springs back.
 */
@Composable
internal fun Swipeable(onGone: () -> Unit, content: @Composable () -> Unit) {
    var dx by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    Box(
        Modifier
            .offset { IntOffset(dx.roundToInt(), 0) }
            .draggable(
                rememberDraggableState { d -> dx = (dx + d).coerceAtLeast(0f) },
                Orientation.Horizontal,
                onDragStopped = { if (dx > width * 0.35f) onGone() else dx = 0f },
            )
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) },
    ) { content() }
}

@Composable
internal fun SideCard(
    offer: PushOffer,
    onAccept: (Boolean) -> Unit,
    onDecline: () -> Unit,
    onClose: () -> Unit,
    onView: (Int) -> Unit,
) {
    val colors = Filet.colors
    var always by remember(offer.id) { mutableStateOf(false) }
    val thumbs = rememberThumbs(offer)
    Column(
        Modifier
            .padding(end = 8.dp, bottom = 16.dp, start = 16.dp)
            .width(256.dp)
            .shadow(14.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(CARD_TOP, CARD_BOTTOM)))
            .border(1.dp, CARD_LINE, RoundedCornerShape(20.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF9CC3D2), Color(0xFF4E7787)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(IncomingRules.initial(offer.name), color = Color(0xFF0E1418), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(offer.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(IncomingRules.wants(offer.files.size), fontSize = 11.sp, color = colors.fg2)
            }
            Icon(
                FiletIcons.Close, "Later",
                tint = colors.fg3,
                modifier = Modifier.align(Alignment.Top).size(22.dp).clip(CircleShape).clickable(onClick = onClose).padding(4.dp),
            )
        }
        Row(Modifier.padding(top = 10.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val n = IncomingRules.tiles(offer.files.size)
            for (i in 0 until n) {
                val more = if (i == n - 1) IncomingRules.moreOnLast(offer.files.size) else 0
                Tile(offer.files[i], thumbs[offer.files[i].name], more, Modifier.weight(1f)) { onView(i) }
            }
            // One or two files keep their tiles the size three would have.
            for (i in n until 3) Spacer(Modifier.weight(1f))
        }
        Text(
            IncomingRules.summary(offer.files) { humanSize(it) },
            fontSize = 11.sp, color = colors.fg2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton("Decline", primary = false, Modifier.weight(1f), onDecline)
            CardButton("Accept", primary = true, Modifier.weight(1f)) { onAccept(always) }
        }
        // A phone that has not paired cannot be trusted by name, so the tick is not offered.
        if (offer.paired) {
            Row(
                Modifier.padding(top = 9.dp).clip(RoundedCornerShape(6.dp)).clickable { always = !always }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (always) colors.accent else Color.Transparent)
                        .border(1.5.dp, if (always) colors.accent else colors.fg3, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (always) Icon(FiletIcons.Check, null, tint = Color(0xFF10100F), modifier = Modifier.size(10.dp))
                }
                Spacer(Modifier.width(7.dp))
                Text("Always accept from this phone", fontSize = 11.sp, color = colors.fg2)
            }
        }
    }
}

/** `.mini`: one line, one tile, Get or dismiss. */
@Composable
internal fun MiniCard(offer: PushOffer, onGet: () -> Unit, onClose: () -> Unit, onView: (Int) -> Unit) {
    val colors = Filet.colors
    val f = offer.files.first()
    val thumbs = rememberThumbs(offer)
    Row(
        Modifier
            .padding(end = 8.dp, bottom = 12.dp, start = 12.dp)
            .width(256.dp)
            .shadow(10.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(CARD_TOP, CARD_BOTTOM)))
            .border(1.dp, CARD_LINE, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile(f, thumbs[f.name], 0, Modifier.size(40.dp), eye = false) { onView(0) }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name + if (f.dir) ".zip" else "", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(offer.name).append(" · fast share")
                    if (!f.dir && f.size > 0) append(" · ").append(humanSize(f.size))
                },
                fontSize = 10.5.sp, color = colors.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(
            "Get",
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0E1418),
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(colors.accent).clickable(onClick = onGet).padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Icon(
            FiletIcons.Close, "Later",
            tint = colors.fg3,
            modifier = Modifier.padding(start = 4.dp).size(24.dp).clip(CircleShape).clickable(onClick = onClose).padding(4.dp),
        )
    }
}

@Composable
internal fun CardButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = Filet.colors
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) colors.accent else colors.raised)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 13.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            color = if (primary) Color(0xFF0E1418) else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** `.th`: the picture when the sender gave one, else the file's kind; ▶ on a video, "+N" for the rest. */
@Composable
internal fun Tile(
    f: PushFile,
    art: ImageBitmap?,
    more: Int,
    modifier: Modifier,
    eye: Boolean = true,
    onView: () -> Unit,
) {
    val colors = Filet.colors
    // A tap views the file before anything is saved (`.th .eye` in the mock). A folder has
    // nothing to show until it arrives; the tile standing for the rest opens on its own file,
    // and the viewer swipes on from there.
    val viewable = IncomingRules.preview(f.name, f.dir) != PreviewKind.NONE || more > 0
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.high)
            .then(if (viewable) Modifier.clickable(onClick = onView) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val icon = if (f.dir) FiletIcons.Folder else FileKind.of(VNode(VPath.of("local", "/" + f.name), isDir = false, size = f.size, mtime = 0)).icon
            Icon(icon, null, tint = colors.fg2, modifier = Modifier.size(18.dp))
        }
        if (more == 0 && IncomingRules.isVideo(f.name)) {
            Icon(FiletIcons.Play, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        if (more > 0) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Text("+$more", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
            }
        } else if (eye && viewable) {
            Text(
                "View",
                fontSize = 9.sp, color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xAA000000))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** Thumbnails asked of the sending phone, by file name; empty until they arrive. */
@Composable
internal fun rememberThumbs(offer: PushOffer): Map<String, ImageBitmap> {
    val px = (64 * LocalContext.current.resources.displayMetrics.density).roundToInt()
    val state = produceState<Map<String, ImageBitmap>>(emptyMap(), offer.id) {
        val wanted = offer.files.take(3).filter { !it.dir && IncomingRules.hasThumb(it.name) }
        if (wanted.isEmpty()) return@produceState
        value = withContext(Dispatchers.IO) { IncomingPeek.thumbs(offer.base, offer.key, wanted.map { it.name }.toSet(), px) }
    }
    return state.value
}

/** The card's ground: drawn dark whatever the theme, since it floats over apps of any colour. */
internal val CARD_TOP = Color(0xFF22262A)
internal val CARD_BOTTOM = Color(0xFF1C1F21)
internal val CARD_LINE = Color(0xFF39444A)

private fun kindIcon(f: PushFile) =
    if (f.dir) FiletIcons.Folder else FileKind.of(VNode(VPath.of("local", "/" + f.name), isDir = false, size = f.size, mtime = 0)).icon

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private const val SWIPE_PX = 120f

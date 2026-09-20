package dev.niccc2007.filet.handlers

import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import android.media.AudioManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The video player.
 *
 * What was here was `android.widget.MediaController` bolted onto a `VideoView`, which is where
 * "odd looking and old" came from - it is the 2010 stock chrome, it draws in the platform's
 * colours rather than Filet's, and its seek bar only responds if you land on a thumb the width
 * of a pencil. All of that is gone. The chrome below is Filet's, and the gestures are the ones
 * The requirement: drag anywhere on the picture to scrub with the position following the finger,
 * double tap the left to go back and the right to go forward.
 *
 * `VideoView` stays as the surface. It is a thin wrapper over `MediaPlayer` that already
 * handles the surface lifecycle correctly, and replacing it with a raw `SurfaceView` would be
 * a few hundred lines to end up in the same place. Only its controller is replaced.
 *
 * The gesture arithmetic is in `SeekGesture.kt` and tested there.
 */

/** How long the chrome stays up after a touch while something is playing. */
private const val CHROME_MS = 3_200L

/** How long the "-10s" flash stays up after the last double tap of a run. */
private const val FLASH_MS = 700L

/**
 * The handle, kept out of Compose state so exactly one place starts and stops it.
 *
 * It holds the `MediaPlayer` as well as the view, and that is the whole fix for the seek
 * measured. `VideoView.seekTo(int)` goes to the nearest keyframe BEHIND the target, so a
 * stream with a keyframe every five seconds turns a drag to 12.5s into a jump to 10s - which
 * reads as a control that quantises rather than one that follows your finger. `MediaPlayer`
 * has taken a mode since API 26 and this app floors at 26, so the exact seek is simply
 * available and was not being asked for.
 */
private class VideoHandle {
    var view: VideoView? = null
    var player: android.media.MediaPlayer? = null

    /**
     * Seek to [ms] itself, not to the keyframe before it.
     *
     * SEEK_CLOSEST decodes forward from the preceding keyframe to land on the exact frame. It
     * costs more than a sync seek, and it is what a scrub bar is supposed to feel like.
     */
    fun seekExactly(ms: Long) {
        val target = ms.coerceAtLeast(0L)
        val mp = player
        if (mp != null) {
            runCatching { mp.seekTo(target, android.media.MediaPlayer.SEEK_CLOSEST) }
                .onFailure { view?.seekTo(target.toInt()) }
        } else {
            view?.seekTo(target.toInt())
        }
    }
}

/**
 * How big a preview frame is decoded.
 *
 * Small on purpose: it is drawn about 150dp wide, and decoding a 4K frame to throw most of it
 * away is the difference between a preview that keeps up with a drag and one that does not.
 */
private const val PREVIEW_W = 320
private const val PREVIEW_H = 180

@Composable
fun VideoScreen(vm: BrowserViewModel, node: VNode) {
    val colors = Filet.colors
    val handle = remember(node.path) { VideoHandle() }

    var uri by remember(node.path) { mutableStateOf<Uri?>(null) }
    var error by remember(node.path) { mutableStateOf<String?>(null) }
    var duration by remember(node.path) { mutableLongStateOf(0L) }
    var position by remember(node.path) { mutableLongStateOf(0L) }
    var buffered by remember(node.path) { mutableIntStateOf(0) }
    var playing by remember(node.path) { mutableStateOf(false) }
    var ready by remember(node.path) { mutableStateOf(false) }
    var scrubTo by remember(node.path) { mutableStateOf<Long?>(null) }
    var chromeVisible by remember(node.path) { mutableStateOf(true) }

    // The playback zoom. Same value type as the image viewer, so the arithmetic and its tests
    // are shared rather than written twice and drifting - see ZoomState.kt.
    var picture by remember(node.path) { mutableStateOf(ZoomView.NONE) }
    var chromeTouchedAt by remember(node.path) { mutableLongStateOf(System.currentTimeMillis()) }
    var run by remember(node.path) { mutableStateOf<SeekRun?>(null) }
    var flashAt by remember(node.path) { mutableLongStateOf(0L) }
    var boxWidth by remember(node.path) { mutableIntStateOf(0) }
    // Off means follow the phone, which is what it did before and is still the default.
    var lockedOrientation by remember(node.path) { mutableStateOf<Int?>(null) }
    var repeating by remember(node.path) { mutableStateOf(true) }
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    // Brightness and volume, dragged on the picture. See PlayerGesture for which side is which
    // and why: every other player puts brightness left and volume right.
    val audio = remember(activity) {
        activity?.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
    }
    val maxVolume = remember(audio) {
        runCatching { audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0 }.getOrDefault(0)
    }
    /** What a drag is currently changing, and where it has got to, for the readout. */
    var levelKind by remember(node.path) { mutableStateOf(PlayerGesture.Drag.NONE) }
    var levelShown by remember(node.path) { mutableFloatStateOf(0f) }
    /**
     * The window's brightness override, NOT the system setting.
     *
     * Per-window on purpose: writing the system brightness needs a permission worth asking for
     * nothing, and would leave the phone dimmed after the video closed - a fault nobody would
     * think to blame on a file manager.
     */
    var windowBrightness by remember(node.path) { mutableFloatStateOf(-1f) }

    /**
     * The frame shown above the bar while it is being dragged.
     *
     * Kept as the frame's own timestamp beside the bitmap, so a decode that lands after the
     * finger has moved on can be recognised as stale rather than drawn.
     */
    var previewFrame by remember(node.path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var previewAt by remember(node.path) { mutableStateOf<Long?>(null) }

    fun applyBrightness(level: Float) {
        windowBrightness = level
        activity?.window?.let { w ->
            w.attributes = w.attributes.also { it.screenBrightness = level }
        }
    }

    // Hand the screen back when the video closes, whichever way it closed.
    DisposableEffect(node.path) {
        onDispose {
            activity?.window?.let { w ->
                w.attributes = w.attributes.also {
                    it.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    fun wake() {
        chromeVisible = true
        chromeTouchedAt = System.currentTimeMillis()
    }

    fun seekTo(target: Long) {
        handle.seekExactly(target)
        position = target
    }

    LaunchedEffect(node.path) {
        val u = runCatching { vm.localUriFor(node) }.getOrNull()
        if (u == null) {
            error = "This file has to be copied to local storage before it can play. " +
                "Copy it somewhere local first."
        } else {
            uri = u
        }
    }

    // Position ticker, and the auto-hide clock, on one loop. Two loops would be two clocks to
    // keep in step for no gain.
    LaunchedEffect(playing, ready, node.path) {
        while (ready) {
            val v = handle.view
            if (v != null && scrubTo == null) position = v.currentPosition.toLong().coerceAtLeast(0L)
            if (v != null) buffered = v.bufferPercentage
            if (playing && chromeVisible && System.currentTimeMillis() - chromeTouchedAt > CHROME_MS) {
                chromeVisible = false
            }
            delay(200)
        }
    }

    // The scrub preview.
    //
    // Keyed on the QUANTISED frame, which is what makes this affordable: a drag reports
    // hundreds of positions a second, and keying on the raw position would start a decode for
    // every one of them. Snapping to ScrubPreview.STEP_MS means a slow drag sits on one frame,
    // and changing frames cancels the decode that is running - so an overtaken frame is
    // abandoned rather than finished and drawn late.
    val wantedFrame = scrubTo?.let { ScrubPreview.frameFor(it, duration) }
    LaunchedEffect(wantedFrame, uri) {
        val want = wantedFrame
        val source = uri
        if (want == null || source == null) return@LaunchedEffect
        if (!ScrubPreview.shouldDecode(previewAt, null, want)) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(context, source)
                    // Scaled by the platform where it can: decoding a 4K frame to throw most
                    // of it away is the difference between a preview that keeps up and one
                    // that does not.
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        r.getScaledFrameAtTime(
                            want * 1000,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            PREVIEW_W, PREVIEW_H,
                        )
                    } else {
                        r.getFrameAtTime(want * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                } finally {
                    runCatching { r.release() }
                }
            }.getOrNull()
        }
        if (decoded != null) {
            previewFrame = decoded.asImageBitmap()
            previewAt = want
        }
    }

    // The seek flash fades on its own so a run of taps reads as one gesture rather than a
    // label that sticks around after the video has moved on.
    LaunchedEffect(flashAt) {
        if (flashAt > 0L) {
            delay(FLASH_MS)
            if (System.currentTimeMillis() - flashAt >= FLASH_MS) run = null
        }
    }

    // Repeat is a live setting, not a start-up one: turning it on halfway through a clip has
    // to take effect on that clip.
    LaunchedEffect(repeating, ready) {
        runCatching { handle.player?.isLooping = repeating }
    }

    // A locked orientation belongs to the viewer, not to the app. Leaving it set would rotate
    // the file list on the way back out, which reads as the app having broken.
    DisposableEffect(lockedOrientation) {
        activity?.requestedOrientation =
            lockedOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { }
    }

    DisposableEffect(handle) {
        onDispose {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            runCatching {
                handle.view?.stopPlayback()
                handle.view = null
                handle.player = null
            }
        }
    }

    val shownPosition = scrubTo ?: position
    // Paused counts as up: a paused video with no controls looks like a frozen one.
    val chromeUp = error == null && (chromeVisible || !playing)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { boxWidth = it.width }
    ) {
        when {
            error != null -> Text(
                error!!,
                fontSize = 13.sp,
                color = colors.fg2,
                modifier = Modifier.align(Alignment.Center).padding(28.dp),
            )

            uri == null -> CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(26.dp), strokeWidth = 2.dp,
            )

            else -> AndroidView(
                // The zoom lives HERE and only here. The requirement is explicit: "the
                // controls are still on the proper size and orientation.. so it's like I'm only
                // zooming the playback". The transport, the seek bar, the flash readout and the
                // top chrome are all siblings of this surface rather than children of it, so
                // none of them inherits the transform and none of them needs to opt out of it.
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = picture.scale; scaleY = picture.scale
                        translationX = picture.offsetX; translationY = picture.offsetY
                    },
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(uri)
                        // No setMediaController: the whole point of this rewrite.
                        setOnPreparedListener { mp ->
                            handle.player = mp
                            mp.isLooping = repeating
                            duration = mp.duration.toLong().coerceAtLeast(0L)
                            ready = true
                            mp.start()
                            playing = true
                        }
                        setOnCompletionListener {
                            // Only reached when repeat is off: a looping MediaPlayer never
                            // completes. Rewinding rather than sitting on a black last frame,
                            // so the play button does what it says.
                            playing = false
                            chromeVisible = true
                            position = 0L
                            handle.seekExactly(0L)
                        }
                        setOnErrorListener { _, what, extra ->
                            error = "This video would not play (error $what/$extra). " +
                                "Try opening it in another app."
                            ready = false
                            true
                        }
                    }.also { handle.view = it }
                },
            )
        }

        // The chrome and the gestures are laid out, not stacked.
        //
        // They used to be two full-screen siblings, and a drag along the scrub bar was then
        // delivered to both: the bar mapped it absolutely, the picture mapped it relatively,
        // and whichever wrote last won. Dragging the bar from three quarters along to a fifth
        // moved the video by the DISTANCE rather than to the place under the finger. Giving
        // the gestures only the band between the bars means one touch has exactly one owner,
        // which is a layout fact rather than a race that happens to come out right.
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = chromeUp, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OverlayAction(FiletIcons.Back, "Close") { vm.closeHandler() }
                    Text(
                        node.name,
                        color = Color.White,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    )
                    OverlayAction(
                        FiletIcons.Repeat,
                        if (repeating) "Repeat is on" else "Repeat is off",
                        active = repeating,
                    ) { repeating = !repeating; wake() }
                    OverlayAction(FiletIcons.Expand, orientationLabel(lockedOrientation)) {
                        // Three states rather than a toggle: follow the phone, hold landscape,
                        // hold portrait. A two-state button cannot express "stop rotating",
                        // which is the thing you want while lying down.
                        lockedOrientation = nextOrientation(lockedOrientation)
                        wake()
                    }
                    OverlayAction(FiletIcons.Link, "Open in another app") {
                        vm.openExternally(node, force = true)
                    }
                    OverlayAction(FiletIcons.Share, "Share") { vm.shareOne(node) }
                    OverlayAction(FiletIcons.Info, "Properties") { vm.showProperties(node) }
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(node.path, duration) {
                        detectTapGestures(
                            onTap = { if (chromeVisible) chromeVisible = false else wake() },
                            onDoubleTap = { at ->
                                val zone = tapZone(at.x, size.width.toFloat())
                                if (zone == TapZone.MIDDLE) {
                                    val v = handle.view
                                    if (v != null) {
                                        if (playing) v.pause() else v.start()
                                        playing = !playing
                                    }
                                    wake()
                                } else {
                                    val next = advanceRun(run, zone, System.currentTimeMillis())
                                    run = next
                                    flashAt = System.currentTimeMillis()
                                    seekTo(doubleTapTarget(position, duration, zone, next.taps))
                                    // Deliberately no wake(): the gesture exists so the picture
                                    // can be moved without the chrome coming back over it.
                                }
                            },
                        )
                    }
                    // Last, so a drag beats the tap detector on the main pass.
                    //
                    // One detector for all three drags, not three. Sideways seeks; up and down
                    // on the left is brightness and on the right is volume, which is what every
                    // other player does. Separate detectors would each claim the gesture and
                    // whichever ran first would eat the others.
                    .pointerInput(node.path, duration) {
                        var startX = 0f
                        var startY = 0f
                        var startPosition = 0L
                        var startLevel = 0f
                        var kind = PlayerGesture.Drag.NONE
                        detectDragGestures(
                            onDragStart = { at ->
                                startX = at.x
                                startY = at.y
                                startPosition = position
                                kind = PlayerGesture.Drag.NONE
                                wake()
                            },
                            onDragEnd = {
                                if (kind == PlayerGesture.Drag.SEEK) scrubTo?.let { seekTo(it) }
                                scrubTo = null
                                kind = PlayerGesture.Drag.NONE
                                levelKind = PlayerGesture.Drag.NONE
                                wake()
                            },
                            onDragCancel = {
                                scrubTo = null
                                kind = PlayerGesture.Drag.NONE
                                levelKind = PlayerGesture.Drag.NONE
                            },
                        ) { change, _ ->
                            change.consume()
                            val dx = change.position.x - startX
                            val dy = change.position.y - startY
                            // Decided once, from the travel so far, then held for the rest of
                            // the gesture. Deciding again every frame is what makes a slightly
                            // diagonal drag flicker between seeking and changing the volume.
                            if (kind == PlayerGesture.Drag.NONE) {
                                kind = PlayerGesture.drag(startX, dx, dy, size.width.toFloat())
                                startLevel = when (kind) {
                                    PlayerGesture.Drag.BRIGHTNESS ->
                                        // A window that has never been overridden reports -1,
                                        // which is "follow the system". Start from the middle
                                        // rather than from nothing, so the first drag moves
                                        // from something visible.
                                        if (windowBrightness in 0f..1f) windowBrightness else 0.5f
                                    PlayerGesture.Drag.VOLUME -> PlayerGesture.volumeLevel(
                                        runCatching {
                                            audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                                        }.getOrDefault(0),
                                        maxVolume,
                                    )
                                    else -> 0f
                                }
                            }
                            when (kind) {
                                PlayerGesture.Drag.SEEK -> if (duration > 0L) {
                                    scrubTo = PlayerGesture.seekAfter(
                                        startPosition, dx, size.width.toFloat(), duration,
                                    )
                                }
                                PlayerGesture.Drag.BRIGHTNESS -> {
                                    val level = PlayerGesture.levelAfter(startLevel, dy, size.height.toFloat())
                                    applyBrightness(level)
                                    levelKind = PlayerGesture.Drag.BRIGHTNESS
                                    levelShown = level
                                }
                                PlayerGesture.Drag.VOLUME -> {
                                    val level = PlayerGesture.levelAfter(startLevel, dy, size.height.toFloat())
                                    runCatching {
                                        audio?.setStreamVolume(
                                            AudioManager.STREAM_MUSIC,
                                            PlayerGesture.volumeSteps(level, maxVolume),
                                            0,
                                        )
                                    }
                                    levelKind = PlayerGesture.Drag.VOLUME
                                    levelShown = level
                                }
                                PlayerGesture.Drag.NONE -> Unit
                            }
                        }
                    }
                    // Pinch, and ONLY pinch.
                    //
                    // `detectTransformGestures` cannot be used here: it reports single-finger
                    // pan as well, which is the scrub gesture directly above, and whichever ran
                    // first would eat the other. So this loop waits for a second finger before
                    // it engages and consumes nothing until it has one - a one-finger drag never
                    // reaches it, and a two-finger pinch never reaches the scrubber.
                    .pointerInput(node.path, duration) {
                        awaitPointerEventScope {
                            while (true) {
                                var event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } < 2) continue

                                var zoom = 1f
                                var pan = Offset.Zero
                                do {
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed >= 2) {
                                        zoom = event.calculateZoom()
                                        pan = event.calculatePan()
                                        if (zoom != 1f || pan != Offset.Zero) {
                                            picture = picture.pinched(
                                                zoom, pan.x, pan.y,
                                                size.width.toFloat(), size.height.toFloat(),
                                            )
                                            // Consumed only once it is definitely a pinch, so a
                                            // second finger landing by accident does not swallow
                                            // the tap that was already in flight.
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                    event = awaitPointerEvent()
                                } while (event.changes.any { it.pressed })
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Inside the gesture band, so the buttons sit over the picture and the
                // gestures still have the whole band to themselves when they are hidden.
                CentreTransport(
                    visible = chromeUp && ready,
                    playing = playing,
                    onBack = {
                        seekTo(doubleTapTarget(position, duration, TapZone.BACK))
                        wake()
                    },
                    onForward = {
                        seekTo(doubleTapTarget(position, duration, TapZone.FORWARD))
                        wake()
                    },
                    onToggle = {
                        val v = handle.view ?: return@CentreTransport
                        if (playing) v.pause() else v.start()
                        playing = !playing
                        wake()
                    },
                )

                // The flash and the readout belong to the picture, so they live in its band.
                val activeRun = run
                if (activeRun != null && activeRun.zone != TapZone.MIDDLE) {
                    Box(
                        Modifier.fillMaxSize().padding(horizontal = 34.dp),
                        contentAlignment =
                            if (activeRun.zone == TapZone.BACK) Alignment.CenterStart
                            else Alignment.CenterEnd,
                    ) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (activeRun.zone == TapZone.BACK) FiletIcons.Back
                                else FiletIcons.Forward,
                                null, tint = Color.White, modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                "${if (activeRun.zone == TapZone.BACK) "-" else "+"}" +
                                    "${activeRun.taps * 10}s",
                                color = Color.White, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                // Brightness or volume, while a drag is changing it. A vertical bar rather
                // than a number: the value matters far less than seeing that it is moving and
                // which way, and a percentage invites reading rather than adjusting.
                if (levelKind != PlayerGesture.Drag.NONE) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (levelKind == PlayerGesture.Drag.BRIGHTNESS) FiletIcons.Brightness
                            else FiletIcons.Volume,
                            null, tint = Color.White, modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Box(
                            Modifier
                                .width(74.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.25f)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(levelShown.coerceIn(0f, 1f))
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(colors.accent),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${(levelShown.coerceIn(0f, 1f) * 100).roundToInt()}",
                            color = Color.White, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                if (scrubTo != null) {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // The frame under the finger. Held on screen while the next one
                        // decodes rather than blanked, because a preview that flickers to
                        // black between frames is worse than one that lags a little.
                        previewFrame?.let { frame ->
                            Image(
                                frame,
                                contentDescription = null,
                                modifier = Modifier
                                    .width(150.dp)
                                    .height(84.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            clockOf(shownPosition),
                            color = Color.White, fontSize = 21.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        val delta = shownPosition - position
                        Text(
                            (if (delta >= 0) "+" else "-") + clockOf(abs(delta)),
                            color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = chromeUp, enter = fadeIn(), exit = fadeOut()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    ScrubBar(
                        positionMs = shownPosition,
                        durationMs = duration,
                        enabled = duration > 0,
                        bufferedFraction = buffered / 100f,
                        onScrub = { scrubTo = it; wake() },
                        onScrubEnd = { target ->
                            scrubTo = null
                            seekTo(target)
                            wake()
                        },
                    )
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Text(
                            clockOf(shownPosition),
                            color = Color.White, fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            remainingOf(shownPosition, duration),
                            color = Color.White.copy(alpha = 0.8f), fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

    }
}

/**
 * Back ten, play or pause, forward ten.
 *
 * Its own composable because it is drawn inside a Box that is inside a Column, and an
 * `AnimatedVisibility` written there resolves against the Column's scope while the Box's is
 * the one in hand. A function gives it a scope of its own, which is also where it belongs.
 */
@Composable
private fun CentreTransport(
    visible: Boolean,
    playing: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onToggle: () -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayRound(FiletIcons.Back, "Back 10 seconds", 48.dp, onClick = onBack)
            OverlayRound(
                if (playing) FiletIcons.Pause else FiletIcons.PlaySolid,
                if (playing) "Pause" else "Play",
                66.dp,
                primary = true,
                onClick = onToggle,
            )
            OverlayRound(FiletIcons.Forward, "Forward 10 seconds", 48.dp, onClick = onForward)
        }
    }
}

/**
 * Follow the phone, hold landscape, hold portrait, and back to following.
 *
 * Null is "follow", which is also what the viewer is restored to on the way out - a lock left
 * behind would rotate the file list and read as the app having broken.
 */
private fun nextOrientation(current: Int?): Int? = when (current) {
    null -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ->
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    else -> null
}

private fun orientationLabel(current: Int?): String = when (current) {
    null -> "Rotation follows the phone"
    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "Held landscape"
    else -> "Held portrait"
}

@Composable
private fun OverlayAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Icon(
        icon, label, tint = if (active) Filet.colors.accent else Color.White,
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(7.dp),
    )
}

@Composable
private fun OverlayRound(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: androidx.compose.ui.unit.Dp,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color.White.copy(alpha = if (primary) 0.22f else 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

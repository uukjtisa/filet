package dev.niccc2007.filet.handlers

import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The video player.
 *
 * What was here was `android.widget.MediaController` bolted onto a `VideoView`, which is where
 * "odd looking and old" came from - it is the 2010 stock chrome, it draws in the platform's
 * colours rather than Filet's, and its seek bar only responds if you land on a thumb the width
 * of a pencil. All of that is gone. The chrome below is Filet's, and the gestures are the ones
 * Nic asked for: drag anywhere on the picture to scrub with the position following the finger,
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
 * It holds the `MediaPlayer` as well as the view, and that is the whole fix for the seek Nic
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
    var chromeTouchedAt by remember(node.path) { mutableLongStateOf(System.currentTimeMillis()) }
    var run by remember(node.path) { mutableStateOf<SeekRun?>(null) }
    var flashAt by remember(node.path) { mutableLongStateOf(0L) }
    var boxWidth by remember(node.path) { mutableIntStateOf(0) }
    // Off means follow the phone, which is what it did before and is still the default.
    var lockedOrientation by remember(node.path) { mutableStateOf<Int?>(null) }
    var repeating by remember(node.path) { mutableStateOf(true) }
    val activity = LocalContext.current as? android.app.Activity

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
                modifier = Modifier.fillMaxSize(),
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
                    .pointerInput(node.path, duration) {
                        var startX = 0f
                        var startPosition = 0L
                        detectDragGestures(
                            onDragStart = { at ->
                                startX = at.x
                                startPosition = position
                                wake()
                            },
                            onDragEnd = {
                                scrubTo?.let { seekTo(it) }
                                scrubTo = null
                                wake()
                            },
                            onDragCancel = { scrubTo = null },
                        ) { change, _ ->
                            change.consume()
                            if (duration > 0L) {
                                // Relative here, unlike the bar: the finger did not land on a
                                // playhead, so an absolute map would jump the video to wherever
                                // the thumb happened to be before it moved at all. A full width
                                // of travel covers the whole file.
                                val travelled = (change.position.x - startX) / size.width.toFloat()
                                scrubTo = (startPosition + (travelled * duration).toLong())
                                    .coerceIn(0L, duration)
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

                if (scrubTo != null) {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
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

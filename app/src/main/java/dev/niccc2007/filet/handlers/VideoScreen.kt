package dev.niccc2007.filet.handlers

import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

/**
 * How long the chrome stays up after a touch.
 *
 * Not "while something is playing" any more. See [ViewerChrome]: a paused frame being studied is
 * exactly the case where the controls are most in the way, and the old rule kept them up for it.
 */
private const val CHROME_MS = ViewerChrome.IDLE_MS

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
 * Small on purpose, and now doubly so: a whole storyboard of these is held in memory while the
 * player is open, so the size is multiplied by the storyboard frame count. At this size and
 * colour depth that is a few megabytes, freed when the video closes. Drawn about 150dp wide,
 * so it is soft rather than sharp - which every other player scrub preview is too.
 */
private const val PREVIEW_W = 160
private const val PREVIEW_H = 90

/**
 * Holds one `MediaMetadataRetriever` open for as long as the player is on screen.
 *
 * Opening one is expensive - it reads and parses the container's headers - so doing it per
 * previewed frame is what made scrubbing stutter. Closed from a DisposableEffect, which runs
 * however the screen goes away.
 */
private class PreviewSource {
    private var retriever: android.media.MediaMetadataRetriever? = null
    private var openedFor: Uri? = null

    fun get(context: android.content.Context, uri: Uri): android.media.MediaMetadataRetriever? {
        retriever?.let { if (openedFor == uri) return it }
        close()
        return runCatching {
            android.media.MediaMetadataRetriever().also {
                it.setDataSource(context, uri)
                retriever = it
                openedFor = uri
            }
        }.getOrNull()
    }

    fun close() {
        runCatching { retriever?.release() }
        retriever = null
        openedFor = null
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

    /**
     * True from the moment Play as audio is pressed until this screen goes away.
     *
     * It used to be a flag read by `onDispose`: the button closed the screen and the teardown
     * started the music. That is what made the switch "cut" - the screen vanished instantly,
     * the file list appeared behind it for a beat, and the audio arrived a prepare later at a
     * position captured before any of it, so the first thing you heard was a few hundred
     * milliseconds you had already heard.
     *
     * Now the button runs the hand-off and the screen waits for it. Three things overlap
     * instead of following each other:
     *
     *  - the video keeps DECODING while the audio player prepares, so its position is live
     *    and `handOffFromVideo` can read it at the last instant before the sound starts;
     *  - its volume ramps to nothing across [HANDOFF_FADE_MS], so there is no double audio
     *    and no hard stop either;
     *  - a veil fades up over the picture, so the swap to the music face is a dissolve.
     *
     * The screen is not closed at all. `MediaScreen` routes on `Playback.asAudio`, which
     * `handOffFromVideo` sets once the sound is actually running - so the composition swaps
     * itself to the music face in place, and "watch" to "listen" stays on one screen instead
     * of going out to the file list and back.
     */
    var handingOver by remember(node.path) { mutableStateOf(false) }

    /** 0 while watching, 1 once the veil is fully up. Drives the dissolve and the volume. */
    val handOff by animateFloatAsState(
        if (handingOver) 1f else 0f,
        animationSpec = tween(HANDOFF_FADE_MS),
        label = "hand-off",
    )

    // The ramp, applied every frame the animation produces. A `setVolume` per frame is a
    // cheap call on an already-running MediaPlayer - far cheaper than the alternative, which
    // is cutting the sound dead and hoping the next player is quick.
    LaunchedEffect(handOff) {
        if (handingOver) {
            runCatching {
                val v = (1f - handOff).coerceIn(0f, 1f)
                handle.player?.setVolume(v, v)
            }
        }
    }

    /** The overflow menu, which now holds everything that is neither a mode nor frequent. */
    var overflow by remember(node.path) { mutableStateOf(false) }

    /**
     * Playback speed, and the sheet that sets it.
     *
     * The same control the music player grew, because a speed is a speed and having it on
     * one of the two players is the kind of gap that reads as an oversight.
     *
     * The sheet is the same composable; only the player it drives is different - this screen
     * owns its own `MediaPlayer` inside a `VideoView`, which is also why the speed is local
     * here and lives on the shared player there.
     *
     * The PITCH mode is not local. It is the same preference the music player and Settings
     * read, because "what happens to a voice when I slow it down" is one answer, not one per
     * screen - the same rule Uninterrupted follows.
     */
    var speed by remember(node.path) { mutableFloatStateOf(1f) }
    var speedOpen by remember(node.path) { mutableStateOf(false) }
    var speedCeiling by remember(node.path) { mutableFloatStateOf(dev.niccc2007.filet.playback.MAX_SPEED) }
    val pitchMode = dev.niccc2007.filet.playback.PitchMode.of(
        vm.prefs.pitchMode.collectAsStateWithLifecycle().value,
    )

    // Applied to the running decoder, and the refusal is handled rather than thrown away.
    //
    // A rate Android will not resample throws from `setPlaybackParams`; left uncaught that is
    // a crash in the middle of a drag. Caught, it is the device telling us where its ceiling
    // is, so the rail shortens to what this phone can actually do instead of keeping a
    // position that plays nothing.
    LaunchedEffect(speed, pitchMode, ready) {
        val mp = handle.player ?: return@LaunchedEffect
        val wasPlaying = playing
        val ok = runCatching {
            mp.playbackParams = mp.playbackParams
                .setSpeed(speed)
                .setPitch(pitchMode.pitchFor(speed))
        }.isSuccess
        if (!ok) {
            if (speed > 1f) {
                speedCeiling = maxOf(1f, speed - dev.niccc2007.filet.playback.SPEED_BACKOFF)
                speed = speedCeiling
            } else {
                speed = 1f
            }
            return@LaunchedEffect
        }
        // Documented quirk: on some devices writing `playbackParams` STARTS a paused player.
        if (!wasPlaying) runCatching { mp.pause() }
    }

    /**
     * The SAME preference the music player and Settings read.
     *
     * One value with three doors, not three switches: turning it on here has to be on when
     * the next track starts in the other player, and has to be what Settings shows.
     */
    val uninterrupted by vm.prefs.uninterrupted.collectAsStateWithLifecycle()
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
    /**
     * The extracted storyboard: frame timestamp to picture.
     *
     * Filled once in the background and read during the drag. Nothing is decoded while the
     * finger is down, which is the whole point - see Storyboard.
     */
    val storyboard = remember(node.path) { androidx.compose.runtime.mutableStateMapOf<Long, androidx.compose.ui.graphics.ImageBitmap>() }

    /**
     * The one retriever the previews share, opened lazily on the first scrub.
     *
     * Not opened up front: most videos are watched without ever touching the bar, and opening
     * a second decoder alongside the player costs memory for nothing.
     */
    val previewSource = remember(node.path) { PreviewSource() }
    DisposableEffect(node.path) { onDispose { previewSource.close() } }

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

    /**
     * Where to start, when this video is the one playing in the background.
     *
     * The other half of the hand-off contract in the `DisposableEffect` above. Opening the
     * video while the background player holds it means taking it back: read the position,
     * stop that player, and resume here. Skipping this would leave both playing the same
     * audio a few hundred milliseconds apart.
     */
    var resumeAt by remember(node.path) { mutableLongStateOf(0L) }

    /**
     * True while the background player still owns the sound and this screen is taking it.
     *
     * The mirror of `handingOver`. Both directions now overlap instead of following each
     * other: whichever player is leaving keeps going until the one arriving is ready, and
     * the position crosses at that moment rather than being captured before the wait.
     */
    var takingOver by remember(node.path) { mutableStateOf(false) }

    LaunchedEffect(node.path) {
        // The sound is NOT stopped here.
        //
        // It used to be: the shared player was stopped the instant this screen mounted and
        // its position written into `resumeAt`, so the audio died, several hundred
        // milliseconds of header-reading went by, and the video started at a position that
        // was by then stale. Silence, then a small jump backwards - the same cut as the
        // other direction, just reversed.
        //
        // Instead the music keeps playing while this screen opens its own decoder, and the
        // swap happens in `onPrepared`, at the last instant, off the LIVE position. See
        // `takingOver`.
        takingOver = vm.playback.isCurrent(node.path.toString())
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
        // Runs while the view exists, not only while it is playing: the retract timer is now
        // about being touched rather than about playback, so it has to tick when paused.
        while (ready) {
            val v = handle.view
            if (v != null && scrubTo == null) position = v.currentPosition.toLong().coerceAtLeast(0L)
            if (v != null) buffered = v.bufferPercentage
            if (chromeVisible &&
                ViewerChrome.shouldRetract(
                    now = System.currentTimeMillis(),
                    lastTouchedAt = chromeTouchedAt,
                    // A drag on the picture or the scrubber holds them open; letting go starts
                    // the clock from there rather than from the touch that began the drag.
                    interacting = scrubTo != null,
                    idleMs = CHROME_MS,
                )
            ) {
                chromeVisible = false
            }
            delay(200)
        }
    }

    // The scrub preview, extracted once rather than decoded during the drag.
    //
    // Bug identified: this decoded a frame whenever the finger moved far enough to want a new
    // one. Reading a frame takes tens of milliseconds and a drag wants one every few, so it
    // could only chase - and cancelling the overtaken work to keep up threw away the frame it
    // had already produced, so the preview stopped appearing at all.
    //
    // Decoding during the gesture is simply the wrong shape. Frames are pulled once, in the
    // background, in an order that covers the whole video early (see Storyboard.fillOrder), so
    // the preview is rough within a second and sharpens as the rest arrive. The drag reads
    // memory and cannot be slow.
    LaunchedEffect(uri, duration, node.path) {
        val source = uri ?: return@LaunchedEffect
        val plan = Storyboard.plan(duration)
        if (plan.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            for (index in Storyboard.fillOrder(plan.size)) {
                if (!isActive) return@withContext
                val at = plan[index]
                if (storyboard.containsKey(at)) continue
                val bitmap = runCatching {
                    val r = previewSource.get(context, source) ?: return@runCatching null
                    // Scaled by the platform: a full-size frame is decoded and then thrown
                    // away, and sixty of them would not fit in memory anyway.
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        r.getScaledFrameAtTime(
                            at * 1000,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            PREVIEW_W, PREVIEW_H,
                        )
                    } else {
                        r.getFrameAtTime(
                            at * 1000,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        )
                    }
                }.getOrNull()
                if (bitmap != null) {
                    // 16-bit colour, which halves what the storyboard costs and is what pays
                    // for holding four times as many frames. A thumbnail at this size shows no
                    // banding worth the memory; the full-colour original is released either
                    // way, so the alternative is not "sharper", it is "fewer frames".
                    val small = runCatching {
                        if (bitmap.config == android.graphics.Bitmap.Config.RGB_565) bitmap
                        else bitmap.copy(android.graphics.Bitmap.Config.RGB_565, false)
                            ?.also { bitmap.recycle() }
                    }.getOrNull() ?: bitmap
                    val image = small.asImageBitmap()
                    withContext(Dispatchers.Main) { storyboard[at] = image }
                }
            }
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

    /**
     * Keep the sound going after the screen is gone, when the option is on.
     *
     * ## The contract, which is the part that matters
     *
     * **Exactly one of the two players ever holds this file.** The video screen owns a
     * `VideoView`; the background player in the graph owns a `MediaPlayer`. Handing over means
     * this screen stops first and the other starts at the position this one reached - and
     * re-opening the video does the reverse, in the `LaunchedEffect` below. Letting both run
     * would play the same audio twice, a few hundred milliseconds apart, which is unmistakable
     * and would read as the app being broken rather than as a race.
     *
     * The pictures are simply not drawn. `MediaPlayer` carries on decoding audio with no
     * surface attached, which is what makes this cheap: nothing transcodes and nothing is
     * re-read beyond re-opening the file.
     *
     * The honest limit, and it is why this is a hand-off rather than a shared player: there is
     * a gap while the second player prepares. Seamless would mean rebuilding this screen on top
     * of the graph's player, and this screen's seek gesture, storyboard previews and zoom are
     * all built around owning its own `VideoView`. A gap of a few hundred milliseconds on
     * leaving a screen is worth considerably less than that rewrite.
     */
    DisposableEffect(handle) {
        onDispose {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // Teardown, and ONLY teardown.
            //
            // Nothing is handed over here any more. Closing a video used to start the music
            // player whenever the keeps-playing preference was on, which made Close and Play
            // as audio do the same thing - the button looked pointless and the back arrow
            // looked broken. Reported as exactly that: closing the video player "does exactly
            // what clicking play as music feels like".
            //
            // Close means close. Play as audio means keep the sound, and it is the button
            // that does it, while this screen is still running - see `handingOver`. A
            // rotation therefore needs no special case at all: it disposes, rebuilds, and
            // reopens the video, which is what turning a phone sideways should do.
            runCatching {
                handle.view?.stopPlayback()
                handle.view = null
                handle.player = null
            }
        }
    }

    val shownPosition = scrubTo ?: position
    // Paused no longer forces them up. It used to, on the reasoning that a paused video with no
    // controls looks like a frozen one - but pausing to look at a frame is a reason to UNCOVER it,
    // and a touch anywhere brings them straight back.
    val chromeUp = error == null && chromeVisible

    // The picture gets the whole screen. Restored automatically when this screen is left.
    ImmersiveViewer()

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
                            // The swap, at the last possible instant.
                            //
                            // The position is read off the still-playing background player
                            // HERE rather than when this screen mounted, so the two meet at
                            // the same moment of the file; the sound is stopped on the line
                            // before the picture starts, so the gap is a seek rather than a
                            // prepare.
                            if (takingOver) {
                                resumeAt = vm.playback.state.value.positionMs
                                vm.playback.stop()
                                takingOver = false
                            }
                            if (resumeAt > 0L) {
                                handle.seekExactly(resumeAt)
                                position = resumeAt
                                resumeAt = 0L
                            }
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
                    // Back, the name, and everything else behind one button.
                    //
                    // It carried six actions beside the title on a 423dp phone, which left
                    // the filename about 40% of the row and the icons shoulder to shoulder.
                    // The modes moved DOWN to the transport, where there was a whole row of
                    // empty space beside the clock and where a mode belongs anyway; the
                    // three that are neither modes nor frequent are behind the overflow.
                    OverlayAction(FiletIcons.Back, "Close") { vm.closeHandler() }
                    Text(
                        node.name,
                        color = Color.White,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Box {
                        OverlayAction(FiletIcons.More, "More") { overflow = true; wake() }
                        DropdownMenu(
                            expanded = overflow,
                            onDismissRequest = { overflow = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open in another app", fontSize = 13.sp) },
                                onClick = { overflow = false; vm.openExternally(node, force = true) },
                            )
                            DropdownMenuItem(
                                text = { Text("Share", fontSize = 13.sp) },
                                onClick = { overflow = false; vm.share(node) },
                            )
                            DropdownMenuItem(
                                text = { Text("Properties", fontSize = 13.sp) },
                                onClick = { overflow = false; vm.showProperties(node) },
                            )
                        }
                    }
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
                        val previewAt = scrubTo?.let { Storyboard.nearest(it, storyboard.keys) }
                        previewAt?.let { storyboard[it] }?.let { frame ->
                            Image(
                                frame,
                                // Named rather than null: a thumbnail of the frame you are
                                // about to land on is content, not decoration, and a screen
                                // reader has nothing else to say about where a scrub is going.
                                contentDescription = "Preview at ${clockOf(previewAt ?: 0L)}",
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
                        // Out of the system's gesture strip.
                        //
                        // This screen is immersive, so the navigation bar is hidden - but the
                        // few dp at the very bottom edge still belong to the OS, which reads a
                        // touch there as a back or home gesture before the app ever sees it.
                        // The chrome used to end with the clock and had padding to spare; the
                        // modes row went in below it and landed in exactly that strip, which
                        // is why its buttons needed two or three goes.
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp)
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
                    Row(Modifier.fillMaxWidth()) {
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
                    // The modes, under the clock rather than crowded against the title.
                    //
                    // Each one is a switch whose STATE matters - is it looping, can anything
                    // stop it, is the screen locked - and a switch in an overflow menu is a
                    // switch whose state you have to open a menu to read. There is a whole
                    // row of width here and the clock was using a tenth of it.
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OverlayAction(
                            FiletIcons.Repeat,
                            if (repeating) "Repeat is on" else "Repeat is off",
                            active = repeating,
                        ) { repeating = !repeating; wake() }
                        OverlayAction(
                            FiletIcons.Speed,
                            "Playback speed",
                            active = speed != 1f,
                        ) { speedOpen = true; wake() }
                        // Said in words whenever it is not 1x, for the same reason the shield
                        // is: a lit glyph is a state somebody has to learn, and a speed is a
                        // number you want to be able to read rather than infer.
                        if (speed != 1f) {
                            Text(
                                speedLabel(speed),
                                color = Filet.colors.accent,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp,
                                modifier = Modifier.padding(start = 2.dp, end = 4.dp),
                            )
                        }
                        OverlayAction(
                            FiletIcons.Shield,
                            if (uninterrupted) {
                                "Uninterrupted - nothing will pause this"
                            } else {
                                "Uninterrupted playback is off"
                            },
                            active = uninterrupted,
                        ) { vm.prefs.setUninterrupted(!uninterrupted); wake() }
                        // Named in words whenever it is on, because a lit shield is a state
                        // somebody has to learn and this is the switch that decides whether
                        // a call can take the sound away.
                        if (uninterrupted) {
                            Text(
                                "Uninterrupted",
                                color = Filet.colors.accent,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp,
                                modifier = Modifier.padding(start = 2.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        OverlayAction(FiletIcons.Audio, "Play as audio") {
                            if (!handingOver) {
                                handingOver = true
                                // The position is NOT captured here. It is read back out of
                                // this still-running player immediately before the audio
                                // decoder starts, so the two meet at the same instant of the
                                // file rather than a prepare apart. See handOffFromVideo.
                                vm.playback.handOffFromVideo(
                                    track = dev.niccc2007.filet.playback.tracksOf(listOf(node))
                                        .first(),
                                    positionNow = {
                                        runCatching {
                                            handle.player?.currentPosition?.toLong()
                                        }.getOrNull() ?: position
                                    },
                                )
                            }
                        }
                        OverlayAction(FiletIcons.Expand, orientationLabel(lockedOrientation)) {
                            lockedOrientation = nextOrientation(lockedOrientation)
                            wake()
                        }
                    }
                }
            }
        }

        if (speedOpen) {
            SpeedSheet(
                speed = speed,
                maxSpeed = speedCeiling,
                pitch = pitchMode,
                // The theme accent, not an artwork palette: this screen is a black slab with
                // a picture on it and has no ground to take a colour from.
                tint = Filet.colors.accent,
                onTint = MaterialTheme.colorScheme.onPrimary,
                onSpeed = { speed = it; wake() },
                onPitch = { vm.prefs.setPitchMode(it.id) },
                onDismiss = { speedOpen = false; wake() },
            )
        }

        // No veil here any more.
        //
        // It faded this screen to black, and the music face then appeared out of the black -
        // which is two maskings over one swap, and reads as the app closing and reopening.
        // `MediaScreen` now dissolves the two screens into each other, so the only thing
        // left for the hand-off to do is take the sound down gently, which the volume ramp
        // above does. `handOff` still drives that ramp.
    }
}

/**
 * How long the picture and the sound take to leave on a hand-off.
 *
 * Long enough to read as a dissolve rather than a cut, and comfortably longer than a local
 * file's `prepare()` - so the music is usually already running by the time the frame has
 * gone. Short enough that it never feels like waiting for something.
 */
private const val HANDOFF_FADE_MS = 320

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

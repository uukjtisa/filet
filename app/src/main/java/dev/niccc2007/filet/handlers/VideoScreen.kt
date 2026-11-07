package dev.niccc2007.filet.handlers

import android.net.Uri
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalDensity
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
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgAction
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgTone
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

/** How long the "-10s" flash stays up after the last double tap of a run. The step is Settings'. */
private const val FLASH_MS = 700L

/** How long the ticker trusts a frame step over the player's own position. See `steppedAt`. */
private const val STEP_SETTLE_MS = 400L

/** The longest a held step waits for the last seek to land before stepping anyway. */
private const val SEEK_WAIT_MS = 600L

/** Presses per video measured into the log, see `reportStep`. Enough to settle it, not a flood. */
private const val MEASURED_STEPS = 20

/** How long after a seek lands the picture is looked at: a frame or two for it to be drawn. */
private const val PRINT_AFTER_MS = 150L

/**
 * The handle, kept out of Compose state so exactly one place starts and stops it.
 *
 * It holds the engine - whatever plays the video, behind [VideoEngine] - and the exact seek
 * goes through it: see [VideoEngine.seekExactly] for why a seek to the keyframe behind is not
 * good enough.
 */
private class VideoHandle {
    var engine: VideoEngine? = null

    /** This video's link to the video player's own sound. Remade per prepare. */
    var sound: dev.niccc2007.filet.playback.SoundLink? = null

    /**
     * When the last exact seek was asked for, and when the player last said a seek was done,
     * in uptime. A held frame step waits on the second catching up with the first, so it never
     * asks faster than the frames land. Set from the player's seek-complete callback.
     */
    @Volatile var seekAskedAt = 0L
    @Volatile var seekDoneAt = 0L

    /**
     * Seek to [ms] itself, not to the keyframe before it.
     *
     * SEEK_CLOSEST decodes forward from the preceding keyframe to land on the exact frame. It
     * costs more than a sync seek, and it is what a scrub bar is supposed to feel like.
     */
    fun seekExactly(ms: Long) {
        val target = ms.coerceAtLeast(0L)
        seekAskedAt = android.os.SystemClock.uptimeMillis()
        engine?.seekExactly(target)
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

/**
 * A video: its preview first, the player once asked. See [VideoStart] for which of the two it
 * opens on and what Back does, and [VideoPreview] for the preview.
 */
@Composable
fun VideoScreen(vm: BrowserViewModel, node: VNode) {
    val runPage = LocalViewerRun.current
    val nextPlays = vm.prefs.videoNextPlays.collectAsStateWithLifecycle().value
    // Decided once, as the video arrives: the setting changing while it is up does not start it.
    var face by remember(node.path) {
        mutableStateOf(
            VideoStart.opensOn(
                takingOver = vm.playback.isCurrent(node.path.toString()),
                arrived = runPage?.arrived == true,
                nextPlays = nextPlays,
            ),
        )
    }
    // The picture Back stopped on and the time it was taken at, so it is shown with that time
    // only; 0 and none until Back has stopped it on this visit.
    var stoppedAt by remember(node.path) { mutableLongStateOf(0L) }
    var still by remember(node.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    // What the preview's Play asked for, the place it offered or the start; null when the screen
    // opened playing.
    var playAt by remember(node.path) { mutableStateOf<Long?>(null) }
    // The saved place, read again whenever the record settles - a pause, a Back, a leaving - so
    // the preview offers where this video was left after being scrolled away and back. See
    // VideoResume.
    val saves by vm.playback.resume.changes.collectAsStateWithLifecycle()
    val saved = remember(node.path, saves) { vm.playback.resume.pointOf(node.path.toString()) }
    val resumeOn by vm.prefs.resumePlayback.collectAsStateWithLifecycle()
    var playOffer by remember(node.path) { mutableStateOf<MediaResume.Offer?>(null) }
    val preview = MediaResume.preview(resumeOn, saved?.positionMs, saved?.durationMs ?: 0L, stoppedAt)
    // Opened straight into playing - a run's next video with Next video on - starts where the
    // setting says, and offers the place when that was the start. Not a take-over from the music
    // player: that is Carry on or a return, which have asked already. Decided once, as the video
    // arrives.
    val opening = remember(node.path) {
        if (face == VideoStart.Face.PLAYING && !vm.playback.isCurrent(node.path.toString())) {
            MediaResume.opening(resumeOn, saved?.positionMs, saved?.durationMs ?: 0L)
        } else {
            null
        }
    }

    // The picture gets the whole screen, previewing or playing. Restored automatically when this
    // screen is left.
    ImmersiveViewer()
    // A playing video's drags are its own - seeking, brightness, volume - so the run's swipe is
    // held while it plays. The preview lets it through: a swipe past a video is how a gallery is
    // looked through.
    HoldRunSwipe(face == VideoStart.Face.PLAYING)
    when (face) {
        // The frame Back stopped on only goes with the time it was stopped at: a place saved
        // since, elsewhere, has its own frame read from the file.
        VideoStart.Face.PREVIEW -> VideoPreview(vm, node, preview, still.takeIf { preview.playFromMs == stoppedAt }) { at, chosen ->
            playAt = at
            // Play at 0:00 with a place saved offers it once playing; the pill or Play from the
            // start picked where to go, so there is nothing to offer.
            playOffer = if (chosen) null else MediaResume.offer(at, MediaResume.place(saved?.positionMs, saved?.durationMs ?: 0L))
            still = null
            face = VideoStart.Face.PLAYING
        }
        VideoStart.Face.PLAYING -> VideoPlayer(
            vm, node,
            startAt = playAt ?: opening?.startMs ?: 0L,
            offer = if (playAt == null) opening?.offer else playOffer,
        ) { at, frame ->
            stoppedAt = at
            still = frame
            playAt = null
            face = VideoStart.Face.PREVIEW
        }
    }
}

/**
 * The player itself, with its sound, from [startAt].
 *
 * @param offer the place the continue card offers, for playing that started at 0:00 with one
 *   saved; null for no card. See [MediaResume].
 * @param onStop Back was pressed: the player is paused, and this is where and on what picture,
 *   for the preview to show. See [VideoStart].
 */
@Composable
private fun VideoPlayer(
    vm: BrowserViewModel,
    node: VNode,
    startAt: Long,
    offer: MediaResume.Offer?,
    onStop: (atMs: Long, still: android.graphics.Bitmap?) -> Unit,
) {
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
    // The progress bar's width, so a drag on the picture moves at the bar's pace. See
    // PlayerGesture.seekTrackPx for why it is checked before it is used.
    var seekBarPx by remember { mutableFloatStateOf(0f) }
    // The gear a drag on the picture is in, named under the time while it is not the normal one.
    var seekGear by remember(node.path) { mutableStateOf(dev.niccc2007.filet.playback.Scrub.Mode.NORMAL) }
    var chromeVisible by remember(node.path) { mutableStateOf(true) }

    // The playback zoom. Same value type as the image viewer, so the arithmetic and its tests
    // are shared rather than written twice and drifting - see ZoomState.kt.
    var picture by remember(node.path) { mutableStateOf(ZoomView.NONE) }
    var chromeTouchedAt by remember(node.path) { mutableLongStateOf(System.currentTimeMillis()) }
    var run by remember(node.path) { mutableStateOf<SeekRun?>(null) }
    var flashAt by remember(node.path) { mutableLongStateOf(0L) }
    // How far one double tap moves it, from Settings, and the buttons beside Play with it. Read
    // as it is now inside the gesture as well, which outlives the composition it started in.
    val skipSeconds by rememberUpdatedState(vm.prefs.videoSkipSeconds.collectAsStateWithLifecycle().value)
    // In the picture-in-picture window: the picture and nothing over it. See VideoPip.
    val inPip = rememberInPip(LocalContext.current as? android.app.Activity)
    // The video's own size, for the window's shape. Read at prepare: VideoView keeps the size
    // listener for its own layout, so this one is not taken from it.
    var videoSize by remember(node.path) { mutableStateOf<Pair<Int, Int>?>(null) }
    // The picture, as distinct from the sound. MediaPlayer plays the sound alone, and says
    // nothing, when this phone cannot decode the picture; this is what notices. See VideoDecode.
    var watch by remember(node.path) { mutableStateOf<PictureWatch?>(null) }
    var staged by remember(node.path) { mutableStateOf(false) }
    var noPicture by remember(node.path) { mutableStateOf<String?>(null) }
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

    /**
     * And the moment the sound is actually running, which is NOT the moment it was asked for.
     *
     * This is the bug the first version had, and it is a timing one. The fade ran on a fixed
     * clock started by the button, while the audio started whenever `prepare()` finished - a
     * header read, off the main thread, on a video container. Those are two different lengths
     * of time, so what you got depended on which won:
     *
     *  - prepare slower than the fade: the video was already silent and veiled, and the music
     *    had not started. A gap, and a video screen sitting there doing nothing - which is
     *    exactly how it was reported, "it keeps the video player open" and "the chop".
     *  - prepare faster: both played at once for the rest of the fade.
     *
     * Now nothing moves until the sound is up. `handOffFromVideo` calls back once the decoder
     * is running, the volume ramp and the veil start from there, and they run inside the
     * `MediaScreen` dissolve that the same instant begins. The picture and the sound cross
     * over together because they are now started by the same event.
     */
    var handedOver by remember(node.path) { mutableStateOf(false) }

    /**
     * Sound off, picture on.
     *
     * Per video rather than remembered, which is the quieter of the two wrong answers: a mute
     * that persists means somebody opens a clip tomorrow, hears nothing, and has no idea why.
     * The state of a thing that makes no sound is hard to notice, so the button is lit while
     * it is on rather than only changing its glyph.
     */
    var muted by remember(node.path) { mutableStateOf(false) }

    /**
     * The sound's level for this player, under 1 when the app volume is under 100%. Multiplied
     * into the mute and the hand-off fade, so none of the three overrides the others.
     */
    var soundLevel by remember(node.path) { mutableFloatStateOf(1f) }

    /** Nightcore's or Vaporwave's rate, multiplied into the speed. See `SoundMaths.rateOf`. */
    var soundRate by remember(node.path) { mutableFloatStateOf(1f) }

    // The last volume this player was given, before 8D's two sides, for the sweep to move.
    val lastVolume = remember(node.path) { floatArrayOf(1f) }

    /**
     * [v] on both sides, each times 8D's side. Every volume this player is given goes through
     * here, so the sweep moves the mute, the level and the hand-off's fade alike, and a step of
     * the sweep is the last volume given with the sides moved, never a volume of its own.
     */
    fun sideVolume(v: Float) {
        lastVolume[0] = v
        val link = handle.sound
        handle.engine?.setVolume(v * (link?.panLeft ?: 1f), v * (link?.panRight ?: 1f))
    }

    LaunchedEffect(muted, handedOver, soundLevel) {
        if (handedOver) return@LaunchedEffect
        sideVolume(if (muted) 0f else soundLevel)
    }

    /** 0 while watching, 1 once the veil is fully up. Drives the dissolve and the volume. */
    val handOff by animateFloatAsState(
        if (handedOver) 1f else 0f,
        animationSpec = tween(HANDOFF_FADE_MS),
        label = "hand-off",
    )

    // The ramp, applied every frame the animation produces. A `setVolume` per frame is a
    // cheap call on an already-running MediaPlayer - far cheaper than the alternative, which
    // is cutting the sound dead and hoping the next player is quick.
    LaunchedEffect(handOff) {
        if (handedOver) {
            runCatching {
                // A muted video is already at zero; the ramp must not turn it back up on its
                // way out.
                sideVolume(if (muted) 0f else (1f - handOff).coerceIn(0f, 1f) * soundLevel)
            }
        }
    }

    /**
     * The hand-off has a deadline.
     *
     * Even with the failure path wired, a `prepare()` that neither returns nor throws leaves
     * this screen waiting for a callback that is never coming - and what somebody sees is a
     * video player that ignored the button. So: if the sound is not running by
     * [HANDOFF_GIVE_UP_MS], the request is abandoned, the picture comes back, and the player
     * is told rather than left guessing. A wrong answer beats no answer.
     */
    LaunchedEffect(handingOver) {
        if (!handingOver) return@LaunchedEffect
        kotlinx.coroutines.delay(HANDOFF_GIVE_UP_MS)
        if (!handedOver) {
            handingOver = false
            sideVolume(if (muted) 0f else soundLevel)
            vm.toast("Could not switch this to audio. Still playing as video.")
        }
    }

    // Once the ramp has finished there is nothing left for this decoder to do, and it is
    // still decoding a video at volume zero while the music plays. It is paused rather than
    // released: the composition is a few hundred milliseconds from being disposed anyway,
    // and releasing out from under a VideoView that is still on screen is how that crashes.
    LaunchedEffect(handedOver) {
        if (handedOver) {
            kotlinx.coroutines.delay(HANDOFF_FADE_MS.toLong())
            runCatching { handle.engine?.pause() }
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
     * screen.
     */
    // This video's remembered sheet, if it has one: its speed, pitch and sound come back with it.
    // Said to the store while the screen is up, so the sheet's Remember toggle has a file to keep.
    val soundState = dev.niccc2007.filet.playback.SoundState.of(LocalContext.current)
    val fileKey = node.path.toString()
    DisposableEffect(fileKey) {
        soundState.playing(dev.niccc2007.filet.playback.SoundTarget.VIDEO, fileKey)
        onDispose {
            if (soundState.fileOf(dev.niccc2007.filet.playback.SoundTarget.VIDEO) == fileKey) {
                soundState.playing(dev.niccc2007.filet.playback.SoundTarget.VIDEO, null)
            }
        }
    }
    val remembered by remember(fileKey) {
        soundState.rememberedOf(dev.niccc2007.filet.playback.SoundTarget.VIDEO, fileKey)
    }.collectAsStateWithLifecycle(
        soundState.rememberedFor(dev.niccc2007.filet.playback.SoundTarget.VIDEO, fileKey),
    )

    var speed by remember(node.path) { mutableFloatStateOf(remembered?.speed ?: 1f) }
    // A remembered video's speed follows its sheet. Off again, the speed stays where it is: a
    // video's speed is its own for as long as it is open, remembered or not.
    LaunchedEffect(remembered) { remembered?.let { speed = it.speed } }
    var speedOpen by remember(node.path) { mutableStateOf(false) }
    var speedCeiling by remember(node.path) { mutableFloatStateOf(dev.niccc2007.filet.playback.MAX_SPEED) }
    val generalPitch = dev.niccc2007.filet.playback.PitchMode.of(
        vm.prefs.pitchMode.collectAsStateWithLifecycle().value,
    )
    val pitchMode = remembered?.pitch ?: generalPitch

    // The video player's own sound - read here only to light the sheet's button. The sheet
    // reads and writes it itself, and `Sound` applies it to this video's session.
    val soundNow by soundState.sound(dev.niccc2007.filet.playback.SoundTarget.VIDEO).collectAsStateWithLifecycle()

    // Frame by frame, on the bar under the picture (FrameBar). The steps go to the file's own
    // frames once their list is read; until then, and where it cannot be, by the container's
    // rate, read with the storyboard below, or a thirtieth where the file does not say. See
    // FrameStep.
    var fps by remember(node.path) { mutableStateOf(FrameStep.FALLBACK_FPS) }
    var frameList by remember(node.path) { mutableStateOf<FrameList?>(null) }
    /** How much of the frame list is read, 0 to 1, while it is being read. */
    var framesReading by remember(node.path) { mutableStateOf<Float?>(null) }
    /** The bar's height, which the picture is lifted by so the bar never covers it. */
    var frameBarPx by remember(node.path) { mutableIntStateOf(0) }
    /** How many steps on this video have been measured into the log. See `reportStep`. */
    var measuredSteps by remember(node.path) { mutableIntStateOf(0) }
    val frameBarUp = FrameBarState.on && error == null && uri != null && !inPip
    val scope = rememberCoroutineScope()

    // When the last frame step was asked for. The ticker leaves the position alone for a moment
    // after one: an exact seek takes a few frames' time to land, and until it does the player
    // still reports where it was - so a second press, read from that, stepped from the old
    // frame and the two presses came out as one.
    var steppedAt by remember(node.path) { mutableLongStateOf(0L) }

    // Applied to the running decoder, and the refusal is handled rather than thrown away.
    //
    // A rate Android will not resample throws from `setPlaybackParams`; left uncaught that is
    // a crash in the middle of a drag. Caught, it is the device telling us where its ceiling
    // is, so the rail shortens to what this phone can actually do instead of keeping a
    // position that plays nothing.
    LaunchedEffect(speed, pitchMode, ready, soundRate) {
        val engine = handle.engine ?: return@LaunchedEffect
        val wasPlaying = playing
        // Nightcore and Vaporwave multiply both, so they ride on whatever the sheet is set to.
        val (played, pitch) = dev.niccc2007.filet.playback.SoundMaths.played(
            speed, pitchMode.pitchFor(speed), soundRate, speedCeiling,
        )
        val ok = engine.setSpeed(played, pitch) ?: return@LaunchedEffect
        if (!ok) {
            if (played > 1f) {
                speedCeiling = maxOf(1f, played - dev.niccc2007.filet.playback.SPEED_BACKOFF)
                speed = minOf(speed, speedCeiling)
            } else {
                speed = 1f
            }
            return@LaunchedEffect
        }
        // Documented quirk: on some devices writing `playbackParams` STARTS a paused player.
        if (!wasPlaying) runCatching { engine.pause() }
    }

    /**
     * The video's own uninterruptible setting, which Settings shows beside the music's.
     *
     * It was the music's once, through one shared switch only the music player read: turning it
     * on here changed what the next track did and nothing about this video. See [VideoFocus].
     */
    val uninterrupted by vm.prefs.videoUninterrupted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    // Who else may play over this video, and what pauses it. `VideoView` is told to ask for no
    // focus of its own (see the factory), so this is the only request. The listener reads the
    // state as it is when a change arrives: all of it is per file, like this.
    val focus = remember(node.path) {
        VideoFocus(
            context,
            ignoring = { vm.prefs.videoUninterrupted.value || handingOver },
            playing = { playing },
        ) { step ->
            val v = handle.engine ?: return@VideoFocus
            when (step) {
                FocusStep.PAUSE -> { runCatching { v.pause() }; playing = false }
                FocusStep.RESUME -> { runCatching { v.start() }; playing = true }
                FocusStep.NONE -> Unit
            }
        }
    }
    // Asked for again on every start: a video played again after another app took the sound
    // has to take it back. Given back the moment uninterruptible goes on. Not asked for while
    // the sound is being handed to the music player: that asks for its own, and the loss it
    // causes here is ignored.
    LaunchedEffect(focus, playing, uninterrupted, handingOver) {
        if (uninterrupted) {
            focus.abandon()
        } else if (playing && !handingOver) {
            focus.steps.forget()
            focus.request()
        }
    }
    DisposableEffect(focus) { onDispose { focus.abandon() } }
    // Headphones out, Bluetooth gone: pause, whatever the setting says. See noisyWatch.
    DisposableEffect(node.path) {
        val noisy = noisyWatch(context) {
            focus.steps.forget()
            if (playing) {
                runCatching { handle.engine?.pause() }
                playing = false
            }
        }
        onDispose { noisy.close() }
    }

    // The window's settings follow the player while it is up: its shape, its play button, and
    // whether leaving Filet goes into it, which the setting decides and only while playing.
    val pipOnHome by vm.prefs.videoPipOnHome.collectAsStateWithLifecycle()
    val pipAuto = VideoPip.autoEnters(pipOnHome, playing, ready, handingOver)
    fun pictureRect(): android.graphics.Rect? =
        handle.engine?.surface?.let { v -> android.graphics.Rect().also { v.getGlobalVisibleRect(it) } }
    PipEffects(activity, playing, videoSize, pipAuto, { pictureRect() }) {
        val v = handle.engine ?: return@PipEffects
        if (playing) v.pause() else v.start()
        playing = !playing
    }

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

    // Played to the end and not moved since: the pause that comes with finishing is no stop.
    var finished by remember(node.path) { mutableStateOf(false) }

    fun seekTo(target: Long) {
        handle.seekExactly(target)
        position = target
        finished = false
    }

    // Carry on's record of this video. This screen plays on its own player, which the music
    // player's record never sees, so it writes the place down itself. See VideoResume.
    val resumeTrack = remember(node.path) { dev.niccc2007.filet.playback.tracksOf(listOf(node)).first() }
    // The one write, for a stop and for the end alike: every place past 0:00 is kept (see
    // ResumeStore.keeps), and each is logged, so a Carry on that stays empty says why.
    fun writeResume(at: Long) {
        val wrote = runCatching {
            vm.playback.resume.put(
                path = resumeTrack.id,
                title = resumeTrack.title,
                video = true,
                positionMs = at,
                durationMs = duration,
                settled = true,
            )
        }
        dev.niccc2007.filet.log.FiletLog.i("resume") {
            "video stop at $at of $duration ms: " + when {
                wrote.isFailure -> "write failed (${wrote.exceptionOrNull()?.javaClass?.simpleName})"
                dev.niccc2007.filet.playback.ResumeStore.keeps(at) -> "kept"
                else -> "at 0:00, nothing to keep"
            }
        }
    }
    fun saveResume(atMs: Long? = null) {
        // Nothing to write before the video has opened, and nothing once the music player has
        // the sound: that player keeps the record from the moment it takes over. Opened is
        // `ready`, not a known length: a file whose player cannot say how long it is still has a
        // place to come back to. Whatever Resume playback is set to - that decides how a video
        // opens, not whether Carry on hears of it.
        // Nor once it has played to the end: the player can still read its last moment before
        // the rewind lands, and that is not a place anybody stopped at.
        if (!ready || handingOver || handedOver || finished) return
        val at = atMs
            ?: handle.engine?.position?.coerceAtLeast(0L)
            ?: position
        writeResume(at)
    }
    // Every pause writes it - the button, a tap on the picture, the headphones coming out, focus
    // lost, a frame step - so it is one watch on `playing` rather than a line beside each.
    LaunchedEffect(node.path) {
        snapshotFlow { playing }.collect { if (it) finished = false else saveResume() }
    }
    // And the app leaving the foreground, which can be the last this screen hears before the
    // process is killed.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, node.path) {
        val watch = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) saveResume()
        }
        lifecycle.addObserver(watch)
        onDispose { lifecycle.removeObserver(watch) }
    }

    /**
     * Where the video is now, to step from.
     *
     * The player's own report, read at the press rather than the ticker's copy of it: that is
     * up to a tick old, and a step pressed just after a pause would go from where the video was
     * before it stopped. Inside a step's settling time it is the step's target instead, for the
     * reason `steppedAt` gives.
     */
    fun livePosition(): Long {
        if (System.currentTimeMillis() - steppedAt <= STEP_SETTLE_MS) return position
        return handle.engine?.position?.coerceAtLeast(0L) ?: position
    }

    /** Returns once the last seek has landed, or after [SEEK_WAIT_MS]. A held step's pace. */
    suspend fun settled() {
        val asked = handle.seekAskedAt
        withTimeoutOrNull(SEEK_WAIT_MS) { while (handle.seekDoneAt < asked) delay(8) }
    }

    /**
     * One step measured into the log: how long the seek took, where the player says it is, and
     * whether the picture changed.
     *
     * The question it answers is this phone's, not the code's: whether a paused exact seek is
     * drawn at all. A player that seeks without drawing would show the old frame while the
     * readout moves on, and on a moving clip the picture change says which it is. A still scene
     * reads as unchanged either way. The first [MEASURED_STEPS] presses on a video only.
     */
    suspend fun reportStep(view: android.view.SurfaceView, before: IntArray, from: Long, to: Long, askedAt: Long) {
        val took = withTimeoutOrNull(1_500L) {
            while (handle.seekDoneAt < askedAt) delay(8)
            handle.seekDoneAt - askedAt
        }
        delay(PRINT_AFTER_MS)
        val change = printChange(before, surfacePrint(view))
        val at = handle.engine?.position
        dev.niccc2007.filet.log.FiletLog.i("frames") {
            "step $from -> $to ms: " +
                (if (took != null) "seek done in $took ms" else "no seek-complete in 1.5 s") +
                ", player at $at ms, picture " +
                when {
                    change == null -> "unreadable"
                    change < 0.5f -> "unchanged (%.2f/255)".format(change)
                    else -> "changed (%.2f/255)".format(change)
                } +
                ", list " + (frameList?.let { "${it.size} frames" } ?: "not read")
        }
    }

    /**
     * [by] frames back or on, paused there.
     *
     * On the file's own frames once their list is read, on the rate's grid until then. Not
     * waking the chrome: a step is a look at the picture, and the chrome would come back over it.
     */
    suspend fun stepFrames(by: Int, first: Boolean) {
        val engine = handle.engine ?: return
        val v = engine.surface
        if (playing) engine.pause()
        playing = false
        val from = livePosition()
        val to = frameList?.step(from, by) ?: FrameStep.target(from, duration, fps, by)
        val before = if (first && measuredSteps < MEASURED_STEPS) surfacePrint(v) else null
        steppedAt = System.currentTimeMillis()
        seekTo(to)
        if (before != null) {
            measuredSteps++
            val askedAt = handle.seekAskedAt
            scope.launch { reportStep(v, before, from, to, askedAt) }
        }
    }

    /** A second either way, to the frame nearest it. Playing or paused stays as it was. */
    fun jumpFrames(direction: Int) {
        val from = livePosition()
        val by = direction * FrameStep.JUMP_MS
        val to = frameList?.jump(from, by) ?: FrameStep.jump(from, duration, by)
        steppedAt = System.currentTimeMillis()
        seekTo(to)
    }

    /** Hand the sound to the music player and let the picture go. Asked once; see `handingOver`. */
    fun playAsMusic() {
        if (handingOver) return
        handingOver = true
        // The position is NOT captured here. It is read back out of this still-running player
        // immediately before the audio decoder starts, so the two meet at the same instant of the
        // file rather than a prepare apart. See handOffFromVideo.
        vm.playback.handOffFromVideo(
            track = dev.niccc2007.filet.playback.tracksOf(listOf(node)).first(),
            positionNow = {
                handle.engine?.position ?: position
            },
            // The cue to let go, and the start of everything that looks like a hand-off.
            whenPlaying = { handedOver = true },
            whenFailed = { handingOver = false },
        )
    }

    // Back stops it and goes to the preview at the frame it stopped on, rather than leaving. See
    // VideoStart. Registered after the viewer's own Back, so it is asked first - the button, the
    // gesture and a predictive back alike, which all arrive through the one dispatcher.
    //
    // The arrow on the screen is the same Back, so it calls the same function: it used to close
    // the viewer outright, and so left from a video the phone's Back would have stopped.
    var stopping by remember(node.path) { mutableStateOf(false) }
    // Read when asked, not when drawn: the tap and the dispatcher see the same state.
    fun backToPreview() = !stopping &&
        VideoStart.back(VideoStart.Face.PLAYING, handingOver) == VideoStart.BackStep.TO_PREVIEW
    fun stopToPreview() {
        stopping = true
        val at = handle.engine?.position?.coerceAtLeast(0L) ?: position
        // Written down before the preview comes up, so the preview opens on it. See VideoResume.
        saveResume(at)
        val engine = handle.engine
        runCatching { engine?.pause() }
        playing = false
        // The picture as it was on the screen, read before the player goes.
        val v = engine?.surface
        scope.launch { onStop(at, v?.let { captureStill(it) }) }
    }
    fun back() = if (backToPreview()) stopToPreview() else vm.closeHandler()
    androidx.activity.compose.BackHandler(enabled = backToPreview()) { back() }

    /**
     * Where to start: where Back stopped it, if it did, or - when this video is the one playing
     * in the background - where that player has got to.
     *
     * The second is the other half of the hand-off contract in the `DisposableEffect` above.
     * Opening the video while the background player holds it means taking it back: read the
     * position, stop that player, and resume here. Skipping this would leave both playing the
     * same audio a few hundred milliseconds apart.
     */
    var resumeAt by remember(node.path) { mutableLongStateOf(startAt) }

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
        // A share is copied in to play, and the picture's wait is longer for it. See VideoDecode.
        staged = runCatching { vm.osPathOf(node) }.getOrNull() == null
        if (u == null) {
            error = "This file has to be copied to local storage before it can play. " +
                "Copy it somewhere local first."
        } else {
            uri = u
        }
    }

    // A dialogue or menu holds the controls (see OverlayHold); closing it restarts the idle clock,
    // so the controls do not vanish the instant something read for a while is put away.
    val overlayOpen = dev.niccc2007.filet.ui.dialogs.OverlayHold.held
    LaunchedEffect(overlayOpen) { if (!overlayOpen) chromeTouchedAt = System.currentTimeMillis() }

    // Position ticker, and the auto-hide clock, on one loop. Two loops would be two clocks to
    // keep in step for no gain.
    LaunchedEffect(playing, ready, node.path) {
        // Runs while the view exists, not only while it is playing: the retract timer is now
        // about being touched rather than about playback, so it has to tick when paused.
        while (ready) {
            val v = handle.engine
            if (v != null && scrubTo == null && System.currentTimeMillis() - steppedAt > STEP_SETTLE_MS) {
                // 0 when it cannot say, as the view's own reading was: between a surface going and
                // the player made again for the next one.
                position = (v.position ?: 0L).coerceAtLeast(0L)
            }
            if (v != null) buffered = v.bufferPercent
            if (chromeVisible &&
                ViewerChrome.shouldRetract(
                    now = System.currentTimeMillis(),
                    lastTouchedAt = chromeTouchedAt,
                    // A drag on the picture or the scrubber holds them open; letting go starts
                    // the clock from there rather than from the touch that began the drag. So
                    // does the open menu, which lives inside the bar and would go with it.
                    interacting = scrubTo != null || overflow || dev.niccc2007.filet.ui.dialogs.OverlayHold.held,
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
        withContext(Dispatchers.IO) {
            // The frame rate, from the same retriever. Read here because this is the one place
            // that touches it, and a retriever is not safe on two threads at once.
            runCatching {
                val r = previewSource.get(context, source) ?: return@runCatching
                val count = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                } else {
                    null
                }
                fps = FrameStep.fpsOf(
                    count,
                    r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION),
                    r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE),
                )
            }
            if (plan.isEmpty()) return@withContext
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

    // The frame list, read once the bar is up and kept for as long as this video is open. Not
    // read for a video nobody steps through: it reads the whole video track once.
    LaunchedEffect(uri, frameBarUp) {
        val source = uri ?: return@LaunchedEffect
        if (!frameBarUp || frameList != null) return@LaunchedEffect
        framesReading = 0f
        val started = android.os.SystemClock.uptimeMillis()
        try {
            val list = readFrames(context, source) { framesReading = it }
            frameList = list
            dev.niccc2007.filet.log.FiletLog.i("frames") {
                (if (list != null) "${list.size} frames" else "no frame list") +
                    " in ${android.os.SystemClock.uptimeMillis() - started} ms; the header's rate is " +
                    "%.2f".format(fps)
            }
        } finally {
            framesReading = null
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
        handle.engine?.looping = repeating
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
            //
            // Where it got to is written down first, while the player can still say: leaving is
            // one of the places Carry on carries on from.
            saveResume()
            runCatching {
                handle.sound?.close()
                handle.sound = null
                handle.engine?.release()
                handle.engine = null
            }
        }
    }

    // The first-frame watch's clock. It stops for good once a frame is drawn or it has spoken,
    // and it counts only time the video really runs. See PictureWatch.
    LaunchedEffect(watch) {
        val w = watch ?: return@LaunchedEffect
        var last = -1L
        var seekSeen = handle.seekDoneAt
        while (isActive && !w.done) {
            delay(PictureWatch.TICK_MS)
            val now = handle.engine?.position ?: continue
            val seeked = handle.seekDoneAt != seekSeen
            seekSeen = handle.seekDoneAt
            val going = playing && scrubTo == null && run == null && !handingOver && !inPip
            if (w.tick(PictureWatch.TICK_MS, going, advanced = last >= 0 && now > last, seeked = seeked)) {
                runCatching { handle.engine?.pause() }
                playing = false
                val at = uri
                noPicture = withContext(Dispatchers.IO) {
                    val stream = at?.let { VideoDecode.streamOf(context, it) }
                    VideoDecode.words(stream, stream?.let { VideoDecode.decodersFor(it) }.orEmpty())
                }
            }
            last = now
        }
    }
    noPicture?.let { words ->
        Dlg(onDismiss = { noPicture = null }) {
            DlgHeader(FiletIcons.Video, "No picture", tone = DlgTone.WARN, onClose = { noPicture = null })
            DlgBody {
                Text(words, fontSize = 13.sp, lineHeight = 19.sp, color = colors.fg2)
                Spacer(Modifier.height(10.dp))
                DlgAction(FiletIcons.Audio, "Play as music", sub = "The sound, with the screen off or in another app.") {
                    noPicture = null
                    playAsMusic()
                }
                DlgAction(FiletIcons.Link, "Open in another app") {
                    noPicture = null
                    vm.openExternally(node, force = true)
                }
                DlgAction(FiletIcons.Play, "Keep playing here", sub = "Sound only, unless the picture comes.") {
                    noPicture = null
                    runCatching { handle.engine?.start() }
                    playing = true
                }
            }
        }
    }

    val shownPosition = scrubTo ?: position
    // Paused no longer forces them up. It used to, on the reasoning that a paused video with no
    // controls looks like a frozen one - but pausing to look at a frame is a reason to UNCOVER it,
    // and a touch anywhere brings them straight back.
    val chromeUp = error == null && chromeVisible && !inPip

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
                    // Above the frame bar, not under it: a frame looked at one at a time is the
                    // one picture that must not lose its bottom fifth to a control.
                    .padding(bottom = if (frameBarUp) with(LocalDensity.current) { frameBarPx.toDp() } else 0.dp)
                    .graphicsLayer {
                        scaleX = picture.scale; scaleY = picture.scale
                        translationX = picture.offsetX; translationY = picture.offsetY
                    },
                factory = { ctx ->
                    val events = object : VideoEngine.Events {
                        override fun onPrepared(prepared: VideoEngine.Prepared) {
                            val engine = handle.engine ?: return
                            videoSize = prepared.width to prepared.height
                            // Linked per prepare: a prepare is a new player on the session, and
                            // effects are remade for it rather than trusted to have survived.
                            handle.sound = dev.niccc2007.filet.playback.Sound.of(ctx).link(
                                prepared.audioSessionId,
                                dev.niccc2007.filet.playback.SoundTarget.VIDEO,
                                onRate = { soundRate = it },
                                onPan = { sideVolume(lastVolume[0]) },
                            ) { soundLevel = it }
                            sideVolume(if (muted) 0f else handle.sound?.level ?: 1f)
                            engine.looping = repeating
                            duration = prepared.durationMs
                            ready = true
                            // Armed per prepare, and only for a file with a picture to wait for.
                            watch = if (prepared.hasVideoTrack) PictureWatch(VideoDecode.windowFor(staged)) else null
                            // The swap, at the last possible instant.
                            //
                            // The position is read off the still-playing background player
                            // HERE rather than when this screen mounted, so the two meet at
                            // the same moment of the file; the sound is stopped on the line
                            // before the picture starts, so the gap is a seek rather than a
                            // prepare.
                            //
                            // Until the music player has a position of its own, the saved place
                            // is the one it was asked for: see VideoResume.takeOverAt.
                            if (takingOver) {
                                val point = vm.playback.resume.pointOf(node.path.toString())
                                resumeAt = VideoResume.takeOverAt(
                                    vm.playback.state.value.positionMs,
                                    point?.positionMs,
                                    point?.durationMs ?: 0L,
                                )
                                vm.playback.stop()
                                takingOver = false
                            }
                            if (resumeAt > 0L) {
                                handle.seekExactly(resumeAt)
                                position = resumeAt
                                resumeAt = 0L
                            }
                            engine.start()
                            // And once more now that it is playing: an effect made before the
                            // session's first start is dropped by some phones. See `SoundLink`.
                            handle.sound?.reattach()
                            playing = true
                        }

                        override fun onCompletion() {
                            // Only reached when repeat is off: a looping player never completes.
                            // Rewinding rather than sitting on a black last frame, so the play
                            // button does what it says.
                            //
                            // Played to the end is kept, at the end: Carry on tags it finished
                            // and opening it starts it again (see Finished). First, so the pause
                            // that follows - read off a player rewinding to 0:00 - is not taken
                            // for a stop.
                            finished = true
                            writeResume(duration)
                            playing = false
                            chromeVisible = true
                            position = 0L
                            handle.seekExactly(0L)
                        }

                        override fun onError(what: Int, extra: Int) {
                            error = "This video would not play (error $what/$extra). " +
                                "Try opening it in another app."
                            ready = false
                        }

                        override fun onFirstFrame() {
                            watch?.drawn()
                        }

                        override fun onSeekDone() {
                            handle.seekDoneAt = android.os.SystemClock.uptimeMillis()
                        }
                    }
                    // Made only in the branch where the file is staged: `uri` is set by then. The
                    // engine is the default, or in a debug build the Developer tab's choice.
                    VideoEngines.open(ctx, checkNotNull(uri), events).also { handle.engine = it }.surface
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
                // The bar the tap-to-play preview wears as well: see VideoTopBar. The menu is
                // this state's own - Play as music, picture-in-picture and Uninterruptible are
                // things only a playing video can do.
                VideoTopBar(
                    name = node.name,
                    onBack = { back() },
                    menuOpen = overflow,
                    onMenu = { overflow = it },
                    onTouch = { wake() },
                ) {
                    // Here and not on the transport: a headphones glyph beside the speaker
                    // read as a second volume control, and nothing on it said it would
                    // carry on with the screen off. A row can say so in words.
                    dev.niccc2007.filet.ui.dialogs.PopRow(
                        FiletIcons.Audio,
                        "Play as music",
                        detail = "Keeps the sound going with the screen off or in another app.",
                    ) { overflow = false; playAsMusic() }
                    if (activity != null && pipOffered(context)) {
                        dev.niccc2007.filet.ui.dialogs.PopRow(
                            FiletIcons.Expand,
                            "Picture-in-picture",
                            detail = "Goes on in a small window over other apps.",
                        ) {
                            overflow = false
                            if (!enterPip(activity, playing, videoSize, pipAuto, pictureRect())) {
                                vm.toast("This phone would not open a picture-in-picture window.")
                            }
                        }
                    }
                    // Here and not as a lit button in the bar, and with no mark in the
                    // bar while it is on: see PlayerTopBar.
                    dev.niccc2007.filet.ui.dialogs.PopRow(
                        FiletIcons.Shield,
                        "Uninterruptible",
                        detail = if (uninterrupted) {
                            "Plays over other apps, and they do not pause it."
                        } else {
                            "Pauses for calls and other audio."
                        },
                        active = uninterrupted,
                        checked = uninterrupted,
                    ) {
                        overflow = false
                        vm.prefs.setVideoUninterrupted(!uninterrupted)
                        wake()
                    }
                    dev.niccc2007.filet.ui.dialogs.PopSeparator()
                    // In a run only: it is what the arrows and the swipe do to the next one.
                    if (LocalViewerRun.current != null) {
                        NextVideoRow(vm) { overflow = false }
                        dev.niccc2007.filet.ui.dialogs.PopSeparator()
                    }
                    dev.niccc2007.filet.ui.dialogs.PopRow(FiletIcons.Link, "Open in another app") {
                        overflow = false
                        vm.openExternally(node, force = true)
                    }
                    dev.niccc2007.filet.ui.dialogs.PopRow(FiletIcons.Share, "Share") { overflow = false; vm.share(node) }
                    dev.niccc2007.filet.ui.dialogs.PopRow(FiletIcons.Info, "Properties") {
                        overflow = false
                        vm.showProperties(node)
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
                                    val v = handle.engine
                                    if (v != null) {
                                        if (playing) v.pause() else v.start()
                                        playing = !playing
                                    }
                                    wake()
                                } else {
                                    val next = advanceRun(run, zone, System.currentTimeMillis())
                                    run = next
                                    flashAt = System.currentTimeMillis()
                                    seekTo(doubleTapTarget(position, duration, zone, next.taps, skipSeconds * 1000L))
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
                        var startHeight = 0f
                        var startPosition = 0L
                        var startLevel = 0f
                        var kind = PlayerGesture.Drag.NONE
                        // The finger's travel, summed per event and NOT read as "position now
                        // minus position at the start": wake() below brings the top bar back,
                        // which pushes this box down under a still finger. See
                        // PlayerGesture.Travel for the bug that made.
                        val travel = PlayerGesture.Travel()
                        // Made when the drag turns out to be a seek. See PlayerGesture.SeekDrag.
                        var seek: PlayerGesture.SeekDrag? = null
                        detectDragGestures(
                            onDragStart = { at ->
                                startX = at.x
                                // Taken now, before the chrome coming back shrinks the box: the
                                // sweep of a level drag is a fraction of the height it started on.
                                startHeight = size.height.toFloat()
                                startPosition = position
                                travel.reset()
                                seek = null
                                kind = PlayerGesture.Drag.NONE
                                wake()
                            },
                            onDragEnd = {
                                if (kind == PlayerGesture.Drag.SEEK) scrubTo?.let { seekTo(it) }
                                scrubTo = null
                                seek = null
                                seekGear = dev.niccc2007.filet.playback.Scrub.Mode.NORMAL
                                kind = PlayerGesture.Drag.NONE
                                levelKind = PlayerGesture.Drag.NONE
                                wake()
                            },
                            onDragCancel = {
                                scrubTo = null
                                seek = null
                                seekGear = dev.niccc2007.filet.playback.Scrub.Mode.NORMAL
                                kind = PlayerGesture.Drag.NONE
                                levelKind = PlayerGesture.Drag.NONE
                            },
                        ) { change, moved ->
                            change.consume()
                            travel.add(moved.x, moved.y)
                            val dx = travel.dx
                            val dy = travel.dy
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
                                    val track = PlayerGesture.seekTrackPx(size.width.toFloat(), seekBarPx)
                                    val going = seek
                                    if (going == null) {
                                        // The travel that made it a seek, at the normal pace:
                                        // nothing has pulled yet.
                                        seek = PlayerGesture.SeekDrag(startPosition, duration, density)
                                            .also { it.move(dx, 0f, track) }
                                    } else {
                                        going.move(moved.x, moved.y, track)
                                    }
                                    scrubTo = seek?.target()
                                    seekGear = seek?.mode ?: dev.niccc2007.filet.playback.Scrub.Mode.NORMAL
                                }
                                PlayerGesture.Drag.BRIGHTNESS -> {
                                    val level = PlayerGesture.levelAfter(startLevel, dy, startHeight)
                                    applyBrightness(level)
                                    levelKind = PlayerGesture.Drag.BRIGHTNESS
                                    levelShown = level
                                }
                                PlayerGesture.Drag.VOLUME -> {
                                    val level = PlayerGesture.levelAfter(startLevel, dy, startHeight)
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
                    skipSeconds = skipSeconds,
                    onBack = {
                        seekTo(doubleTapTarget(position, duration, TapZone.BACK, stepMs = skipSeconds * 1000L))
                        wake()
                    },
                    onForward = {
                        seekTo(doubleTapTarget(position, duration, TapZone.FORWARD, stepMs = skipSeconds * 1000L))
                        wake()
                    },
                    onToggle = {
                        val v = handle.engine ?: return@CentreTransport
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
                                    "${activeRun.taps * skipSeconds}s",
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
                        // The gear, said while it is in use, as the bar says it: a finger moving
                        // as far as before and the time moving a quarter as far reads as a fault
                        // unless something names it.
                        if (seekGear != dev.niccc2007.filet.playback.Scrub.Mode.NORMAL) {
                            Text(
                                seekGear.label,
                                color = Filet.colors.accent, fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
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
                        // is why its buttons needed two or three goes. With the frame bar up,
                        // the bar is the bottom edge and keeps out of the strip itself.
                        .then(if (frameBarUp) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                        .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp)
                ) {
                    ScrubBar(
                        positionMs = shownPosition,
                        durationMs = duration,
                        modifier = Modifier.onSizeChanged { seekBarPx = it.width.toFloat() },
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
                    // Each one is a switch whose STATE matters - is it looping, is the sound
                    // changed, which way is it held - and a switch in an overflow menu is a
                    // switch whose state you have to open a menu to read. There is a whole
                    // row of width here and the clock was using a tenth of it. Uninterruptible
                    // is the exception, in the overflow, where its switch shows its state: see
                    // PlayerTopBar.
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The left group takes the row's width, so the buttons on the right stay
                        // at the right edge however short the label is.
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            OverlayAction(
                                FiletIcons.Repeat,
                                if (repeating) "Repeat is on" else "Repeat is off",
                                active = repeating,
                            ) { repeating = !repeating; wake() }
                            OverlayAction(
                                SoundSheetGlyph,
                                "Speed and sound",
                                active = soundChipLit(speed, soundNow),
                            ) { speedOpen = true; wake() }
                            // Said in words whenever anything in the sheet is off its default: a
                            // lit glyph is a state somebody has to learn, and a speed or a volume is
                            // a number to read rather than infer.
                            // Weighted without filling, so the buttons around it are measured first:
                            // it gets what they leave and says as much as fits, never wrapping.
                            // See soundChipLadder.
                            if (soundChipLit(speed, soundNow)) {
                                FittedText(
                                    soundChipLadder(speed, soundNow),
                                    androidx.compose.material3.LocalTextStyle.current.merge(
                                        androidx.compose.ui.text.TextStyle(
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.6.sp,
                                        ),
                                    ),
                                    Filet.colors.accent,
                                    Modifier.weight(1f, fill = false).padding(start = 2.dp, end = 4.dp),
                                )
                            }
                        }
                        // Play as music moved to the overflow menu - see `playAsMusic`.
                        OverlayAction(
                            if (muted) FiletIcons.VolumeOff else FiletIcons.Volume,
                            if (muted) "Sound off - tap for sound" else "Mute",
                            active = muted,
                        ) {
                            muted = !muted
                            wake()
                        }
                        OverlayAction(FiletIcons.Expand, orientationLabel(lockedOrientation)) {
                            lockedOrientation = nextOrientation(lockedOrientation)
                            wake()
                        }
                    }
                }
            }

            // Below the chrome and not inside it: the chrome comes and goes with a touch, and
            // this stays put until it is switched off in the sheet. Out of the gesture band, so
            // a press on it is never also a tap on the picture.
            if (frameBarUp) {
                val list = frameList
                val readout = if (list != null) {
                    val i = list.at(shownPosition)
                    FrameStep.readout(i + 1L, list.timeOf(i), exact = true)
                } else {
                    FrameStep.readout(FrameStep.numberAt(shownPosition, fps), shownPosition, exact = false)
                }
                FrameBar(
                    readout = readout,
                    reading = framesReading,
                    playing = playing,
                    onStep = { by, first -> stepFrames(by, first) },
                    onJump = { jumpFrames(it) },
                    settle = { settled() },
                    onToggle = {
                        handle.engine?.let { v ->
                            if (playing) v.pause() else v.start()
                            playing = !playing
                        }
                    },
                    modifier = Modifier.onSizeChanged { frameBarPx = it.height },
                )
            }
        }

        // The continue card, for playing that started at 0:00 with a place saved: top right,
        // under the top bar, over the picture rather than in the chrome, so it stays while the
        // chrome hides. A tap goes to the place. See MediaResume.
        offer?.let { place ->
            dev.niccc2007.filet.ui.ContinueCard(
                key = place,
                title = place.title,
                detail = null,
                closeLabel = place.closeLabel,
                modifier = Modifier.align(Alignment.TopEnd).belowPlayerBar(VIDEO_BAR),
            ) {
                seekTo(place.atMs)
                wake()
            }
        }

        if (speedOpen) {
            SoundSheet(
                speed = speed,
                maxSpeed = speedCeiling,
                pitch = pitchMode,
                // The theme accent, not an artwork palette: this screen is a black slab with
                // a picture on it and has no ground to take a colour from.
                tint = Filet.colors.accent,
                onTint = MaterialTheme.colorScheme.onPrimary,
                onSpeed = { v ->
                    // Kept with the video while it is remembered; its own for this viewing otherwise.
                    soundState.editPlayback(dev.niccc2007.filet.playback.SoundTarget.VIDEO) { it.copy(speed = v) }
                    speed = v
                    wake()
                },
                onPitch = { vm.prefs.setPitchMode(it.id) },
                onDismiss = { speedOpen = false; wake() },
                target = dev.niccc2007.filet.playback.SoundTarget.VIDEO,
                // Switching it on closes the sheet, so the bar is seen at once and over nothing.
                frames = FrameBarSwitch(FrameBarState.on) { on ->
                    FrameBarState.on = on
                    if (on) speedOpen = false
                },
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
 * How long the hand-off may take before the screen gives up on it.
 *
 * Four seconds. A local file prepares in tens of milliseconds and a staged one in a second or
 * so, so this is far past any honest case and well inside the time somebody decides the app
 * is broken.
 */
private const val HANDOFF_GIVE_UP_MS = 4_000L

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
    /** How far Back and Forward move it: the double tap's length. */
    skipSeconds: Int,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onToggle: () -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayRound(FiletIcons.Back, "Back $skipSeconds seconds", 48.dp, onClick = onBack)
            OverlayRound(
                if (playing) FiletIcons.Pause else FiletIcons.PlaySolid,
                if (playing) "Pause" else "Play",
                66.dp,
                primary = true,
                onClick = onToggle,
            )
            OverlayRound(FiletIcons.Forward, "Forward $skipSeconds seconds", 48.dp, onClick = onForward)
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

/**
 * The video's top bar, whether it is playing or waiting at its tap-to-play preview: Back, the
 * name centred on the screen, and everything else behind one button.
 *
 * ONE composable for both, so the two cannot drift. They did: the playing bar was rebuilt with
 * the name centred and clear of the camera while the preview kept the old bar, name at the left
 * and the arrows after it, so the bar changed shape under the thumb the moment Play was pressed.
 *
 * It carried six actions beside the title on a 423dp phone once, which left the filename about
 * 40% of the row and the icons shoulder to shoulder. The modes moved DOWN to the transport, and
 * what is neither a mode nor frequent is behind the overflow, whose rows each state passes in.
 *
 * The name is centred on the screen, and the buttons are split so that neither end outweighs
 * the other: Previous beside Back, Next beside the overflow. With all of them on the right, the
 * wider end would have left a name in a run about a quarter of the bar. In a run the arrows are
 * here as well as the swipe: a playing video's sideways drag seeks, so a swipe cannot be the
 * way to the next one there.
 *
 * The scrim runs to the top edge, behind the camera and the status bar. The buttons and the name
 * are kept below both: below the camera always, and below the status bar while a swipe has it
 * showing. With no cutout and the bars hidden the padding is nothing.
 *
 * @param onTouch any press on the bar: the playing screen keeps its chrome up a while longer.
 * @param menu the overflow's rows. The state's own: only a playing video can play as music.
 */
@Composable
internal fun VideoTopBar(
    name: String,
    onBack: () -> Unit,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onTouch: () -> Unit = {},
    menu: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val runPage = LocalViewerRun.current
    Box(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f))) {
        CentredBar(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.displayCutout.union(WindowInsets.statusBars)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
            gap = 8.dp,
            start = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OverlayAction(FiletIcons.Back, "Back") { onBack() }
                    if (runPage?.hasPrevious == true) {
                        OverlayAction(FiletIcons.Prev, "Previous") { runPage.step(-1); onTouch() }
                    }
                }
            },
            end = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (runPage?.hasNext == true) {
                        OverlayAction(FiletIcons.Next, "Next") { runPage.step(1); onTouch() }
                    }
                    Box {
                        OverlayAction(FiletIcons.More, "More") { onMenu(true); onTouch() }
                        // On the app's own menu kit rather than Material's dropdown, which was the
                        // one menu in Filet drawn in the platform's shape and colours.
                        dev.niccc2007.filet.ui.dialogs.PopMenu(expanded = menuOpen, onDismiss = { onMenu(false) }, content = menu)
                    }
                }
            },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // A name, so never dotted: it gives up size, then fades before its extension.
                // See ListName.
                dev.niccc2007.filet.browser.ListNameText(
                    name,
                    Color.White,
                    startSp = 12.5f,
                    align = androidx.compose.ui.text.style.TextAlign.Center,
                )
                runPage?.let {
                    Text(it.label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

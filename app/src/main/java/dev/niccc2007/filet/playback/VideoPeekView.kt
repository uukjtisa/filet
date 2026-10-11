package dev.niccc2007.filet.playback

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * A few seconds of a video, looping, in place of cover art.
 *
 * ## What it is for
 *
 * A video played as audio is a track in a queue, and the player screen draws a track as a
 * square of cover art it does not have - so every video got the generic audio glyph, and the
 * one screen whose job is to say what you are listening to said nothing. See [VideoPeek] for
 * the window arithmetic and for why a still frame was not enough.
 *
 * ## What it costs, and the three rules that keep that honest
 *
 * This is a second decoder running on the same file, so it is only ever worth it while
 * somebody is looking at it:
 *
 *  1. **Muted, always.** The sound is the real player's job. Two decoders on one file both
 *     making noise is an echo, and this one is a decoration.
 *  2. **It stops when the screen does.** A `LifecycleEventObserver`, not a `DisposableEffect`
 *     alone - Compose does not dispose a composition when the app goes to the background, and
 *     the whole point of background playback is that this screen is still composed while the
 *     phone is in a pocket. A video decoder running in a pocket is a battery complaint.
 *  3. **It follows the audio.** Pausing the track pauses the picture. A loop carrying on over
 *     a paused player makes the pause look like it did not take.
 *
 * ## Why `TextureView` and not `SurfaceView`
 *
 * It sits inside a rounded, clipped box. A `SurfaceView` punches its own window through the
 * hierarchy and ignores the clip, so the corners would be square and the picture would sit on
 * top of anything drawn over it. A `TextureView` is drawn like any other view, which is what
 * the rounding needs - and this is a small square, which is where the extra copy a
 * `TextureView` costs stops mattering.
 */
@Composable
fun VideoPeekView(
    source: Uri?,
    /** Whether the real player is playing. The picture follows the sound. */
    playing: Boolean,
    modifier: Modifier = Modifier,
    /** Drawn until there are frames: the poster, or the placeholder glyph. */
    placeholder: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var ready by remember(source) { mutableStateOf(false) }
    val holder = remember { PeekHolder(context.applicationContext) }

    // Torn down on the way out, and on the way to the background.
    //
    // ON_STOP rather than ON_PAUSE: a dialog over the player pauses the activity without
    // hiding the picture, and releasing the decoder there would blank the art behind a
    // confirmation prompt.
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    holder.release()
                    ready = false
                }
                Lifecycle.Event.ON_START -> holder.restart()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            holder.release()
        }
    }

    DisposableEffect(source) {
        holder.source = source
        holder.restart()
        onDispose { }
    }

    LaunchedEffect(playing) { holder.follow(playing) }

    // The loop. Polled rather than timed: a decoder that stalls, or a seek that lands late,
    // would leave a timed loop running off the end of its window and sitting on a still frame.
    LaunchedEffect(source) {
        while (isActive) {
            delay(POLL_MS)
            holder.tick()
        }
    }

    Box(modifier) {
        if (!ready) placeholder()
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            texture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            holder.attach(Surface(texture)) { ready = true }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            texture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            holder.release()
                            ready = false
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** How often the loop checks where it is. Cheap, and well inside the shortest window. */
private const val POLL_MS = 120L

/**
 * The decoder and its surface, outside the composition.
 *
 * A `MediaPlayer` read from a recomposition is a half-released one eventually, which is the
 * same rule [Playback] states about the real player. This holds the handle; the composable
 * only ever tells it what changed.
 */
private class PeekHolder(private val app: Context) {
    var source: Uri? = null
    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var window: LongRange = LongRange.EMPTY
    private var wantPlaying = true
    private var onFirstFrame: (() -> Unit)? = null

    fun attach(target: Surface, onReady: () -> Unit) {
        surface = target
        onFirstFrame = onReady
        restart()
    }

    fun follow(playing: Boolean) {
        wantPlaying = playing
        val p = player ?: return
        runCatching { if (playing) p.start() else p.pause() }
    }

    fun restart() {
        val uri = source
        val target = surface
        release()
        if (uri == null || target == null) return
        runCatching {
            MediaPlayer().apply {
                setDataSource(app, uri)
                setSurface(target)
                // Silent, always. The real player owns the sound.
                setVolume(0f, 0f)
                setOnPreparedListener { prepared ->
                    window = VideoPeek.windowOf(prepared.duration.toLong())
                    runCatching { prepared.seekTo(VideoPeek.loopStart(window).toInt()) }
                    // Looping inside the decoder when the WHOLE file is the loop. A short clip
                    // then repeats with no seam at all, and `tick` has nothing to do.
                    prepared.isLooping = window.isEmpty()
                    if (wantPlaying) runCatching { prepared.start() }
                    onFirstFrame?.invoke()
                }
                // A file this phone cannot decode is not an error worth saying out loud here -
                // the audio is playing perfectly well and this is the decoration. Returning
                // true marks it handled so the framework does not also fire onCompletion.
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            }
        }.onSuccess { player = it }
    }

    fun tick() {
        val p = player ?: return
        if (window.isEmpty()) return
        val at = runCatching { p.currentPosition.toLong() }.getOrNull() ?: return
        val duration = runCatching { p.duration.toLong() }.getOrNull() ?: 0L
        if (VideoPeek.shouldLoop(at, duration, window)) {
            runCatching { p.seekTo(VideoPeek.loopStart(window).toInt()) }
        }
    }

    fun release() {
        runCatching { player?.setSurface(null) }
        runCatching { player?.release() }
        player = null
    }
}

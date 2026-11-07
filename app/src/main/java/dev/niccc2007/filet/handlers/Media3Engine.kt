package dev.niccc2007.filet.handlers

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlin.math.roundToInt

/**
 * Media3's player behind [VideoEngine]: the one that chooses its own decoder.
 *
 * ## Why
 *
 * `MediaPlayer` takes the decoder the phone offers first and cannot be told otherwise, so a
 * file past what that decoder declares - the 2160 x 3402, 60 fps one - plays its sound over a
 * black picture. This one is told two things `MediaPlayer` cannot be: fall back to the next
 * decoder when one fails to start, and try a decoder even on a file past its declared limits
 * (the track selector's default, kept). That is what the phone's gallery does.
 *
 * ## What it is not, yet
 *
 * Not the default, and not reachable outside a debug build: see `VideoParity`, which holds it
 * back until every feature of the viewer has been tried on it on a phone. One is known to be
 * missing - 8D pan, which needs each side's level and this player has one volume for both. Its
 * way in is an audio processor in the renderer chain, not yet written; the level and the mute
 * work as they are.
 *
 * Like the platform engine it asks for no audio focus and ignores headphones coming out: the
 * screen does both, through `VideoFocus` and its noisy watch.
 */
@OptIn(UnstableApi::class)
internal class Media3Engine(context: Context, uri: Uri, private val events: VideoEngine.Events) : VideoEngine {

    private val view = FittedSurface(context)
    private val player: ExoPlayer = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context).setEnableDecoderFallback(true),
    )
        // To the frame asked for, not the keyframe before it: see VideoEngine.seekExactly.
        .setSeekParameters(SeekParameters.EXACT)
        .build()

    private var prepared = false
    private var drawn = false
    private var seeking = false
    private var released = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    if (!prepared) {
                        prepared = true
                        events.onPrepared(preparedNow())
                    }
                    seekLanded()
                }
                // Only with looping off: a repeating player never ends.
                Player.STATE_ENDED -> events.onCompletion()
                else -> Unit
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            view.fit(videoSize)
        }

        override fun onRenderedFirstFrame() {
            // Also after every seek: the picture at the place asked for is on the screen.
            seekLanded()
            if (!drawn) {
                drawn = true
                events.onFirstFrame()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Media3's own code where the platform engine gives MediaPlayer's pair.
            events.onError(error.errorCode, 0)
        }
    }

    init {
        // A session of its own, made here rather than left to the player, so the screen's sound
        // effects always have one to attach to - never 0, which is the whole output mix.
        context.getSystemService(AudioManager::class.java)?.generateAudioSessionId()
            ?.takeIf { it > 0 }
            ?.let { player.audioSessionId = it }
        player.addListener(listener)
        player.setVideoSurfaceView(view)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    private fun seekLanded() {
        if (seeking) {
            seeking = false
            events.onSeekDone()
        }
    }

    private fun preparedNow(): VideoEngine.Prepared {
        val size = FittedSurface.shown(player.videoSize)
        return VideoEngine.Prepared(
            width = size.first,
            height = size.second,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
            audioSessionId = player.audioSessionId,
            hasVideoTrack = player.currentTracks.containsType(C.TRACK_TYPE_VIDEO),
        )
    }

    override val surface: SurfaceView get() = view

    override val position: Long?
        get() = if (prepared && !released) player.currentPosition else null

    override val bufferPercent: Int get() = if (released) 0 else player.bufferedPercentage

    override var looping: Boolean
        get() = !released && player.repeatMode == Player.REPEAT_MODE_ONE
        set(on) {
            if (!released) player.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }

    override fun start() {
        if (!released) player.play()
    }

    override fun pause() {
        if (!released) player.pause()
    }

    override fun seekExactly(ms: Long) {
        if (released) return
        seeking = true
        player.seekTo(ms)
    }

    /** One volume for both sides: the louder of the two, so the level and the mute hold. */
    override fun setVolume(left: Float, right: Float) {
        if (!released) player.volume = maxOf(left, right).coerceIn(0f, 1f)
    }

    override fun setSpeed(speed: Float, pitch: Float): Boolean? {
        if (!prepared || released) return null
        return runCatching { player.playbackParameters = PlaybackParameters(speed, pitch) }.isSuccess
    }

    override fun release() {
        if (released) return
        released = true
        player.removeListener(listener)
        player.release()
    }
}

/**
 * The picture's view, as wide and tall as the video allows inside the space it is given and
 * centred there - what `VideoView` does for the platform engine, so the two place the picture
 * the same way. Until the size is known it fills the space, as `VideoView` does.
 */
private class FittedSurface(context: Context) : SurfaceView(context) {

    /** Width over height as shown, or 0 while unknown. */
    private var aspect = 0f

    fun fit(size: VideoSize) {
        val (w, h) = shown(size)
        val next = if (w > 0 && h > 0) w.toFloat() / h else 0f
        if (next != aspect) {
            aspect = next
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (aspect <= 0f || w == 0 || h == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        if (w.toFloat() / h > aspect) {
            setMeasuredDimension((h * aspect).roundToInt(), h)
        } else {
            setMeasuredDimension(w, (w / aspect).roundToInt())
        }
    }

    companion object {
        /**
         * The size as it is shown: the pixel shape applied, and turned a quarter when the player
         * leaves the turn to whoever shows it.
         */
        @Suppress("DEPRECATION")
        fun shown(size: VideoSize): Pair<Int, Int> {
            if (size.width <= 0 || size.height <= 0) return 0 to 0
            val w = (size.width * size.pixelWidthHeightRatio).roundToInt()
            val h = size.height
            return if (size.unappliedRotationDegrees % 180 != 0) h to w else w to h
        }
    }
}

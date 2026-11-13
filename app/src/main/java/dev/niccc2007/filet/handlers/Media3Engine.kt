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
 * ## Where it is used
 *
 * Filet's player: the one every video opens in unless the phone's player is chosen in the player
 * menu or in Settings (see `VideoEngines`). When it cannot play a file it says why and offers the
 * others as taps; it never hands a video on by itself (see [VideoLadder]). The phone's hardware
 * decoder is tried first even past its declared limits (see [HardwareFirst]); with FFmpeg in the
 * build it also decodes what the phone has no decoder for (see [FfmpegDecoders]).
 *
 * 8D needs each side's level and this player has one volume for both, so the sides are set by
 * [SidePan], a step in its sound chain, and the volume carries the louder of the two.
 *
 * Like the platform engine it asks for no audio focus and ignores headphones coming out: the
 * screen does both, through `VideoFocus` and its noisy watch.
 */
@OptIn(UnstableApi::class)
internal class Media3Engine(
    context: Context,
    uri: Uri,
    private val events: VideoEngine.Events,
    /** FFmpeg decodes the picture rather than the phone. See [FfmpegDecoders] and [VideoLadder]. */
    forcePicture: Boolean = false,
) : VideoEngine {

    private val view = FittedSurface(context)

    // FFmpeg where the build has it, Media3's own factory where it does not (F-Droid, x86) - with
    // the hardware decoder first in both. See HardwareFirst.
    // Each side's level, for 8D, in the sound of whichever factory is used. See SidePan.
    private val pan = SidePan()
    private val ffmpeg = FfmpegDecoders.setup(context, forcePicture, pan)
    private val player: ExoPlayer = ExoPlayer.Builder(
        context,
        ffmpeg?.factory ?: HardwareFirst.Factory(context, pan).setEnableDecoderFallback(true),
    )
        // To the frame asked for, not the keyframe before it: see VideoEngine.seekExactly.
        .setSeekParameters(SeekParameters.EXACT)
        .setLoadControl(quickStart())
        .build()

    /** Whether FFmpeg is drawing the picture, so the ladder does not move to FFmpeg again. */
    override var pictureByFfmpeg: Boolean = false
        private set

    /** The decoders that really started, for the Playback stats readout. */
    private var pictureDecoder: String? = null
    private var pictureHardware: Boolean? = null
    private var soundDecoder: String? = null

    /** Dropped frames inside the current window, for [VideoEngine.Events.onHeavy]. */
    private var droppedInWindow = 0
    private var windowStartedAt = 0L
    private var saidHeavy = false

    /**
     * Which decoder really started, in the log: the file says what it wants, and this says what it
     * got, which is the half a bug report about a black picture needs. Dropped frames are counted
     * here too, because on a weak phone software decoding shows up as frames dropped and nowhere
     * else - Media3 keeps the sound in time and drops late pictures rather than stalling.
     */
    private val decoders = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            pictureByFfmpeg = decoderName.startsWith("ffmpeg")
            pictureDecoder = decoderName
            pictureHardware = hardwareOf(decoderName)
            dev.niccc2007.filet.log.FiletLog.i("video") {
                "picture decoder $decoderName (${initializationDurationMs} ms)" +
                    (FfmpegDecoders.version?.let { ", ffmpeg $it" } ?: "")
            }
        }

        override fun onAudioDecoderInitialized(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            soundDecoder = decoderName
            dev.niccc2007.filet.log.FiletLog.i("video") { "sound decoder $decoderName" }
        }

        override fun onDroppedVideoFrames(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            dev.niccc2007.filet.log.FiletLog.i("video") { "dropped $droppedFrames frames in $elapsedMs ms" }
            val now = android.os.SystemClock.uptimeMillis()
            if (now - windowStartedAt > HEAVY_WINDOW_MS) {
                windowStartedAt = now
                droppedInWindow = 0
            }
            droppedInWindow += droppedFrames
            if (!saidHeavy && droppedInWindow >= HEAVY_DROPS) {
                saidHeavy = true
                events.onHeavy()
            }
        }
    }

    private var prepared = false
    private var drawn = false
    private var seeking = false
    private var released = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            events.onBuffering(playbackState == Player.STATE_BUFFERING && prepared)
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
        player.addAnalyticsListener(decoders)
        // Before prepare, on this thread, which is the player's: the manager applies its modes by
        // turning renderers on and off in the track selector.
        ffmpeg?.attach(player)
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
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(ms)
    }

    /**
     * To a keyframe within [NEAR_US] of [ms] when there is one, else to [ms] exactly.
     *
     * A skip or a scrub that lands exactly decodes forward from the keyframe before the target;
     * on a long-GOP movie that is seconds of decoding to show one frame, and it is why seeking
     * felt slow beside VLC, which lands on keyframes. Bounded either side, so a skip of ten seconds
     * never lands at five or at fifteen; frame steps and resume points stay exact.
     */
    override fun seekNear(ms: Long) {
        if (released) return
        seeking = true
        player.setSeekParameters(SeekParameters(NEAR_US, NEAR_US))
        player.seekTo(ms)
    }

    /**
     * The louder side as the player's volume, so the level and the mute hold, and each side's share
     * of it to [SidePan], so 8D moves the sound. At silence the sides are kept for the way back up.
     */
    override fun setVolume(left: Float, right: Float) {
        if (released) return
        val top = maxOf(left, right).coerceIn(0f, 1f)
        player.volume = top
        if (top > 0f) pan.set(left.coerceIn(0f, 1f) / top, right.coerceIn(0f, 1f) / top)
    }

    override fun setSpeed(speed: Float, pitch: Float): Boolean? {
        if (!prepared || released) return null
        return runCatching { player.playbackParameters = PlaybackParameters(speed, pitch) }.isSuccess
    }

    /**
     * Media3's own counters for the video renderer. `ensureUpdated` first: the counts are written
     * on the playback thread and read here on the main one.
     */
    override fun stats(): PlaybackStats.Snapshot? {
        if (released) return null
        val c = player.videoDecoderCounters?.also { it.ensureUpdated() }
        val f = player.videoFormat
        return PlaybackStats.Snapshot(
            atMs = android.os.SystemClock.uptimeMillis(),
            pictureDecoder = pictureDecoder,
            hardware = pictureHardware,
            soundDecoder = soundDecoder,
            mime = f?.sampleMimeType,
            width = f?.width?.takeIf { it > 0 } ?: 0,
            height = f?.height?.takeIf { it > 0 } ?: 0,
            fps = f?.frameRate?.takeIf { it > 0f } ?: 0f,
            bitrate = f?.let { maxOf(it.averageBitrate, it.peakBitrate, it.bitrate) }?.takeIf { it > 0 } ?: 0,
            shown = c?.renderedOutputBufferCount?.toLong(),
            dropped = c?.let { (it.droppedBufferCount + it.droppedInputBufferCount).toLong() },
            toKeyframe = c?.droppedToKeyframeCount?.toLong(),
            offsetUsTotal = c?.totalVideoFrameProcessingOffsetUs,
            offsetCount = c?.videoFrameProcessingOffsetCount?.toLong(),
            bufferedMs = player.totalBufferedDuration,
            speed = player.playbackParameters.speed,
        )
    }

    override fun release() {
        if (released) return
        released = true
        player.removeListener(listener)
        player.removeAnalyticsListener(decoders)
        ffmpeg?.detach()
        player.release()
    }

    private companion object {
        /**
         * "Too heavy for this phone": this many frames dropped inside one window. A frame or two
         * dropped on a seek is normal; a quarter of the frames for ten seconds is not.
         */
        const val HEAVY_DROPS = 60
        const val HEAVY_WINDOW_MS = 10_000L

        /** How far from a skip's target a keyframe may be and still be used: two seconds. */
        const val NEAR_US = 2_000_000L

        /**
         * Playing starts once a second is buffered, and two after running dry. Media3's own
         * defaults wait two and a half and five, which is the pause before a picture that VLC does
         * not have (its network cache is a second). The ceiling, how far ahead it reads while
         * playing, is Media3's.
         */
        fun quickStart(): androidx.media3.exoplayer.LoadControl =
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    1_000,
                    2_000,
                )
                .build()
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

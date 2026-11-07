package dev.niccc2007.filet.handlers

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceView
import android.widget.VideoView

/**
 * What the video viewer asks of whatever plays the video, and nothing more.
 *
 * ## Why there is a seam here
 *
 * The viewer plays through `VideoView`, which plays through `MediaPlayer`, which picks its own
 * decoder and cannot be told otherwise. A file past what the hardware decoder declares - the
 * 2160 x 3402, 60 fps one - plays its sound over a black picture, while the phone's gallery
 * plays it, because the gallery chooses its decoder itself. Media3 can: decoder fallback, and a
 * decoder allowed past its declared limits. It comes in steps, and this is the first: the
 * viewer talks to this interface, today's player sits behind it, and nothing changes.
 *
 * ## Why exactly these
 *
 * It is as wide as the viewer's use of the player, and no wider: what it calls today, read off
 * the screen, so a second engine has a short and complete list to meet. The picture is a
 * [SurfaceView] in both, because three things read it as one - the window picture-in-picture
 * grows from, the still Back leaves behind (`captureStill`), and the frame-step log's print of
 * the picture (`surfacePrint`).
 *
 * Speed can be refused: a rate the phone will not resample is its ceiling, and the sheet backs
 * off to it (see the screen). The engine says so rather than throwing.
 */
internal interface VideoEngine {

    /** The picture, on screen. */
    val surface: SurfaceView

    /** Where it is now in ms, or null when it cannot say: not prepared yet, or released. */
    val position: Long?

    /** How much is buffered ahead, as a percentage of the whole. */
    val bufferPercent: Int

    /** Whether it starts over at the end rather than completing. */
    var looping: Boolean

    fun start()
    fun pause()

    /** To [ms] itself, not the keyframe before it. [Events.onSeekDone] when it has landed. */
    fun seekExactly(ms: Long)

    /** Each side's volume, 0 to 1: the level, the mute and 8D's sweep all arrive here. */
    fun setVolume(left: Float, right: Float)

    /**
     * Speed and pitch together: true when set, false when the engine refused the pair, null
     * before it is prepared, when there is nothing to set them on yet.
     */
    fun setSpeed(speed: Float, pitch: Float): Boolean?

    /** Let the decoder and the file go. The engine is not used again. */
    fun release()

    /** What a prepare found. One per prepare: a prepare is a new player on the session. */
    data class Prepared(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        /** For the video player's own sound - effects are linked to a session. */
        val audioSessionId: Int,
        /** Whether there is a picture to wait for at all. See `PictureWatch`. */
        val hasVideoTrack: Boolean,
    )

    /** What the engine tells the viewer. On the main thread. */
    interface Events {
        fun onPrepared(prepared: Prepared)

        /** The end, reached with looping off. */
        fun onCompletion()

        /** It cannot play: the platform's own codes, for the message. */
        fun onError(what: Int, extra: Int)

        /** The first frame of the picture is on the screen. */
        fun onFirstFrame()

        /** The last seek has landed. */
        fun onSeekDone()
    }
}

/**
 * Today's player behind [VideoEngine]: a `VideoView` and the `MediaPlayer` inside it.
 *
 * Moved here whole from the screen, in the same order, so nothing it did changes - including
 * the two things the screen relied on it for:
 *
 * - It asks for no audio focus of its own. `VideoView` asks every time it opens a URI and asks
 *   with no listener, so nothing it lost would ever pause it; the screen asks instead, through
 *   `VideoFocus`. Set before the URI, which is where it would ask.
 * - The exact seek. `VideoView.seekTo(int)` goes to the keyframe BEHIND the target, so a stream
 *   with a keyframe every five seconds turned a drag to 12.5 s into a jump to 10 s. The player
 *   has taken a mode since API 26, which is where this app starts, so `SEEK_CLOSEST` is asked
 *   for, and the view's own seek is only the fallback.
 */
internal class VideoViewEngine(context: Context, uri: Uri, events: VideoEngine.Events) : VideoEngine {

    private val view = VideoView(context)
    private var player: MediaPlayer? = null

    init {
        view.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        view.setVideoURI(uri)
        // No setMediaController: the viewer draws its own chrome.
        view.setOnPreparedListener { mp ->
            player = mp
            // VideoView sets no seek listener of its own, so this one is free.
            mp.setOnSeekCompleteListener { events.onSeekDone() }
            events.onPrepared(
                VideoEngine.Prepared(
                    width = mp.videoWidth,
                    height = mp.videoHeight,
                    durationMs = mp.duration.toLong().coerceAtLeast(0L),
                    audioSessionId = mp.audioSessionId,
                    hasVideoTrack = VideoDecode.hasVideoTrack(mp),
                ),
            )
        }
        view.setOnCompletionListener { events.onCompletion() }
        view.setOnErrorListener { _, what, extra ->
            events.onError(what, extra)
            true
        }
        view.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) events.onFirstFrame()
            false
        }
    }

    override val surface: SurfaceView get() = view

    override val position: Long?
        get() = runCatching { player?.currentPosition?.toLong() }.getOrNull()

    override val bufferPercent: Int get() = view.bufferPercentage

    override var looping: Boolean
        get() = runCatching { player?.isLooping }.getOrNull() ?: false
        set(on) {
            runCatching { player?.isLooping = on }
        }

    override fun start() = view.start()

    override fun pause() = view.pause()

    override fun seekExactly(ms: Long) {
        val mp = player
        if (mp != null) {
            runCatching { mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST) }
                .onFailure { view.seekTo(ms.toInt()) }
        } else {
            view.seekTo(ms.toInt())
        }
    }

    override fun setVolume(left: Float, right: Float) {
        runCatching { player?.setVolume(left, right) }
    }

    override fun setSpeed(speed: Float, pitch: Float): Boolean? {
        val mp = player ?: return null
        return runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed).setPitch(pitch) }.isSuccess
    }

    override fun release() {
        runCatching { view.stopPlayback() }
        player = null
    }
}

// ── the parity list ───────────────────────────────────────────────────────────────

/**
 * Everything the viewer does that a player behind it must keep doing.
 *
 * Written down as a check rather than remembered, because the step that makes Media3 the
 * default is the one that would lose a feature quietly: an engine that plays a file the old one
 * could not is an obvious win, and the speed sheet that no longer moves the pitch is found a week
 * later. [VideoParity] will not let an engine be the default until it passes every one.
 */
internal enum class VideoFeature(val label: String) {
    SEEK("seek"),
    SPEED("speed"),
    FRAME_STEP("frame step"),
    DOUBLE_TAP_SKIP("double-tap skip"),
    SOUND_EFFECTS("the sound effects"),
    PAN_8D("8D pan"),
    RATE_EFFECTS("nightcore and vaporwave rate"),
    PICTURE_IN_PICTURE("picture-in-picture"),
    TAP_TO_PLAY_PREVIEW("the tap-to-play preview"),
    RESUME_POINTS("resume points"),
}

/** The players the viewer can run on. */
internal enum class EngineKind { PLATFORM }

internal object VideoParity {

    /** The engine every video opens in. */
    val DEFAULT = EngineKind.PLATFORM

    /**
     * Whether [engine] does [feature], as tested on a phone - not as hoped. Exhaustive, so a new
     * engine has to answer for every feature before it compiles.
     */
    fun passes(engine: EngineKind, feature: VideoFeature): Boolean = when (engine) {
        // The player all of them were built on.
        EngineKind.PLATFORM -> when (feature) {
            VideoFeature.SEEK, VideoFeature.SPEED, VideoFeature.FRAME_STEP,
            VideoFeature.DOUBLE_TAP_SKIP, VideoFeature.SOUND_EFFECTS, VideoFeature.PAN_8D,
            VideoFeature.RATE_EFFECTS, VideoFeature.PICTURE_IN_PICTURE,
            VideoFeature.TAP_TO_PLAY_PREVIEW, VideoFeature.RESUME_POINTS,
            -> true
        }
    }

    /** What [engine] still lacks. */
    fun missing(engine: EngineKind): List<VideoFeature> = VideoFeature.entries.filterNot { passes(engine, it) }

    /** Only an engine that misses nothing may be the one every video opens in. */
    fun canBeDefault(engine: EngineKind): Boolean = missing(engine).isEmpty()
}

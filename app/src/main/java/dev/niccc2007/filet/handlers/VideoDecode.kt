package dev.niccc2007.filet.handlers

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build

/**
 * Whether this phone can draw a video's picture, and the words for when it did not.
 *
 * ## The failure this exists for
 *
 * Filet plays video through the platform's `MediaPlayer`, and the platform picks the decoder.
 * When the VIDEO decoder cannot take a file and the audio one can, `MediaPlayer` carries on with
 * the sound alone and never calls its error listener: the screen stays black, the sound plays,
 * and nothing says why. Bug identified on a 2160 x 3402, 60 fps H.264 file on a Snapdragon 778G,
 * whose hardware decoder stops at 4096 x 2176 and 1,044,480 blocks a second (that file needs
 * 1,725,300). Other players drew it because they choose their own decoder; this one cannot.
 *
 * ## Why the test is the picture, not the capability list
 *
 * A decoder's stated limits are a promise, not a measurement, and several decode past them -
 * other players on that same phone did. Refusing to start whatever the list says no to would
 * break videos that play today. So nothing is decided up front: [PictureWatch] waits for the
 * first frame, and only once it has failed to come are the limits read, to say why. A video the
 * phone can draw is never interrupted, whatever the list says, and one it cannot is never left
 * black, whatever the cause - a size, a frame rate, or a codec the phone has no decoder for.
 */
internal object VideoDecode {

    /** A video track, as the file states it. [fps] is null when the file does not say. */
    data class Stream(val mime: String, val width: Int, val height: Int, val fps: Double?)

    /**
     * One decoder this phone has for a stream's type, and what it says about this stream.
     *
     * @param maxWidth the widest picture it takes at all.
     * @param maxHeight the tallest.
     * @param sizeFits it takes this picture size.
     * @param fits it takes this size at this frame rate - what it would need to keep up.
     * @param maxFps the most frames a second it takes at this size, when [sizeFits].
     */
    data class Decoder(
        val name: String,
        val hardware: Boolean,
        val maxWidth: Int,
        val maxHeight: Int,
        val sizeFits: Boolean,
        val fits: Boolean,
        val maxFps: Double? = null,
    )

    /**
     * How much playing, with sound and no picture, before the picture is given up on.
     *
     * Three seconds from a file on the phone. A first frame arrives within a second there even
     * at 4K, and a decoder still silent after three seconds of the sound running is not about to
     * start.
     */
    const val NO_PICTURE_MS = 3_000L

    /**
     * And from a share, copied in to be played.
     *
     * Eight. The copy is read while it plays, and the sound can start from the first part of it
     * before the video's first whole frame is there - a slow network is not a decoder failing.
     */
    const val NO_PICTURE_STAGED_MS = 8_000L

    /** The window for a source: [staged] is a copy made from a share rather than the file itself. */
    fun windowFor(staged: Boolean): Long = if (staged) NO_PICTURE_STAGED_MS else NO_PICTURE_MS

    /** Whether a prepared player's tracks include a picture. Never true when they cannot be read. */
    fun hasVideoTrack(trackTypes: List<Int>): Boolean = MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO in trackTypes

    fun hasVideoTrack(mp: MediaPlayer): Boolean =
        hasVideoTrack(runCatching { mp.trackInfo.map { it.trackType } }.getOrDefault(emptyList()))

    /** A type as people know it, for the words. */
    fun codecName(mime: String): String = when (mime.lowercase()) {
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265 (HEVC)"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/dolby-vision" -> "Dolby Vision"
        "video/mp4v-es" -> "MPEG-4"
        "video/3gpp" -> "H.263"
        "video/mpeg2" -> "MPEG-2"
        else -> mime.substringAfter('/').uppercase()
    }

    /** A frame rate without noise: 60, 29.97. */
    fun fpsText(fps: Double): String {
        val r = Math.round(fps * 100) / 100.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
    }

    /**
     * What to say when the sound played and no picture came.
     *
     * Only what is known. The limits come from the phone's own list, and the list can say a
     * decoder takes a file it then fails to draw - which is said as that, rather than as a
     * guess at the cause.
     */
    fun words(stream: Stream?, decoders: List<Decoder>): String {
        val lead = "The sound played, but no picture came."
        val tail = " Another app may still show it."
        if (stream == null) return "$lead Filet's player could not draw this video on this phone.$tail"
        val kind = codecName(stream.mime)
        val what = "$kind video at ${stream.width} × ${stream.height}" +
            (stream.fps?.let { " and ${fpsText(it)} frames a second" } ?: "")
        if (decoders.isEmpty()) {
            return "$lead This is $what, and this phone has no decoder for $kind.$tail"
        }
        val hardware = decoders.filter { it.hardware }
        val judged = hardware.ifEmpty { decoders }
        val which = if (hardware.isNotEmpty()) "hardware video decoder" else "video decoder"
        val limit = judged.firstOrNull { !it.fits && it.sizeFits && it.maxFps != null }
            ?.let { " takes at most ${fpsText(it.maxFps!!)} frames a second at that size" }
            ?: judged.maxBy { it.maxWidth.toLong() * it.maxHeight }
                .let { " takes pictures up to ${it.maxWidth} × ${it.maxHeight}" }
        return when {
            judged.none { it.fits } && hardware.isNotEmpty() && decoders.any { !it.hardware && it.fits } ->
                "$lead This is $what. This phone's $which$limit. Only its software decoder says " +
                    "it takes this, and the picture still did not come.$tail"
            judged.none { it.fits } -> "$lead This is $what. This phone's $which$limit.$tail"
            else -> "$lead This is $what, which this phone says it can decode, so the cause is not " +
                "its size or speed.$tail"
        }
    }

    // ── read from the phone, only once a picture has failed to come ──

    /** The file's first video track, or null when it cannot be read. */
    fun streamOf(context: Context, uri: Uri): Stream? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.let { f ->
                    Stream(
                        mime = f.getString(MediaFormat.KEY_MIME)!!,
                        width = f.getInteger(MediaFormat.KEY_WIDTH),
                        height = f.getInteger(MediaFormat.KEY_HEIGHT),
                        fps = frameRate(f),
                    )
                }
        } finally {
            ex.release()
        }
    }.getOrNull()

    private fun frameRate(f: MediaFormat): Double? {
        if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) return null
        return (runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull()
            ?: runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull())
            ?.takeIf { it > 0 }
    }

    /** Every ordinary decoder this phone has for [stream]'s type. Secure ones are for DRM only. */
    fun decodersFor(stream: Stream): List<Decoder> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && !it.name.endsWith(".secure") }
            .filter { info -> info.supportedTypes.any { it.equals(stream.mime, ignoreCase = true) } }
            .mapNotNull { info -> runCatching { decoder(info, stream) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun decoder(info: MediaCodecInfo, s: Stream): Decoder? {
        val vc = info.getCapabilitiesForType(s.mime).videoCapabilities ?: return null
        val sizeFits = vc.isSizeSupported(s.width, s.height)
        return Decoder(
            name = info.name,
            hardware = hardware(info),
            maxWidth = vc.supportedWidths.upper,
            maxHeight = vc.supportedHeights.upper,
            sizeFits = sizeFits,
            fits = if (s.fps != null) vc.areSizeAndRateSupported(s.width, s.height, s.fps) else sizeFits,
            maxFps = if (sizeFits) runCatching { vc.getSupportedFrameRatesFor(s.width, s.height).upper }.getOrNull() else null,
        )
    }

    private fun hardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            info.isHardwareAccelerated
        } else {
            val n = info.name.lowercase()
            !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || ".sw." in n)
        }
}

/**
 * The first-frame watch: whether a video that is playing has drawn its picture yet.
 *
 * It counts only time the video is actually running - playing, not seeking, and its position
 * moving - so a paused video, a scrub, or a stall waiting on a share never adds to it. A seek or
 * a resume starts the count again. It answers true once, the moment [window] has gone by
 * running with no picture, and never again for this opening: the screen says so once, in words.
 *
 * @param window how long, from [VideoDecode.windowFor].
 */
internal class PictureWatch(private val window: Long) {

    private var drawn = false
    private var fired = false
    private var running = false
    private var ran = 0L

    /** The player said its first frame is on screen. */
    fun drawn() {
        drawn = true
    }

    /** Whether it has said its piece, or has nothing to say: either way, no more ticks needed. */
    val done: Boolean get() = drawn || fired

    /**
     * One look, [ms] after the last.
     *
     * @param playing the player is playing, not paused, and no seek or scrub is in hand.
     * @param advanced its position moved since the last look: the sound is really running.
     * @param seeked a seek finished since the last look.
     * @return true exactly once, when the picture is overdue.
     */
    fun tick(ms: Long, playing: Boolean, advanced: Boolean, seeked: Boolean): Boolean {
        if (done) return false
        if (seeked || !playing || !advanced) {
            running = false
            if (seeked) ran = 0L
            return false
        }
        if (!running) {
            running = true
            ran = 0L
        }
        ran += ms
        if (ran < window) return false
        fired = true
        return true
    }

    companion object {
        /** How often it looks. Fine enough that the words come within half a second of due. */
        const val TICK_MS = 500L
    }
}

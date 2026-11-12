package dev.niccc2007.filet.handlers

/**
 * The players a video is tried on, in order, when the one before could not play it.
 *
 * Bug identified here: a file the first player could not decode ended at an error line or a black
 * picture with the sound running, even when another player in the app would have played it. A
 * 4.1 GB MKV stopped at "error 1/-2147483648", and short phone recordings played sound over black,
 * while the same files played in VLC and in the phone's gallery. The fix is what those players
 * do: try the next decoder instead of giving up on the first.
 *
 * Decision: the move is made without asking, at the same position, with a line saying so. The
 * alternative was a "Try the other player" button, which only ever has one useful answer.
 *
 * - [PLATFORM]: the phone's built-in player.
 * - [MEDIA3]: Filet's player - Media3, the phone's decoders first, FFmpeg for anything the phone
 *   has none for.
 * - [FFMPEG]: Media3 with FFmpeg decoding the picture, for files the phone's decoder claims and
 *   then gets wrong. Only where FFmpeg is in the build and loaded.
 */
internal enum class VideoRung { PLATFORM, MEDIA3, FFMPEG }

internal object VideoLadder {

    /** Where a video starts: the rung for the engine chosen. */
    fun first(kind: EngineKind): VideoRung = when (kind) {
        EngineKind.PLATFORM -> VideoRung.PLATFORM
        EngineKind.MEDIA3 -> VideoRung.MEDIA3
    }

    /** The engine a rung runs on: the one the player menu shows ticked. */
    fun engineOf(rung: VideoRung): EngineKind = when (rung) {
        VideoRung.PLATFORM -> EngineKind.PLATFORM
        VideoRung.MEDIA3, VideoRung.FFMPEG -> EngineKind.MEDIA3
    }

    /**
     * The order a video started on [start] is tried in. Filet's own rungs come first when it is
     * the one chosen, and the phone's player is still there at the end: a file that Media3 and
     * FFmpeg both refuse is rare, but the phone's player is the one with the vendor's own decoders,
     * and the point of the ladder is not to give up while a player is left.
     */
    fun order(start: VideoRung): List<VideoRung> = when (start) {
        VideoRung.PLATFORM -> listOf(VideoRung.PLATFORM, VideoRung.MEDIA3, VideoRung.FFMPEG)
        VideoRung.MEDIA3 -> listOf(VideoRung.MEDIA3, VideoRung.FFMPEG, VideoRung.PLATFORM)
        VideoRung.FFMPEG -> listOf(VideoRung.FFMPEG, VideoRung.PLATFORM)
    }

    /**
     * The rung after [rung] failed, for a video started on [start], or null when nothing is left.
     *
     * [pictureByFfmpeg]: FFmpeg was already drawing the picture on [rung]. Bug identified here: a
     * video Media3 had handed to FFmpeg played badly, and the step down was to [VideoRung.FFMPEG] -
     * the same decoder again, at the cost of a reload. The step skips it when it would change
     * nothing.
     */
    fun next(rung: VideoRung, ffmpeg: Boolean, pictureByFfmpeg: Boolean = false, start: VideoRung = VideoRung.PLATFORM): VideoRung? {
        val order = order(start)
        val at = order.indexOf(rung)
        if (at < 0) return null
        return order.drop(at + 1).firstOrNull { candidate ->
            when (candidate) {
                VideoRung.FFMPEG -> ffmpeg && !(rung == VideoRung.MEDIA3 && pictureByFfmpeg)
                VideoRung.PLATFORM, VideoRung.MEDIA3 -> true
            }
        }
    }

    /** The line shown when the video falls to [rung] because the one before could not play it. */
    fun notice(rung: VideoRung): String = when (rung) {
        VideoRung.PLATFORM -> "Filet's player could not play this. Trying the phone's player."
        VideoRung.MEDIA3 -> "The phone's player could not play this. Trying Filet's player."
        VideoRung.FFMPEG -> "Decoding the picture with FFmpeg"
    }

    /** The line shown when the player menu switches to [kind], carrying on at [at]. */
    fun switched(kind: EngineKind, at: String): String = when (kind) {
        EngineKind.MEDIA3 -> "Filet's player · carrying on at $at"
        EngineKind.PLATFORM -> "The phone's player · carrying on at $at"
    }
}

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
 * - [MEDIA3]: Media3, the phone's decoders first, FFmpeg for anything the phone has none for.
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

    /** The rung after [rung] failed, or null when nothing is left to try. */
    fun next(rung: VideoRung, ffmpeg: Boolean): VideoRung? = when (rung) {
        VideoRung.PLATFORM -> VideoRung.MEDIA3
        VideoRung.MEDIA3 -> if (ffmpeg) VideoRung.FFMPEG else null
        VideoRung.FFMPEG -> null
    }

    /** The line shown when the video moves to [rung]. */
    fun notice(rung: VideoRung): String = when (rung) {
        VideoRung.PLATFORM -> "Playing with the phone's player"
        VideoRung.MEDIA3 -> "The phone's player could not play this. Trying Filet's player."
        VideoRung.FFMPEG -> "Decoding the picture with FFmpeg"
    }
}

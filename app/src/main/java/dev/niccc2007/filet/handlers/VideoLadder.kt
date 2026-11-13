package dev.niccc2007.filet.handlers

/**
 * The players a video can be played on, and what is offered when the one playing it fails.
 *
 * - [PLATFORM]: the phone's built-in player.
 * - [MEDIA3]: Filet's player - Media3, the phone's hardware decoders first, FFmpeg for anything the
 *   phone has no decoder for.
 * - [FFMPEG]: Media3 with FFmpeg decoding the picture, for files the phone's decoder claims and
 *   then gets wrong. Only where FFmpeg is in the build and loaded.
 */
internal enum class VideoRung { PLATFORM, MEDIA3, FFMPEG }

/**
 * Decision: **a video never changes player on its own.** It plays on the player chosen in the menu
 * or in Settings, and only there.
 *
 * Bug identified here: this used to be a ladder that moved a video to the next player by itself
 * when the first-frame watch saw no picture. A 4K 60 fps H.264 file dropped frames on a software
 * decoder, the watch read that as no picture, and the video walked Filet's player, then FFmpeg,
 * then the phone's player - which it was then stuck on, still lagging, without anyone choosing it.
 * Dropped frames are not a failure: the sound keeps time and late pictures are skipped, as in VLC.
 *
 * A real failure - an error, or no picture at all - stays on the player it happened on and says
 * what went wrong, and the other players are [offers]: one tap each, never taken without it.
 */
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
     * What a failure on [rung] offers, in the order shown. Pure.
     *
     * [ffmpeg]: FFmpeg is in the build and loaded. [pictureByFfmpeg]: it was already drawing the
     * picture on [rung] - Media3 hands it the picture by itself for a format the phone has no
     * decoder for - so offering it again would change nothing.
     */
    fun offers(rung: VideoRung, ffmpeg: Boolean, pictureByFfmpeg: Boolean = false): List<VideoRung> = when (rung) {
        VideoRung.MEDIA3 -> listOfNotNull(VideoRung.FFMPEG.takeIf { ffmpeg && !pictureByFfmpeg }, VideoRung.PLATFORM)
        VideoRung.FFMPEG -> listOf(VideoRung.PLATFORM)
        VideoRung.PLATFORM -> listOf(VideoRung.MEDIA3)
    }

    /** The tap that tries [rung], for this video only. */
    fun offerLabel(rung: VideoRung): String = when (rung) {
        VideoRung.PLATFORM -> "Try the phone's player"
        VideoRung.MEDIA3 -> "Try Filet's player"
        VideoRung.FFMPEG -> "Decode the picture with FFmpeg"
    }

    /** The line shown once an offer was taken: where this video is now, and that it is only this one. */
    fun tried(rung: VideoRung, at: String): String = when (rung) {
        VideoRung.PLATFORM -> "The phone's player, for this video · at $at"
        VideoRung.MEDIA3 -> "Filet's player, for this video · at $at"
        VideoRung.FFMPEG -> "FFmpeg decoding the picture, for this video · at $at"
    }

    /** The line shown when the player menu switches to [kind], carrying on at [at]. */
    fun switched(kind: EngineKind, at: String): String = when (kind) {
        EngineKind.MEDIA3 -> "Filet's player · carrying on at $at"
        EngineKind.PLATFORM -> "The phone's player · carrying on at $at"
    }

    /**
     * What went wrong, in words, for an error on [rung]. Pure. [what] is Media3's error code on
     * Filet's player and `MediaPlayer`'s what on the phone's, with [extra] beside it; the numbers
     * stay in the line, because they are what a bug report needs.
     */
    fun failure(rung: VideoRung, what: Int, extra: Int): String {
        val who = if (rung == VideoRung.PLATFORM) "The phone's player" else "Filet's player"
        val why = if (rung == VideoRung.PLATFORM) platformWhy(what, extra) else media3Why(what)
        return "$who could not play this video: $why (error $what/$extra)."
    }

    // Media3's PlaybackException codes, by range: 2xxx reading, 3xxx the file's structure, 4xxx
    // decoding, 5xxx the sound output, 6xxx DRM.
    private fun media3Why(code: Int): String = when (code) {
        4001 -> "the decoder would not start"
        4003 -> "decoding failed partway"
        4004 -> "the file is beyond what this phone's decoders take"
        4005 -> "no decoder here plays this format"
        in 4000..4999 -> "the decoder failed"
        in 3000..3999 -> "the file looks damaged or is in a form the player cannot read"
        in 2000..2999 -> "the file could not be read"
        in 5000..5999 -> "the sound could not be played"
        in 6000..6999 -> "the video is protected"
        else -> "something unexpected went wrong"
    }

    // MediaPlayer's what is nearly always 1 (unknown); its extra carries the reason.
    private fun platformWhy(what: Int, extra: Int): String = when {
        extra == -1004 -> "the file could not be read"
        extra == -1007 -> "the file looks damaged"
        extra == -1010 -> "this format is not supported"
        extra == -110 -> "it timed out"
        what == 100 -> "the phone's media service stopped"
        else -> "something unexpected went wrong"
    }
}

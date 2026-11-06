package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.playback.Finished

/**
 * Where a video carries on from, between the video screen, its preview and Carry on.
 *
 * The video screen plays on its own player, not the music player, so the music player's record
 * of where a track got to never saw a video, and Carry on never listed one. The screen now writes
 * `ResumeStore` itself - on a pause, on Back, on leaving, and when the app goes to the background
 * - and writes its end when it plays to the end, which Carry on tags finished. Every stop is
 * kept, whatever the length and however far in: see `ResumeStore.keeps`. Whatever Resume
 * playback is set to: the setting decides how a video opens, not whether Carry on hears of it.
 *
 * One record, so Carry on and the preview's Play from always agree. A finished place - 99% or
 * more, see [Finished] - is listed, and opens at 0:00: carrying on at the end would end at once.
 *
 * The preview reads the same record, so its card survives the preview being scrolled away and
 * back, and is wherever the video was last left: see [MediaResume], which decides where a video
 * starts and what the card offers. This is the take-over from the music player.
 *
 * Pure, so every case is a test.
 */
object VideoResume {

    /**
     * Where the screen starts when it takes the video over from the music player.
     *
     * The music player's live position when it has one. Carry on opens a video by asking the
     * music player to play it from the saved place and then opening the screen, and the music
     * player's position reads 0 from the moment it is asked until its own decoder has opened
     * the file and sought. A screen whose decoder was ready first took that 0 and started the
     * video from the beginning. Until the music player has a position, the saved place is what
     * it was asked to seek to - the start, for a finished one.
     *
     * @param liveMs the music player's position now.
     * @param savedMs the saved place for this video, null when there is none.
     * @param savedDurationMs the length saved with it, 0 when unknown.
     */
    fun takeOverAt(liveMs: Long, savedMs: Long?, savedDurationMs: Long): Long =
        if (liveMs > 0L) liveMs else savedMs?.let { Finished.startAt(it.coerceAtLeast(0L), savedDurationMs) } ?: 0L

}

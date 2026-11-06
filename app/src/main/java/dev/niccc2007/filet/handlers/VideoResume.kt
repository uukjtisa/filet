package dev.niccc2007.filet.handlers

/**
 * Where a video carries on from, between the video screen, its preview and Carry on.
 *
 * The video screen plays on its own player, not the music player, so the music player's
 * record of where a track got to never saw a video, and Carry on never listed one. The screen now
 * writes `ResumeStore` itself - on a pause, on Back, on leaving, and when the app goes to the
 * background - and forgets the video when it plays to the end. What is worth keeping is still
 * `ResumeStore.worthKeeping`'s call: nothing under fifteen seconds long, nothing in the first
 * ten seconds or the last twenty.
 *
 * The preview reads the same record, so its Play from survives the preview being scrolled away
 * and back, and is whatever the video was last left at, wherever it was left.
 *
 * Pure, so every case is a test.
 */
object VideoResume {

    /**
     * Where Play starts, and what the preview offers: the saved place, else where Back stopped
     * it on this visit, else the start.
     *
     * The saved place first because it is the one that outlives the screen. The stop on this
     * visit is for what the store will not keep - a clip too short, a stop too near either end -
     * where carrying on after an accidental Back still matters for as long as the screen is up.
     *
     * @param savedMs `ResumeStore.positionOf` for this video, null when there is none.
     * @param stoppedAtMs where Back stopped it on this visit, 0 if it has not.
     */
    fun playFrom(savedMs: Long?, stoppedAtMs: Long): Long =
        savedMs?.takeIf { it > 0L } ?: stoppedAtMs.coerceAtLeast(0L)

    /**
     * Where the screen starts when it takes the video over from the music player.
     *
     * The music player's live position when it has one. Carry on opens a video by asking the
     * music player to play it from the saved place and then opening the screen, and the music
     * player's position reads 0 from the moment it is asked until its own decoder has opened
     * the file and sought. A screen whose decoder was ready first took that 0 and started the
     * video from the beginning. Until the music player has a position, the saved place is what
     * it was asked to seek to: Carry on forgets the record before asking for the start.
     *
     * @param liveMs the music player's position now.
     * @param savedMs `ResumeStore.positionOf` for this video, null when there is none.
     */
    fun takeOverAt(liveMs: Long, savedMs: Long?): Long =
        if (liveMs > 0L) liveMs else savedMs?.coerceAtLeast(0L) ?: 0L

    /** The preview's pill: what Play does, said as the time it plays from. */
    fun label(fromMs: Long): String = "Play from ${clockOf(fromMs)}"
}

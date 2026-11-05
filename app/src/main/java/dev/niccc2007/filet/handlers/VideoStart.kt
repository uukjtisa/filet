package dev.niccc2007.filet.handlers

/**
 * Whether a video plays the moment it is on screen or waits under its preview for a tap, and
 * what Back does to it.
 *
 * Bug identified here: a tap on a video's tile started it with sound at once, wherever the tap
 * was - a folder, a search, a category, New files - so looking at a clip in a quiet room was the
 * same act as playing it out loud. A video now opens under its preview (see `VideoPreview`), a
 * silent glimpse of itself, the way the picture viewer opens a picture, and plays with sound only
 * when asked.
 *
 * Two cases do not wait:
 *
 * - **Taken back from the music player.** A video sent to the background as sound and opened
 *   again is a hand-off in the middle of playing, not an opening. Stopping it at a preview
 *   would be the music cutting out.
 * - **Arrived at in a run, when the setting says so.** A run's arrows and its swipe move from
 *   one file to the next; whether a video reached that way plays at once is the Next video
 *   setting, off by default, so moving through a gallery is quiet unless asked otherwise.
 *
 * ## Back
 *
 * Back while it plays stops it and goes back to the preview, at the frame it stopped on; Back
 * from the preview leaves, as it always did. Playing is one level deeper than looking, so Back
 * undoes one level at a time - and a Back pressed by accident in the middle of something is a
 * Play away from carrying on, not a reopen and a hunt for the place.
 *
 * Not while it is being handed to the music player: that is already leaving, and Back during it
 * leaves with it as before.
 *
 * Pure, so every case is a test.
 */
object VideoStart {

    /** What the video screen is showing: the preview, or the player with its sound. */
    enum class Face { PREVIEW, PLAYING }

    /** What Back does. */
    enum class BackStep {
        /** Stop the player and show the preview at the frame it stopped on. */
        TO_PREVIEW,

        /** Leave the video, which is the viewer's own Back. */
        LEAVE,
    }

    /**
     * The face a video opens on.
     *
     * @param takingOver the music player holds this file and the screen is taking it back.
     * @param arrived the video was reached by a run's arrows or its swipe, not opened.
     * @param nextPlays the Next video setting: an arrived-at video plays at once.
     */
    fun opensOn(takingOver: Boolean, arrived: Boolean, nextPlays: Boolean): Face =
        if (takingOver || (arrived && nextPlays)) Face.PLAYING else Face.PREVIEW

    /**
     * What Back does on [face].
     *
     * @param handingOver the sound is on its way to the music player.
     */
    fun back(face: Face, handingOver: Boolean): BackStep =
        if (face == Face.PLAYING && !handingOver) BackStep.TO_PREVIEW else BackStep.LEAVE

    /**
     * Whether [page] of a run was arrived at rather than opened.
     *
     * The page the run was opened on is opened, until the run has moved off it once; after
     * that, coming back to it is arriving like any other page.
     *
     * @param opened the page the run was opened on.
     * @param movedOff the run has stood on another page since it opened.
     */
    fun arrived(page: Int, opened: Int, movedOff: Boolean): Boolean = page != opened || movedOff
}

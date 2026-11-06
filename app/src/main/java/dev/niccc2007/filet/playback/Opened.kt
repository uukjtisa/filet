package dev.niccc2007.filet.playback

/**
 * Why the current track started playing. A continue card is right for one of these and wrong
 * for the rest, and only the player knows which happened - so it says, rather than every
 * screen guessing from a position.
 */
enum class OpenedBy {
    /** Opened from a list, a folder or a search: the moment a remembered place is news. */
    OPEN,

    /** A Carry on row: the tap already chose where to start. */
    CARRY_ON,

    /** Next, Previous, the end of a track moving on, repeat-one, or a row of the queue sheet. */
    QUEUE,

    /** The track already playing, opened again, or reloaded under the notification's Play. */
    RETURN,

    /** A video's sound taken over by the music player at the video's own position. */
    HAND_OFF,
}

/**
 * How the current track opened.
 *
 * @param startedAtMs where it actually started.
 * @param stored the remembered place worth offering, read whatever the Resume playback setting
 *   says: null when nothing is stored, it is at 0:00, or it is finished (see [Finished]). With
 *   the setting on and nothing asked for, [startedAtMs] is this place; with it off the track
 *   starts at 0:00 and this is what a card can offer to go back to.
 */
data class Opened(
    val trackId: String,
    val startedAtMs: Long,
    val stored: Long?,
    val by: OpenedBy,
)

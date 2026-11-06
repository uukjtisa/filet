package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.playback.Finished
import dev.niccc2007.filet.playback.Opened
import dev.niccc2007.filet.playback.OpenedBy

/**
 * Where a video or a song starts, the video preview's Play from pill, and when the players'
 * continue card offers a place. The card itself is the shared `ui.ContinueCard`; this is only
 * what goes on it, and when.
 *
 * ## Resume playback, on and off
 *
 * The setting decides how media opens, never what Carry on records (see `ResumeStore.keeps`):
 *
 * - **On**: it opens where it stopped. Nothing to offer, so no card.
 * - **Off**: it opens at 0:00, and while it plays the card offers the place, as a document
 *   offers its spot: "Continue playing at 12:34".
 *
 * The card is an offer and never a notice: it is there only when playing started at 0:00 while a
 * place was saved, and a tap goes to the place. A finished place - 99% or more, see [Finished] -
 * is no place to carry on from, and offers nothing.
 *
 * ## The preview
 *
 * The video's tap-to-play preview keeps its own way to the place, whatever the setting: the
 * Play from pill under the play button. Play itself follows the setting. The card is the
 * playing screen's, never the preview's.
 *
 * Pure, so every case is a test.
 */
object MediaResume {

    /** The card: the place offered, and where a tap on it goes. */
    data class Offer(val atMs: Long) {
        val title: String get() = "Continue playing at ${clockOf(atMs)}"

        /** For a screen reader: what closing the card leaves things as. */
        val closeLabel: String get() = "Stay where it is"
    }

    /**
     * A video's preview.
     *
     * @param playFromMs where Play starts.
     * @param pillMs where the Play from pill starts, or null for no pill.
     */
    data class Preview(val playFromMs: Long, val pillMs: Long?) {
        val pillLabel: String? get() = pillMs?.let { "Play from ${clockOf(it)}" }
    }

    /** A video or a song opening straight into playing: where it starts, and its card. */
    data class Opening(val startMs: Long, val offer: Offer?)

    /** The saved place worth carrying on from, or null: none, at 0:00, or finished. */
    fun place(savedMs: Long?, durationMs: Long): Long? =
        savedMs?.takeIf { it > 0L }?.let { Finished.startAt(it, durationMs) }?.takeIf { it > 0L }

    /** The card for playing that started at [startedAtMs], with [placeMs] saved: only from 0:00. */
    fun offer(startedAtMs: Long, placeMs: Long?): Offer? =
        placeMs?.takeIf { it > 0L && startedAtMs <= 0L }?.let(::Offer)

    /**
     * The tap-to-play preview.
     *
     * Back on this visit is a stop like any other, and Play carries on from it whatever the
     * setting: it is a step back from playing, not an opening, and the picture is the frame it
     * stopped on. Otherwise Play goes where the setting says - the saved place when on, the
     * start when off. The pill goes to the place either way.
     *
     * @param resumeOn the Resume playback setting.
     * @param savedMs the saved place for this video, null when there is none.
     * @param durationMs the length saved with it, 0 when unknown.
     * @param stoppedAtMs where Back stopped it on this visit, 0 if it has not.
     */
    fun preview(resumeOn: Boolean, savedMs: Long?, durationMs: Long, stoppedAtMs: Long): Preview {
        val saved = place(savedMs, durationMs)
        val stopped = place(stoppedAtMs, durationMs)
        val from = stopped ?: saved?.takeIf { resumeOn } ?: 0L
        return Preview(from, saved ?: stopped)
    }

    /** A video opened straight into playing - reached in a run with Next video on. */
    fun opening(resumeOn: Boolean, savedMs: Long?, durationMs: Long): Opening {
        val saved = place(savedMs, durationMs) ?: return Opening(0L, null)
        return if (resumeOn) Opening(saved, null) else Opening(0L, Offer(saved))
    }

    /**
     * The music player's card for the track that just opened, or null.
     *
     * Opened from a list, or moved on to in the queue - Next, Previous, the end of a track, a
     * row of the queue sheet - it is the video's opening: started at 0:00 with a place saved
     * offers it. The rest asked already: a Carry on row chose where to start, the track already
     * playing carries on, and a video's sound carries on at the video's own place.
     */
    fun forTrack(opened: Opened?): Offer? {
        val o = opened ?: return null
        return when (o.by) {
            OpenedBy.OPEN, OpenedBy.QUEUE -> offer(o.startedAtMs, o.stored)
            OpenedBy.CARRY_ON, OpenedBy.RETURN, OpenedBy.HAND_OFF -> null
        }
    }
}

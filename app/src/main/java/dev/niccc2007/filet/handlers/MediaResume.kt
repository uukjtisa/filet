package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.playback.Finished
import dev.niccc2007.filet.playback.Opened
import dev.niccc2007.filet.playback.OpenedBy

/**
 * Where a video or a song starts when it opens, and what its continue card says - the media
 * players' half of the documents' reading spot. The card itself is the shared
 * `ui.ContinueCard`; this is only what goes on it, and when.
 *
 * ## Resume playback, on and off
 *
 * The setting decides how media opens, never what Carry on records (see `ResumeStore.keeps`):
 *
 * - **On**: it opens where it stopped, and the card says so and offers the start:
 *   "Continued from 12:34 · Start over".
 * - **Off**: it opens at 0:00, and the card offers the place, as a document offers its spot:
 *   "Continue where you left off · 12:34".
 *
 * A finished place - 99% or more, see [Finished] - is no place to carry on from: it opens at
 * 0:00 and offers nothing.
 *
 * The video's tap-to-play preview carries the same card in place of a pill of its own, saying
 * what Play does - "Play from 0:09 · Start over" - and staying for as long as the preview is up,
 * since nothing plays under it.
 *
 * The music player decides where a track starts itself, and says how it opened - see
 * `playback.Opened`. [forTrack] reads that the same way [opening] reads a video.
 *
 * Pure, so every case is a test.
 */
object MediaResume {

    /** What the card says, and where a tap on it goes. */
    data class Card(val kind: Kind, val atMs: Long) {

        enum class Kind {
            /** The preview: Play carries on from [atMs]; the card offers the start. */
            PLAY_FROM,

            /** Opened where it stopped, at [atMs]; the card offers the start. */
            CONTINUED,

            /** Opened at the start; the card offers [atMs]. */
            OFFER,
        }

        val title: String
            get() = when (kind) {
                Kind.PLAY_FROM -> "Play from ${clockOf(atMs)}"
                Kind.CONTINUED -> "Continued from ${clockOf(atMs)}"
                Kind.OFFER -> "Continue where you left off"
            }

        val detail: String
            get() = when (kind) {
                Kind.PLAY_FROM, Kind.CONTINUED -> "Start over"
                Kind.OFFER -> clockOf(atMs)
            }

        /** For a screen reader: what closing the card leaves things as. */
        val closeLabel: String
            get() = when (kind) {
                Kind.PLAY_FROM, Kind.CONTINUED -> "Keep the place"
                Kind.OFFER -> "Stay at the start"
            }

        /** Where a tap on the card goes: the start, or the place it offers. */
        val goesTo: Long get() = if (kind == Kind.OFFER) atMs else 0L
    }

    /** A video's preview: what Play does, and its card. */
    data class Preview(val playFromMs: Long, val card: Card?)

    /** A video or a song opening straight into playing: where it starts, and its card. */
    data class Opening(val startMs: Long, val card: Card?)

    /** The saved place worth carrying on from, or null: none, at 0:00, or finished. */
    private fun place(savedMs: Long?, durationMs: Long): Long? =
        savedMs?.takeIf { it > 0L }?.let { Finished.startAt(it, durationMs) }?.takeIf { it > 0L }

    /**
     * The tap-to-play preview.
     *
     * Back on this visit is a stop like any other, and Play carries on from it whatever the
     * setting: it is a step back from playing, not an opening. Otherwise Play goes where the
     * setting says - the saved place when on, the start when off, with the place on the card.
     *
     * @param resumeOn the Resume playback setting.
     * @param savedMs the saved place for this video, null when there is none.
     * @param durationMs the length saved with it, 0 when unknown.
     * @param stoppedAtMs where Back stopped it on this visit, 0 if it has not.
     */
    fun preview(resumeOn: Boolean, savedMs: Long?, durationMs: Long, stoppedAtMs: Long): Preview {
        val saved = place(savedMs, durationMs)
        val stopped = place(stoppedAtMs, durationMs)
        val from = (if (resumeOn) saved ?: stopped else stopped) ?: 0L
        val card = when {
            from > 0L -> Card(Card.Kind.PLAY_FROM, from)
            saved != null -> Card(Card.Kind.OFFER, saved)
            else -> null
        }
        return Preview(from, card)
    }

    /**
     * A video opened straight into playing - reached in a run with Next video on - or a song
     * opened from a list.
     */
    fun opening(resumeOn: Boolean, savedMs: Long?, durationMs: Long): Opening {
        val saved = place(savedMs, durationMs) ?: return Opening(0L, null)
        return if (resumeOn) {
            Opening(saved, Card(Card.Kind.CONTINUED, saved))
        } else {
            Opening(0L, Card(Card.Kind.OFFER, saved))
        }
    }

    /**
     * The music player's card for the track that just opened, or null.
     *
     * Opened from a list, or moved on to in the queue - Next, Previous, the end of a track, a
     * row of the queue sheet - it is the video's opening: started at the place says so, started
     * at 0:00 with a place offers it. The rest asked already: a Carry on row chose where to
     * start, the track already playing carries on, and a video's sound carries on at the
     * video's own place.
     */
    fun forTrack(opened: Opened?): Card? {
        val o = opened ?: return null
        return when (o.by) {
            OpenedBy.OPEN, OpenedBy.QUEUE -> {
                val stored = o.stored?.takeIf { it > 0L } ?: return null
                when {
                    o.startedAtMs == stored -> Card(Card.Kind.CONTINUED, stored)
                    o.startedAtMs <= 0L -> Card(Card.Kind.OFFER, stored)
                    else -> null
                }
            }
            OpenedBy.CARRY_ON, OpenedBy.RETURN, OpenedBy.HAND_OFF -> null
        }
    }
}

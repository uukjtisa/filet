package dev.niccc2007.filet.handlers

import kotlin.random.Random

/**
 * What a video's glimpse plays: short clips from across the video, so a held tile shows what the
 * whole video is rather than how it starts.
 *
 * ## The rule, and where it comes from
 *
 * Three clips of a second and a half, from the middle 70% of the video (15% to 85%), one from
 * each third of that stretch at a random spot inside it. The timing is NxDashVids' content
 * preview, which is the feel this was asked to have.
 *
 * It was six clips of two seconds, one from each sixth starting at 0%: twelve seconds a round.
 * A tile is held for a few seconds, so what anybody saw was the first clip or two, both from the
 * opening third - too slow, and not spread across the video, which are the same fault. Now a
 * round is four and a half seconds, all of it from across the video.
 *
 * The 15% and 85% leave out the parts least like the rest: a phone video's opening seconds are
 * usually somebody finding the subject, and a download's ending is often credits or a card. A
 * random spot rather than a fixed one, so holding the same tile twice shows two moments.
 *
 * When that middle stretch is too short to hold the clips with room to spare (less than the
 * clips' length plus two seconds), the stretch is everything but the first and last two seconds
 * instead, as NxDashVids does. Clips may overlap there; the video is short enough that they
 * still cover it.
 *
 * ## Short videos play whole
 *
 * Under five seconds the whole video loops, as in NxDashVids. Under three, NxDashVids shows no
 * preview at all; here it still plays whole, because the peek's alternative is a still frame of
 * something that is nearly all motion. That is a deliberate difference.
 *
 * The same plan is a held tile's glimpse and a video's paused preview, so the two always show a
 * video the same way. Nothing is cut or encoded: the clips are seek targets into the original
 * file, played muted. See [GlimpsePlayer]. Pure, and [random] is passed in, so where every clip
 * lands is a test.
 */
object GlimpsePlan {

    const val CLIPS = 3
    const val CLIP_MS = 1_500L

    /** Below this the whole video is the glimpse, looped. */
    const val WHOLE_BELOW_MS = 5_000L

    /** The middle stretch the clips come from, as fractions of the length. */
    const val ZONE_FROM = 0.15
    const val ZONE_TO = 0.85

    /** The margin at each end when the middle stretch is too short. */
    const val EDGE_MS = 2_000L

    /** One clip: where it starts and how long it plays. */
    data class Clip(val startMs: Long, val lengthMs: Long) {
        val endMs: Long get() = startMs + lengthMs
    }

    /** Where the clips are taken from in a video [durationMs] long: start and end. */
    fun zone(durationMs: Long): Pair<Long, Long> {
        val from = (durationMs * ZONE_FROM).toLong()
        val to = (durationMs * ZONE_TO).toLong()
        return if (to - from < CLIPS * CLIP_MS + EDGE_MS) EDGE_MS to durationMs - EDGE_MS else from to to
    }

    /** The clips for a video [durationMs] long. Empty when the length is not known. */
    fun of(durationMs: Long, random: Random = Random.Default): List<Clip> {
        if (durationMs <= 0L) return emptyList()
        if (durationMs < WHOLE_BELOW_MS) return listOf(Clip(0L, durationMs))
        val (from, to) = zone(durationMs)
        val part = (to - from) / CLIPS
        return List(CLIPS) { i ->
            val partFrom = from + i * part
            // The latest start that still ends inside this part.
            val room = (part - CLIP_MS).coerceAtLeast(0L)
            Clip(partFrom + if (room > 0L) random.nextLong(room + 1) else 0L, CLIP_MS)
        }
    }

    /** True when the plan is the whole video looped, rather than clips from its parts. */
    fun isWhole(clips: List<Clip>): Boolean = clips.size == 1
}

package dev.niccc2007.filet.handlers

import kotlin.random.Random

/**
 * What a video's glimpse plays: short clips from across the video, so a held tile shows what the
 * whole video is rather than how it starts.
 *
 * ## The rule
 *
 * Five clips of 1.2 seconds, a six-second round, from 3% to 97% of the video: one from each
 * fifth of that stretch, at a random spot inside it. The first clip comes from the opening and
 * the last from the ending, so one round passes over the whole video.
 *
 * ## How it got here
 *
 * It was six clips of two seconds starting at 0%: twelve seconds a round, and a tile held for a
 * few seconds only ever showed the opening. Then three clips of 1.5 seconds from the middle 70%
 * (NxDashVids' timing): quicker, but no clip ever came from the opening or the ending, so a round
 * was still not a glimpse of the whole video. Cutting 15% off each end had kept out exactly the
 * parts that were missed. Five clips cover more of the video in a round only a second and a half
 * longer.
 *
 * The 3% at each end is kept out because a video's first and last instants are often a black
 * frame or a fade, and a clip that starts on one looks like the glimpse failing. A random spot
 * rather than a fixed one, so holding the same tile twice shows two moments.
 *
 * ## Short videos play whole
 *
 * A video whose 3% to 97% cannot hold the five clips without them overlapping, which is anything
 * under about 6.4 seconds, plays whole and looped instead. Five overlapping clips of a short
 * video are the video played in a stutter. That includes anything under three seconds, where
 * NxDashVids shows no preview at all. Here it still plays, because the peek's alternative is a
 * still frame of something that is nearly all motion. That is a deliberate difference.
 *
 * The same plan is a held tile's glimpse and a video's paused preview, so the two always show a
 * video the same way. Nothing is cut or encoded: the clips are seek targets into the original
 * file, played muted. See [GlimpsePlayer]. Pure, and [random] is passed in, so where every clip
 * lands is a test.
 */
object GlimpsePlan {

    const val CLIPS = 5
    const val CLIP_MS = 1_200L

    /** The stretch the clips come from, as fractions of the length. */
    const val ZONE_FROM = 0.03
    const val ZONE_TO = 0.97

    /** One clip: where it starts and how long it plays. */
    data class Clip(val startMs: Long, val lengthMs: Long) {
        val endMs: Long get() = startMs + lengthMs
    }

    /** Where the clips are taken from in a video [durationMs] long: start and end. */
    fun zone(durationMs: Long): Pair<Long, Long> =
        (durationMs * ZONE_FROM).toLong() to (durationMs * ZONE_TO).toLong()

    /** Whether a video [durationMs] long is too short for the clips, and so plays whole. */
    fun playsWhole(durationMs: Long): Boolean {
        val (from, to) = zone(durationMs)
        return to - from < CLIPS * CLIP_MS
    }

    /** The clips for a video [durationMs] long. Empty when the length is not known. */
    fun of(durationMs: Long, random: Random = Random.Default): List<Clip> {
        if (durationMs <= 0L) return emptyList()
        if (playsWhole(durationMs)) return listOf(Clip(0L, durationMs))
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

package dev.niccc2007.filet.handlers

import kotlin.random.Random

/**
 * What a video's glimpse plays: a few seconds from each part of it, so a held tile shows what
 * the whole video is rather than how it starts.
 *
 * Five clips of three seconds, one from each fifth, each from a random spot inside its fifth -
 * the opening seconds of a phone video are usually somebody finding the subject, and a fixed
 * spot would show the same dull frame every time the tile is held. A video too short for five
 * separate clips plays whole, looped.
 *
 * Nothing is cut or encoded: the clips are seek targets into the original file, played muted.
 * Pure, and [random] is passed in, so where every clip lands is a test.
 */
object GlimpsePlan {

    const val CLIPS = 5
    const val CLIP_MS = 3_000L

    /** Below this the whole video is the glimpse: five clips would overlap or touch. */
    const val WHOLE_BELOW_MS = CLIPS * CLIP_MS

    /** One clip: where it starts and how long it plays. */
    data class Clip(val startMs: Long, val lengthMs: Long) {
        val endMs: Long get() = startMs + lengthMs
    }

    /** The clips for a video [durationMs] long. Empty when the length is not known. */
    fun of(durationMs: Long, random: Random = Random.Default): List<Clip> {
        if (durationMs <= 0L) return emptyList()
        if (durationMs < WHOLE_BELOW_MS) return listOf(Clip(0L, durationMs))
        val fifth = durationMs / CLIPS
        return List(CLIPS) { i ->
            val from = i * fifth
            // The latest start that still ends inside this fifth.
            val room = (fifth - CLIP_MS).coerceAtLeast(0L)
            val start = from + if (room > 0L) random.nextLong(room + 1) else 0L
            Clip(start, CLIP_MS)
        }
    }

    /** True when the plan is the whole video looped, rather than clips from its parts. */
    fun isWhole(clips: List<Clip>): Boolean = clips.size == 1
}

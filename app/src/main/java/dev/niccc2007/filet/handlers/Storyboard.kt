package dev.niccc2007.filet.handlers

/**
 * The set of frames a scrub preview draws from, extracted once instead of during the drag.
 *
 * Bug identified: the preview decoded a frame every time the finger moved far enough to want a
 * new one. Reading a frame out of a container takes tens of milliseconds and a drag asks for
 * one every few, so that arrangement can only ever chase the finger - and when the work was
 * cancelled to keep up, the decoded frame went with it and nothing appeared at all.
 *
 * Decoding during the gesture is the wrong shape. Frames are pulled once, in the background,
 * into a cache; the drag then reads memory and cannot be slow. It is what a real player does -
 * YouTube ships pre-rendered storyboard sprites and decodes nothing at all while you scrub.
 *
 * This file is the arithmetic: which timestamps to pull, in what order, and which of the ones
 * pulled so far is the right answer for a position. The extraction and the bitmaps live in the
 * screen.
 */
object Storyboard {

    /**
     * How far apart storyboard frames should ideally be.
     *
     * Bug identified: the count was fixed at sixty, so the spacing was whatever
     * `duration / 59` happened to be - exactly two seconds on a two-minute video, which is a
     * visible jump. What should be roughly constant is the spacing, not the count.
     */
    const val TARGET_SPACING_MS = 1_000L

    /**
     * Enough that even a very short clip has something to scrub through.
     *
     * Sixty rather than something smaller because sixty is what every video used to get, and a
     * change meant to make the preview finer must not make any length coarser. A forty-frame
     * floor made a ten-second clip step in 256ms where it used to step in 169ms - finer on the
     * long videos that prompted the complaint, worse on the short ones nobody complained about.
     */
    const val MIN_FRAMES = 60

    /**
     * The memory ceiling, in frames.
     *
     * Each is held while the player is open, so this multiplies the decoded frame size. They
     * are decoded small and in 16-bit colour for exactly this reason - about 28KB each, so the
     * ceiling is under seven megabytes, freed when the video closes.
     *
     * Past this the spacing grows instead: a two-hour film cannot be covered at one frame a
     * second by any amount of cleverness, and pretending otherwise would just mean running out
     * of memory on the device least able to spare it.
     */
    const val MAX_FRAMES = 240

    /**
     * How many frames to hold for a video of this length.
     *
     * Aims at [TARGET_SPACING_MS] and gives up gracefully at both ends: a short clip gets the
     * floor, a long film gets the ceiling and coarser spacing with it.
     */
    fun framesFor(durationMs: Long): Int {
        if (durationMs <= 0L) return MIN_FRAMES
        return (durationMs / TARGET_SPACING_MS).toInt().coerceIn(MIN_FRAMES, MAX_FRAMES)
    }

    /**
     * The best spacing achievable for a video of this length.
     *
     * [TARGET_SPACING_MS] until the frame ceiling binds, and the honest number after that.
     * Worth having as its own function because it is what the preview actually feels like, and
     * a claim about it should be checkable rather than assumed from the constants.
     */
    fun spacingFor(durationMs: Long): Long {
        val frames = framesFor(durationMs)
        if (durationMs <= 0L || frames <= 1) return TARGET_SPACING_MS
        return durationMs / (frames - 1)
    }

    /**
     * The timestamps to extract for a video of this length, in order along the video.
     *
     * Evenly spaced and always including the first frame, because the start of the bar is
     * where a drag most often begins.
     */
    fun plan(durationMs: Long, frames: Int = framesFor(durationMs)): List<Long> {
        if (durationMs <= 0L || frames <= 0) return emptyList()
        if (frames == 1) return listOf(0L)
        val step = durationMs.toDouble() / (frames - 1)
        return (0 until frames).map { i -> (i * step).toLong().coerceIn(0L, durationMs) }.distinct()
    }

    /**
     * The order to extract [count] frames in, as indices into [plan].
     *
     * Not left to right. A storyboard takes a second or two to fill, and filling it in order
     * means a scrub to the end of a long video has nothing to show until the very end of that
     * wait. Bisecting instead - middle, then quarters, then eighths - means coverage is even
     * at every moment, so the preview is rough immediately and sharpens, rather than being
     * perfect at the start and absent everywhere else.
     */
    fun fillOrder(count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val out = ArrayList<Int>(count)
        val taken = BooleanArray(count)
        fun take(i: Int) {
            if (i in 0 until count && !taken[i]) {
                taken[i] = true
                out += i
            }
        }
        // The ends first: they are the two positions a drag reaches most often.
        take(0)
        take(count - 1)
        // Then repeatedly halve the gaps.
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.addLast(0 to count - 1)
        while (queue.isNotEmpty()) {
            val (lo, hi) = queue.removeFirst()
            if (hi - lo < 2) continue
            val mid = (lo + hi) / 2
            take(mid)
            queue.addLast(lo to mid)
            queue.addLast(mid to hi)
        }
        // Anything the halving missed, so every frame is eventually asked for.
        for (i in 0 until count) take(i)
        return out
    }

    /**
     * The best frame to show for [positionMs] out of the ones extracted so far.
     *
     * Nearest rather than preceding: half a step away in either direction looks the same to
     * the eye, and insisting on the preceding frame leaves the first part of the video with
     * nothing to show while the storyboard is still filling.
     *
     * @param have timestamps already extracted. Order does not matter.
     * @return the timestamp to draw, or null while nothing has been extracted at all.
     */
    fun nearest(positionMs: Long, have: Collection<Long>): Long? =
        have.minByOrNull { kotlin.math.abs(it - positionMs) }
}

package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The beat map's readout: the progress figure, the activity dot, and the rule that a partial
 * map may not shrink what is on screen.
 *
 * The progress figure is drawn as a bar for somebody checking whether the work happens at all,
 * so it must come from the measuring loop and must never run backwards; the dot must go when
 * the work ends or is seen, and only then.
 */
class BeatWorkTest {

    private fun tone(seconds: Int, rate: Int = TrackPcm.TARGET_RATE): TrackPcm {
        val n = seconds * rate
        val s = ShortArray(n) { i ->
            // A click every half second over a quiet tone, so the measurement has work in it.
            val click = if (i % (rate / 2) < 40) 20_000 else 0
            (click + 800 * kotlin.math.sin(i * 0.05)).toInt().coerceIn(-32768, 32767).toShort()
        }
        return TrackPcm(s, rate)
    }

    // ── progress ──

    @Test
    fun `the measuring loop reports progress that only goes up and ends at the total`() {
        val seen = ArrayList<Pair<Int, Int>>()
        BeatMap.from(tone(6), onProgress = { d, t -> seen += d to t })
        assertTrue("reported more than once: ${seen.size}", seen.size > 3)
        val total = seen.first().second
        assertTrue(seen.all { it.second == total })
        for (i in 1 until seen.size) assertTrue(seen[i].first >= seen[i - 1].first)
        assertEquals(total, seen.last().first)
    }

    @Test
    fun `a cancelled measurement stops reporting and does not reach the total`() {
        val seen = ArrayList<Int>()
        var calls = 0
        runCatching {
            BeatMap.from(tone(6), alive = { calls++ < 2 }, onProgress = { d, _ -> seen += d })
        }
        assertTrue(seen.isNotEmpty())
        val total = (tone(6).samples.size - BeatMap.WINDOW) / (TrackPcm.TARGET_RATE * 20 / 1000)
        assertTrue(seen.last() < total)
    }

    @Test
    fun `within a run the fraction never goes back, and an older run is ignored`() {
        val a = BeatProgress(BeatPhase.MEASURING, 0.40f, run = 2)
        assertEquals(a, BeatProgress.advance(a, a.copy(fraction = 0.30f)))
        assertEquals(0.55f, BeatProgress.advance(a, a.copy(fraction = 0.55f)).fraction)
        // From an earlier run - a cancelled track still reporting.
        assertEquals(a, BeatProgress.advance(a, BeatProgress(BeatPhase.MEASURING, 0.9f, run = 1)))
        // A newer run replaces, even from zero.
        val b = BeatProgress(BeatPhase.DECODING, null, run = 3)
        assertEquals(b, BeatProgress.advance(a, b))
    }

    @Test
    fun `within a run the phase only moves forward, except to stop`() {
        val m = BeatProgress(BeatPhase.MEASURING, 0.5f, run = 4)
        assertEquals(m, BeatProgress.advance(m, BeatProgress(BeatPhase.DECODING, 0.9f, run = 4)))
        assertEquals(BeatPhase.DONE, BeatProgress.advance(m, BeatProgress(BeatPhase.DONE, 1f, run = 4)).phase)
        assertEquals(BeatPhase.IDLE, BeatProgress.advance(m, BeatProgress(BeatPhase.IDLE, null, run = 4)).phase)
    }

    @Test
    fun `sub-percent moves are not published, the last step always is`() {
        val a = BeatProgress(BeatPhase.MEASURING, 0.500f, run = 1)
        assertEquals(a, BeatProgress.advance(a, a.copy(fraction = 0.505f)))
        assertEquals(1f, BeatProgress.advance(a.copy(fraction = 0.995f), a.copy(fraction = 1f)).fraction)
    }

    // ── the dot ──

    @Test
    fun `the dot shows while a run works and goes when it finishes`() {
        val d = ActivityDots()
        assertFalse(d.showing)
        d.started("beat", 1)
        assertTrue(d.showing)
        d.finished("beat", 1)
        assertFalse(d.showing)
    }

    @Test
    fun `opening the panel clears the dot for that run, and a new run lights it again`() {
        val d = ActivityDots()
        d.started("beat", 1)
        d.seen()
        assertFalse("seen while running clears it", d.showing)
        d.started("beat", 2)
        assertTrue("the next track's build is new news", d.showing)
    }

    @Test
    fun `a cancelled older run cannot clear the current one`() {
        val d = ActivityDots()
        d.started("beat", 1)
        d.started("beat", 2)
        d.finished("beat", 1)
        assertTrue(d.showing)
    }

    @Test
    fun `kinds are independent, one dot for all of them`() {
        val d = ActivityDots()
        d.started("beat", 1)
        d.seen()
        d.started("other", 1)
        assertTrue(d.showing)
        d.finished("other", 1)
        assertFalse(d.showing)
    }

    // ── what is on screen ──

    @Test
    fun `a partial map that covers less does not replace the early one`() {
        val early = BeatMap.from(tone(4))
        val full = tone(20)
        var first: BeatMap? = null
        BeatMap.from(full, onPartial = { if (first == null) first = it })
        val p = first!!
        // The first partial of the full measurement covers half a second; the early map four.
        assertTrue(p.coveredMs < early.coveredMs)
        assertFalse(BeatMap.keeps(early, p))
        assertTrue(BeatMap.keeps(null, p))
        assertTrue(BeatMap.keeps(early, BeatMap.from(full)))
    }
}

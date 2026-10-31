package dev.niccc2007.filet.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** The beat grid the pulse fires on. See [BeatGrid]. */
class BeatGridTest {

    private val bucket = 0.02

    @Test
    fun `an onset off the beat is dropped and one on it is kept as hard as it hit`() {
        val onsets = FloatArray(400)
        val beats = DoubleArray(10) { (it * 25 + 10) * bucket } // a beat every half second
        for (b in beats) onsets[(b / bucket).roundToInt()] = 0.9f
        onsets[22] = 0.8f // between the first two beats: a hat, not the beat
        val out = BeatGrid.place(onsets, FloatArray(400), beats, bucket)
        assertEquals(0f, out[22], 0f)
        assertEquals(0.9f, out[10], 0f)
        assertEquals(0.9f, out[35], 0f)
    }

    @Test
    fun `a beat takes a nearby onset onto its own bucket`() {
        val onsets = FloatArray(400)
        val beats = DoubleArray(10) { (it * 25 + 10) * bucket }
        onsets[37] = 0.7f // two buckets after the beat at 35
        val out = BeatGrid.place(onsets, FloatArray(400), beats, bucket)
        assertEquals(0.7f, out[35], 0f)
        assertEquals(0f, out[37], 0f)
    }

    @Test
    fun `a beat with nothing under it pulses faintly with the music, and not at all in silence`() {
        val onsets = FloatArray(400)
        val levels = FloatArray(400) { if (it < 200) 0.8f else 0f }
        val beats = DoubleArray(12) { (it * 25 + 10) * bucket }
        val out = BeatGrid.place(onsets, levels, beats, bucket)
        assertEquals(BeatGrid.GHOST * 0.8f, out[10], 1e-6f)
        assertEquals(0f, out[260], 0f)
    }

    @Test
    fun `no grid keeps the onsets as they were`() {
        val onsets = FloatArray(100) { if (it % 7 == 0) 0.5f else 0f }
        val out = BeatGrid.place(onsets, FloatArray(100), DoubleArray(3) { it * 0.5 }, bucket)
        assertArrayEquals(onsets, out, 0f)
        assertEquals(0, BeatGrid.beats(List(50) { 0f }, bucket).size)
    }

    @Test
    fun `a steady kick with weaker hats between is tracked at the kick's tempo and phase`() {
        // Thirty seconds at 120bpm: a kick every 25 buckets, a hat half way between each pair.
        val n = 1500
        val kicks = (5 until n step 25).toSet()
        val flux = List(n) { i ->
            when {
                i in kicks -> 20f
                (i - 5) % 25 == 12 -> 6f
                else -> 1f + ((i * 7919) % 13) / 13f
            }
        }
        val beats = BeatGrid.beats(flux, bucket)
        assertTrue("found ${beats.size} beats", beats.size >= 40)
        val gaps = beats.toList().zipWithNext { a, b -> b - a }.sorted()
        assertEquals("median beat interval", 0.5, gaps[gaps.size / 2], 0.03)
        val onKick = beats.count { t ->
            val i = (t / bucket).roundToInt()
            kicks.any { abs(it - i) <= 1 }
        }
        assertTrue("$onKick of ${beats.size} beats on a kick", onKick >= beats.size * 0.9)
    }
}

package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatMapTest {

    @Test
    fun `a hit after quiet is an onset`() {
        val energy = List(20) { 0.05f } + listOf(0.9f) + List(10) { 0.05f }
        val onsets = BeatMap.onsetsOf(energy)
        assertEquals(1f, onsets[20], 0.001f)
        assertTrue("the quiet buckets fired too", onsets.take(20).all { it == 0f })
    }

    @Test
    fun `a sustained loud note is not a beat after it starts`() {
        // Loud all the way through. Only the first rise counts, and here there is not even
        // one - the running average is the same as the signal.
        val onsets = BeatMap.onsetsOf(List(60) { 0.8f })
        assertTrue("a flat loud track pulsed", onsets.all { it == 0f })
    }

    @Test
    fun `silence has no beats`() {
        assertTrue(BeatMap.onsetsOf(List(60) { 0f }).all { it == 0f })
    }

    @Test
    fun `a quiet track pulses as hard as a loud one`() {
        val quiet = BeatMap.onsetsOf(List(20) { 0.01f } + listOf(0.2f) + List(10) { 0.01f })
        val loud = BeatMap.onsetsOf(List(20) { 0.2f } + listOf(0.95f) + List(10) { 0.2f })
        // Normalised against the track's own biggest hit, or a quietly mastered song would
        // barely move the ring while a loud one slammed it.
        assertEquals(quiet.max(), loud.max(), 0.001f)
    }

    @Test
    fun `a beat carries into the next bucket so a frame cannot step over it`() {
        val map = BeatMap(floatArrayOf(0f, 1f, 0f, 0f))
        assertEquals(1f, map.strengthAt(BeatMap.BUCKET_MS), 0.001f)
        // The bucket after the hit still shows some of it.
        assertTrue(map.strengthAt(BeatMap.BUCKET_MS * 2) > 0.3f)
    }

    @Test
    fun `a position outside the track is silent rather than a crash`() {
        val map = BeatMap(floatArrayOf(1f, 1f))
        assertEquals(0f, map.strengthAt(-500L), 0.001f)
        assertEquals(0f, map.strengthAt(9_999_999L), 0.001f)
    }

    @Test
    fun `an ordinary beat reaches the top of the range, not a fifth of it`() {
        // Why the scale is a percentile and not the maximum. A track of steady drums with
        // one cymbal crash in it: scaled against the crash, every drum measured a fraction
        // of the range and the light barely moved. This is the whole of "i barely see any
        // pulse".
        val bed = MutableList(240) { 0.10f }
        for (i in 20 until 240 step 10) bed[i] = 0.30f
        bed[150] = 2.0f
        val onsets = BeatMap.onsetsOf(bed)
        val drums = (40 until 140 step 10).map { onsets[it] }
        assertTrue("the drums are $drums", drums.all { it > 0.8f })
    }

    @Test
    fun `a rise is measured against what the track has been doing, not against zero`() {
        // The same jump in a quiet passage and in a loud one is the same beat. Absolute
        // thresholds called the first one nothing and the second one a hit.
        val quiet = BeatMap.onsetsOf(List(20) { 0.02f } + listOf(0.05f) + List(10) { 0.02f })
        assertTrue("a quiet passage's beat was thrown away", quiet[20] > 0f)
    }

    @Test
    fun `a rise out of true silence is a beat`() {
        val onsets = BeatMap.onsetsOf(List(20) { 0f } + listOf(0.4f) + List(10) { 0f })
        assertEquals(1f, onsets[20], 0.001f)
    }

    @Test
    fun `an empty map says so`() {
        assertTrue(BeatMap(FloatArray(0)).empty)
        assertEquals(0f, BeatMap(FloatArray(0)).strengthAt(100L), 0.001f)
    }
}

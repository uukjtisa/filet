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
    fun `a beat is picked up just BEFORE it lands, not after`() {
        val map = BeatMap(floatArrayOf(0f, 1f, 0f, 0f))
        assertEquals(1f, map.strengthAt(BeatMap.BUCKET_MS), 0.001f)
        // The bucket BEFORE the hit already shows some of it, so the light has started
        // moving by the time the sound arrives. The window used to point the other way,
        // which put every pulse up to forty milliseconds behind the music.
        assertTrue(map.strengthAt(0L) > 0.3f)
        // And the bucket after is clean: a beat that has been heard is over.
        assertEquals(0f, map.strengthAt(BeatMap.BUCKET_MS * 2), 0.001f)
    }

    @Test
    fun `a weak hit survives as a weak number instead of being thrown away`() {
        // Two-way, and the reason the floor came down: the soft hits have to reach the
        // light at all before anything downstream can decide how much of them to show.
        val bed = MutableList(240) { 0.10f }
        for (i in 20 until 240 step 10) bed[i] = 0.30f
        bed[85] = 0.13f
        val onsets = BeatMap.onsetsOf(bed)
        assertTrue("the soft hit was gated out", onsets[85] > 0f)
        assertTrue("it is not as loud as a real one", onsets[85] < onsets[80])
    }

    @Test
    fun `the sensitivity slider maps onto a gain and back`() {
        assertEquals(PULSE_SENSE_MIN, senseFor(0f), 0.001f)
        assertEquals(PULSE_SENSE_MAX, senseFor(1f), 0.001f)
        // Geometric, so the middle of the travel is the geometric mean rather than the
        // arithmetic one - which is what keeps unity gain off the far left of the track.
        assertEquals(
            kotlin.math.sqrt(PULSE_SENSE_MIN * PULSE_SENSE_MAX),
            senseFor(0.5f),
            0.01f,
        )
        for (p in listOf(0f, 0.2f, 0.5f, 0.77f, 1f)) {
            assertEquals(p, sensePosition(senseFor(p)), 0.001f)
        }
    }

    @Test
    fun `the glow swings both ways about its resting level`() {
        // Below rest between beats, above it on one. A pulse that only ever adds is half a
        // pulse and spends most of its time indistinguishable from the pulse being off.
        assertTrue("silence did not dip", glowSwing(0f, 1f) < 0f)
        assertTrue("a beat did not rise", glowSwing(1f, 1f) > 0f)
        // The dip is the gentler half, or a quiet passage just goes dark and stays there.
        assertTrue(kotlin.math.abs(glowSwing(0f, 1f)) < glowSwing(1f, 1f))
        // And depth scales both halves together.
        assertEquals(0f, glowSwing(0f, 0f), 0.001f)
        assertEquals(0f, glowSwing(1f, 0f), 0.001f)
        assertEquals(glowSwing(1f, 1f) / 2f, glowSwing(1f, 0.5f), 0.001f)
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
    fun `the opening of a track is not blank`() {
        // The pulse starting late had two causes and this is the small one: waiting for a
        // full sixteen-bucket window before anything counted blanked the first third of a
        // second of every track - which on a song that opens on a hit is the beat somebody
        // is actually listening for.
        val onsets = BeatMap.onsetsOf(List(6) { 0.05f } + listOf(0.8f) + List(20) { 0.05f })
        assertTrue("the opening hit was swallowed by the warm-up", onsets[6] > 0f)
        assertEquals("the very first bucket fired on nothing", 0f, onsets[0], 0.001f)
    }

    @Test
    fun `a partial read already gives a usable map`() {
        // What makes streaming the decode work: the first second of a track, measured on
        // its own, grades its beats the same way the whole track would.
        val whole = MutableList(400) { 0.10f }
        for (i in 20 until 400 step 10) whole[i] = 0.30f
        val early = BeatMap.onsetsOf(whole.take(60))
        val full = BeatMap.onsetsOf(whole)
        for (i in listOf(30, 40, 50)) {
            assertEquals("bucket $i graded differently", full[i], early[i], 0.15f)
        }
    }

    @Test
    fun `glow strength and beat intensity are different scales`() {
        // They were one control, and that is why turning it up under "Living glow" with the
        // pulse off did nothing: it only set how far a BEAT moved the glow. Strength is the
        // level; intensity is the range about that level.
        assertEquals(GLOW_MIN, glowScale(0f), 0.001f)
        assertEquals(GLOW_MAX, glowScale(1f), 0.001f)
        assertTrue("strength must move without any beat", glowScale(0.9f) > glowScale(0.1f))
        // And the named steps are stops on that same scale, not a parallel setting.
        for (step in GlowLevel.entries) {
            assertEquals(step.scale, glowScale(step.position), 0.001f)
            assertEquals(step, GlowLevel.nearest(step.position))
        }
    }

    @Test
    fun `the disc travel control spans record speed to a whole song`() {
        assertEquals(Scratch.TURN_MIN_MS, Scratch.turnMsFor(0f), 1f)
        assertEquals(Scratch.TURN_MAX_MS, Scratch.turnMsFor(1f), 1f)
        for (p in listOf(0f, 0.3f, 0.5f, 1f)) {
            assertEquals(p, Scratch.turnPosition(Scratch.turnMsFor(p)), 0.001f)
        }
        // And it says what it means in the unit a thumb works in.
        assertTrue(Scratch.travelLabel(2_400f).contains("200ms"))
        assertTrue(Scratch.travelLabel(24_000f).contains("2.0s"))
    }

    @Test
    fun `the loudness envelope is continuous where the onsets are spikes`() {
        // Why the wave is drawn from one and the pulse from the other. A ring drawn from a
        // derivative is a circle with occasional dents; the envelope has the track's shape
        // in it at every angle.
        val energy = List(60) { 0.1f + 0.3f * kotlin.math.sin(it / 4f).let { v -> v * v } }
        val levels = BeatMap.levelsOf(energy)
        assertEquals(energy.size, levels.size)
        assertTrue("the envelope is flat", levels.max() - levels.min() > 0.2f)
        assertTrue("nothing may leave the range", levels.all { it in 0f..1f })
        // The onsets over the same material are mostly nothing, which is correct for them.
        val onsets = BeatMap.onsetsOf(energy)
        assertTrue(onsets.count { it == 0f } > levels.count { it == 0f })
    }

    @Test
    fun `a quiet track still has a wave`() {
        // Normalised against the track rather than against full scale, or a quietly
        // mastered song would draw a flat line.
        val quiet = BeatMap.levelsOf(List(60) { 0.004f + 0.003f * (it % 5) })
        assertTrue("a quiet track flattened to nothing", quiet.max() > 0.9f)
    }

    @Test
    fun `an empty map says so`() {
        assertTrue(BeatMap(FloatArray(0)).empty)
        assertEquals(0f, BeatMap(FloatArray(0)).strengthAt(100L), 0.001f)
    }
}

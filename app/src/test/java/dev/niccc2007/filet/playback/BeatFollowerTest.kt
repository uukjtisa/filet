package dev.niccc2007.filet.playback

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two ways the pulse has already been broken, as tests.
 *
 * Both failures were invisible by looking at a light and obvious the moment a synthetic track is
 * run through the arithmetic. Neither would have shipped if this file had existed.
 *
 * Every test here drives the follower at 60 frames a second for a stated number of seconds, the
 * way the real loop does, and asserts about the back half of the run - because both bugs were
 * about what happens AFTER the followers have settled, and a test that only looks at the first
 * second passes while the feature is broken.
 */
class BeatFollowerTest {

    /** 60 fps, which is what `PULSE_FPS` runs at. */
    private val frame = 1000f / 60f

    /**
     * Run a track through and collect the swing on every frame.
     *
     * @param beatEvery how many milliseconds between onsets.
     * @param strength how hard each onset reads, 0 to 1.
     * @param floorLevel a constant added to every frame, for a track that never falls silent.
     */
    private fun run(
        seconds: Float,
        beatEvery: Float,
        strength: Float = 1f,
        floorLevel: Float = 0f,
    ): List<Float> {
        val follower = BeatFollower()
        val out = ArrayList<Float>()
        var sinceBeat = beatEvery
        var t = 0f
        while (t < seconds * 1000f) {
            sinceBeat += frame
            val raw = if (sinceBeat >= beatEvery) {
                sinceBeat = 0f
                strength
            } else {
                floorLevel
            }
            follower.step(raw, 0.5f, frame)
            out.add(follower.swing)
            t += frame
        }
        return out
    }

    /** The second half of a run, which is where a settled follower lives. */
    private fun List<Float>.settled(): List<Float> = drop(size / 2)

    @Test
    fun `the swing is still there after the averaging window has settled`() {
        // FAILURE 2, as a test. A three-second average over the raw envelope climbed into the
        // beat pattern itself, so the swing shrank to nothing over the first few seconds - which
        // presented as the pulse disappearing three or four seconds into every song.
        //
        // Thirty seconds is ten times the window that broke, and four times the one that
        // replaced it, so a follower that is going to fade has faded by here.
        val swings = run(seconds = 30f, beatEvery = 500f).settled()
        val top = swings.max()
        val bottom = swings.min()
        assertTrue("the swing collapsed: peak $top", top > 0.3f)
        assertTrue("it never dips: trough $bottom", bottom < -0.02f)
        assertTrue("the range is too small: ${top - bottom}", (top - bottom) > 0.4f)
    }

    @Test
    fun `a dense track still pulses`() {
        // The hardest case for any scheme that subtracts an average: hits so close together
        // that the envelope never gets near zero between them, so there is little left to
        // measure against the average. The gain control is what rescues this.
        val swings = run(seconds = 30f, beatEvery = 200f, floorLevel = 0.25f).settled()
        assertTrue("a dense track stopped pulsing: ${swings.max()}", swings.max() > 0.25f)
    }

    @Test
    fun `a quiet track pulses as much as a loud one`() {
        // Two tracks, one hitting at full strength and one at a fifth of it. After the gain
        // control they should move the light by comparable amounts - that is the whole point of
        // normalising, and it is what makes one Sensitivity setting work across a library.
        val loud = run(seconds = 30f, beatEvery = 500f, strength = 1f).settled().max()
        val quiet = run(seconds = 30f, beatEvery = 500f, strength = 0.2f).settled().max()
        assertTrue("quiet=$quiet loud=$loud", quiet > loud * 0.6f)
    }

    @Test
    fun `the pulse never leaves the glow dimmer on average than no pulse`() {
        // FAILURE 1, as a test. The resting point was a hardcoded 0.34 against an envelope that
        // decays to zero, so the average swing was decisively negative - a glow permanently
        // about a third dimmer with the pulse on than off, which is the opposite of a pulse.
        //
        // Checked over three shapes of track, including the two extremes that break a fixed
        // resting point: sparse hits in near-silence, and something that never lets up.
        for ((name, swings) in listOf(
            "sparse" to run(40f, beatEvery = 2000f),
            "ordinary" to run(40f, beatEvery = 500f),
            "relentless" to run(40f, beatEvery = 150f, floorLevel = 0.4f),
        )) {
            val average = swings.settled().average().toFloat()
            assertTrue("$name is a net dim: $average", average > -0.05f)
        }
    }

    @Test
    fun `silence settles to no swing rather than to a dip`() {
        val follower = BeatFollower()
        repeat(2000) { follower.step(0f, 0f, frame) }
        assertTrue("silence dips: ${follower.swing}", follower.swing > -0.05f)
        assertTrue("silence lifts: ${follower.swing}", follower.swing < 0.05f)
    }

    @Test
    fun `a flat loud passage does not strobe`() {
        // The guard the minimum span exists for: an envelope that barely moves would otherwise
        // be divided by almost nothing and turn dither into a flicker.
        val follower = BeatFollower()
        repeat(2000) { follower.step(0.7f, 0.7f, frame) }
        assertTrue("a flat passage is swinging: ${follower.swing}", kotlin.math.abs(follower.swing) < 0.1f)
    }

    @Test
    fun `the wave is a burst, not a swell`() {
        // Peaks on the step the hit is read, and is mostly gone before a beat at 120bpm
        // (500ms) arrives - so consecutive hits never merge into one long swell, which is what
        // the old 40ms chase and 600ms tail did.
        val follower = BeatFollower()
        // Settle first, so the followers are where a real track would have them.
        repeat(600) { follower.step(if (it % 30 == 0) 1f else 0f, 0.5f, frame) }
        repeat(60) { follower.step(0f, 0.5f, frame) }
        val before = follower.blast
        follower.step(1f, 0.5f, frame)
        val onTheHit = follower.blast
        follower.step(0f, 0.5f, frame)
        val nextFrame = follower.blast
        repeat(27) { follower.step(0f, 0.5f, frame) }
        val halfSecond = follower.blast
        assertTrue("the wave did not jump on the hit: $before -> $onTheHit", onTheHit > before + 0.3f)
        assertTrue("the wave was still rising after the hit", nextFrame <= onTheHit)
        assertTrue("the wave was still there half a second later: $halfSecond", halfSecond < onTheHit * 0.2f)
    }

    @Test
    fun `the shock fires once per hit and not on every frame of it`() {
        val follower = BeatFollower()
        repeat(600) { follower.step(if (it % 30 == 0) 1f else 0f, 0.5f, frame) }
        var fired = 0
        var wasHigh = false
        repeat(300) { i ->
            follower.step(if (i % 60 == 0) 1f else 0f, 0.5f, frame)
            val high = follower.shock > 0.99f
            if (high && !wasHigh) fired++
            wasHigh = high
        }
        assertTrue("the shock fired $fired times for 5 hits", fired in 1..5)
    }

    @Test
    fun `a long gap between frames does not blow the arithmetic up`() {
        // The screen was off. The loop comes back with a gap of minutes, and every coefficient
        // here is gap-over-something - so without the clamp the followers jump to wherever the
        // current frame is and the first beat after a resume is wrong.
        val follower = BeatFollower()
        repeat(600) { follower.step(if (it % 30 == 0) 1f else 0f, 0.5f, frame) }
        follower.step(0f, 0.5f, 600_000f)
        assertTrue("level went out of range: ${follower.level}", follower.level in 0f..1f)
        assertTrue("swing went out of range: ${follower.swing}", follower.swing in -1f..1f)
        assertTrue("blast went out of range: ${follower.blast}", follower.blast in 0f..1f)
    }

    @Test
    fun `reset puts everything back to rest`() {
        val follower = BeatFollower()
        repeat(600) { follower.step(if (it % 30 == 0) 1f else 0f, 0.5f, frame) }
        follower.reset()
        assertTrue(follower.level == 0f && follower.swing == 0f && follower.blast == 0f)
        assertTrue(follower.floor == 0f && follower.ceiling == 0f && follower.mean == 0f)
    }
}

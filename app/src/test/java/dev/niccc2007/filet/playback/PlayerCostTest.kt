package dev.niccc2007.filet.playback

import dev.niccc2007.filet.handlers.Platter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The decisions behind issue #8's fix: what may animate, how often the position is polled, when
 * the shade card and the session are told anything, and what each device tier spends.
 *
 * Every one of these is a decision that is invisible when wrong on a fast phone, which is how
 * the costs they gate got shipped in the first place.
 */
class PlayerCostTest {

    // ── the tier classifier ──────────────────────────────────────────────────────────────

    private val flagship = DeviceFacts(
        sdk = 34, cores = 8, lowRam = false, totalRamMb = 11_500,
        mediaPerformanceClass = 0,
    )
    private val midRange = DeviceFacts(
        sdk = 31, cores = 8, lowRam = false, totalRamMb = 5_600,
        mediaPerformanceClass = 0,
    )

    @Test
    fun `the same facts always give the same tier`() {
        repeat(5) {
            assertEquals(DeviceTier.CAPABLE, classifyTier(flagship))
            assertEquals(DeviceTier.MEDIUM, classifyTier(midRange))
        }
    }

    @Test
    fun `a mid-range phone with a fast panel is not promoted`() {
        // The reported phone's class: eight cores, six gigabytes or less. The
        // refresh rate is deliberately not an input, so nothing about a 120 Hz panel can
        // move this.
        assertEquals(DeviceTier.MEDIUM, classifyTier(midRange))
    }

    @Test
    fun `any one entry-level fact makes a phone low`() {
        assertEquals(DeviceTier.LOW, classifyTier(flagship.copy(lowRam = true)))
        assertEquals(DeviceTier.LOW, classifyTier(flagship.copy(cores = 4)))
        assertEquals(DeviceTier.LOW, classifyTier(flagship.copy(totalRamMb = 2_900)))
        assertEquals(DeviceTier.LOW, classifyTier(flagship.copy(sdk = 25)))
    }

    @Test
    fun `an unreadable fact never counts against a phone`() {
        // Zero is "could not be read": not low, and not enough to be capable either.
        assertEquals(DeviceTier.MEDIUM, classifyTier(flagship.copy(totalRamMb = 0)))
        assertEquals(DeviceTier.MEDIUM, classifyTier(flagship.copy(cores = 0)))
    }

    @Test
    fun `capable has to be earned on every count`() {
        assertEquals(DeviceTier.MEDIUM, classifyTier(flagship.copy(cores = 6)))
        assertEquals(DeviceTier.MEDIUM, classifyTier(flagship.copy(totalRamMb = 6_000)))
    }

    @Test
    fun `a declared media performance class is capable`() {
        assertEquals(DeviceTier.CAPABLE, classifyTier(midRange.copy(mediaPerformanceClass = 31)))
        // ...unless something entry-level outranks it.
        assertEquals(
            DeviceTier.LOW,
            classifyTier(midRange.copy(mediaPerformanceClass = 31, lowRam = true)),
        )
    }

    @Test
    fun `Full gives a low phone exactly the capable dials`() {
        // Advice, never a ceiling: the detected tier is not consulted once Full is chosen.
        val forced = dialsFor(resolveTier(TIER_FULL, classifyTier(flagship.copy(lowRam = true))))
        assertEquals(dialsFor(DeviceTier.CAPABLE), forced)
    }

    @Test
    fun `the override forces a tier and anything else is automatic`() {
        assertEquals(DeviceTier.CAPABLE, resolveTier(TIER_FULL, DeviceTier.LOW))
        assertEquals(DeviceTier.LOW, resolveTier(TIER_LIGHT, DeviceTier.CAPABLE))
        assertEquals(DeviceTier.MEDIUM, resolveTier(TIER_AUTO, DeviceTier.MEDIUM))
        assertEquals(DeviceTier.MEDIUM, resolveTier(null, DeviceTier.MEDIUM))
        assertEquals(DeviceTier.MEDIUM, resolveTier("stale-value", DeviceTier.MEDIUM))
    }

    // ── the dials ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a capable phone spends exactly what it did before tiers existed`() {
        // Literals, not the constants: these are the values at the base commit, and the
        // point is to fail if the capable tier drifts from them.
        val d = dialsFor(DeviceTier.CAPABLE)
        assertEquals(60L, d.pulseFps)
        assertEquals(15L, d.glowFps)
        assertEquals(20L, d.phaseFps)
        assertEquals(180, d.wavePoints)
        assertEquals(150, d.emitEvery)
        assertEquals(DISC_EVERY_FRAME, d.discFps)
    }

    @Test
    fun `every dial spends no less as the tier rises`() {
        val (low, mid, cap) = DeviceTier.entries.map(::dialsFor)
        fun rising(name: String, a: Long, b: Long, c: Long) =
            assertTrue("$name is not monotonic: $a $b $c", a <= b && b <= c)
        rising("pulseFps", low.pulseFps, mid.pulseFps, cap.pulseFps)
        rising("glowFps", low.glowFps, mid.glowFps, cap.glowFps)
        rising("phaseFps", low.phaseFps, mid.phaseFps, cap.phaseFps)
        rising("wavePoints", low.wavePoints.toLong(), mid.wavePoints.toLong(), cap.wavePoints.toLong())
        // Partials are a cost when frequent, so the GAP between them shrinks as the tier rises.
        rising("emitEvery (inverted)", -low.emitEvery.toLong(), -mid.emitEvery.toLong(), -cap.emitEvery.toLong())
    }

    @Test
    fun `no tier turns anything off`() {
        for (tier in DeviceTier.entries) {
            val d = dialsFor(tier)
            assertTrue(tier.name, d.pulseFps > 0 && d.glowFps > 0 && d.phaseFps > 0)
            assertTrue(tier.name, d.wavePoints > 0 && d.emitEvery > 0)
            // The disc steps at some rate at every tier; 0 is "every frame", never "off".
            assertTrue(tier.name, d.discFps >= 0L)
        }
    }

    @Test
    fun `the slowest follower still lands inside every onset window`() {
        // strengthAt reads the bucket under the position and the next: two buckets wide. A
        // step longer than that could fall between two reads and miss a beat entirely.
        val slowest = DeviceTier.entries.minOf { dialsFor(it).pulseFps }
        assertTrue(1000L / slowest < 2 * BeatMap.BUCKET_MS)
    }

    // ── what may animate ─────────────────────────────────────────────────────────────────

    @Test
    fun `only playing, on screen, uncovered and allowed animates`() {
        assertTrue(shouldAnimate(onScreen = true, playing = true, systemAnimations = true))
        assertFalse("paused", shouldAnimate(true, playing = false, systemAnimations = true))
        assertFalse("backgrounded", shouldAnimate(onScreen = false, playing = true, systemAnimations = true))
        assertFalse("OS switch", shouldAnimate(true, true, systemAnimations = false))
        assertFalse("under a viewer", shouldAnimate(true, true, true, covered = true))
    }

    // ── the position poll ────────────────────────────────────────────────────────────────

    @Test
    fun `nobody watching polls only as often as the resume store writes`() {
        assertEquals(200L, Playback.tickInterval(watched = true))
        assertEquals(Playback.SAVE_EVERY_MS, Playback.tickInterval(watched = false))
        assertTrue(Playback.tickInterval(false) > Playback.tickInterval(true))
    }

    @Test
    fun `the strip's playhead fraction`() {
        assertEquals(0.5f, playheadFraction(30_000, 60_000), 1e-6f)
        assertEquals(0f, playheadFraction(5_000, 0))
        assertEquals(1f, playheadFraction(70_000, 60_000))
    }

    // ── the shade card and the session ───────────────────────────────────────────────────

    private val track = Track(id = "/music/a.mp3", name = "a.mp3")
    private val playing = NowPlaying(
        queue = listOf(track), playing = true, active = true, durationMs = 180_000, positionMs = 10_000,
    )

    @Test
    fun `a position tick alone never reposts the card`() {
        // The defect: this used to be reposted on every one of these, five times a second.
        assertFalse(PlaybackNotice.needsRepost(playing, playing.copy(positionMs = 10_200)))
        assertTrue(playing.samePlayback(playing.copy(positionMs = 99_000)))
    }

    @Test
    fun `a real change reposts the card`() {
        assertTrue(PlaybackNotice.needsRepost(null, playing))
        assertTrue(PlaybackNotice.needsRepost(playing, playing.copy(playing = false)))
        assertTrue(PlaybackNotice.needsRepost(playing, playing.copy(repeat = Repeat.ONE)))
        assertTrue(PlaybackNotice.needsRepost(playing, playing.copy(queue = listOf(track, track))))
    }

    @Test
    fun `metadata is set again only for a new track, duration or picture`() {
        val art = Any()
        assertFalse(PlaybackNotice.metadataChanged(playing, art, playing.copy(positionMs = 50_000), art))
        assertTrue(PlaybackNotice.metadataChanged(playing, art, playing, Any()))
        assertTrue(PlaybackNotice.metadataChanged(playing, art, playing.copy(durationMs = 1), art))
        assertTrue(PlaybackNotice.metadataChanged(null, art, playing, art))
    }

    @Test
    fun `the session is left to run its own clock until it would be wrong`() {
        // Published at 10s, playing at 1x, two seconds ago.
        assertFalse("on the clock", PlaybackNotice.sessionStale(10_000, 0, 1f, 12_100, 2_000, 1f))
        assertTrue("a seek", PlaybackNotice.sessionStale(10_000, 0, 1f, 60_000, 2_000, 1f))
        assertTrue("paused", PlaybackNotice.sessionStale(10_000, 0, 1f, 12_000, 2_000, 0f))
        assertTrue("speed", PlaybackNotice.sessionStale(10_000, 0, 1f, 12_000, 2_000, 1.5f))
        assertFalse("still paused", PlaybackNotice.sessionStale(10_000, 0, 0f, 10_000, 60_000, 0f))
        assertFalse("1.5x on its clock", PlaybackNotice.sessionStale(10_000, 0, 1.5f, 13_000, 2_000, 1.5f))
    }

    @Test
    fun `the colour drift does not repost with the screen off`() {
        assertTrue(PlaybackNotice.driftWanted(glowOn = true, playing = true, interactive = true))
        assertFalse(PlaybackNotice.driftWanted(glowOn = true, playing = true, interactive = false))
        assertFalse(PlaybackNotice.driftWanted(glowOn = true, playing = false, interactive = true))
        assertFalse(PlaybackNotice.driftWanted(glowOn = false, playing = true, interactive = true))
    }

    @Test
    fun `status carries no position`() {
        assertEquals(0L, playing.withoutPosition().positionMs)
        assertTrue(playing.withoutPosition().samePlayback(playing))
    }

    // ── the disc and the measurement ─────────────────────────────────────────────────────

    private fun Platter.run(seconds: Float, playing: Boolean, fps: Int = 60): Boolean {
        var moving = true
        repeat((seconds * fps).toInt()) { moving = step(1f / fps, playing) }
        return moving
    }

    @Test
    fun `the platter spins up, never jumping`() {
        val p = Platter()
        var lastAngle = 0f
        var maxStep = 0f
        repeat(60) {
            p.step(1f / 60, playing = true)
            maxStep = maxOf(maxStep, (p.angle - lastAngle + 360f) % 360f)
            lastAngle = p.angle
        }
        assertTrue("spun up instantly: ${p.rate}", p.rate in 0.4f..0.8f)
        // Full speed is 15 degrees a second: a quarter of a degree a frame at most.
        assertTrue("jumped $maxStep degrees in a frame", maxStep <= 0.26f)
        p.run(6f, playing = true)
        assertEquals(1f, p.rate, 0.01f)
    }

    @Test
    fun `the platter coasts to rest and then says it has stopped`() {
        val p = Platter()
        p.run(8f, playing = true)
        val before = p.angle
        p.step(1f / 60, playing = false)
        assertTrue("stopped dead on pause", p.rate > 0.9f && p.angle != before)
        val stillMoving = p.run(30f, playing = false)
        assertEquals(0f, p.rate)
        assertFalse("a platter at rest kept its loop alive", stillMoving)
    }

    @Test
    fun `a released scratch recovers far faster than a cold start`() {
        val cold = Platter()
        cold.run(0.4f, playing = true)
        val grabbed = Platter()
        grabbed.run(8f, playing = true)
        grabbed.grab()
        assertEquals(0f, grabbed.rate)
        grabbed.run(0.4f, playing = true)
        assertTrue("recovery ${grabbed.rate} vs cold ${cold.rate}", grabbed.rate > 0.9f && cold.rate < 0.4f)
    }

    private fun clicks(seconds: Int): TrackPcm {
        val rate = TrackPcm.TARGET_RATE
        val samples = ShortArray(seconds * rate) { i ->
            if (i % (rate / 2) < 200) 20_000 else 300
        }
        return TrackPcm(samples, rate)
    }

    @Test
    fun `a measurement nobody wants stops and caches nothing`() {
        try {
            BeatMap.from(clicks(10), alive = { false })
            fail("the measurement ran to the end with nobody waiting")
        } catch (e: java.util.concurrent.CancellationException) {
            // Expected: the caller's coroutine is gone.
        }
    }

    @Test
    fun `the strip finds the map the player stored`() {
        // The defect: the player stored under the resolved URI, the strip asked with the
        // track's VFS path, and the strip's pulse never fired. Both sides go through keyFor.
        val resolved = "file:///storage/emulated/0/Music/strip-key-test.mp3"
        val stored = BeatMap.of(BeatMap.keyFor(resolved), clicks(3))
        assertTrue(BeatMap.cached(BeatMap.keyFor(resolved)) === stored)
        // The track id the strip used to ask with is not that key.
        assertTrue(BeatMap.cached("/storage/emulated/0/Music/strip-key-test.mp3") == null)
    }

    // ── the drop-out instrument ──────────────────────────────────────────────────────────

    @Test
    fun `each drop-out cause is told apart`() {
        assertEquals(DropoutCause.STALLED, classifyDropout(2000, 400, 0, 0.9f, 0.6f))
        assertEquals(DropoutCause.PAST_MAP, classifyDropout(2000, 20, 1500, 0.9f, 0.6f))
        assertEquals(DropoutCause.FLATTENED, classifyDropout(2000, 20, 0, 0.6f, 0.6f))
        assertEquals(DropoutCause.EMPTY_MAP, classifyDropout(2000, 20, 0, 0.0f, 0.6f))
        assertEquals(DropoutCause.QUIET, classifyDropout(2000, 20, 0, 0.0f, 0.05f))
    }

    @Test
    fun `a flat stretch is reported once, when it ends, and a short one never`() {
        val watch = DropoutWatch()
        var t = 0L
        fun step(flat: Boolean, raw: Float = 0f) =
            watch.step(t, pastMap = false, raw = raw, loud = 0.6f,
                blast = if (flat) 0f else 0.8f, swing = if (flat) -0.1f else 0.5f)
                .also { t += 16 }
        // A short gap between beats is not a drop-out.
        repeat(20) { assertEquals(null, step(flat = true)) }
        assertEquals(null, step(flat = false))
        // Two seconds flat while hits are being read: the follower swallowed them.
        repeat(125) { assertEquals(null, step(flat = true, raw = 0.7f)) }
        val report = step(flat = false)
        assertEquals(DropoutCause.FLATTENED, report?.cause)
        assertTrue((report?.quietMs ?: 0) >= DROPOUT_MS)
        assertEquals(null, step(flat = false))
    }

    @Test
    fun `reading past the map is still caught after the coverage fix`() {
        // The known cause behind the drop-outs. The coverage rule stops it at the source; this
        // keeps the instrument able to say so if it ever comes back, whatever was read.
        val watch = DropoutWatch()
        var t = 0L
        repeat(125) {
            watch.step(t, pastMap = true, raw = 0.6f, loud = 0.6f, blast = 0f, swing = -0.1f)
            t += 16
        }
        val report = watch.step(t, pastMap = false, raw = 0.6f, loud = 0.6f, blast = 0.8f, swing = 0.5f)
        assertEquals(DropoutCause.PAST_MAP, report?.cause)
    }

    @Test
    fun `a late step is a stall, and resting is not`() {
        val watch = DropoutWatch()
        watch.step(0, false, 0f, 0.5f, 0.5f, 0.5f)
        assertEquals(DropoutCause.STALLED, watch.step(400, false, 0f, 0.5f, 0.5f, 0.5f)?.cause)
        watch.rest()
        // Paused for a minute on purpose: the first step back is not a stall.
        assertEquals(null, watch.step(60_400, false, 0f, 0.5f, 0.5f, 0.5f))
    }

    @Test
    fun `a wider gap means fewer partial maps`() {
        var often = 0
        var rarely = 0
        BeatMap.from(clicks(20), onPartial = { often++ }, emitEvery = 150)
        BeatMap.from(clicks(20), onPartial = { rarely++ }, emitEvery = 600)
        assertTrue("$often vs $rarely", rarely < often)
    }
}

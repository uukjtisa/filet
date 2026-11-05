package dev.niccc2007.filet.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the sound's effects are made, kept, remade and let go - against a phone that is pretend,
 * so a change of track and a headset reconnecting can be played out without one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoundLinkTest {

    /** Counts what it is asked for, and refuses whatever it is told to. */
    private class FakePort(
        var refuseEq: Boolean = false,
        var refuseBoost: Boolean = false,
        var refuseSpace: Boolean = false,
        var refuseWide: Boolean = false,
    ) : SoundPort {
        val open = mutableListOf<String>()
        var opened = 0
        var lastBoostMb = -1
        var lastInputDb = Float.NaN
        var lastGains: FloatArray? = null
        var lastSpace: SoundMaths.Space? = null
        var lastWide = -1
        var lastCompression: SoundMaths.Compression? = null
        var compressed = 0
        var reverbKind = SoundMaths.Reverb.ENVIRONMENTAL

        override val reverb: SoundMaths.Reverb get() = reverbKind

        override fun openSpace(session: Int): SpaceEffect? {
            if (refuseSpace) return null
            opened++
            val name = "space@$session#$opened"
            open += name
            return object : SpaceEffect {
                override fun set(space: SoundMaths.Space) { lastSpace = space }
                override fun close() { open -= name }
            }
        }

        override fun offers(part: SoundPart): Boolean =
            !(part == SoundPart.SPACE && refuseSpace) && !(part == SoundPart.WIDE && refuseWide)

        override fun openWide(session: Int): WideEffect? {
            if (refuseWide) return null
            opened++
            val name = "wide@$session#$opened"
            open += name
            return object : WideEffect {
                override fun set(strength: Int) { lastWide = strength }
                override fun close() { open -= name }
            }
        }

        override fun openEq(session: Int): EqEffect? {
            if (refuseEq) return null
            opened++
            val name = "eq@$session#$opened"
            open += name
            return object : EqEffect {
                override fun set(inputDb: Float, gains: FloatArray) {
                    lastInputDb = inputDb
                    lastGains = gains
                }

                override fun compress(c: SoundMaths.Compression?) {
                    lastCompression = c
                    compressed++
                }

                override fun close() { open -= name }
            }
        }

        override fun openBoost(session: Int): BoostEffect? {
            if (refuseBoost) return null
            opened++
            val name = "boost@$session#$opened"
            open += name
            return object : BoostEffect {
                override fun set(mb: Int) { lastBoostMb = mb }
                override fun close() { open -= name }
            }
        }
    }

    @Test
    fun `at the defaults a player is linked with nothing in its signal`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val settings = MutableStateFlow(SoundSettings())
        val sound = Sound({ settings }, port, backgroundScope)
        var level = -1f
        val link = sound.link(7, SoundTarget.MUSIC) { level = it }
        assertEquals(0, port.opened)
        assertEquals(1f, link.level, 0f)
        assertEquals(1f, level, 0f)
    }

    @Test
    fun `a boost brings its limiter in, and going back to 100 takes it out again`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val settings = MutableStateFlow(SoundSettings())
        val sound = Sound({ settings }, port, backgroundScope)
        val link = sound.link(7, SoundTarget.MUSIC) {}
        settings.value = SoundSettings(volume = 200)
        assertTrue(link.boosting)
        assertEquals(602, port.lastBoostMb)
        assertEquals(1f, link.level, 0f)
        settings.value = SoundSettings(volume = 100)
        assertFalse(link.boosting)
        assertTrue(port.open.isEmpty())
        // Under 100 is the player's own volume and no effect at all.
        var level = 1f
        sound.link(8, SoundTarget.MUSIC) { level = it }
        settings.value = SoundSettings(volume = 40)
        assertEquals(0.4f, level, 0f)
        assertTrue(port.open.isEmpty())
    }

    @Test
    fun `a change to the music leaves the video untouched, and the reverse`() = runTest(UnconfinedTestDispatcher()) {
        // Bug identified here: the music's look turned up in the video player, because there was
        // one sound for the process. Each player has its own now.
        val port = FakePort()
        val musicSound = MutableStateFlow(SoundSettings())
        val videoSound = MutableStateFlow(SoundSettings())
        val sound = Sound({ if (it == SoundTarget.MUSIC) musicSound else videoSound }, port, backgroundScope)
        val music = sound.link(1, SoundTarget.MUSIC) {}
        val video = sound.link(2, SoundTarget.VIDEO) {}
        musicSound.value = SoundSettings(eqOn = true, bass = 60)
        assertTrue(music.equalising)
        assertFalse(video.equalising)
        assertEquals(setOf(1), port.open.map { it.substringAfter('@').substringBefore('#').toInt() }.toSet())
        videoSound.value = SoundSettings(volume = 150)
        assertTrue(video.boosting)
        assertFalse(music.boosting)
        // Each switch takes out its own player's and no other.
        musicSound.value = musicSound.value.copy(eqOn = false)
        assertFalse(music.equalising)
        assertTrue(video.boosting)
    }

    @Test
    fun `a new player on the session gets fresh effects, never a second set`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val settings = MutableStateFlow(SoundSettings(eqOn = true, bass = 50, volume = 150))
        val sound = Sound({ settings }, port, backgroundScope)
        sound.link(7, SoundTarget.MUSIC) {}
        assertEquals(2, port.open.size)
        // The next track, on the same session: linked again, and the old pair is gone.
        val again = sound.link(7, SoundTarget.MUSIC) {}
        assertEquals(2, port.open.size)
        assertEquals(4, port.opened)
        assertTrue(again.boosting && again.equalising)
    }

    @Test
    fun `a change of output remakes every effect with the same settings`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val settings = MutableStateFlow(SoundSettings(eqOn = true, bands = listOf(0, 0, 6, 0, 0), volume = 180))
        val sound = Sound({ settings }, port, backgroundScope)
        sound.link(7, SoundTarget.MUSIC) {}
        val before = port.open.toList()
        val gains = port.lastGains!!.copyOf()
        // A headset connects.
        sound.outputChanged()
        assertEquals(2, port.open.size)
        assertTrue("nothing from before survives", port.open.none { it in before })
        assertTrue(gains.contentEquals(port.lastGains))
        assertEquals(SoundMaths.boostMbOf(180), port.lastBoostMb)
    }

    @Test
    fun `a part the phone refuses is said, and the rest still works`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort(refuseBoost = true)
        val settings = MutableStateFlow(SoundSettings(eqOn = true, bass = 40, volume = 200))
        val sound = Sound({ settings }, port, backgroundScope)
        val link = sound.link(7, SoundTarget.MUSIC) {}
        assertEquals(setOf(SoundPart.BOOST), sound.refused.value)
        assertTrue(link.equalising)
        assertFalse(link.boosting)
    }

    @Test
    fun `a hall on the music is the music's alone, survives a reconnect, and goes with its switch`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val musicSound = MutableStateFlow(SoundSettings())
        val videoSound = MutableStateFlow(SoundSettings())
        val sound = Sound({ if (it == SoundTarget.MUSIC) musicSound else videoSound }, port, backgroundScope)
        val music = sound.link(1, SoundTarget.MUSIC) {}
        val video = sound.link(2, SoundTarget.VIDEO) {}
        musicSound.value = musicSound.value.copy(reverb = EffectLevel(true, 100))
        assertTrue(music.spacing)
        assertFalse(video.spacing || video.equalising)
        assertEquals(SoundMaths.Space.HALL, port.lastSpace)
        // The flat equaliser comes with it, for the headroom.
        assertTrue(music.equalising)
        sound.outputChanged()
        assertTrue(music.spacing)
        assertFalse(video.spacing)
        assertEquals(2, port.open.size)
        // And a hall on the video is the video's, with the music's still in.
        videoSound.value = videoSound.value.copy(reverb = EffectLevel(true, 100))
        assertTrue(video.spacing && music.spacing)
        musicSound.value = musicSound.value.copy(reverb = EffectLevel(false, 100))
        assertFalse(music.spacing)
        assertTrue(video.spacing)
        videoSound.value = videoSound.value.copy(reverb = EffectLevel(false, 100))
        assertTrue(port.open.isEmpty())
    }

    @Test
    fun `a phone without a reverb says so before anything is made, and the rest still works`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort(refuseSpace = true)
        val settings = MutableStateFlow(SoundSettings(eqOn = true, bass = 30, reverb = EffectLevel(true, 25)))
        val sound = Sound({ settings }, port, backgroundScope)
        assertFalse(SoundPart.SPACE in sound.offers)
        assertTrue(SoundPart.EQUALISER in sound.offers)
        val link = sound.link(7, SoundTarget.MUSIC) {}
        assertEquals(setOf(SoundPart.SPACE), sound.refused.value)
        assertTrue(link.equalising)
        assertFalse(link.spacing)
    }

    @Test
    fun `each phone's reverb gets its own headroom in front of it`() = runTest(UnconfinedTestDispatcher()) {
        val hall = SoundSettings(reverb = EffectLevel(true, 100))
        val env = FakePort()
        Sound({ MutableStateFlow(hall) }, env, backgroundScope).link(7, SoundTarget.MUSIC) {}
        assertEquals(SoundMaths.Space.HALL, env.lastSpace)
        assertEquals(-SoundMaths.room(SoundMaths.Space.HALL)!!.headroomDb, env.lastInputDb, 0.001f)
        val preset = FakePort().apply { reverbKind = SoundMaths.Reverb.PRESET }
        val sound = Sound({ MutableStateFlow(hall) }, preset, backgroundScope)
        assertEquals(SoundMaths.Reverb.PRESET, sound.reverb)
        sound.link(7, SoundTarget.MUSIC) {}
        assertEquals(SoundMaths.Space.HALL, preset.lastSpace)
        assertEquals(-SoundMaths.SPACE_HEADROOM_DB, preset.lastInputDb, 0.001f)
    }

    @Test
    fun `wide is a virtualizer of its own, made with its switch, remade on a new output, gone with it`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val settings = MutableStateFlow(SoundSettings())
        val sound = Sound({ settings }, port, backgroundScope)
        assertTrue(SoundPart.WIDE in sound.offers)
        val link = sound.link(7, SoundTarget.MUSIC) {}
        settings.value = SoundSettings(wide = EffectLevel(true, 60))
        assertTrue(link.widening)
        assertEquals(600, port.lastWide)
        // The flat equaliser comes with it, for the margin in front.
        assertTrue(link.equalising)
        assertEquals(-SoundMaths.WIDE_HEADROOM_DB, port.lastInputDb, 0.001f)
        val before = port.open.toList()
        sound.outputChanged()
        assertTrue(link.widening)
        assertTrue(port.open.none { it in before })
        settings.value = SoundSettings(wide = EffectLevel(false, 60))
        assertFalse(link.widening)
        assertTrue(port.open.isEmpty())
    }

    @Test
    fun `a phone without a virtualizer says so before anything is made, and the rest still works`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort(refuseWide = true)
        val settings = MutableStateFlow(SoundSettings(eqOn = true, bass = 30, wide = EffectLevel(true, 100)))
        val sound = Sound({ settings }, port, backgroundScope)
        assertFalse(SoundPart.WIDE in sound.offers)
        val link = sound.link(7, SoundTarget.MUSIC) {}
        assertEquals(setOf(SoundPart.WIDE), sound.refused.value)
        assertTrue(link.equalising)
        assertFalse(link.widening)
    }

    @Test
    fun `even out is the equaliser's own effect, never a second one on the session`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val settings = MutableStateFlow(SoundSettings())
        val sound = Sound({ settings }, port, backgroundScope)
        val link = sound.link(7, SoundTarget.MUSIC) {}
        settings.value = SoundSettings(evenOut = EffectLevel(true, 50))
        assertEquals(1, port.open.size)
        assertTrue(port.open.single().startsWith("eq@"))
        assertEquals(SoundMaths.compression(50), port.lastCompression)
        // A curve as well is still the one effect, and the compressor goes when its switch does.
        settings.value = SoundSettings(eqOn = true, bass = 60, evenOut = EffectLevel(true, 100))
        assertEquals(1, port.open.size)
        assertEquals(SoundMaths.compression(100), port.lastCompression)
        settings.value = settings.value.copy(evenOut = EffectLevel(false, 100))
        assertTrue(link.equalising)
        assertNull(port.lastCompression)
        settings.value = SoundSettings()
        assertTrue(port.open.isEmpty())
    }

    @Test
    fun `nightcore's rate is told to the player, and nothing goes in the signal for it`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val settings = MutableStateFlow(SoundSettings())
        val sound = Sound({ settings }, port, backgroundScope)
        var told = -1f
        val link = sound.link(7, SoundTarget.MUSIC, onRate = { told = it }) {}
        assertEquals(1f, told, 0f)
        settings.value = SoundMaths.Overlay.NIGHTCORE.switched(SoundSettings(), true)
        assertEquals(1.24f, told, 0.0001f)
        assertEquals(1.24f, link.rate, 0.0001f)
        settings.value = SoundMaths.Overlay.VAPORWAVE.switched(settings.value, true)
        assertEquals(0.79f, told, 0.0001f)
        assertEquals(0, port.opened)
        // A change elsewhere is not a change of rate, so the player is not told again.
        told = -1f
        settings.value = settings.value.copy(volume = 80)
        assertEquals(-1f, told, 0f)
    }

    @Test
    fun `8D sweeps the sides while it is on, puts them back at 1 when off, and stops with the link`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val settings = MutableStateFlow(SoundSettings())
        val sound = Sound({ settings }, port, backgroundScope, clockMs = { testScheduler.currentTime })
        var pans = 0
        val link = sound.link(7, SoundTarget.MUSIC, onPan = { pans++ }) {}
        assertEquals(0, pans)
        settings.value = SoundSettings(orbit = EffectLevel(true, 100))
        advanceTimeBy(1_500)
        runCurrent()
        // Every step, at the sweep's own place for that moment.
        assertTrue("$pans", pans >= 1_500 / Sound.SWEEP_STEP_MS.toInt())
        val step = testScheduler.currentTime / Sound.SWEEP_STEP_MS * Sound.SWEEP_STEP_MS
        val (l, r) = SoundMaths.orbitGains(1f, step)
        assertEquals(l, link.panLeft, 0f)
        assertEquals(r, link.panRight, 0f)
        assertEquals(0, port.opened)
        // Off: both sides at 1 at once, and no more steps.
        settings.value = SoundSettings()
        assertEquals(1f, link.panLeft, 0f)
        assertEquals(1f, link.panRight, 0f)
        val stopped = pans
        advanceTimeBy(1_000)
        assertEquals(stopped, pans)
        // On again and then the player goes: the sweep goes with it.
        settings.value = SoundSettings(orbit = EffectLevel(true, 50))
        link.close()
        val closed = pans
        advanceTimeBy(1_000)
        assertEquals(closed, pans)
    }

    @Test
    fun `a closed link lets everything go and hears nothing more`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val settings = MutableStateFlow(SoundSettings(volume = 200))
        val sound = Sound({ settings }, port, backgroundScope)
        val link = sound.link(7, SoundTarget.MUSIC) {}
        link.close()
        assertTrue(port.open.isEmpty())
        val opened = port.opened
        settings.value = SoundSettings(eqOn = true, bass = 100, volume = 200)
        sound.outputChanged()
        assertEquals(opened, port.opened)
    }
}

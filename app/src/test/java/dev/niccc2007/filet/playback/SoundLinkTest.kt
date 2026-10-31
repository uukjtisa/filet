package dev.niccc2007.filet.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    ) : SoundPort {
        val open = mutableListOf<String>()
        var opened = 0
        var lastBoostMb = -1
        var lastInputDb = Float.NaN
        var lastGains: FloatArray? = null
        var lastSpace: SoundMaths.Space? = null

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

        override fun offers(part: SoundPart): Boolean = !(part == SoundPart.SPACE && refuseSpace)

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
        musicSound.value = SoundMaths.Look.HALL.on(musicSound.value)
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
        videoSound.value = SoundMaths.Look.HALL.on(videoSound.value)
        assertTrue(video.spacing && music.spacing)
        musicSound.value = musicSound.value.copy(eqOn = false)
        assertFalse(music.spacing)
        assertTrue(video.spacing)
        videoSound.value = videoSound.value.copy(eqOn = false)
        assertTrue(port.open.isEmpty())
    }

    @Test
    fun `a phone without a reverb says so before anything is made, and the rest still works`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort(refuseSpace = true)
        val settings = MutableStateFlow(SoundMaths.Look.ROOM.on(SoundSettings()).copy(bass = 30))
        val sound = Sound({ settings }, port, backgroundScope)
        assertFalse(SoundPart.SPACE in sound.offers)
        assertTrue(SoundPart.EQUALISER in sound.offers)
        val link = sound.link(7, SoundTarget.MUSIC) {}
        assertEquals(setOf(SoundPart.SPACE), sound.refused.value)
        assertTrue(link.equalising)
        assertFalse(link.spacing)
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

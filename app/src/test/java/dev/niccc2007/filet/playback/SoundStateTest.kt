package dev.niccc2007.filet.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each player's sound, and a file remembered with its whole sheet: where an edit goes, what a
 * file plays with, and what survives a restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoundStateTest {

    private class FakeGeneral : GeneralSound {
        val flows = SoundTarget.entries.associateWith { MutableStateFlow(SoundSettings()) }
        override fun soundOf(target: SoundTarget): StateFlow<SoundSettings> = flows.getValue(target)
        override fun setSound(target: SoundTarget, settings: SoundSettings) { flows.getValue(target).value = settings }
    }

    private class FakeStore : RememberStore {
        val map = LinkedHashMap<String, String>()
        override fun all(): Map<String, String> = map.toMap()
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun TestScope.state(general: GeneralSound = FakeGeneral(), store: RememberStore = FakeStore()) =
        SoundState(general, store, backgroundScope)

    private val loud = SoundSettings(eqOn = true, bands = listOf(5, 3, 0, 0, 1), bass = 40, volume = 150)

    @Test
    fun `a file nobody remembered plays with its player's settings, and an edit goes to them`() = runTest(UnconfinedTestDispatcher()) {
        val general = FakeGeneral()
        val s = state(general)
        s.playing(SoundTarget.MUSIC, "/Music/a.mp3")
        assertNull(s.rememberedNow(SoundTarget.MUSIC).value)
        s.editSound(SoundTarget.MUSIC) { it.copy(bass = 30) }
        assertEquals(30, general.flows.getValue(SoundTarget.MUSIC).value.bass)
        assertEquals(30, s.sound(SoundTarget.MUSIC).value.bass)
    }

    @Test
    fun `remembering keeps what is heard now, so nothing jumps`() = runTest(UnconfinedTestDispatcher()) {
        val general = FakeGeneral()
        general.setSound(SoundTarget.MUSIC, loud)
        val s = state(general)
        s.playing(SoundTarget.MUSIC, "/Music/a.mp3")
        s.remember(SoundTarget.MUSIC, on = true, speed = 0.8f, pitch = PitchMode.HOLD)
        val r = s.rememberedNow(SoundTarget.MUSIC).value
        assertEquals(Remembered(0.8f, PitchMode.HOLD, loud), r)
        assertEquals(loud, s.sound(SoundTarget.MUSIC).value)
    }

    @Test
    fun `a remembered file's edits are its own, and the next file plays with the player's settings`() = runTest(UnconfinedTestDispatcher()) {
        val general = FakeGeneral()
        val s = state(general)
        s.playing(SoundTarget.MUSIC, "/Music/a.mp3")
        s.remember(SoundTarget.MUSIC, on = true, speed = 1f, pitch = PitchMode.FOLLOW)
        s.editSound(SoundTarget.MUSIC) { SoundMaths.Look.HALL.on(it) }
        assertTrue(s.editPlayback(SoundTarget.MUSIC) { it.copy(speed = 0.7f) })
        // The player's own settings did not move.
        assertEquals(SoundSettings(), general.flows.getValue(SoundTarget.MUSIC).value)
        assertEquals(SoundMaths.Space.HALL, s.sound(SoundTarget.MUSIC).value.space)
        // The album's next track is not this one.
        s.playing(SoundTarget.MUSIC, "/Music/b.mp3")
        assertNull(s.rememberedNow(SoundTarget.MUSIC).value)
        assertEquals(SoundSettings(), s.sound(SoundTarget.MUSIC).value)
        // And this one comes back with all of it.
        s.playing(SoundTarget.MUSIC, "/Music/a.mp3")
        assertEquals(0.7f, s.rememberedNow(SoundTarget.MUSIC).value!!.speed, 0f)
        assertEquals(SoundMaths.Space.HALL, s.sound(SoundTarget.MUSIC).value.space)
    }

    @Test
    fun `the pitch goes with a remembered file, and to the general setting otherwise`() = runTest(UnconfinedTestDispatcher()) {
        val s = state()
        var general: PitchMode? = null
        s.playing(SoundTarget.VIDEO, "/Movies/v.mp4")
        s.setPitch(SoundTarget.VIDEO, PitchMode.AUTO) { general = it }
        assertEquals(PitchMode.AUTO, general)
        assertFalse(s.editPlayback(SoundTarget.VIDEO) { it.copy(speed = 2f) })
        general = null
        s.remember(SoundTarget.VIDEO, on = true, speed = 1.5f, pitch = PitchMode.AUTO)
        s.setPitch(SoundTarget.VIDEO, PitchMode.HOLD) { general = it }
        assertNull(general)
        assertEquals(PitchMode.HOLD, s.rememberedNow(SoundTarget.VIDEO).value!!.pitch)
    }

    @Test
    fun `music and video remember apart, even for the same file`() = runTest(UnconfinedTestDispatcher()) {
        val s = state()
        s.playing(SoundTarget.MUSIC, "/Movies/clip.mp4")
        s.remember(SoundTarget.MUSIC, on = true, speed = 0.5f, pitch = PitchMode.FOLLOW)
        s.playing(SoundTarget.VIDEO, "/Movies/clip.mp4")
        assertNull(s.rememberedNow(SoundTarget.VIDEO).value)
        s.editSound(SoundTarget.VIDEO) { it.copy(volume = 120) }
        assertEquals(100, s.sound(SoundTarget.MUSIC).value.volume)
    }

    @Test
    fun `turning it off throws the file's sheet away and the player's settings apply again`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val general = FakeGeneral()
        val s = state(general, store)
        s.playing(SoundTarget.MUSIC, "/Music/a.mp3")
        s.remember(SoundTarget.MUSIC, on = true, speed = 1f, pitch = PitchMode.FOLLOW)
        s.editSound(SoundTarget.MUSIC) { it.copy(volume = 180) }
        assertEquals(1, store.map.size)
        s.remember(SoundTarget.MUSIC, on = false, speed = 1f, pitch = PitchMode.FOLLOW)
        assertTrue(store.map.isEmpty())
        assertNull(s.rememberedNow(SoundTarget.MUSIC).value)
        assertEquals(100, s.sound(SoundTarget.MUSIC).value.volume)
    }

    @Test
    fun `a remembered file comes back after a restart, boost and all`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val first = state(store = store)
        first.playing(SoundTarget.MUSIC, "/Music/a.mp3")
        first.remember(SoundTarget.MUSIC, on = true, speed = 1.25f, pitch = PitchMode.AUTO)
        first.editSound(SoundTarget.MUSIC) { it.copy(volume = 180) }
        // A new process: nothing playing yet, and the file is still known.
        val next = state(store = store)
        val r = next.rememberedFor(SoundTarget.MUSIC, "/Music/a.mp3")!!
        assertEquals(180, r.sound.volume)
        assertEquals(1.25f, r.speed, 0f)
        next.playing(SoundTarget.MUSIC, "/Music/a.mp3")
        assertEquals(180, next.sound(SoundTarget.MUSIC).value.volume)
    }

    @Test
    fun `with no file open there is nothing to remember`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val s = state(store = store)
        s.remember(SoundTarget.MUSIC, on = true, speed = 1f, pitch = PitchMode.FOLLOW)
        assertTrue(store.map.isEmpty())
        assertNull(s.fileOf(SoundTarget.MUSIC))
    }

    @Test
    fun `a file's sheet reads back as written, and anything else reads as not remembered`() {
        val r = Remembered(0.75f, PitchMode.HOLD, loud.copy(space = SoundMaths.Space.ROOM, filter = SoundMaths.Filter.RADIO))
        assertEquals(r, Remembered.decode(r.encode()))
        assertNull(Remembered.decode(null))
        assertNull(Remembered.decode(""))
        assertNull(Remembered.decode(r.encode().replaceFirst("v1", "v2")))
        assertNull(Remembered.decode(r.encode().replaceFirst("0.75", "NaN")))
        assertNull(Remembered.decode(r.encode() + ";extra"))
        // Out of range is held in range, not refused: the file is still that file.
        val wild = Remembered.decode("v1;99;follow;1;5,3,0,0,1;400;900;hall;none")!!
        assertEquals(MAX_SPEED, wild.speed, 0f)
        assertEquals(100, wild.sound.bass)
        assertEquals(SoundMaths.MAX_VOLUME, wild.sound.volume)
    }
}

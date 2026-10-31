package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.playback.SoundMaths
import dev.niccc2007.filet.playback.SoundSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the sound sheet and its button say, which is all of it that is not drawing. */
class SoundSheetTest {

    @Test
    fun `the button reads as the speed at the defaults and is not lit`() {
        assertEquals("1×", soundChipLabel(1f, SoundSettings()))
        assertFalse(soundChipLit(1f, SoundSettings()))
        // A curve shaped but switched off is not in the signal, so it lights nothing.
        assertFalse(soundChipLit(1f, SoundSettings(eqOn = false, bands = listOf(6, 0, 0, 0, 0), bass = 50)))
        // Nor does the switch on over nothing at all.
        assertFalse(soundChipLit(1f, SoundSettings(eqOn = true)))
    }

    @Test
    fun `the button names whatever is off its default, and only that`() {
        assertEquals("1.5×", soundChipLabel(1.5f, SoundSettings()))
        assertEquals("150%", soundChipLabel(1f, SoundSettings(volume = 150)))
        assertEquals("Hall", soundChipLabel(1f, SoundMaths.Look.HALL.on(SoundSettings())))
        assertEquals(
            "0.75× · 40% · Custom",
            soundChipLabel(0.75f, SoundSettings(eqOn = true, volume = 40, bands = listOf(2, 0, 0, 0, 0))),
        )
        assertTrue(soundChipLit(1f, SoundSettings(volume = 99)))
        assertTrue(soundChipLit(1f, SoundMaths.Look.MUFFLED.on(SoundSettings())))
    }

    @Test
    fun `the effects are named by their look, or custom`() {
        assertEquals("Flat", effectsName(SoundSettings()))
        assertEquals("Vocal", effectsName(SoundMaths.Look.VOCAL.on(SoundSettings())))
        assertEquals("Bass heavy", effectsName(SoundMaths.Look.BASS_HEAVY.on(SoundSettings())))
        assertEquals("Custom", effectsName(SoundSettings(bands = listOf(1, 0, 0, 0, 0))))
        assertEquals("Custom", effectsName(SoundMaths.Look.ROOM.on(SoundSettings()).copy(bass = 20)))
    }

    @Test
    fun `bands and gains read the way an equaliser is labelled`() {
        assertEquals(listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz"), SoundMaths.BANDS_HZ.map(::hzLabel))
        assertEquals("+3 dB", dbLabel(3))
        assertEquals("0 dB", dbLabel(0))
        assertEquals("-12 dB", dbLabel(-12))
    }

    @Test
    fun `the volume rail lands on 100 from close by and in fives elsewhere`() {
        assertEquals(100, SoundMaths.snapVolume(97f))
        assertEquals(100, SoundMaths.snapVolume(104f))
        assertEquals(105, SoundMaths.snapVolume(105f))
        assertEquals(95, SoundMaths.snapVolume(95.4f))
        assertEquals(150, SoundMaths.snapVolume(151.9f))
        assertEquals(0, SoundMaths.snapVolume(-20f))
        assertEquals(200, SoundMaths.snapVolume(260f))
        assertEquals(100, SoundMaths.snapVolume(Float.NaN))
    }
}

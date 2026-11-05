package dev.niccc2007.filet.handlers

import dev.niccc2007.filet.playback.EffectLevel
import dev.niccc2007.filet.playback.RememberStore
import dev.niccc2007.filet.playback.SoundMaths
import dev.niccc2007.filet.playback.SoundSettings
import dev.niccc2007.filet.playback.SoundTarget
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
        assertEquals("Large hall", soundChipLabel(1f, SoundSettings(reverb = EffectLevel(true, 100))))
        assertEquals(
            "0.75× · 40% · Custom",
            soundChipLabel(0.75f, SoundSettings(eqOn = true, volume = 40, bands = listOf(2, 0, 0, 0, 0))),
        )
        assertTrue(soundChipLit(1f, SoundSettings(volume = 99)))
        assertTrue(soundChipLit(1f, SoundSettings(muffled = EffectLevel(true, 60))))
        // The curve's name first, then each effect heard, the reverb by its size.
        assertEquals(
            "Rock · Room · Muffled",
            soundChipLabel(1f, SoundMaths.EqPreset.ROCK.on(SoundSettings()).copy(
                reverb = EffectLevel(true, 25),
                muffled = EffectLevel(true, 50),
                radio = EffectLevel(false, 100),
            )),
        )
        // A filter switched on at nothing is not heard, so it lights nothing.
        assertFalse(soundChipLit(1f, SoundSettings(radio = EffectLevel(true, 0))))
        assertFalse(soundChipLit(1f, SoundSettings(reverb = EffectLevel(false, 100))))
    }

    @Test
    fun `the equalizer is named by its preset, or custom, and the effects do not change the name`() {
        assertEquals("Flat", eqName(SoundSettings()))
        assertEquals("Vocal", eqName(SoundMaths.EqPreset.VOCAL.on(SoundSettings())))
        assertEquals("Bass boost", eqName(SoundMaths.EqPreset.BASS_BOOST.on(SoundSettings())))
        assertEquals("R&B", eqName(SoundMaths.EqPreset.RNB.on(SoundSettings())))
        assertEquals("Custom", eqName(SoundSettings(bands = listOf(1, 0, 0, 0, 0))))
        assertEquals("Custom", eqName(SoundMaths.EqPreset.ROCK.on(SoundSettings()).copy(bass = 20)))
        assertEquals("Jazz", eqName(SoundMaths.EqPreset.JAZZ.on(SoundSettings()).copy(radio = EffectLevel(true, 80))))
    }

    @Test
    fun `the tabs come back as left, and only the three on the phone's effects say beta`() {
        assertEquals(listOf("Playback", "Loudness", "Effects", "EQ"), SheetTab.entries.map { it.label })
        assertEquals(listOf(SheetTab.LOUDNESS, SheetTab.EFFECTS, SheetTab.EQ), SheetTab.entries.filter { it.beta })
        for (t in SheetTab.entries) assertEquals(t, SheetTab.of(t.id))
        assertEquals(SheetTab.PLAYBACK, SheetTab.of(null))
        assertEquals(SheetTab.PLAYBACK, SheetTab.of("mixer"))
    }

    @Test
    fun `each player's sheet keeps its own tab and its own folded sections`() {
        val store = object : RememberStore {
            val map = LinkedHashMap<String, String>()
            override fun all(): Map<String, String> = map.toMap()
            override fun put(key: String, value: String) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
        }
        val memory = SheetMemory(store)
        assertEquals(SheetTab.PLAYBACK, memory.tab(SoundTarget.MUSIC))
        assertTrue(memory.folded(SoundTarget.MUSIC).isEmpty())
        memory.setTab(SoundTarget.MUSIC, SheetTab.EQ)
        memory.setFolded(SoundTarget.MUSIC, setOf("presets", "bands"))
        memory.setTab(SoundTarget.VIDEO, SheetTab.EFFECTS)
        // Read back by a fresh one, as after a restart, and the two players apart.
        val next = SheetMemory(store)
        assertEquals(SheetTab.EQ, next.tab(SoundTarget.MUSIC))
        assertEquals(setOf("presets", "bands"), next.folded(SoundTarget.MUSIC))
        assertEquals(SheetTab.EFFECTS, next.tab(SoundTarget.VIDEO))
        assertTrue(next.folded(SoundTarget.VIDEO).isEmpty())
        // Nothing folded is no entry at all.
        next.setFolded(SoundTarget.MUSIC, emptySet())
        assertEquals(setOf("music.tab", "video.tab"), store.map.keys)
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

    @Test
    fun `the chip gives way names first, then counts, then the volume, keeping the speed longest`() {
        val busy = SoundMaths.EqPreset.SPOKEN_WORD.on(SoundSettings(volume = 200)).copy(
            reverb = EffectLevel(true, 100),
            muffled = EffectLevel(true, 70),
            radio = EffectLevel(true, 100),
        )
        assertEquals(
            listOf(
                "0.75× · 200% · Spoken word · Large hall · Muffled · Radio",
                "0.75× · 200% · Spoken word · 3 effects",
                "0.75× · 200% · 4 effects",
                "0.75× · 200%",
                "0.75×",
                "",
            ),
            soundChipLadder(0.75f, busy),
        )
        // Each rung shorter than the one above, so every step down saves width.
        val ladder = soundChipLadder(0.75f, busy)
        for (i in 1 until ladder.size) assertTrue(ladder[i].length < ladder[i - 1].length)
        // At the defaults the chip is the speed, and only the glyph below that.
        assertEquals(listOf("1×", ""), soundChipLadder(1f, SoundSettings()))
        // One curve alone: "1 effect" says less in more room than its name, so it is left out.
        assertEquals(listOf("Rock", ""), soundChipLadder(1f, SoundMaths.EqPreset.ROCK.on(SoundSettings())))
        // No speed or volume: the counts are the last words.
        assertEquals(
            listOf("Room · Radio", "2 effects", ""),
            soundChipLadder(1f, SoundSettings(reverb = EffectLevel(true, 25), radio = EffectLevel(true, 50))),
        )
        assertEquals(ladder.first(), soundChipLabel(0.75f, busy))
    }

    @Test
    fun `the chip says the longest rung that fits, and nothing rather than half a word`() {
        val ladder = soundChipLadder(0.75f, SoundSettings(volume = 200, muffled = EffectLevel(true, 50)))
        assertEquals(listOf("0.75× · 200% · Muffled", "0.75× · 200%", "0.75×", ""), ladder)
        val fits = { width: Int -> { s: String -> s.length <= width } }
        assertEquals("0.75× · 200% · Muffled", fitLadder(ladder, fits(40)))
        assertEquals("0.75× · 200%", fitLadder(ladder, fits(21)))
        assertEquals("0.75×", fitLadder(ladder, fits(11)))
        assertEquals("", fitLadder(ladder, fits(3)))
        assertEquals("", fitLadder(emptyList(), fits(99)))
    }
}

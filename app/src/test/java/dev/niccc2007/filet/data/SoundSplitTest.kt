package dev.niccc2007.filet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one shared sound, split into the music's and the video's without anybody losing a setting. */
class SoundSplitTest {

    @Test
    fun `the shared sound lands on both players, and a player's own wins`() {
        val stored = mapOf(
            "sound.eqOn" to true,
            "sound.bass" to 40,
            "sound.space" to "hall",
            // This player already has its own: the newer of the two answers.
            "sound.music.bass" to 10,
        )
        assertEquals(
            mapOf(
                "sound.music.eqOn" to true,
                "sound.video.eqOn" to true,
                "sound.video.bass" to 40,
                "sound.music.space" to "hall",
                "sound.video.space" to "hall",
            ),
            Prefs.soundSplit(stored),
        )
    }

    @Test
    fun `an install that never had the shared sound has nothing to split`() {
        assertTrue(Prefs.soundSplit(mapOf("sound.music.volume" to 120, "play.pitch" to "follow")).isEmpty())
    }

    @Test
    fun `every shared key is split, and into both players`() {
        for (old in Prefs.LEGACY_SOUND) {
            val newer = SettingsTransfer.SUPERSEDED.getValue(old)
            assertEquals(old, listOf(old.replace("sound.", "sound.music."), old.replace("sound.", "sound.video.")), newer)
        }
    }
}

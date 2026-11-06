package dev.niccc2007.filet.handlers

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFocusTest {

    private val loss = AudioManager.AUDIOFOCUS_LOSS
    private val passing = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    private val duck = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
    private val gain = AudioManager.AUDIOFOCUS_GAIN

    @Test
    fun `a call pauses a playing video and its end plays it again`() {
        val s = FocusSteps()
        assertEquals(FocusStep.PAUSE, s.on(passing, ignoring = false, playing = true))
        assertTrue(s.pausedForFocus)
        assertEquals(FocusStep.RESUME, s.on(gain, ignoring = false, playing = false))
        assertFalse(s.pausedForFocus)
    }

    @Test
    fun `another player starting pauses it for good`() {
        val s = FocusSteps()
        assertEquals(FocusStep.PAUSE, s.on(loss, ignoring = false, playing = true))
        assertEquals(FocusStep.NONE, s.on(gain, ignoring = false, playing = false))
    }

    @Test
    fun `a lasting loss after a passing one cancels the resume`() {
        val s = FocusSteps()
        s.on(passing, ignoring = false, playing = true)
        assertEquals(FocusStep.NONE, s.on(loss, ignoring = false, playing = false))
        assertEquals(FocusStep.NONE, s.on(gain, ignoring = false, playing = false))
    }

    @Test
    fun `a video already paused is left paused by a call and by its end`() {
        val s = FocusSteps()
        assertEquals(FocusStep.NONE, s.on(passing, ignoring = false, playing = false))
        assertEquals(FocusStep.NONE, s.on(gain, ignoring = false, playing = false))
    }

    @Test
    fun `uninterruptible ignores every change, the hand-off to music as well`() {
        val s = FocusSteps()
        for (change in listOf(loss, passing, duck, gain)) {
            assertEquals(FocusStep.NONE, s.on(change, ignoring = true, playing = true))
        }
        assertFalse(s.pausedForFocus)
    }

    @Test
    fun `a ducking loss is left to the system`() {
        assertEquals(FocusStep.NONE, FocusSteps().on(duck, ignoring = false, playing = true))
    }

    @Test
    fun `play pressed during the call, or headphones out, overrules the resume`() {
        val pressedPause = FocusSteps()
        pressedPause.on(passing, ignoring = false, playing = true)
        // Played during the call, then paused again: the last word was the pause. Headphones
        // out arrives the same way, and the end of the call must not use the speaker.
        pressedPause.forget()
        assertEquals(FocusStep.NONE, pressedPause.on(gain, ignoring = false, playing = false))

        val stillPlaying = FocusSteps()
        stillPlaying.on(passing, ignoring = false, playing = true)
        // Already playing when the focus comes back: nothing to start.
        assertEquals(FocusStep.NONE, stillPlaying.on(gain, ignoring = false, playing = true))
    }
}

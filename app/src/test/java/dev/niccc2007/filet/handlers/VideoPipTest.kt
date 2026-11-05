package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picture-in-picture window's shape, and when leaving Filet goes into it. */
class VideoPipTest {

    @Test
    fun `the window is the video's own shape, held inside what the platform takes`() {
        assertEquals(1920 to 1080, VideoPip.shape(1920, 1080))
        assertEquals(1080 to 1920, VideoPip.shape(1080, 1920))
        // Cinema scope at 2.4 to 1 and a tall screen recording are held at the edge, not refused.
        assertEquals(238 to 100, VideoPip.shape(2400, 1000))
        assertEquals(100 to 238, VideoPip.shape(1000, 3000))
        // Both edges are inside the platform's 2.39.
        assertTrue(238.0 / 100 < 2.39 && 100.0 / 238 > 1 / 2.39)
        // Not known yet is no shape, and the window takes the platform's own.
        assertNull(VideoPip.shape(0, 1080))
        assertNull(VideoPip.shape(1920, -1))
    }

    @Test
    fun `leaving goes into the window only with the setting on and a video playing`() {
        assertTrue(VideoPip.autoEnters(onHome = true, playing = true, ready = true, handingOver = false))
        assertFalse("the setting off", VideoPip.autoEnters(onHome = false, playing = true, ready = true, handingOver = false))
        assertFalse("paused", VideoPip.autoEnters(onHome = true, playing = false, ready = true, handingOver = false))
        assertFalse("still preparing", VideoPip.autoEnters(onHome = true, playing = true, ready = false, handingOver = false))
        // On its way to the music player, the sound is already leaving with it.
        assertFalse("handing over", VideoPip.autoEnters(onHome = true, playing = true, ready = true, handingOver = true))
    }
}

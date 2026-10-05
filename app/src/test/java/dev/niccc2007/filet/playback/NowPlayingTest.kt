package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTest {

    private fun track(n: String, artist: String? = null) =
        Track(id = "local:///music/$n", name = n, artist = artist)

    private fun state(vararg names: String, index: Int = 0) =
        NowPlaying(queue = names.map { track(it) }, index = index, active = names.isNotEmpty())

    // ── the notice ──

    @Test
    fun `title falls back to the filename without its extension`() {
        assertEquals("Hallelujah", PlaybackNotice.title(state("Hallelujah.mp3")))
    }

    @Test
    fun `title says so when there is nothing`() {
        assertEquals("Nothing playing", PlaybackNotice.title(NowPlaying()))
    }

    @Test
    fun `subtitle prefers the artist and adds the position in a real queue`() {
        val s = NowPlaying(
            queue = listOf(track("a.mp3", "Cohen"), track("b.mp3", "Cohen")),
            index = 0, active = true,
        )
        assertEquals("Cohen  ·  1 of 2", PlaybackNotice.subtitle(s))
    }

    @Test
    fun `subtitle is the artist alone when the queue holds one track`() {
        val s = NowPlaying(queue = listOf(track("a.mp3", "Cohen")), index = 0, active = true)
        assertEquals("Cohen", PlaybackNotice.subtitle(s))
    }

    /**
     * The fallback that earns the function a test: a folder of untagged recordings is the normal
     * case on a phone, and the filename is already on the title line.
     */
    @Test
    fun `subtitle falls back to the queue position when there is no artist`() {
        assertEquals("2 of 3", PlaybackNotice.subtitle(state("a.mp3", "b.mp3", "c.mp3", index = 1)))
    }

    @Test
    fun `subtitle is empty for a single untagged track`() {
        assertEquals("", PlaybackNotice.subtitle(state("a.mp3")))
    }

    @Test
    fun `a blank artist tag is treated as absent`() {
        val s = NowPlaying(queue = listOf(track("a.mp3", "   "), track("b.mp3")), active = true)
        assertEquals("1 of 2", PlaybackNotice.subtitle(s))
    }

    // ── the service's own lifetime ──

    /**
     * The rule the reported bug turns on. A paused track must keep the service alive, because
     * the notification is the only Play button that exists once the app is in the background -
     * and a service that stops on pause takes it away.
     */
    @Test
    fun `the service survives a pause`() {
        val paused = state("a.mp3").copy(playing = false)
        assertTrue(PlaybackNotice.shouldKeepService(paused))
    }

    @Test
    fun `the service stops once the queue is cleared`() {
        assertFalse(PlaybackNotice.shouldKeepService(NowPlaying()))
        assertFalse(PlaybackNotice.shouldKeepService(state("a.mp3").copy(active = false)))
    }

    @Test
    fun `an index past the end keeps nothing alive`() {
        assertFalse(PlaybackNotice.shouldKeepService(state("a.mp3", index = 4)))
    }

    // ── stepping ──

    @Test
    fun `stepping is only offered when there is somewhere to go`() {
        assertFalse(state("a.mp3").canStep)
        assertTrue(state("a.mp3", "b.mp3").canStep)
    }

    @Test
    fun `position is one-based`() {
        assertEquals(3, state("a.mp3", "b.mp3", "c.mp3", index = 2).position)
    }

    // ── media buttons ──

    @Test
    fun `the dedicated keys map to their own actions`() {
        assertEquals(MediaButtons.Action.PLAY, MediaButtons.actionOf(MediaButtons.KEYCODE_MEDIA_PLAY))
        assertEquals(MediaButtons.Action.PAUSE, MediaButtons.actionOf(MediaButtons.KEYCODE_MEDIA_PAUSE))
        assertEquals(MediaButtons.Action.TOGGLE, MediaButtons.actionOf(MediaButtons.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(MediaButtons.Action.NEXT, MediaButtons.actionOf(MediaButtons.KEYCODE_MEDIA_NEXT))
        assertEquals(MediaButtons.Action.PREVIOUS, MediaButtons.actionOf(MediaButtons.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(MediaButtons.Action.STOP, MediaButtons.actionOf(MediaButtons.KEYCODE_MEDIA_STOP))
    }

    /**
     * The one worth having. A one-button wired remote sends HEADSETHOOK for everything and the
     * tap count is the only thing distinguishing skip from pause - so a mapping that ignores it
     * leaves that hardware unable to change track at all.
     */
    @Test
    fun `the headset hook counts taps`() {
        val hook = MediaButtons.KEYCODE_HEADSETHOOK
        assertEquals(MediaButtons.Action.TOGGLE, MediaButtons.actionOf(hook, taps = 1))
        assertEquals(MediaButtons.Action.NEXT, MediaButtons.actionOf(hook, taps = 2))
        assertEquals(MediaButtons.Action.PREVIOUS, MediaButtons.actionOf(hook, taps = 3))
        assertEquals(MediaButtons.Action.PREVIOUS, MediaButtons.actionOf(hook, taps = 7))
    }

    @Test
    fun `a tap count on a dedicated key changes nothing`() {
        assertEquals(
            MediaButtons.Action.NEXT,
            MediaButtons.actionOf(MediaButtons.KEYCODE_MEDIA_NEXT, taps = 3),
        )
    }

    @Test
    fun `an unrelated key does nothing`() {
        assertEquals(MediaButtons.Action.NONE, MediaButtons.actionOf(4))
        assertEquals(MediaButtons.Action.NONE, MediaButtons.actionOf(0))
    }
}

package dev.niccc2007.filet.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One record's beat correction: its steps, what it says, how a held button speeds up, that it
 * is kept for that track alone, and which way it moves the light.
 */
class BeatNudgeTest {

    private class FakeStore : RememberStore {
        val map = LinkedHashMap<String, String>()
        override fun all(): Map<String, String> = map.toMap()
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun `a nudge is on the 10 ms grid and inside half a second`() {
        assertEquals(30, BeatNudge.clamp(30))
        assertEquals(30, BeatNudge.clamp(27))
        assertEquals(-20, BeatNudge.clamp(-24))
        assertEquals(BeatNudge.MAX_MS, BeatNudge.clamp(9_000))
        assertEquals(-BeatNudge.MAX_MS, BeatNudge.clamp(-9_000))
    }

    @Test
    fun `it says which way and by how much`() {
        assertEquals("+30 ms later", BeatNudge.label(30))
        assertEquals("−20 ms earlier", BeatNudge.label(-20))
        assertEquals("0 ms", BeatNudge.label(0))
    }

    @Test
    fun `a held button waits first, so a tap is 10 ms, then speeds up to a limit`() {
        assertEquals(BeatNudge.HOLD_FIRST_MS, BeatNudge.holdDelay(0))
        val waits = (1..20).map { BeatNudge.holdDelay(it) }
        assertEquals(waits.sortedDescending(), waits)
        assertTrue(waits.first() < BeatNudge.HOLD_FIRST_MS)
        assertEquals(BeatNudge.HOLD_FASTEST_MS, waits.last())
        // Half a second of nudge, held from 0, in a few seconds and not a minute.
        var held = BeatNudge.HOLD_FIRST_MS
        var steps = 1
        while (steps * BeatNudge.STEP_MS < BeatNudge.MAX_MS) held += BeatNudge.holdDelay(steps++)
        assertTrue("$held ms", held in 1_500L..4_000L)
    }

    @Test
    fun `a nudge is that track's alone, and every other track is at 0`() {
        val n = BeatNudges(FakeStore())
        n.add("/Music/live.flac", 10)
        n.add("/Music/live.flac", 10)
        n.add("/Music/live.flac", 10)
        assertEquals(30, n.nudgeOf("/Music/live.flac"))
        assertEquals(0, n.nudgeOf("/Music/next.flac"))
        assertEquals(0, n.nudgeOf(null))
    }

    @Test
    fun `it outlives a restart, and back at 0 it is not kept at all`() {
        val store = FakeStore()
        BeatNudges(store).set("/Music/a.mp3", -40)
        assertEquals("-40", store.map["/Music/a.mp3"])
        val next = BeatNudges(store)
        assertEquals(-40, next.nudgeOf("/Music/a.mp3"))
        next.add("/Music/a.mp3", 40)
        assertTrue(store.map.isEmpty())
        // A held button past the end stays at the end.
        repeat(80) { next.add("/Music/a.mp3", 10) }
        assertEquals(BeatNudge.MAX_MS, next.nudgeOf("/Music/a.mp3"))
        // Anything stored that is not a number, or is 0, reads as no nudge.
        store.map["/Music/b.mp3"] = "x"
        store.map["/Music/c.mp3"] = "0"
        assertEquals(0, BeatNudges(store).nudgeOf("/Music/b.mp3"))
        assertEquals(0, BeatNudges(store).nudgeOf("/Music/c.mp3"))
    }

    @Test
    fun `later reads an earlier part of the map, so the light fires after the sound it used to`() {
        val plain = BeatLatency.earMs(reportedMs = 10_000L, ageMs = 0L, biasMs = 36L)
        assertEquals(plain - 30, BeatLatency.earMs(10_000L, 0L, 36L, nudgeMs = 30L))
        assertEquals(plain + 20, BeatLatency.earMs(10_000L, 0L, 36L, nudgeMs = -20L))
        assertEquals(plain, BeatLatency.earMs(10_000L, 0L, 36L, nudgeMs = 0L))
    }
}

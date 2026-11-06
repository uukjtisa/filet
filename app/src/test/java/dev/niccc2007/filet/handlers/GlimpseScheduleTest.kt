package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The glimpse's two players: who plays which clip, and that every one of them is released. */
class GlimpseScheduleTest {

    @Test
    fun `with two players the next clip is never on the one still showing`() {
        for (step in 0 until 50) {
            assertNotEquals(GlimpseSchedule.deckFor(step, 2), GlimpseSchedule.deckFor(step + 1, 2))
        }
    }

    @Test
    fun `the player made ready during a clip is the one that plays the next`() {
        for (step in 0 until 50) {
            assertEquals(GlimpseSchedule.deckFor(step + 1, 2), GlimpseSchedule.cueFor(step, 2))
            assertNotEquals(GlimpseSchedule.deckFor(step, 2), GlimpseSchedule.cueFor(step, 2))
        }
    }

    @Test
    fun `with one player it plays every clip and nothing is made ready - the quiet fallback`() {
        for (step in 0 until 20) {
            assertEquals(0, GlimpseSchedule.deckFor(step, 1))
            assertNull(GlimpseSchedule.cueFor(step, 1))
        }
    }

    @Test
    fun `the clips go round in order`() {
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0), (0 until 7).map { GlimpseSchedule.clipFor(it, 3) })
        assertEquals(0, GlimpseSchedule.clipFor(5, 0))
    }

    @Test
    fun `three clips on two players, every clip is played by both in turn`() {
        val seen = (0 until 6).map { GlimpseSchedule.clipFor(it, 3) to GlimpseSchedule.deckFor(it, 2) }.toSet()
        assertEquals(6, seen.size)
    }

    // ── releasing ──

    private class Probe {
        val released = ArrayList<String>()
        val decks = GlimpseDecks<String> { released += it }
    }

    @Test
    fun `the glimpse going releases every player it holds, once`() {
        val p = Probe()
        p.decks.put(0, "a")
        p.decks.put(1, "b")
        p.decks.close()
        assertEquals(listOf("a", "b"), p.released)
        assertEquals(0, p.decks.count)
        p.decks.close()
        assertEquals("closing twice releases nothing twice", 2, p.released.size)
    }

    @Test
    fun `a player not yet ready is released all the same`() {
        // Held from the moment it is made: the peek dismissed mid-prepare must not leave it.
        val p = Probe()
        p.decks.put(1, "preparing")
        p.decks.close()
        assertEquals(listOf("preparing"), p.released)
    }

    @Test
    fun `a surface going releases that player only`() {
        val p = Probe()
        p.decks.put(0, "a")
        p.decks.put(1, "b")
        p.decks.drop(1)
        assertEquals(listOf("b"), p.released)
        assertEquals("a", p.decks[0])
        assertNull(p.decks[1])
        p.decks.drop(1)
        assertEquals("dropping an empty slot releases nothing", 1, p.released.size)
    }

    @Test
    fun `a second player in the same slot releases the first`() {
        val p = Probe()
        p.decks.put(0, "old")
        p.decks.put(0, "new")
        assertEquals(listOf("old"), p.released)
        assertEquals("new", p.decks[0])
    }

    @Test
    fun `a player arriving after the glimpse has gone is released at once and not held`() {
        val p = Probe()
        p.decks.close()
        assertFalse(p.decks.put(0, "late"))
        assertEquals(listOf("late"), p.released)
        assertNull(p.decks[0])
        assertTrue(p.decks.closed)
    }

    @Test
    fun `back from the background, dropped players are replaced without leaking`() {
        val p = Probe()
        p.decks.put(0, "a1")
        p.decks.put(1, "b1")
        p.decks.drop(0)
        p.decks.drop(1)
        p.decks.put(0, "a2")
        p.decks.put(1, "b2")
        p.decks.close()
        assertEquals(listOf("a1", "b1", "a2", "b2"), p.released)
    }

    @Test
    fun `a release that throws does not stop the rest`() {
        val released = ArrayList<String>()
        val decks = GlimpseDecks<String> { if (it == "bad") error("boom") else released += it }
        decks.put(0, "bad")
        decks.put(1, "good")
        decks.close()
        assertEquals(listOf("good"), released)
        assertEquals(0, decks.count)
    }
}

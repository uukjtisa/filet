package dev.niccc2007.filet.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Counters aggregate, windows reset, names are bounded, and loops register and leave. */
class CountersTest {

    @After
    fun clear() {
        Counters.clear()
        Loops.active.clear()
    }

    @Test
    fun `a counter aggregates count, total, and worst`() {
        val c = Counters.of("beat.step")
        c.add(1_000)
        c.add(3_000)
        c.add(2_000)
        c.hit()
        val s = c.total()
        assertEquals(4, s.count)
        assertEquals(6_000, s.nanos)
        assertEquals(3_000, s.worst)
    }

    @Test
    fun `the window resets on flush and the run total does not`() {
        val c = Counters.of("glow")
        repeat(10) { c.add(500) }
        val first = c.takeWindow()
        assertEquals(10, first.count)
        assertEquals(5_000, first.nanos)
        c.add(9_000)
        val second = c.takeWindow()
        assertEquals(1, second.count)
        assertEquals(9_000, second.worst)
        assertEquals(11, c.total().count)
        assertEquals(9_000, c.total().worst)
    }

    @Test
    fun `the same name is the same counter, from any thread`() {
        val threads = (1..8).map {
            Thread { repeat(10_000) { Counters.of("shared").add(1) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val s = Counters.of("shared").total()
        assertEquals(80_000, s.count)
        assertEquals(80_000, s.nanos)
    }

    @Test
    fun `flush lists only counters that moved, busiest first, then nothing`() {
        Counters.of("quiet").hit()
        Counters.of("busy").add(5_000_000)
        Counters.of("idle")
        val lines = Counters.flush(30_000)
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("busy: 1 in 30.0s"))
        assertTrue(lines[1].startsWith("quiet: 1"))
        assertTrue(Counters.flush(30_000).isEmpty())
    }

    @Test
    fun `the answer reads as count, rate, total, average and worst`() {
        val line = Stat("beat.step", 54_000, 21_600_000, 400_000).line(900_000)
        assertEquals("beat.step: 54000 in 15m00s (60.0/s), busy 21.60ms, avg 400ns, worst 400.0µs", line)
    }

    @Test
    fun `names past the cap share one overflow counter`() {
        repeat(Counters.MAX + 50) { Counters.of("track $it").hit() }
        val names = Counters.totals().map { it.name }
        assertTrue(names.size <= Counters.MAX + 1)
        assertTrue(Counters.OVERFLOW in names)
    }

    @Test
    fun `a loop is listed while open and gone once closed`() {
        val l = Loops.open("glow.clock", 33)
        repeat(5) { l.tick() }
        assertEquals(listOf("glow.clock"), Loops.running().map { it.name })
        assertTrue(l.describe().startsWith("glow.clock every 33ms, 5 ticks"))
        l.close()
        assertTrue(Loops.running().isEmpty())
        assertTrue(Loops.open("disc", Loops.FRAME).describe().contains("every frame"))
    }

    @Test
    fun `durations read in the unit that fits`() {
        assertEquals("840ns", Counters.duration(840))
        assertEquals("12.4µs", Counters.duration(12_400))
        assertEquals("3.21ms", Counters.duration(3_210_000))
        assertEquals("4.5s", Counters.duration(4_500_000_000))
        assertEquals("2m05s", Counters.duration(125_000_000_000))
    }
}

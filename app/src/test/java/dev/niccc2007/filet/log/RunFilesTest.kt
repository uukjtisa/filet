package dev.niccc2007.filet.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retention across runs, the cap inside one run, run-name safety, and `/proc` parsing. */
class RunFilesTest {

    private fun run(day: Int, bytes: Long) = RunFiles.Run("filet_2026-10-%02d_120000.000_mon.log".format(day), bytes)

    @Test
    fun `retention keeps the newest and deletes oldest first`() {
        val runs = (1..25).map { run(it, 1_000) }.shuffled()
        val gone = RunFiles.toDelete(runs, keep = 20, maxBytes = Long.MAX_VALUE, current = null)
        assertEquals((1..5).map { run(it, 0).name }, gone)
    }

    @Test
    fun `retention also stops at a byte total`() {
        val runs = (1..10).map { run(it, 10_000) }
        val gone = RunFiles.toDelete(runs, keep = 20, maxBytes = 35_000, current = null)
        // The three newest fit; everything older goes, oldest first.
        assertEquals((1..7).map { run(it, 0).name }, gone)
    }

    @Test
    fun `the current run is never deleted, whatever its size`() {
        val current = run(1, 999_999_999)
        val gone = RunFiles.toDelete(listOf(current, run(2, 10)), keep = 1, maxBytes = 100, current = current.name)
        assertFalse(current.name in gone)
    }

    @Test
    fun `only run names are runs, so a read cannot be pointed elsewhere`() {
        assertTrue(RunFiles.isRun("filet_2026-10-18_101010.000_sun.log"))
        assertFalse(RunFiles.isRun("../filet_x.log"))
        assertFalse(RunFiles.isRun("filet_../../data.log"))
        assertFalse(RunFiles.isRun("filet_a/b.log"))
        assertFalse(RunFiles.isRun("settings.xml"))
        assertFalse(RunFiles.isRun("diag.log"))
    }

    @Test
    fun `under the soft cap everything is written`() {
        val b = Budget(soft = 100, hard = 200)
        assertTrue(b.admit(important = false, bytes = 60).write)
        assertEquals(60, b.written)
    }

    @Test
    fun `past the soft cap only important lines go in, with one notice`() {
        val b = Budget(soft = 100, hard = 1_000)
        b.admit(false, 100)
        val first = b.admit(false, 10)
        assertFalse(first.write)
        assertNotNull(first.notice)
        val second = b.admit(false, 10)
        assertFalse(second.write)
        assertNull(second.notice)
        assertTrue(b.admit(true, 10).write)
        assertEquals(2, b.dropped)
    }

    @Test
    fun `past the hard cap nothing goes in, with one notice`() {
        val b = Budget(soft = 50, hard = 100)
        b.admit(true, 95)
        val over = b.admit(true, 10)
        assertFalse(over.write)
        assertNotNull(over.notice)
        assertNull(b.admit(true, 1).notice)
        assertFalse(b.admit(true, 1).write)
    }

    @Test
    fun `a proc stat line parses, even with spaces and parentheses in the name`() {
        val line = "4242 (filet beat (2)) S 1 2 3 4 5 6 7 8 9 10 150 37 0 0 20 0 1 0 100 0"
        val t = ProcStat.parse(line)!!
        assertEquals(4242, t.tid)
        assertEquals("filet beat (2)", t.name)
        assertEquals(187, t.ticks)
        assertNull(ProcStat.parse("garbage"))
        assertNull(ProcStat.parse("12 (short) S 1 2"))
    }
}

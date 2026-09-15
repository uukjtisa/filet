package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The index that looked like it had been thrown away.
 *
 * Bug identified: the index was **already** doing the right thing - a generation stamp, a sweep
 * of anything not seen that pass, new files appended. Nothing was ever rebuilt. But the only
 * number on screen was the count for the current RUN, so starting one under a 30,000-file index
 * printed "0 files scanned so far" and read as a wipe.
 *
 * These tests are about the line being unmisreadable, and about the two phases - building an
 * index from nothing, and updating one that exists - never saying the same thing.
 */
class IndexUpdateTest {

    // ── the report ──

    @Test
    fun `an update in progress keeps the existing total on screen`() {
        val run = IndexRun(active = true, phase = IndexPhase.UPDATE, known = 31_206, seen = 12_430)
        assertEquals("Updating — checked 12,430 of 31,206", run.line)
    }

    @Test
    fun `an update that has just started never reads as zero files`() {
        // The frame that caused the report. The old line read "Indexing - starting", with the
        // 30k nowhere on screen, and a moment later "Indexing - 14 files so far".
        val run = IndexRun(active = true, phase = IndexPhase.UPDATE, known = 31_206, seen = 0)
        assertTrue("the existing index must be visible from the first frame", run.line.contains("31,206"))
        assertTrue(run.line.startsWith("Updating"))
    }

    @Test
    fun `a first ever pass says it is building, because that one really is from nothing`() {
        val run = IndexRun(active = true, phase = IndexPhase.BUILD, known = 0, seen = 12_430)
        assertEquals("Building the index — 12,430 files so far", run.line)
    }

    // ── the two states, on the button as well as in the text ──

    @Test
    fun `the button says Build when there is no index and Update when there is`() {
        assertEquals("Build index", IndexRun(known = 0).action)
        assertEquals("Update index", IndexRun(known = 31_206).action)
    }

    @Test
    fun `and says Stop while a run is going, whichever phase it is`() {
        assertEquals("Stop indexing", IndexRun(active = true, known = 0, phase = IndexPhase.BUILD).action)
        assertEquals("Stop indexing", IndexRun(active = true, known = 31_206).action)
    }

    // ── what a finished pass reports ──

    @Test
    fun `a finished update names what changed, in both directions`() {
        val run = IndexRun(
            active = false, endedBecause = CrawlEnd.DONE, phase = IndexPhase.UPDATE,
            known = 31_206, added = 18, removed = 4,
        )
        assertEquals("Updated — 18 new, 4 gone", run.line)
    }

    @Test
    fun `added and removed are separate numbers, not a net change`() {
        // 200 in and 200 out is not "nothing happened", and a net figure would say it was.
        val run = IndexRun(
            active = false, endedBecause = CrawlEnd.DONE, phase = IndexPhase.UPDATE,
            known = 31_206, added = 200, removed = 200,
        )
        assertTrue(run.line.contains("200 new"))
        assertTrue(run.line.contains("200 gone"))
    }

    @Test
    fun `an update that found nothing says so instead of pretending to have worked`() {
        val run = IndexRun(
            active = false, endedBecause = CrawlEnd.DONE, phase = IndexPhase.UPDATE,
            known = 31_206, added = 0, removed = 0,
        )
        assertEquals("Up to date — nothing had changed", run.line)
    }

    @Test
    fun `only one side changing reads cleanly`() {
        val onlyNew = IndexRun(active = false, endedBecause = CrawlEnd.DONE, known = 10, added = 3)
        assertEquals("Updated — 3 new", onlyNew.line)

        val onlyGone = IndexRun(active = false, endedBecause = CrawlEnd.DONE, known = 10, removed = 3)
        assertEquals("Updated — 3 gone", onlyGone.line)
    }

    @Test
    fun `a finished first build reports the size it built`() {
        val run = IndexRun(
            active = false, endedBecause = CrawlEnd.DONE, phase = IndexPhase.BUILD,
            known = 0, added = 31_206,
        )
        assertEquals("Finished — 31,206 files indexed", run.line)
    }

    // ── the ways a run can end badly still win ──

    @Test
    fun `stopping and failing are still reported over any change count`() {
        val stopped = IndexRun(endedBecause = CrawlEnd.STOPPED, known = 100, added = 5)
        assertEquals("Stopped by you", stopped.line)

        val failed = IndexRun(endedBecause = CrawlEnd.FAILED, note = "disk full", known = 100)
        assertEquals("Stopped — error — disk full", failed.line)
    }

    @Test
    fun `nothing has ever run, so nothing is claimed`() {
        assertEquals("", IndexRun().line)
    }

    // ── the control ──

    @Test
    fun `the two phases genuinely produce different sentences`() {
        // Without this, a line that said the same thing in both states would pass every
        // assertion above that does not compare them - and saying the same thing in both
        // states is precisely the bug.
        val building = IndexRun(active = true, phase = IndexPhase.BUILD, known = 0, seen = 500).line
        val updating = IndexRun(active = true, phase = IndexPhase.UPDATE, known = 31_206, seen = 500).line
        assertTrue("building and updating must not read the same", building != updating)
        assertTrue(building.contains("Building"))
        assertTrue(updating.contains("Updating"))
    }
}

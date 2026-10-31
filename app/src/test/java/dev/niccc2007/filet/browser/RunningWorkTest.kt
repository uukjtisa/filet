package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Stop Filet says it is about to end.
 *
 * The defect behind it: nothing short of reinstalling stopped Filet. Swiping it away left the
 * music service, a hosted share, a crawl and any copy in flight all running.
 */
class RunningWorkTest {

    // ── which jobs are named as cut off partway ──

    @Test
    fun `every writing operation is named as leaving work half-done`() {
        // The titles the operations layer actually writes - Ops, ExtractOps and the APK tools.
        // If one of these is renamed, this is the test that notices the sheet stopped warning.
        for (title in listOf(
            "Copying 3 item(s)",
            "Moving 2 item(s)",
            "Deleting 14 item(s)",
            "Compressing 5 item(s)",
            "Extracting",
            "Saving into Music",
            "Signing",
            "Rebuilding",
            "Decompiling",
            "Running script",
        )) {
            assertTrue(title, RunningWork.leavesPartial(title))
        }
    }

    @Test
    fun `the read-only jobs are not`() {
        for (title in listOf("Indexing storage", "Measuring Download", "Fetching notes.pdf")) {
            assertFalse(title, RunningWork.leavesPartial(title))
        }
    }

    @Test
    fun `a job nobody has classified is assumed to be writing`() {
        // The direction of the default is the decision. A job published over the bridge by
        // another app carries no type, and naming an unknown job as safe to cut off is the one
        // mistake this sheet must not make.
        assertTrue(RunningWork.leavesPartial("Downloading video.mp4"))
        assertTrue(RunningWork.leavesPartial("Something new"))
    }

    // ── the list ──

    @Test
    fun `interrupted work comes first, whatever order it arrived in`() {
        val items = RunningWork.of(
            playing = "Track",
            crawling = false,
            jobs = listOf("Measuring Download" to false, "Copying 3 item(s)" to true),
            hosting = 0,
            nearby = false,
        )
        assertEquals("Copying 3 item(s)", items.first().label)
        assertTrue(items.first().interrupts)
        assertFalse(items.drop(1).any { it.interrupts })
    }

    @Test
    fun `everything running is named once`() {
        val items = RunningWork.of(
            playing = "Track",
            crawling = true,
            jobs = listOf(RunningWork.INDEX_JOB to false),
            hosting = 2,
            nearby = true,
        )
        assertEquals(
            listOf("Playing Track", "Indexing storage", "Sharing 2 folders", "Nearby sharing"),
            items.map { it.label },
        )
    }

    @Test
    fun `the index pass is not listed twice`() {
        // It has its own line and a ledger entry; showing both would count one thing twice.
        val items = RunningWork.of(null, crawling = true, jobs = listOf(RunningWork.INDEX_JOB to false), hosting = 0, nearby = false)
        assertEquals(1, items.count { it.label == RunningWork.INDEX_JOB })
    }

    @Test
    fun `nothing running is an empty list, not a guess`() {
        assertTrue(RunningWork.of(null, false, emptyList(), 0, false).isEmpty())
    }

    @Test
    fun `one share and several are worded differently`() {
        assertEquals("Sharing 1 folder", RunningWork.of(null, false, emptyList(), 1, false).single().label)
    }

    // ── the button ──

    @Test
    fun `the confirm says stop anyway only when something would be cut off`() {
        val cut = RunningWork.of(null, false, listOf("Copying 1 item(s)" to true), 0, false)
        val calm = RunningWork.of("Track", false, emptyList(), 0, false)
        assertEquals("Stop anyway", RunningWork.confirmLabel(cut))
        assertEquals("Stop Filet", RunningWork.confirmLabel(calm))
        assertEquals("Stop Filet", RunningWork.confirmLabel(emptyList()))
    }
}

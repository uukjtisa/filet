package dev.niccc2007.filet.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why files from months ago started appearing under today.
 *
 * Bug identified, and it is a chain rather than one mistake:
 *
 *  1. The home feed publishes from inside each folder's coroutine, so the list is emitted many
 *     times per pass and each emission holds only the folders that finished first.
 *  2. The history screen recorded and PRUNED first-seen dates from that list on every
 *     emission. `prune` drops every path it is not given.
 *  3. So a path in a folder that had not finished yet was forgotten, then met again a moment
 *     later as a brand new path.
 *  4. A new path takes its own mtime only during the seeding window. Past that it takes the
 *     CLOCK - which is correct for a file genuinely copied in today, and catastrophic for one
 *     that was only forgotten.
 *
 * The visible result was a history with two days in it: everything re-stamped as today, and
 * whatever survived as yesterday. The flicker was the same cause one layer up.
 *
 * These tests hold the two rules that break the chain: a partial list is never drawn over a
 * full one, and a partial list is never pruned against.
 */
class DayBucketTest {

    private val MONTHS_AGO = 1_690_000_000_000L
    private val NOW = 1_758_000_000_000L

    private class Store {
        var blob: String? = null
        var writes = 0
        fun open() = FirstSeenStore({ blob }, { blob = it; writes++ })
    }

    // ── the mechanism, asserted so it cannot come back ──

    @Test
    fun `pruning against a partial listing forgets what it was not shown`() {
        // This is the behaviour prune is documented to have. It is not the bug - the bug was
        // calling it this way - and it is pinned here so the danger stays visible.
        val s = Store().open()
        s.record(mapOf("/a.txt" to MONTHS_AGO, "/b.txt" to MONTHS_AGO), now = MONTHS_AGO)
        assertNotNull(s.of("/b.txt"))

        s.prune(setOf("/a.txt"))
        assertNull("b was pruned because it was not in the partial list", s.of("/b.txt"))
    }

    @Test
    fun `a forgotten file re-recorded once seeding is over is stamped with the clock`() {
        // The step that turns a forgotten path into a file dated today. Everything above this
        // is recoverable; this is where the real date is destroyed.
        //
        // The condition used to be elapsed time and is now "a complete pass has happened",
        // because a timer persisted across sessions and expired before the first full scan
        // ever ran - which dated every file on the phone to whenever the app was started.
        val s = Store().open()
        s.record(mapOf("/old.txt" to MONTHS_AGO), now = MONTHS_AGO, complete = true)
        assertEquals(MONTHS_AGO, s.of("/old.txt"))

        s.prune(emptySet())
        s.record(mapOf("/old.txt" to MONTHS_AGO), now = NOW)

        assertEquals(
            "re-recording after a complete pass takes the clock, which is how a file from " +
                "months ago comes back dated today",
            NOW,
            s.of("/old.txt"),
        )
    }

    @Test
    fun `a file first seen inside the seeding window keeps its own date`() {
        // The positive control for the test above, and the behaviour a first install depends
        // on: without it every file already on the device would read as new.
        val s = Store().open()
        s.record(mapOf("/old.txt" to MONTHS_AGO), now = MONTHS_AGO + 1000)
        assertEquals(MONTHS_AGO, s.of("/old.txt"))
    }

    @Test
    fun `a file that is genuinely new after seeding still takes the clock`() {
        // The other positive control. A file COPIED in today keeps whatever mtime it was
        // written with, and only the clock records that it turned up today - so the clock rule
        // is right and must not be removed while fixing this.
        val s = Store().open()
        s.record(mapOf("/seed.txt" to MONTHS_AGO), now = MONTHS_AGO, complete = true)
        s.record(mapOf("/seed.txt" to MONTHS_AGO, "/copied.txt" to MONTHS_AGO), now = NOW)
        assertEquals(MONTHS_AGO, s.of("/seed.txt"))
        assertEquals(NOW, s.of("/copied.txt"))
    }

    @Test
    fun `a complete listing prunes only what has really gone`() {
        val s = Store().open()
        s.record(mapOf("/a.txt" to MONTHS_AGO, "/b.txt" to MONTHS_AGO), now = MONTHS_AGO)
        s.prune(setOf("/a.txt", "/b.txt"))
        assertNotNull(s.of("/a.txt"))
        assertNotNull(s.of("/b.txt"))

        s.prune(setOf("/a.txt"))
        assertNull("b really is gone this time", s.of("/b.txt"))
    }

    // ── the rule that stops the chain starting ──

    @Test
    fun `a partial result is drawn only when there is nothing on screen yet`() {
        assertTrue("a cold start should fill in as folders finish", FeedPublish.showPartial(screenIsEmpty = true))
        assertFalse(
            "a refresh must not replace a full list with a shorter one",
            FeedPublish.showPartial(screenIsEmpty = false),
        )
    }

    @Test
    fun `repeated recording of an unchanged set writes nothing`() {
        // Not about dates - about the screen. Every write here happened on the main thread
        // inside the same effect that was pruning.
        val store = Store()
        val s = store.open()
        val set = mapOf("/a.txt" to MONTHS_AGO, "/b.txt" to MONTHS_AGO)
        s.record(set, now = MONTHS_AGO)
        val after = store.writes
        repeat(4) { s.record(set, now = NOW) }
        assertEquals(after, store.writes)
    }

    // ── the flicker, reported a second time ──

    @Test
    fun `a blocked publish reaches nothing at all`() {
        // The fault: the guard wrapped the backing list while the rows actually on screen were
        // written unconditionally beneath it. So the protection existed and the screen never
        // got it - a refresh painted every intermediate answer anyway.
        assertFalse(FeedPublish.publishable(complete = false, screenIsEmpty = false))
    }

    @Test
    fun `a refresh with a list already up shows nothing until it is complete`() {
        // Every intermediate state during a refresh is a list that was never true.
        assertFalse(FeedPublish.publishable(complete = false, screenIsEmpty = false))
        assertTrue(FeedPublish.publishable(complete = true, screenIsEmpty = false))
    }

    @Test
    fun `a cold start still fills in as it goes`() {
        // The case partials exist for: an empty screen should not sit empty.
        assertTrue(FeedPublish.publishable(complete = false, screenIsEmpty = true))
    }

    @Test
    fun `a complete pass always publishes`() {
        assertTrue(FeedPublish.publishable(complete = true, screenIsEmpty = true))
        assertTrue(FeedPublish.publishable(complete = true, screenIsEmpty = false))
    }

    @Test
    fun `publishable agrees with showPartial wherever showPartial applies`() {
        // One rule, not two that can drift.
        for (empty in listOf(true, false)) {
            assertEquals(FeedPublish.showPartial(empty), FeedPublish.publishable(false, empty))
        }
    }
}

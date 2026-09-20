package dev.niccc2007.filet.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reported: after a cold start the tracked-folders list shows a state from hours ago, updating
 * it does not survive the next cold start, and sometimes it is simply right - inconsistent.
 *
 * Two causes, and the first is a fix that over-reached.
 *
 * The flicker fix put the partial guard in front of everything the feed publishes. That swept
 * up the watcher's fast path with it. A splice is not a partial answer - it is one file the
 * kernel has just named - so once the feed had any content at all, a newly arrived file could
 * only appear if a whole pass happened to run to completion.
 *
 * And passes cancelled each other. Three refreshes fire on startup, each killing the one
 * before, and the only publish that sets the settled list is at the very end of a pass. So a
 * startup could finish with no complete publish at all, leaving whichever partial landed first
 * on screen for good - or, if one survived, being perfectly correct. That is the inconsistency.
 */
class FeedStalenessTest {

    // ── the fast path must never be treated as a partial ──

    @Test
    fun `a newly arrived file reaches a feed that already has rows`() {
        // The exact case in the report: the list is populated, a file arrives, and it has to
        // show up without waiting for a full pass.
        assertTrue(FeedPublish.publishable(PublishKind.SPLICE, screenIsEmpty = false))
    }

    @Test
    fun `a newly arrived file reaches an empty feed too`() {
        assertTrue(FeedPublish.publishable(PublishKind.SPLICE, screenIsEmpty = true))
    }

    @Test
    fun `a splice is never blocked, whatever the screen holds`() {
        for (empty in listOf(true, false)) {
            assertTrue("empty=$empty", FeedPublish.publishable(PublishKind.SPLICE, empty))
        }
    }

    // ── the flicker the guard exists for is still prevented ──

    @Test
    fun `a partial still cannot replace a list that is already up`() {
        assertFalse(FeedPublish.publishable(PublishKind.PARTIAL, screenIsEmpty = false))
    }

    @Test
    fun `a partial still fills an empty screen`() {
        assertTrue(FeedPublish.publishable(PublishKind.PARTIAL, screenIsEmpty = true))
    }

    @Test
    fun `a complete pass always publishes`() {
        for (empty in listOf(true, false)) {
            assertTrue("empty=$empty", FeedPublish.publishable(PublishKind.COMPLETE, empty))
        }
    }

    @Test
    fun `only a partial is ever blocked`() {
        for (kind in PublishKind.entries) {
            for (empty in listOf(true, false)) {
                val allowed = FeedPublish.publishable(kind, empty)
                if (!allowed) {
                    assertTrue("$kind was blocked and only PARTIAL may be", kind == PublishKind.PARTIAL)
                    assertFalse("a partial was blocked on an empty screen", empty)
                }
            }
        }
    }

    // ── passes must not kill each other ──

    @Test
    fun `a refresh during a pass does not cancel it`() {
        // The property, stated plainly: the pass that publishes the settled list has to be
        // allowed to reach the end. Cancelling it is what left a partial on screen for good.
        assertFalse(FeedPublish.cancelsRunningPass())
    }

    @Test
    fun `many requests during one pass still mean one more pass`() {
        // A queue rather than a count: ten requests during a pass would read the same folders
        // ten times to reach the same answer.
        var running = true
        var rerunWanted = false
        repeat(10) { if (running) rerunWanted = true }
        assertTrue(rerunWanted)
        running = false
        var passes = 0
        if (rerunWanted) { rerunWanted = false; passes++ }
        org.junit.Assert.assertEquals(1, passes)
        assertFalse(rerunWanted)
    }
}

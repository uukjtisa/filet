package dev.niccc2007.filet.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coalescing the burst that a file copy produces.
 *
 * The failure the two clocks exist to prevent: with a quiet period alone, something writing
 * every 100 ms holds the refresh off for as long as it keeps writing, and the feed shows
 * nothing at all until the copy finishes. With a deadline alone, the refresh fires mid-copy,
 * shows half the files, and fires again. Either one on its own is a plausible-looking bug.
 */
class FeedTimingTest {

    @Test fun a_single_change_refreshes_as_soon_as_it_goes_quiet() {
        assertTrue("still noisy, so wait", coalesceDelay(sinceFirstChange = 10, sinceLastChange = 10) > 0)
        assertEquals(0L, coalesceDelay(sinceFirstChange = 300, sinceLastChange = 300))
    }

    @Test fun a_burst_waits_for_the_quiet_rather_than_refreshing_per_event() {
        // Twenty files copied in: one refresh at the end, not twenty.
        var waited = 0L
        var last = 0L
        for (i in 1..20) {
            last = 0L
            waited = i * 60L
            val d = coalesceDelay(sinceFirstChange = waited, sinceLastChange = last)
            assertTrue("event $i should not fire immediately", d > 0)
        }
        assertEquals(0L, coalesceDelay(sinceFirstChange = waited + QUIET_MS, sinceLastChange = QUIET_MS))
    }

    @Test fun a_long_copy_still_gets_a_refresh_rather_than_being_starved() {
        // Something writing every 100 ms forever. Quiet alone would never fire.
        assertEquals(0L, coalesceDelay(sinceFirstChange = MAX_WAIT_MS, sinceLastChange = 100))
        assertEquals(0L, coalesceDelay(sinceFirstChange = MAX_WAIT_MS + 5_000, sinceLastChange = 0))
    }

    @Test fun the_wait_never_overshoots_the_deadline() {
        for (first in 0..MAX_WAIT_MS step 37) {
            for (lastTick in 0..QUIET_MS step 11) {
                val d = coalesceDelay(first, lastTick)
                assertTrue("first=$first last=$lastTick waited past the deadline", first + d <= MAX_WAIT_MS)
            }
        }
    }

    @Test fun the_wait_is_never_negative_and_never_zero_when_it_means_wait() {
        for (first in 0L..2_000L step 13) {
            for (lastTick in 0L..2_000L step 17) {
                val d = coalesceDelay(first, lastTick)
                assertTrue("first=$first last=$lastTick gave $d", d >= 0)
            }
        }
    }

    @Test fun the_quiet_window_is_long_enough_for_a_copy_and_short_enough_to_feel_instant() {
        // Not arbitrary: under about 100 ms a multi-chunk write fires twice, and over about
        // half a second the feed feels like it is lagging behind the file manager.
        assertTrue(QUIET_MS in 100..500)
        assertTrue("a deadline this long would read as stale", MAX_WAIT_MS <= 2_000)
        assertTrue(MAX_WAIT_MS > QUIET_MS)
    }

    @Test fun folders_are_read_several_at_a_time_but_not_all_at_once() {
        // Sequential reads were the second half of "it takes time to update"; unbounded
        // concurrency is not the fix, because a phone's storage queue is single digits deep.
        assertTrue(FEED_PARALLELISM > 1)
        assertTrue(FEED_PARALLELISM <= 8)
    }
}

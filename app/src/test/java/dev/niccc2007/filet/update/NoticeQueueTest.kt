package dev.niccc2007.filet.notice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One queue for everything that appears unasked, and the first-run rule.
 *
 * Lives beside the update tests because the update offer is the other customer of the queue.
 */
class NoticeQueueTest {
    private val W = NoticeKind.WELCOME
    private val U = NoticeKind.UPDATE

    @Test fun nothing_pending_shows_nothing() = assertNull(NoticeQueue.head(emptySet(), null))

    @Test fun welcome_goes_before_an_update_on_the_same_launch() = assertEquals(W, NoticeQueue.head(setOf(U, W), null))

    @Test fun the_one_on_screen_is_not_swapped_out_for_a_higher_rank() = assertEquals(U, NoticeQueue.head(setOf(U, W), U))

    @Test fun finishing_one_moves_to_the_next() = assertEquals(U, NoticeQueue.head(setOf(U), W))

    // ── first run ──

    private val T = 1_700_000_000_000L

    @Test fun a_never_updated_install_is_a_first_run() = assertTrue(FirstRun.shouldWelcome(false, T, T))

    @Test fun an_upgrade_is_not_a_first_run() {
        assertFalse(FirstRun.shouldWelcome(false, T, T + 1))
        assertTrue(FirstRun.isUpgrade(T, T + 1))
    }

    @Test fun once_shown_never_again() = assertFalse(FirstRun.shouldWelcome(true, T, T))

    @Test fun no_install_record_is_not_a_first_run() = assertFalse(FirstRun.shouldWelcome(false, 0, 0))

    @Test fun the_debug_override_forces_it() = assertTrue(FirstRun.shouldWelcome(true, T, T + 1, pretendFirstRun = true))
}

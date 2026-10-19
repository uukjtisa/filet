package dev.niccc2007.filet.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Posting succeeds against a channel nobody can see; this is what notices that. */
class NotifyGateTest {

    @Test fun missing_permission_on_13_and_later_does_not_post() =
        assertEquals(NotifyGate.Outcome.NO_PERMISSION, NotifyGate.classify(33, false, true, 3))

    @Test fun the_permission_does_not_exist_before_13() =
        assertEquals(NotifyGate.Outcome.SHOWN, NotifyGate.classify(31, false, true, 3))

    @Test fun app_level_off_and_channel_off_are_reported_separately() {
        assertEquals(NotifyGate.Outcome.APP_OFF, NotifyGate.classify(31, true, false, 3))
        assertEquals(NotifyGate.Outcome.CHANNEL_OFF, NotifyGate.classify(31, true, true, 0))
        assertFalse(NotifyGate.Outcome.CHANNEL_OFF.posts)
    }

    @Test fun a_turned_down_channel_posts_but_is_reported_quiet() {
        assertEquals(NotifyGate.Outcome.QUIET, NotifyGate.classify(31, true, true, 2))
        assertEquals(NotifyGate.Outcome.QUIET, NotifyGate.classify(31, true, true, 1))
        assertEquals(NotifyGate.Outcome.QUIET, NotifyGate.classify(31, true, true, null))
        assertTrue(NotifyGate.Outcome.QUIET.posts)
    }

    @Test fun default_and_high_are_shown() {
        assertEquals(NotifyGate.Outcome.SHOWN, NotifyGate.classify(34, true, true, 3))
        assertEquals(NotifyGate.Outcome.SHOWN, NotifyGate.classify(34, true, true, 4))
    }
}

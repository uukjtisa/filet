package dev.niccc2007.filet.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nag policy.
 *
 * Two failures are worth more than the rest, and both are silent:
 *
 * 1. **A silence that leaks forward.** "Remind me in a week" about 0.2.0 must not hide 0.3.0.
 *    Get this wrong and one tap turns the updater off for good, and nobody ever reports it
 *    because nothing visibly breaks.
 * 2. **A silence that does not hold.** If the version is not stored beside the deadline, or the
 *    comparison is the wrong way round, the prompt comes back immediately and the buttons read
 *    as broken.
 *
 * So most of what is below is about the interaction between a stored answer and a *different*
 * release, not about any one button.
 */
class UpdatePromptTest {

    private val v0_1_0 = Version.parse("0.1.0")
    private val v0_2_0 = Version.parse("0.2.0")
    private val v0_3_0 = Version.parse("0.3.0")
    private val T0 = 1_700_000_000_000L

    /** Stands in for one run of the app. A restart is simply a different number. */
    private val LAUNCH = 7_001L
    private val NEXT_LAUNCH = 7_002L

    private fun after(
        choice: RemindChoice,
        version: String = "0.2.0",
        at: Long = T0,
        launch: Long = LAUNCH,
    ) = applyChoice(choice, version, at, ReminderState(), launch)

    // ── the basics ──

    @Test fun a_newer_release_prompts() {
        assertTrue(shouldPrompt(v0_2_0, v0_1_0, ReminderState(), T0))
    }

    @Test fun the_version_you_are_on_does_not_prompt() {
        assertFalse(shouldPrompt(v0_1_0, v0_1_0, ReminderState(), T0))
    }

    @Test fun an_older_release_does_not_prompt() {
        // A tag that sorts below the running build. Happens after a hotfix tag is deleted.
        assertFalse(shouldPrompt(v0_1_0, v0_2_0, ReminderState(), T0))
    }

    // ── later ──

    @Test fun later_stays_quiet_for_the_rest_of_this_run_and_speaks_up_on_the_next_one() {
        val state = after(RemindChoice.LATER)
        assertFalse(
            "same run, even hours later",
            shouldPrompt(v0_2_0, v0_1_0, state, T0 + 6 * 60 * 60 * 1000L, LAUNCH),
        )
        assertTrue(
            "the app was opened again",
            shouldPrompt(v0_2_0, v0_1_0, state, T0 + 1, NEXT_LAUNCH),
        )
    }

    @Test fun later_is_a_launch_not_a_duration() {
        // The distinction that makes "remind me after opening" mean anything. A clock-based
        // Later either re-fires a millisecond after it is tapped, or needs a made-up duration.
        val state = after(RemindChoice.LATER)
        assertTrue("nothing is pending on the clock", state.silencedUntil == 0L)
        assertTrue("the run it was said in is what was stored", state.silencedLaunch == LAUNCH)
    }

    @Test fun later_about_one_release_says_nothing_about_the_next_one_in_the_same_run() {
        val state = after(RemindChoice.LATER, version = "0.2.0")
        assertTrue(shouldPrompt(v0_3_0, v0_1_0, state, T0, LAUNCH))
    }

    // ── the timed postponements ──

    @Test fun a_day_means_a_day() {
        val state = after(RemindChoice.IN_A_DAY)
        assertFalse(shouldPrompt(v0_2_0, v0_1_0, state, T0 + DAY_MS - 1, NEXT_LAUNCH))
        assertTrue(shouldPrompt(v0_2_0, v0_1_0, state, T0 + DAY_MS, NEXT_LAUNCH))
    }

    @Test fun a_timed_postponement_survives_a_restart() {
        // The difference between "in a week" and "later": one outlives the process, one does
        // not. Both are stored in the same object, so this is worth pinning.
        val state = after(RemindChoice.IN_A_WEEK)
        assertFalse(shouldPrompt(v0_2_0, v0_1_0, state, T0 + DAY_MS, NEXT_LAUNCH))
    }

    @Test fun three_days_and_a_week_mean_what_they_say() {
        val three = after(RemindChoice.IN_THREE_DAYS)
        assertFalse(shouldPrompt(v0_2_0, v0_1_0, three, T0 + 2 * DAY_MS, NEXT_LAUNCH))
        assertTrue(shouldPrompt(v0_2_0, v0_1_0, three, T0 + 3 * DAY_MS, NEXT_LAUNCH))

        val week = after(RemindChoice.IN_A_WEEK)
        assertFalse(shouldPrompt(v0_2_0, v0_1_0, week, T0 + 6 * DAY_MS, NEXT_LAUNCH))
        assertTrue(shouldPrompt(v0_2_0, v0_1_0, week, T0 + 7 * DAY_MS, NEXT_LAUNCH))
    }

    @Test fun every_timed_choice_is_longer_than_the_one_before_it() {
        // A menu where "in 3 days" waits less than "in a day" is a menu nobody can use.
        val timed = listOf(
            RemindChoice.LATER,
            RemindChoice.IN_A_DAY,
            RemindChoice.IN_THREE_DAYS,
            RemindChoice.IN_A_WEEK,
        )
        for (i in 1 until timed.size) {
            assertTrue(
                "${timed[i]} must wait longer than ${timed[i - 1]}",
                timed[i].delayMs > timed[i - 1].delayMs,
            )
        }
    }

    // ── THE ONE THAT MATTERS: a silence is about a version ──

    @Test fun postponing_one_release_says_nothing_about_the_next_one() {
        val state = after(RemindChoice.IN_A_WEEK, version = "0.2.0")
        assertFalse("0.2.0 is postponed", shouldPrompt(v0_2_0, v0_1_0, state, T0 + DAY_MS, NEXT_LAUNCH))
        assertTrue("0.3.0 is a different release", shouldPrompt(v0_3_0, v0_1_0, state, T0 + DAY_MS, NEXT_LAUNCH))
    }

    @Test fun skipping_a_version_skips_only_that_version() {
        val state = after(RemindChoice.SKIP_VERSION, version = "0.2.0")
        assertFalse(shouldPrompt(v0_2_0, v0_1_0, state, T0))
        assertFalse("and it stays skipped forever", shouldPrompt(v0_2_0, v0_1_0, state, T0 + 400 * DAY_MS))
        assertTrue(shouldPrompt(v0_3_0, v0_1_0, state, T0))
    }

    @Test fun skipping_clears_a_pending_timer_rather_than_leaving_two_answers() {
        val postponed = applyChoice(RemindChoice.IN_A_WEEK, "0.2.0", T0, ReminderState(), LAUNCH)
        val skipped = applyChoice(RemindChoice.SKIP_VERSION, "0.2.0", T0, postponed, LAUNCH)
        assertTrue(skipped.silencedVersion.isEmpty())
        assertTrue(skipped.silencedUntil == 0L)
        assertTrue(skipped.silencedLaunch == 0L)
    }

    // ── never ──

    @Test fun never_means_never_for_anything() {
        val state = after(RemindChoice.NEVER)
        assertFalse(shouldPrompt(v0_2_0, v0_1_0, state, T0))
        assertFalse(shouldPrompt(v0_3_0, v0_1_0, state, T0 + 400 * DAY_MS))
    }

    @Test fun never_also_stops_the_network_check_not_just_the_notification() {
        // Asking GitHub every 12 hours for something that will never be shown is somebody's
        // battery and somebody's data.
        assertFalse(shouldCheck(after(RemindChoice.NEVER), T0 + 100 * DAY_MS))
    }

    @Test fun turning_it_back_on_forgets_the_old_answers() {
        // Otherwise the setting reads as broken: switched on, still silent, because a skip from
        // months ago is still stored.
        val silenced = applyChoice(
            RemindChoice.NEVER, "0.2.0", T0,
            applyChoice(RemindChoice.SKIP_VERSION, "0.2.0", T0, ReminderState(), LAUNCH),
            LAUNCH,
        )
        val back = reenable(silenced)
        assertTrue(shouldPrompt(v0_2_0, v0_1_0, back, T0, LAUNCH))
    }

    // ── the check throttle ──

    @Test fun the_first_launch_checks() {
        assertTrue(shouldCheck(ReminderState(), T0))
    }

    @Test fun a_second_launch_a_minute_later_does_not() {
        assertFalse(shouldCheck(ReminderState(lastCheckedAt = T0), T0 + 60_000))
    }

    @Test fun it_checks_again_once_the_interval_is_up() {
        assertTrue(shouldCheck(ReminderState(lastCheckedAt = T0), T0 + CHECK_INTERVAL_MS))
    }

    @Test fun a_clock_that_went_backwards_does_not_lock_the_check_out() {
        // Timezone change, a manual date set, a reboot before NTP. Storing a future timestamp
        // and then comparing `now - last >= interval` would refuse to check until the real
        // clock caught up, which can be months.
        assertTrue(shouldCheck(ReminderState(lastCheckedAt = T0 + 400 * DAY_MS), T0))
    }

    // ── labels ──

    @Test fun every_choice_has_wording_and_no_two_share_it() {
        val labels = RemindChoice.entries.map { it.label() }
        assertTrue(labels.none { it.isBlank() })
        assertTrue("two buttons with the same text", labels.toSet().size == labels.size)
    }
}

package dev.niccc2007.filet.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quiet check's decision, end to end, with the gate that declines named in every case.
 *
 * Asserting the gate rather than a boolean is the point: the fault this file answers was a check
 * that declined correctly and invisibly, on a build one version ahead of every release, and a
 * boolean test cannot tell "declined because nothing is newer" from "declined because broken".
 */
class UpdateGateTest {
    private val T0 = 1_700_000_000_000L
    private val LAUNCH = 42L
    private fun rel(tag: String) = Release(tag, tag, "", "https://x/$tag", "https://x/$tag.apk", 1L, false)
    private fun after(r: Release?, installed: String, state: ReminderState = ReminderState(), now: Long = T0) =
        UpdateGate.afterNetwork(Result.success(r), Version.parse(installed), state, now, LAUNCH)
    private fun gateOf(v: UpdateVerdict) = (v as? UpdateVerdict.Decline)?.gate

    // ── the comparison ──

    @Test fun a_newer_version_is_offered() =
        assertEquals(UpdateVerdict.Ask(rel("v0.1.19")), after(rel("v0.1.19"), "0.1.16"))

    @Test fun the_same_version_declines_as_not_newer() =
        assertEquals(Gate.NOT_NEWER, gateOf(after(rel("v0.1.19"), "0.1.19")))

    @Test fun an_older_version_declines_as_not_newer() =
        // The development install: one version ahead of the newest release, permanently.
        assertEquals(Gate.NOT_NEWER, gateOf(after(rel("v0.1.19"), "0.1.20")))

    @Test fun a_desktop_line_tag_is_never_offered_to_the_app() {
        val picked = ReleaseTags.newestApp(listOf(rel("v0.1.19"), rel("filet-desktop-mount-tool-v0.2.0"), rel("v0.1.18")))
        assertEquals("v0.1.19", picked?.tag)
        assertNull(ReleaseTags.newestApp(listOf(rel("filet-desktop-mount-tool-v9.0.0"))))
        assertEquals(Gate.NO_RELEASES, gateOf(after(ReleaseTags.newestApp(listOf(rel("filet-desktop-mount-tool-v9.0.0"))), "0.1.0")))
    }

    @Test fun double_digit_patch_beats_single_digit() =
        assertEquals("v0.1.10", ReleaseTags.newestApp(listOf(rel("v0.1.9"), rel("v0.1.10"), rel("v0.1.2")))?.tag)

    // ── answers that expire ──

    @Test fun a_postponement_holds_then_expires() {
        val s = applyChoice(RemindChoice.IN_A_DAY, "0.1.19", T0, ReminderState(), LAUNCH)
        assertEquals(Gate.POSTPONED, gateOf(after(rel("v0.1.19"), "0.1.16", s, T0 + DAY_MS - 1)))
        assertTrue(after(rel("v0.1.19"), "0.1.16", s, T0 + DAY_MS) is UpdateVerdict.Ask)
    }

    @Test fun later_holds_this_run_only() {
        val s = applyChoice(RemindChoice.LATER, "0.1.19", T0, ReminderState(), LAUNCH)
        assertEquals(Gate.LATER_THIS_RUN, gateOf(after(rel("v0.1.19"), "0.1.16", s)))
        val nextRun = UpdateGate.afterNetwork(Result.success(rel("v0.1.19")), Version.parse("0.1.16"), s, T0, LAUNCH + 1)
        assertTrue(nextRun is UpdateVerdict.Ask)
    }

    @Test fun a_skip_covers_that_version_and_not_the_next() {
        val s = applyChoice(RemindChoice.SKIP_VERSION, "0.1.19", T0, ReminderState(), LAUNCH)
        assertEquals(Gate.SKIPPED, gateOf(after(rel("v0.1.19"), "0.1.16", s)))
        assertTrue(after(rel("v0.1.20"), "0.1.16", s) is UpdateVerdict.Ask)
    }

    // ── before the network ──

    @Test fun a_throttle_with_a_future_timestamp_still_fires() =
        assertNull(UpdateGate.beforeNetwork(true, ReminderState(lastCheckedAt = T0 + DAY_MS), T0, coldLaunch = false, lastAttempt = 0))

    @Test fun a_warm_resume_inside_the_interval_is_throttled_and_a_cold_launch_is_not() {
        val s = ReminderState(lastCheckedAt = T0 - 60_000)
        assertEquals(Gate.THROTTLED, UpdateGate.beforeNetwork(true, s, T0, coldLaunch = false, lastAttempt = 0)?.gate)
        assertNull(UpdateGate.beforeNetwork(true, s, T0, coldLaunch = true, lastAttempt = 0))
    }

    @Test fun a_retry_window_in_the_future_does_not_lock_the_check_out() {
        assertEquals(Gate.RETRYING, UpdateGate.beforeNetwork(true, ReminderState(), T0, true, lastAttempt = T0 - 1_000)?.gate)
        assertNull(UpdateGate.beforeNetwork(true, ReminderState(), T0, true, lastAttempt = T0 + 3_600_000))
    }

    @Test fun never_remind_and_the_fdroid_flavour_decline_with_their_own_gate() {
        assertEquals(Gate.NOTIFICATIONS_OFF, UpdateGate.beforeNetwork(true, ReminderState(notificationsOff = true), T0, true, 0)?.gate)
        assertEquals(Gate.UPDATER_OFF, UpdateGate.beforeNetwork(false, ReminderState(), T0, true, 0)?.gate)
    }

    @Test fun a_first_run_with_no_stored_state_checks_and_offers() {
        assertNull(UpdateGate.beforeNetwork(true, ReminderState(), T0, coldLaunch = true, lastAttempt = 0))
        assertNull(UpdateGate.beforeNetwork(true, ReminderState(), T0, coldLaunch = false, lastAttempt = 0))
        assertTrue(after(rel("v0.1.19"), "0.1.10") is UpdateVerdict.Ask)
    }

    @Test fun a_network_failure_is_unreachable_not_no_update() {
        val v = UpdateGate.afterNetwork(Result.failure(RuntimeException("GitHub did not answer")), Version.parse("0.1.0"), ReminderState(), T0, LAUNCH)
        assertEquals(Gate.UNREACHABLE, gateOf(v))
    }

    // ── the debug override ──

    @Test fun the_pretend_version_applies_to_debug_builds_only_and_only_when_it_parses() {
        val real = Version.parse("0.1.20")
        assertEquals(Version.parse("0.1.0"), UpdateGate.installedFor(real, "0.1.0\n", debug = true))
        assertEquals(real, UpdateGate.installedFor(real, "0.1.0", debug = false))
        assertEquals(real, UpdateGate.installedFor(real, "banana", debug = true))
        assertEquals(real, UpdateGate.installedFor(real, "", debug = true))
        assertEquals(real, UpdateGate.installedFor(real, null, debug = true))
    }

    @Test fun shouldPrompt_and_the_gate_agree() {
        val cases = listOf(
            ReminderState(), ReminderState(notificationsOff = true),
            applyChoice(RemindChoice.SKIP_VERSION, "0.1.19", T0, ReminderState(), LAUNCH),
            applyChoice(RemindChoice.IN_A_WEEK, "0.1.19", T0, ReminderState(), LAUNCH),
        )
        for (s in cases) for (inst in listOf("0.1.0", "0.1.19", "0.2.0")) {
            val a = Version.parse("0.1.19"); val i = Version.parse(inst)
            assertEquals(shouldPrompt(a, i, s, T0, LAUNCH), promptGate(a, i, s, T0, LAUNCH) == null)
        }
    }
}

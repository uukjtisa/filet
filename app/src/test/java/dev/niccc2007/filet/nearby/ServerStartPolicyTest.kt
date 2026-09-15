package dev.niccc2007.filet.nearby

import dev.niccc2007.filet.nearby.ServerStartPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sharing start that would not give up.
 *
 * The rule has to fail in both directions, so both directions are tested: a policy that always
 * refuses ends the crash loop and breaks sharing, and a policy that always starts is what
 * shipped.
 */
class ServerStartPolicyTest {

    private val T = 1_700_000_000_000L

    private fun decide(
        running: Boolean = false,
        blocked: String? = null,
        failures: Int = 0,
        lastFailureAt: Long = 0L,
        now: Long = T,
    ) = ServerStartPolicy.decide(running, blocked, failures, lastFailureAt, now)

    // ── the loop ──

    @Test
    fun `it stops trying after the third failure in a row`() {
        assertEquals(Decision.Start, decide(failures = 0, lastFailureAt = T - 1_000))
        assertEquals(Decision.Start, decide(failures = 1, lastFailureAt = T - 1_000))
        assertEquals(Decision.Start, decide(failures = 2, lastFailureAt = T - 1_000))

        val stopped = decide(failures = 3, lastFailureAt = T - 1_000)
        assertTrue("the fourth attempt is where a loop becomes a loop", stopped is Decision.Refuse)
    }

    @Test
    fun `the refusal says what to do and admits Filet has stopped retrying`() {
        val why = (decide(failures = 5, lastFailureAt = T - 1_000) as Decision.Refuse).why
        assertTrue("it must name a thing somebody can check", why.contains("Wi-Fi"))
        assertTrue("and be honest that it gave up, or the wait is for nothing", why.contains("stopped retrying"))
        assertTrue("and say that it is not permanent", why.contains("a minute"))
    }

    // ── and the reason it must not be permanent ──

    @Test
    fun `failures stop counting once the cooldown has passed`() {
        // Three failures on a train this morning must not refuse to start this afternoon on
        // working Wi-Fi. Without this the only way to clear the counter is killing the app -
        // the exact thing the attempt limit exists to stop anyone having to do.
        val later = T + ServerStartPolicy.COOLDOWN_MS + 1
        assertEquals(Decision.Start, decide(failures = 9, lastFailureAt = T, now = later))
    }

    @Test
    fun `a failure inside the cooldown still counts`() {
        val soon = T + ServerStartPolicy.COOLDOWN_MS - 1
        assertTrue(decide(failures = 3, lastFailureAt = T, now = soon) is Decision.Refuse)
    }

    @Test
    fun `a success clears the count, a failure adds one`() {
        assertEquals(0, ServerStartPolicy.countAfter(2, succeeded = true))
        assertEquals(3, ServerStartPolicy.countAfter(2, succeeded = false))
    }

    // ── preconditions outrank the counter ──

    @Test
    fun `no network is reported as no network, not as having given up`() {
        // Telling somebody with Wi-Fi off that Filet "stopped retrying" hides the one thing
        // they can actually fix.
        val why = (decide(blocked = "Join a Wi-Fi network first.", failures = 9) as Decision.Refuse).why
        assertEquals("Join a Wi-Fi network first.", why)
    }

    @Test
    fun `pressing start on a running server is a no-op and not an error`() {
        assertEquals(Decision.AlreadyRunning, decide(running = true))
        assertEquals("even with failures behind it", Decision.AlreadyRunning, decide(running = true, failures = 9))
    }

    // ── the control ──

    @Test
    fun `a clean first attempt just starts`() {
        // Without this every assertion above would pass against a policy that refuses always.
        assertEquals(Decision.Start, decide())
    }
}

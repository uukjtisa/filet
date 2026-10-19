package dev.niccc2007.filet.desktop.ui

import dev.niccc2007.filet.desktop.mount.ProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One slow probe must not condemn a working share, and a Public network must be named. */
class HealthAndNetTest {

    @Test fun one_timeout_keeps_a_working_share_answering() {
        val ok = MountModel.nextHealth(null, ProbeResult.Ok(null))
        val once = MountModel.nextHealth(ok, ProbeResult.Unreachable)
        assertEquals(true, once.answering)
        val twice = MountModel.nextHealth(once, ProbeResult.Unreachable)
        assertEquals(false, twice.answering)
    }

    @Test fun an_answer_clears_the_streak() {
        val once = MountModel.nextHealth(MountModel.Health(true), ProbeResult.Unreachable)
        val back = MountModel.nextHealth(once, ProbeResult.Ok(null))
        assertEquals(MountModel.Health(true), back)
        assertEquals(true, MountModel.nextHealth(back, ProbeResult.Unreachable).answering)
    }

    @Test fun a_first_probe_that_times_out_is_unknown_not_dead() =
        assertNull(MountModel.nextHealth(null, ProbeResult.Unreachable).answering)

    @Test fun a_refusal_is_an_answer_reported_at_once_with_its_own_reason() {
        val h = MountModel.nextHealth(MountModel.Health(true), ProbeResult.Refused("Wrong access code.", 403))
        assertEquals(false, h.answering)
        assertTrue(h.note!!.contains("new one"))
    }

    @Test fun profiles_parse_as_numbers() {
        val p = NetCheck.parse("Ethernet|1\r\nWi-Fi|0\r\n\r\ngarbage\r\nVPN|x\r\n")
        assertEquals(listOf(NetCheck.Profile("Ethernet", 1), NetCheck.Profile("Wi-Fi", 0)), p)
    }

    @Test fun only_a_public_network_warns_and_it_is_named() {
        assertNull(NetCheck.warning(listOf(NetCheck.Profile("Wi-Fi", 1), NetCheck.Profile("Corp", 2))))
        assertTrue(NetCheck.warning(listOf(NetCheck.Profile("Ethernet", 1), NetCheck.Profile("Wi-Fi", 0)))!!.startsWith("Windows has Wi-Fi set as a Public network"))
        assertNull(NetCheck.warning(emptyList()))
    }
}

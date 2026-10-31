package dev.niccc2007.filet.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one heartbeat line is allowed to claim.
 *
 * The sampler's failure mode is not a crash, it is a line that gets BELIEVED. Every figure here
 * is read later by somebody trying to explain a hot phone, with no way to tell a measurement
 * from a default - so a headroom the platform refused to forecast must not print as 0%, a minute
 * with the screen off must not print as a perfectly smooth minute, a thread that spent no time
 * must not be named as the busiest, and the run's worst frame must not be printed inside a
 * section about this minute. Each of those reads as a finding, and a wrong finding costs more
 * than a missing one.
 */
class HeartbeatTest {

    private fun sample(
        thermal: String = "none",
        headroom: Float? = null,
        batteryC: Float? = null,
        charging: Boolean = false,
        cpuPercent: Int = 0,
        busiest: String? = null,
        busiestPercent: Int = 0,
        frames: Long = 0,
        missed: Long = 0,
        newWorstWorkNanos: Long = 0,
    ) = Heartbeat.Sample(
        thermal = thermal,
        headroom = headroom,
        batteryC = batteryC,
        charging = charging,
        cpuPercent = cpuPercent,
        busiest = busiest,
        busiestPercent = busiestPercent,
        frames = frames,
        missed = missed,
        newWorstWorkNanos = newWorstWorkNanos,
    )

    @Test fun `a full sample reads as one line`() {
        val line = Heartbeat.line(
            sample(
                thermal = "moderate",
                headroom = 0.78f,
                batteryC = 41.2f,
                charging = true,
                cpuPercent = 34,
                busiest = "filet-index",
                busiestPercent = 22,
                frames = 118,
                missed = 9,
                newWorstWorkNanos = 24_000_000,
            ),
        )
        assertFalse("one line or it is not greppable", line.contains('\n'))
        assertTrue(line.contains("thermal moderate"))
        assertTrue(line.contains("headroom 78%"))
        assertTrue(line.contains("41.2C"))
        assertTrue(line.contains("charging"))
        assertTrue(line.contains("cpu 34% of a core"))
        assertTrue(line.contains("busiest filet-index 22%"))
        assertTrue(line.contains("frames 118"))
        assertTrue(line.contains("9 missed"))
    }

    @Test fun `a headroom the platform would not forecast is absent, not zero`() {
        // `getThermalHeadroom` returns NaN on plenty of devices and does not exist below API 30.
        // Printed as 0% it reads as "nowhere near throttling" - the opposite of "would not say",
        // and the reading somebody would act on.
        val line = Heartbeat.line(sample(headroom = null))
        assertFalse(line.contains("headroom"))
        assertTrue(Heartbeat.line(sample(headroom = 0f)).contains("headroom 0%"))
    }

    @Test fun `a battery that said nothing prints no temperature`() {
        assertFalse(Heartbeat.line(sample(batteryC = null)).contains("C,"))
        assertTrue(Heartbeat.line(sample(batteryC = 31.0f)).contains("31.0C"))
    }

    @Test fun `no frames drawn means no frame section at all`() {
        // The screen was off, or detailed logging was switched on while nothing was resumed.
        // "0 frames (0 missed)" describes a smooth minute that never happened.
        val line = Heartbeat.line(sample(frames = 0, missed = 0))
        assertFalse(line.contains("frames"))
        assertFalse(line.contains("missed"))
    }

    @Test fun `frames drawn with none missed still reports, because that is a measurement`() {
        val line = Heartbeat.line(sample(frames = 120, missed = 0))
        assertTrue(line.contains("frames 120"))
        assertTrue(line.contains("0 missed"))
    }

    @Test fun `a thread that spent no time is not named as the busiest`() {
        // Every run has a dozen idle threads, and whichever one sorted first would look like
        // the culprit for a minute in which nothing happened.
        assertFalse(Heartbeat.line(sample(busiest = "filet-log", busiestPercent = 0)).contains("busiest"))
        assertTrue(Heartbeat.line(sample(busiest = "filet-log", busiestPercent = 3)).contains("busiest"))
    }

    @Test fun `charging is always stated either way`() {
        // Charging is itself a heat source, so "hot on a charger" and "hot on battery" are
        // different findings - and leaving it out when false would make the common case silent.
        assertTrue(Heartbeat.line(sample(charging = false)).contains("on battery"))
        assertTrue(Heartbeat.line(sample(charging = true)).contains("charging"))
    }

    @Test fun `cpu is always stated, including zero`() {
        // Unlike the optional fields this one is always a real measurement: an idle minute is a
        // fact worth having in the record, because it is what a busy minute is compared against.
        assertTrue(Heartbeat.line(sample(cpuPercent = 0)).contains("cpu 0% of a core"))
    }

    @Test fun `a running total is never printed as this minute's figure`() {
        // The counter keeps a run-wide worst frame. Printed inside the per-minute frame section
        // it reads as "this minute had a 24ms frame", which is a claim about the wrong minute -
        // so what is reported is the thing that IS about this minute: that it set a new worst.
        val quiet = Heartbeat.line(sample(frames = 120, newWorstWorkNanos = 0))
        assertFalse(quiet.contains("worst"))
        val spike = Heartbeat.line(sample(frames = 120, newWorstWorkNanos = 24_000_000))
        assertTrue(spike.contains("a new worst cpu frame of"))
    }

    @Test fun `the interval is a minute`() {
        // Shorter and the sampler's own output buries the lines that say what the app was doing;
        // longer and a thermal climb is three points, which is not a shape.
        assertEquals(60_000L, Heartbeat.EVERY_MS)
    }
}

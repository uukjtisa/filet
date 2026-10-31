package dev.niccc2007.filet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the shared clock makes a pending row look like.
 *
 * The failure worth guarding is not motion that looks slightly off. It is a row that becomes
 * HARDER TO READ in order to describe its own uncertainty - a real figure faded towards nothing,
 * or, with the clock stopped for reduce-motion, every pending row parked at its dimmest for ever
 * with no movement to explain why.
 */
class RowPulseTest {

    private val samples = (0..100).map { it / 100f }

    @Test fun `a breath runs from nothing at the start to full at the middle`() {
        assertEquals(0f, RowPulse.breath(0f), 1e-6f)
        assertEquals(1f, RowPulse.breath(0.5f), 1e-6f)
        assertEquals(0f, RowPulse.breath(1f), 1e-6f)
        for (p in samples) assertTrue(RowPulse.breath(p) in 0f..1f)
    }

    @Test fun `a figure being confirmed is never faded below legible`() {
        // The number is real and somebody is reading it. The spinner says it may move; the
        // figure does not have to disappear to say the same thing.
        for (p in samples) assertTrue("phase $p", RowPulse.figure(p) >= RowPulse.FIGURE_FLOOR - 1e-6f)
        for (p in samples) assertTrue("phase $p", RowPulse.figure(p) <= 1f + 1e-6f)
    }

    @Test fun `the missing-figure dash is always dimmer than any real figure`() {
        // The two states must be told apart at a glance at every point of the cycle, or a dash
        // at the top of its breath reads as a figure at the bottom of its.
        val brightestDash = samples.maxOf { RowPulse.dash(it) }
        val dimmestFigure = samples.minOf { RowPulse.figure(it) }
        assertTrue("$brightestDash vs $dimmestFigure", brightestDash < dimmestFigure)
    }

    @Test fun `the stopped clock leaves every shape at its legible end`() {
        // Reduce-motion, previews and tests. Parked at the bottom of the cycle instead, a pending
        // figure would sit at its floor and a dash at its faintest, with nothing moving to say
        // why - hiding information from the person who asked for less movement.
        val still = RowPulse.STILL.phase.value
        assertEquals(1f, RowPulse.figure(still), 1e-6f)
        assertEquals(samples.maxOf { RowPulse.dash(it) }, RowPulse.dash(still), 1e-3f)
    }

    @Test fun `the spinner always lands inside one turn`() {
        for (p in samples) {
            val a = RowPulse.sweep(p)
            assertTrue("phase $p gave $a", a >= 0f && a < 360f)
        }
    }

    @Test fun `the spinner turns faster than the breath so one clock drives both`() {
        // Two full turns per breath. At one, the arc would complete its circle exactly as the
        // figure peaked, and the two would read as one slow motion rather than "busy" and "may
        // change".
        assertEquals(2, RowPulse.SPINS)
        assertEquals(180f, RowPulse.sweep(0.25f), 1e-3f)
        assertEquals(0f, RowPulse.sweep(0.5f), 1e-3f)
    }
}

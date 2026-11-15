package dev.niccc2007.filet.qr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which camera frames open the dialog. See [ScanGate]. */
class ScanGateTest {

    @Test
    fun `a code opens once, and nothing opens over it`() {
        val g = ScanGate(goneMs = 1500)
        assertTrue(g.admit("A", now = 0, showing = false))
        assertFalse(g.admit("A", now = 100, showing = true))
        assertFalse("another code must not replace the one being read", g.admit("B", now = 200, showing = true))
    }

    @Test
    fun `a code acted on with no answer showing and no close is let through on every frame`() {
        // The gate holds repeats back only through an answer showing or a close remembered. A
        // code acted on without either - how a fast share's QR used to be fetched - comes through
        // again on the very next frame, which was one download per frame. Hence every code,
        // a fast share's included, now opens an answer.
        val g = ScanGate(goneMs = 1500)
        val admitted = (0L..1_000L step 100).count { g.admit("A", it, showing = false) }
        assertTrue("expected every frame through, got $admitted", admitted == 11)
    }

    @Test
    fun `closing with the code still in view does not reopen it`() {
        val g = ScanGate(goneMs = 1500)
        g.admit("A", now = 0, showing = false)
        g.closed("A", now = 5_000)
        // Ten frames a second for five seconds, the phone held still.
        for (t in 5_100L..10_000L step 100) assertFalse("reopened at $t", g.admit("A", t, showing = false))
    }

    @Test
    fun `looking away and back asks for it again`() {
        val g = ScanGate(goneMs = 1500)
        g.admit("A", now = 0, showing = false)
        g.closed("A", now = 1_000)
        assertFalse(g.admit("A", now = 1_200, showing = false))
        // Nothing read it for two seconds: the phone moved.
        assertTrue(g.admit("A", now = 3_300, showing = false))
    }

    @Test
    fun `a different code is let through straight away`() {
        val g = ScanGate(goneMs = 1500)
        g.admit("A", now = 0, showing = false)
        g.closed("A", now = 1_000)
        assertTrue(g.admit("B", now = 1_050, showing = false))
    }
}

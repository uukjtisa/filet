package dev.niccc2007.filet.nearby

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stopping a share a second after starting it must not leave the server running.
 *
 * Reported as: start sharing, stop it about a second later, and the Start sharing button never
 * responds again until the app is force-stopped.
 *
 * The cause is an ordering, so the test is an ordering: stopping is immediate and starting is
 * not, so a stop can land in the middle of a start and be undone by it. The model below plays
 * the two against each other in the order the device actually runs them.
 */
class ShareIntentTest {

    /** The manager's side of it: a token that stopping moves, and a running flag. */
    private class Share {
        val token = AtomicLong(0)
        var running = false
            private set

        /** @return the token this start began under. */
        fun beginStart(): Long = token.get()

        /** The slow part, finishing later. @return whether the start was allowed to stand. */
        fun finishStart(began: Long): Boolean {
            running = true
            if (!startStillWanted(began, token.get())) {
                running = false
                return false
            }
            return true
        }

        fun stop() {
            token.incrementAndGet()
            running = false
        }
    }

    // ── the report ──

    @Test
    fun `a stop during a start leaves sharing off`() {
        val s = Share()
        val began = s.beginStart()
        s.stop()              // pressed a second later, and it completes first
        s.finishStart(began)  // the start catches up afterwards
        assertFalse("the server was left running after Stop", s.running)
    }

    @Test
    fun `and the next start is not refused as already running`() {
        // The dead button, stated as what causes it: the flag being true while the screen
        // shows Start is what makes every later press return silently.
        val s = Share()
        val began = s.beginStart()
        s.stop()
        s.finishStart(began)
        assertFalse("a later Start would be refused as already running", s.running)

        // And a fresh start after all that works normally.
        assertTrue(s.finishStart(s.beginStart()))
        assertTrue(s.running)
    }

    // ── the ordinary cases still work ──

    @Test
    fun `an uninterrupted start stands`() {
        val s = Share()
        assertTrue(s.finishStart(s.beginStart()))
        assertTrue(s.running)
    }

    @Test
    fun `two quick starts do not cancel each other`() {
        // Only stopping moves the token. If starting moved it as well, the first press would
        // read the second as a contradiction and tear down what the second had just started.
        val s = Share()
        val first = s.beginStart()
        val second = s.beginStart()
        assertTrue(s.finishStart(second))
        assertTrue("the first press undid the second", s.finishStart(first))
        assertTrue(s.running)
    }

    @Test
    fun `stop after a start has fully finished still stops`() {
        val s = Share()
        s.finishStart(s.beginStart())
        s.stop()
        assertFalse(s.running)
    }

    @Test
    fun `a second stop-during-start is also honoured`() {
        // The token has to keep moving, not just flip once.
        val s = Share()
        repeat(3) {
            val began = s.beginStart()
            s.stop()
            assertFalse("lap $it left it running", s.finishStart(began))
        }
        assertEquals(3, s.token.get())
    }

    // ── the decision itself ──

    @Test
    fun `a start is wanted only while nothing has stopped since`() {
        assertTrue(startStillWanted(7L, 7L))
        assertFalse(startStillWanted(7L, 8L))
        assertFalse(startStillWanted(0L, 1L))
    }
}

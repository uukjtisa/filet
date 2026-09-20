package dev.niccc2007.filet.nearby

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stopping sharing and starting it again.
 *
 * Bug identified: the accept loop's condition was a shared `running` flag and a failed accept
 * did `continue`. Stopping sets the flag false and closes the socket, so the loop normally
 * ends. Starting again straight away does not wait for it: `start` sees the flag already
 * false, binds a new socket and sets the flag back to true - and the previous loop, which has
 * not re-read the flag yet, finds it true while holding a socket that was closed underneath
 * it. `accept()` throws immediately, `continue` sends it round again, and the thread spins as
 * fast as the CPU allows.
 *
 * That is the crash loop: a runaway thread rather than a crash, which is why the app could
 * not be closed and why it was blamed on the indexing running in the background.
 */
class ServerRestartTest {

    // ── the loop belongs to its own socket ──

    @Test
    fun `a loop from the previous socket stops even when the flag is true again`() {
        // The exact race. Generation 1's loop, the server now on generation 2 because a new
        // start bound a new socket, and `running` true again.
        assertFalse(
            "this is the spin",
            AcceptLoop.keepGoing(running = true, myGeneration = 1, currentGeneration = 2, socketClosed = true),
        )
    }

    @Test
    fun `it stops on generation alone, even if the close has not landed yet`() {
        // Closing and flag-setting are not atomic with each other, so the generation has to be
        // sufficient by itself.
        assertFalse(
            AcceptLoop.keepGoing(running = true, myGeneration = 1, currentGeneration = 2, socketClosed = false),
        )
    }

    @Test
    fun `the current loop keeps going`() {
        // The positive control. A rule that always stopped would end the spin and break
        // sharing entirely, which is the worse of the two bugs.
        assertTrue(
            AcceptLoop.keepGoing(running = true, myGeneration = 7, currentGeneration = 7, socketClosed = false),
        )
    }

    @Test
    fun `a stopped server ends its loop`() {
        assertFalse(
            AcceptLoop.keepGoing(running = false, myGeneration = 7, currentGeneration = 7, socketClosed = false),
        )
    }

    @Test
    fun `a closed socket ends its loop even while the server thinks it is running`() {
        assertFalse(
            AcceptLoop.keepGoing(running = true, myGeneration = 7, currentGeneration = 7, socketClosed = true),
        )
    }

    // ── a failed accept is not a retry ──

    @Test
    fun `a failed accept ends the loop rather than going round again`() {
        // The socket carries no read timeout, so a throw is not a quiet period - it is the
        // socket being gone. Retrying it is the spin.
        assertTrue(AcceptLoop.stopOnAcceptFailure())
    }

    // ── the sequence, as it happens ──

    @Test
    fun `stop then start leaves exactly one live loop`() {
        var generation = 0L
        fun start(): Long = ++generation
        fun stop() { generation++ }

        val first = start()
        assertTrue(AcceptLoop.keepGoing(true, first, generation, socketClosed = false))

        stop()
        val second = start()

        assertFalse("the old loop must be finished", AcceptLoop.keepGoing(true, first, generation, false))
        assertTrue("the new one must be alive", AcceptLoop.keepGoing(true, second, generation, false))
    }

    @Test
    fun `starting several times in a row still leaves one`() {
        var generation = 0L
        val loops = (1..5).map { ++generation }
        for (old in loops.dropLast(1)) {
            assertFalse("loop $old should be done", AcceptLoop.keepGoing(true, old, generation, false))
        }
        assertTrue(AcceptLoop.keepGoing(true, loops.last(), generation, false))
    }
}

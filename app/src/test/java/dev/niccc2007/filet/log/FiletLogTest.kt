package dev.niccc2007.filet.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The logger's own promises: a suppressed line costs no formatting, nothing it does throws,
 * spans nest into a tree, and counting is cheap enough to put on a hot path.
 *
 * No session is open in a unit test, so lines go to logcat only - and `android.util.Log` is a
 * stub here that throws. That is useful: every test below that logs is also a test that the
 * logger survives its own sink failing.
 */
class FiletLogTest {

    private val was = FiletLog.verbose

    @After
    fun restore() {
        FiletLog.verbose = was
        Counters.clear()
    }

    @Test
    fun `a suppressed debug line never builds its message`() {
        FiletLog.verbose = false
        var built = 0
        repeat(1_000) { FiletLog.d("beat") { built++; "step $it" } }
        assertEquals(0, built)
    }

    @Test
    fun `with detail on the message is built once`() {
        FiletLog.verbose = true
        var built = 0
        FiletLog.d("beat") { built++; "step" }
        assertEquals(1, built)
    }

    @Test
    fun `nothing thrown by the logger or its message reaches the caller`() {
        FiletLog.verbose = true
        FiletLog.d("x") { error("message failed") }
        FiletLog.i("x") { throw OutOfMemoryError("pretend") }
        FiletLog.e("x", "failure", IllegalStateException("boom", RuntimeException("cause")))
        FiletLog.w("x", "warning")
        FiletLog.count("c")
        FiletLog.record("c", 10)
        FiletLog.loop("l", 10).close()
        FiletLog.flushNow()
        assertTrue(FiletLog.runs().isEmpty())
    }

    @Test
    fun `span and time rethrow the block's own failure, and still count it`() {
        val thrown = runCatching { FiletLog.time("work") { error("caller's bug") } }.exceptionOrNull()
        assertEquals("caller's bug", thrown?.message)
        assertEquals(1, Counters.of("work").total().count)
        val inSpan = runCatching { FiletLog.span("t", "s") { error("again") } }.exceptionOrNull()
        assertEquals("again", inSpan?.message)
    }

    @Test
    fun `spans nest into a tree with a number on each node`() {
        val outer = Spans.begin("launch", "onFirstScreen")
        val a = Spans.begin("launch", "vfs.roots")
        assertEquals(null, Spans.end(a))
        val b = Spans.begin("launch", "home.refresh")
        val c = Spans.begin("launch", "index.query")
        assertEquals(null, Spans.end(c))
        assertEquals(null, Spans.end(b))
        val root = Spans.end(outer)
        assertTrue(root === outer)
        outer.ms = 1204; a.ms = 702; b.ms = 311; c.ms = 280
        assertEquals(
            "launch/onFirstScreen 1204ms\n  vfs.roots 702ms\n  home.refresh 311ms\n    index.query 280ms\n",
            Spans.render(outer),
        )
    }

    @Test
    fun `a span ending after its parent is written on its own, marked as such`() {
        val parent = Spans.begin("t", "parent")
        val child = Spans.begin("t", "child")
        Spans.end(parent)
        val alone = Spans.end(child)
        assertTrue(alone === child)
        assertTrue(Spans.render(child).contains("finished after its parent"))
        // And the thread's current span is not left pointing at either.
        val next = Spans.begin("t", "next")
        assertEquals(null, next.parent)
        Spans.end(next)
    }

    /**
     * The cost claim, as a number rather than an adjective. Generous bounds - a shared CI
     * machine is noisy - but two orders of magnitude under anything a per-frame loop does:
     * a suppressed line must stay well under a microsecond, a counted timing under two.
     */
    @Test
    fun `suppressed lines and counter hits are sub-microsecond`() {
        FiletLog.verbose = false
        val n = 2_000_000
        repeat(50_000) { FiletLog.d("warm") { "x$it" }; FiletLog.record("warm", 1) }

        var t0 = System.nanoTime()
        repeat(n) { FiletLog.d("beat") { "step $it" } }
        val suppressed = (System.nanoTime() - t0).toDouble() / n

        t0 = System.nanoTime()
        repeat(n) { FiletLog.record("beat.step", 400) }
        val counted = (System.nanoTime() - t0).toDouble() / n

        println("suppressed debug line: %.1f ns, counter record: %.1f ns".format(suppressed, counted))
        assertTrue("suppressed $suppressed ns", suppressed < 1_000)
        assertTrue("counted $counted ns", counted < 2_000)
        assertFalse(Counters.of("beat.step").total().count < n)
    }
}

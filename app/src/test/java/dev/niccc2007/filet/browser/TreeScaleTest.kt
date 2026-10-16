package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeScaleTest {

    @Test
    fun `a flat list is not a tree and keeps the ordinary margin`() {
        val s = TreeScale.of(widthDp = 180, deepest = 0)
        assertEquals(TreeScale.START, s.startDp, 0.001f)
        assertEquals(TreeScale.INDENT, s.indentDp, 0.001f)
    }

    @Test
    fun `a wide pane never pays for this`() {
        val s = TreeScale.of(widthDp = 420, deepest = 3)
        assertEquals(TreeScale.START, s.startDp, 0.001f)
        assertEquals(TreeScale.INDENT, s.indentDp, 0.001f)
    }

    @Test
    fun `the step gives way before the margin does`() {
        // Deep enough on a narrow pane that the full step will not fit. The indent shrinks
        // and the left margin is untouched - the margin is the last thing spent.
        val s = TreeScale.of(widthDp = 220, deepest = 5)
        assertTrue("the step did not shrink", s.indentDp < TreeScale.INDENT)
        assertTrue("it shrank past its floor", s.indentDp >= TreeScale.INDENT_MIN)
        assertEquals(TreeScale.START, s.startDp, 0.001f)
    }

    @Test
    fun `once the step is at its floor the whole tree moves to the edge`() {
        val s = TreeScale.of(widthDp = 200, deepest = 11)
        assertEquals(TreeScale.INDENT_MIN, s.indentDp, 0.001f)
        assertTrue("it did not move in", s.startDp < TreeScale.START)
        assertTrue("it went past the frame", s.startDp >= TreeScale.START_MIN)
    }

    @Test
    fun `the deeper it opens the tighter it gets, monotonically`() {
        // A tree that got WIDER as it opened would reflow the wrong way, and a step that
        // jumped about between depths would make the ladder unreadable.
        var last = Float.MAX_VALUE
        for (d in 1..TreeRows.MAX_DEPTH) {
            val step = TreeScale.of(240, d).indentDp
            assertTrue("depth $d widened the step", step <= last + 0.001f)
            last = step
        }
    }

    @Test
    fun `it never returns something a guide line cannot be drawn in`() {
        // The elbow runs `indent - 6` dp. Anything at or below six and the corner and the
        // run are the same pixel, which is where "connected" stops being visible.
        for (w in 90..500 step 7) {
            for (d in 0..TreeRows.MAX_DEPTH) {
                val s = TreeScale.of(w, d)
                assertTrue("$w/$d gave ${s.indentDp}", s.indentDp >= TreeScale.INDENT_MIN)
                assertTrue("$w/$d gave ${s.startDp}", s.startDp >= TreeScale.START_MIN)
                assertTrue("$w/$d exceeded the full step", s.indentDp <= TreeScale.INDENT)
            }
        }
    }

    @Test
    fun `an absurdly narrow pane still answers rather than going negative`() {
        val s = TreeScale.of(widthDp = 40, deepest = 9)
        assertEquals(TreeScale.INDENT_MIN, s.indentDp, 0.001f)
        assertEquals(TreeScale.START_MIN, s.startDp, 0.001f)
    }
}

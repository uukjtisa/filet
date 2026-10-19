package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision under the selection-latency fix: which state changes the pane hears about.
 *
 * What this does NOT prove is recomposition. That the pane skips and one row recomposes is a
 * property of Compose read scopes, verified by reading them; these tests only hold the equality
 * the pane's `distinctUntilChanged` relies on.
 */
class SelectionReadsTest {

    private fun p(name: String) = VPath.of("local", "/sdcard/$name")
    private val base = PaneState(id = 1, kind = PaneKind.FOLDER, cwd = VPath.of("local", "/sdcard"))

    @Test
    fun `changing which files are selected is invisible to the pane`() {
        val one = SelectionReads.forPane(base.copy(selected = setOf(p("a"))))
        val two = SelectionReads.forPane(base.copy(selected = setOf(p("a"), p("b"))))
        val all = SelectionReads.forPane(base.copy(selected = setOf(p("a"), p("b"), p("c"))))
        assertEquals(one, two)
        assertEquals(two, all)
    }

    @Test
    fun `starting and ending a selection is still heard`() {
        val none = SelectionReads.forPane(base)
        val some = SelectionReads.forPane(base.copy(selected = setOf(p("a"))))
        assertNotEquals(none, some)
        assertFalse(none.selecting)
        assertTrue(some.selecting)
    }

    @Test
    fun `the marker matches no real file, so a misread row is never highlighted`() {
        val some = SelectionReads.forPane(base.copy(selected = setOf(p("a"))))
        assertFalse(p("a") in some.selected)
    }

    @Test
    fun `everything else still reaches the pane`() {
        val a = SelectionReads.forPane(base.copy(selected = setOf(p("a"))))
        val moved = SelectionReads.forPane(base.copy(cwd = p("Music"), selected = setOf(p("a"))))
        assertNotEquals(a, moved)
        // And an empty selection passes through untouched.
        assertSame(base, SelectionReads.forPane(base))
    }
}

package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tree is rooted at its drive and a list shows the focused folder, whichever way a pane got there. */
class TreeShapeTest {

    private val internal = VPath.of("local", "/storage/emulated/0")
    private val card = VPath.of("local", "/storage/1A2B-3C4D")
    private val roots = listOf(internal, card)
    private fun p(rel: String) = VPath.of("local", "/storage/emulated/0/$rel")
    private fun folder(cwd: VPath, active: VPath? = null, open: Set<VPath> = emptySet()) =
        PaneState(id = 1, kind = PaneKind.FOLDER, cwd = cwd, active = active, open = open)

    @Test
    fun `a folder arrived at in a tree hangs from its drive, opened down to it and focused`() {
        // Bug identified here: a shortcut to Download rooted the tree AT Download, with no trunk.
        val s = TreeShape.shape(folder(p("Download/chemall")), tree = true, roots = roots)
        assertEquals(internal, s.cwd)
        assertEquals(p("Download/chemall"), s.active)
        assertEquals(setOf(p("Download"), p("Download/chemall")), s.open)
        assertEquals(p("Download/chemall"), s.revealTarget)
        assertFalse(s.resume)
    }

    @Test
    fun `the drive root itself is never a fold`() {
        // A fold on the root is not a row, so Back would spend a press closing nothing.
        assertFalse(internal in TreeShape.chainTo(internal, p("Download")))
        assertEquals(setOf(p("Download")), TreeShape.chainTo(internal, p("Download")))
    }

    @Test
    fun `a tree rooted below its drive keeps its focus and gains its trunk`() {
        // What a tab saved before this rule looks like when it comes back.
        val old = folder(p("Download"), active = p("Download/ccvoice"), open = setOf(p("Download/ccvoice")))
        val s = TreeShape.shape(old, tree = true, roots = roots)
        assertEquals(internal, s.cwd)
        assertEquals(p("Download/ccvoice"), s.active)
        assertTrue(p("Download") in s.open && p("Download/ccvoice") in s.open)
    }

    @Test
    fun `a list shows the focused folder, not the drive the tree hangs from`() {
        val s = TreeShape.shape(folder(internal, active = p("Music/live")), tree = false, roots = roots)
        assertEquals(p("Music/live"), s.cwd)
        assertNull(s.active)
    }

    @Test
    fun `what already fits is left alone`() {
        val atRoot = folder(internal, active = p("Music"))
        assertSame(atRoot, TreeShape.shape(atRoot, tree = true, roots = roots))
        val list = folder(p("Music"))
        assertSame(list, TreeShape.shape(list, tree = false, roots = roots))
        // Under no drive: a remote archive staged into the app's cache is its own tree's root.
        // One on a drive hangs from that drive - see TreeBranchTest.
        val zip = folder(VPath("zip", "/data/user/0/dev.niccc2007.filet.debug/cache/a.zip!/docs"))
        assertSame(zip, TreeShape.shape(zip, tree = true, roots = roots))
        // Not a folder at all.
        val home = PaneState(id = 1)
        assertSame(home, TreeShape.shape(home, tree = true, roots = roots))
    }

    @Test
    fun `the deepest drive that holds a path is its drive`() {
        val nested = listOf(VPath.of("local", "/storage"), internal)
        assertEquals(internal, TreeShape.rootFor(p("DCIM"), nested))
        assertEquals(card, TreeShape.rootFor(VPath.of("local", "/storage/1A2B-3C4D/x"), roots))
        assertNull(TreeShape.rootFor(VPath.of("dav", "/nas"), roots))
    }
}

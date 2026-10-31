package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An archive is a folder in the tree: it opens in place, and the levels join across it. */
class TreeBranchTest {

    private val internal = VPath.of("local", "/storage/emulated/0")
    private val download = VPath.of("local", "/storage/emulated/0/Download")
    private fun local(rel: String) = VPath.of("local", "/storage/emulated/0/$rel")
    private fun dir(p: VPath) = VNode(p, true, -1, 0)
    private fun file(p: VPath) = VNode(p, false, 10, 0)

    private val zipFile = local("Download/a.zip")
    private val zip = ArchiveProvider.mount("/storage/emulated/0/Download/a.zip")
    private val docs = zip.child("docs")
    private val inner = docs.child("notes.txt")

    @Test
    fun `a zip a tap would mount opens in place, keyed by its mount`() {
        assertEquals(zip, TreeBranch.keyOf(file(zipFile)))
        assertEquals(download, TreeBranch.keyOf(dir(download)))
        assertNull(TreeBranch.keyOf(file(local("Download/b.txt"))))
        // An app opens the inspector, as a tap on it does, and is not a branch.
        assertNull(TreeBranch.keyOf(file(local("Download/app.apk"))))
        assertNull(TreeBranch.keyOf(file(local("Download/game.xapk"))))
        // Off local storage it would have to be staged first, which a chevron must not hide.
        assertNull(TreeBranch.keyOf(file(VPath.of("dav", "/nas/a.zip"))))
    }

    @Test
    fun `what is inside a zip is under the folder the zip is in, and nowhere else`() {
        assertTrue(TreeBranch.under(download, inner))
        assertTrue(TreeBranch.under(internal, zip))
        assertTrue(TreeBranch.under(zip, inner))
        assertFalse(TreeBranch.under(local("Music"), inner))
    }

    @Test
    fun `an open zip draws its contents under its own row`() {
        val rows = TreeRows.flatten(
            roots = listOf(file(zipFile), file(local("Download/b.txt"))),
            open = setOf(zip, docs),
            branches = mapOf(zip to listOf(dir(docs), file(zip.child("x.txt"))), docs to listOf(file(inner))),
        )
        assertEquals(
            listOf(zipFile to 0, docs to 1, inner to 2, zip.child("x.txt") to 1, local("Download/b.txt") to 0),
            rows.map { it.node.path to it.depth },
        )
        // Opened and not yet read, it says so, like a folder.
        val reading = TreeRows.flatten(listOf(file(zipFile)), setOf(zip), emptyMap())
        assertTrue(reading[1].loading)
        // And the reveal finds the zip's row by its mount.
        assertEquals(0, TreeRows.revealIndex(rows, zip))
    }

    @Test
    fun `the way down to a file inside a zip passes through the zip`() {
        assertEquals(setOf(inner, docs, zip, download), TreeShape.chainTo(internal, inner))
        assertEquals(internal, TreeShape.rootFor(inner, listOf(internal)))
    }

    @Test
    fun `closing the folder a zip is in closes the zip too`() {
        val open = setOf(download, zip, docs, local("Music"))
        assertEquals(setOf(local("Music")), TreeRows.toggle(open, download))
        assertEquals(listOf(download, zip, docs), TreeRows.visibleFolds(internal, setOf(download, zip, docs)))
    }

    @Test
    fun `arriving inside a zip opens the drive down through it and focuses the target`() {
        val list = PaneState(id = 1, kind = PaneKind.FOLDER, cwd = docs)
        val s = TreeShape.shape(list, tree = true, roots = listOf(internal))
        assertEquals(internal, s.cwd)
        assertEquals(docs, s.active)
        assertTrue(s.open.containsAll(listOf(download, zip, docs)))
    }

    @Test
    fun `a drop on a zip or on what is in it goes into the zip`() {
        val rows = TreeRows.flatten(
            roots = listOf(file(zipFile)),
            open = setOf(zip),
            branches = mapOf(zip to listOf(file(zip.child("x.txt")))),
        )
        assertEquals(listOf(zip, zip), TreeDrop.homes(rows, download))
    }
}

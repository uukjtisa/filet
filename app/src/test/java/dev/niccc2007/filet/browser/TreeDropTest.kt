package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Test

/** A drop on a tree row lands in the branch the row is drawn in, not the one in focus. */
class TreeDropTest {

    private val download = VPath.of("local", "/storage/emulated/0/Download")
    private fun p(rel: String) = VPath.of("local", "/storage/emulated/0/Download/$rel")
    private fun dir(rel: String) = VNode(p(rel), true, -1, 0)
    private fun file(rel: String) = VNode(p(rel), false, 10, 0)

    @Test
    fun `a file inside one open branch drops there while another branch is focused`() {
        // Bug identified here: a file row took no drop, so the pane's focused folder got it.
        val rows = TreeRows.flatten(
            roots = listOf(dir("ccvoice"), dir("chemall"), file("top.pdf")),
            open = setOf(p("ccvoice"), p("chemall")),
            branches = mapOf(
                p("ccvoice") to listOf(file("ccvoice/a.mp3"), dir("ccvoice/takes")),
                p("chemall") to listOf(file("chemall/b.pdf")),
            ),
        )
        val homes = TreeDrop.homes(rows, download).let { h -> rows.indices.associate { rows[it].node.path to h[it] } }
        assertEquals(p("ccvoice"), homes[p("ccvoice/a.mp3")])
        assertEquals(p("chemall"), homes[p("chemall/b.pdf")])
        // A folder row is still its own target, and a top-level file is the pane's folder.
        assertEquals(p("ccvoice/takes"), homes[p("ccvoice/takes")])
        assertEquals(download, homes[p("top.pdf")])
    }

    @Test
    fun `the notes under an opened branch drop into that branch`() {
        val rows = TreeRows.flatten(
            roots = listOf(dir("ccvoice"), dir("chemall")),
            open = setOf(p("ccvoice"), p("chemall")),
            branches = mapOf(p("chemall") to emptyList()),
        )
        val homes = TreeDrop.homes(rows, download)
        val loading = rows.indexOfFirst { it.loading }
        val empty = rows.indexOfFirst { it.empty }
        assertEquals(p("ccvoice"), homes[loading])
        assertEquals(p("chemall"), homes[empty])
    }

    @Test
    fun `a file after a deeper branch closes belongs to the shallower folder again`() {
        // ccvoice/takes is open and listed; the file after it is ccvoice's, not takes's.
        val rows = TreeRows.flatten(
            roots = listOf(dir("ccvoice")),
            open = setOf(p("ccvoice"), p("ccvoice/takes")),
            branches = mapOf(
                p("ccvoice") to listOf(dir("ccvoice/takes"), file("ccvoice/z.mp3")),
                p("ccvoice/takes") to listOf(file("ccvoice/takes/t1.wav")),
            ),
        )
        val homes = TreeDrop.homes(rows, download).let { h -> rows.indices.associate { rows[it].node.path to h[it] } }
        assertEquals(p("ccvoice/takes"), homes[p("ccvoice/takes/t1.wav")])
        assertEquals(p("ccvoice"), homes[p("ccvoice/z.mp3")])
    }
}

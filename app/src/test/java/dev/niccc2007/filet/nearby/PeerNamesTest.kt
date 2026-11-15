package dev.niccc2007.filet.nearby

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.provider.net.PeerNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Another phone's rows are named by what it lists, never by its tokens. See [PeerNames]. */
class PeerNamesTest {

    @Test
    fun `a listed name is the row's name, not the token`() {
        val name = PeerNames.clean("Applied maxima and minima.pdf", "5fb6e7b70d42")
        assertEquals("Applied maxima and minima.pdf", name)
        // The row's path ends in it, so its kind, its title and a copy's name all follow.
        assertEquals("pdf", VPath.of("peer", "/Filet-LGN").child(name).name.substringAfterLast('.'))
    }

    @Test
    fun `a name that cannot be a segment still makes a usable one`() {
        assertEquals("a∕b.txt", PeerNames.clean("a/b.txt", "t1"))
        assertEquals("t1", PeerNames.clean("", "t1"))
        assertEquals("t1", PeerNames.clean("..", "t1"))
        assertEquals("t1", PeerNames.clean(" . ", "t1"))
        // Every one of them is accepted as a child, which refuses separators and traversal.
        for (n in listOf("a/b.txt", "", "..", " . ")) VPath.of("peer", "/p").child(PeerNames.clean(n, "t1"))
    }

    @Test
    fun `two rows with one name get two paths`() {
        val names = PeerNames.unique(listOf("a.pdf", "Photos", "a.pdf", "a.pdf", "Photos", "a (2).pdf"))
        assertEquals(listOf("a.pdf", "Photos", "a (2).pdf", "a (3).pdf", "Photos (2)", "a (2) (2).pdf"), names)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `a listing with nothing repeated is left as it is`() {
        val names = listOf("x.mp3", "y.mp3", ".hidden")
        assertEquals(names, PeerNames.unique(names))
        assertFalse(PeerNames.unique(names).any { it.contains(" (") })
    }
}

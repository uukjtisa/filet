package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Test

/** Back in the tree with the setting that folds the folder it leaves (#12). */
class TreeBackFoldTest {

    private fun p(path: String) = VPath.of("local", path)

    private val root = p("/sdcard")
    private val music = p("/sdcard/Music")
    private val album = p("/sdcard/Music/Album")
    private val disc = p("/sdcard/Music/Album/Disc 1")

    @Test
    fun `back from a folder to its parent folds the folder, and what is under it`() {
        val open = setOf(music, album, disc)
        assertEquals(setOf(music), TreeRows.foldLeft(open, left = album, landing = music, keep = setOf(music)))
    }

    @Test
    fun `back to a folder inside the one left keeps it open, or the landing could not be seen`() {
        val open = setOf(music, album)
        assertEquals(open, TreeRows.foldLeft(open, left = music, landing = album, keep = setOf(music, album)))
    }

    @Test
    fun `nothing left, or a folder that was not open, changes nothing`() {
        val open = setOf(music)
        assertEquals(open, TreeRows.foldLeft(open, left = null, landing = music, keep = setOf(music)))
        assertEquals(open, TreeRows.foldLeft(open, left = album, landing = music, keep = setOf(music)))
        assertEquals(open, TreeRows.foldLeft(open, left = music, landing = music, keep = emptySet()))
    }

    @Test
    fun `a sibling branch stays as it was`() {
        val other = p("/sdcard/Pictures")
        val open = setOf(music, album, other)
        assertEquals(setOf(music, other), TreeRows.foldLeft(open, left = album, landing = music, keep = setOf(music)))
        // root unused beyond naming the drive
        assertEquals(root, album.parent?.parent)
    }
}

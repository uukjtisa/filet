package dev.niccc2007.filet.pick

import dev.niccc2007.filet.pick.PickSpec.RowState
import dev.niccc2007.filet.pick.PickSpec.Want
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-app pick: what qualifies, what is shown, and what comes back. */
class InAppPickTest {

    private fun file(name: String) = VNode(VPath.of("local", "/sdcard/Music/$name"), isDir = false, size = 1, mtime = 0)
    private fun dir(name: String) = VNode(VPath.of("local", "/sdcard/$name"), isDir = true, size = 0, mtime = 0)

    private val cover = PickSpec("Choose a picture for song.mp3", Want.FILE, PickFilter.PICTURES)

    @Test
    fun `a filtered pick shows what it refuses, dimmed, instead of hiding it`() {
        // The old chooser listed accepted files only, so a picture in a format it did not take
        // was simply not there - which reads as Filet being unable to see the file.
        assertEquals(RowState.ACCEPTED, cover.rowState(file("front.JPG")))
        assertEquals(RowState.DIMMED, cover.rowState(file("front.heic")))
        assertEquals(RowState.DIMMED, cover.rowState(file("song.mp3")))
        assertEquals(RowState.FOLDER, cover.rowState(dir("Pictures")))
        assertTrue(cover.refusal(file("front.heic")).contains("front.heic"))
    }

    @Test
    fun `a type filter goes through the same matcher as the external picker`() {
        val images = PickSpec("x", Want.FILE, PickFilter.Mime(listOf("image/*"), "an image"))
        assertEquals(RowState.ACCEPTED, images.rowState(file("a.png")))
        assertEquals(RowState.ACCEPTED, images.rowState(file("a.webp")))
        assertEquals(RowState.DIMMED, images.rowState(file("a.pdf")))
        // Unknown extension: no type to match, so an image filter cannot honestly take it.
        assertEquals(RowState.DIMMED, images.rowState(file("a.xyz")))
    }

    @Test
    fun `a single pick returns exactly one accepted file`() {
        val result = cover.resultFor(null, listOf(file("song.mp3"), file("b.png"), file("c.jpg")))
        // The dimmed one is never handed back, and only the first accepted is.
        assertEquals(listOf(file("b.png").path), result!!.paths)
    }

    @Test
    fun `a multi pick returns every accepted file in order`() {
        val many = PickSpec("x", Want.FILES, PickFilter.PICTURES, "Use these")
        val result = many.resultFor(null, listOf(file("b.png"), file("x.txt"), file("a.jpg")))
        assertEquals(listOf(file("b.png").path, file("a.jpg").path), result!!.paths)
    }

    @Test
    fun `nothing acceptable selected is not a result`() {
        assertNull(cover.resultFor(null, emptyList()))
        assertNull(cover.resultFor(null, listOf(file("song.mp3"))))
    }

    @Test
    fun `a folder pick returns the open folder and dims every file`() {
        val dest = PickSpec("Extract to", Want.FOLDER, confirmLabel = "Extract into this")
        val cwd = VPath.of("local", "/sdcard/Download")
        assertEquals(listOf(cwd), dest.resultFor(cwd, listOf(file("a.png")))!!.paths)
        assertEquals(RowState.DIMMED, dest.rowState(file("a.png")))
        assertTrue(dest.refusal(file("a.png")).contains("Extract into this"))
        assertNull(dest.resultFor(null, emptyList()))
    }

    @Test
    fun `in tree view a folder pick takes the open branch, not the volume root`() {
        // Bug identified here: the confirm read `cwd`, which tree view keeps at the volume root
        // while walking moves only the open branch. Every folder pick returned the root.
        val dest = PickSpec("Extract to", Want.FOLDER, confirmLabel = "Extract into this")
        val root = VPath.of("local", "/storage/emulated/0")
        val branch = VPath.of("local", "/storage/emulated/0/Download/Albums")
        assertEquals(branch, dest.folderOf(root, branch))
        assertEquals(listOf(branch), dest.resultFor(root, emptyList(), active = branch)!!.paths)
        // With no open branch, the folder in the path bar is the one being stood in.
        assertEquals(root, dest.folderOf(root, null))
        assertEquals(listOf(root), dest.resultFor(root, emptyList(), active = null)!!.paths)
    }

    @Test
    fun `cancel carries nothing`() {
        val r: PickResult = PickResult.Cancelled
        assertTrue(r !is PickResult.Picked)
    }
}

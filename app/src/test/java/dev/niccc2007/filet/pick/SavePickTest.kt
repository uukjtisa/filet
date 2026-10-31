package dev.niccc2007.filet.pick

import dev.niccc2007.filet.pick.PickSpec.RowState
import dev.niccc2007.filet.pick.PickSpec.Want
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker's save mode: a place and a name. See [PickSpec.Want.SAVE] and [PickName]. */
class SavePickTest {

    private val photos = VPath.of("local", "/sdcard/DCIM")
    private fun file(name: String) = VNode(photos.child(name), isDir = false, size = 1, mtime = 0)

    private val save = PickSpec(
        "Choose where to save a copy of a.jpg", Want.SAVE,
        PickFilter.Extensions(PickName.sameKind("jpg"), "a .jpg file"),
        name = "a-edit.jpg", saveExtension = "jpg",
    )

    @Test
    fun `a save answers with the folder stood in and the typed name`() {
        val r = save.resultFor(photos, emptyList(), name = "holiday.jpg")!!
        assertEquals(photos.child("holiday.jpg"), r.first)
        // In the tree the open branch is where somebody is standing, as for a folder pick.
        val branch = photos.child("2026")
        assertEquals(branch.child("holiday.jpg"), save.resultFor(photos, emptyList(), active = branch, name = "holiday.jpg")!!.first)
    }

    @Test
    fun `a name that cannot be a file's gives no answer`() {
        assertNull(save.resultFor(photos, emptyList(), name = "  "))
        assertNull(save.resultFor(photos, emptyList(), name = "a/b.jpg"))
        assertNull(save.resultFor(null, emptyList(), name = "ok.jpg"))
        assertNotNull(PickName.problem("x".repeat(256)))
        assertNull(PickName.problem("Photo 2026.10.30.jpg"))
    }

    @Test
    fun `the name is given the extension of what is being saved`() {
        assertEquals("holiday.jpg", PickName.fitted("holiday", "jpg"))
        assertEquals("holiday.jpeg", PickName.fitted("holiday.jpeg", "jpg"))
        assertEquals("holiday.jpg", PickName.fitted("holiday.png", "jpg"))
        assertEquals("2026.10.30.jpg", PickName.fitted("2026.10.30", "jpg"))
        assertEquals("notes.txt", PickName.fitted(" notes.txt ", ""))
        assertEquals(photos.child("holiday.jpg"), save.resultFor(photos, emptyList(), name = "holiday")!!.first)
    }

    @Test
    fun `a file of the same kind lights and is not chosen, anything else is dimmed`() {
        assertEquals(RowState.ACCEPTED, save.rowState(file("old.JPEG")))
        assertEquals(RowState.DIMMED, save.rowState(file("clip.mp4")))
        assertEquals(emptyList<VNode>(), save.chosenAfter(emptyList(), file("old.jpg")))
        assertTrue(save.refusal(file("clip.mp4")).contains("name"))
    }

    @Test
    fun `a clash is found whatever the case`() {
        assertTrue(PickName.clashes("IMG.JPG", listOf("img.jpg")))
        assertFalse(PickName.clashes("img2.jpg", listOf("img.jpg")))
    }

    @Test
    fun `the save card sits with its folder and says what it does`() {
        assertTrue(Want.SAVE.isPlace)
        assertFalse(Want.FILES.isPlace)
        assertTrue(PickCard.shownIn(Want.SAVE, anchor = "dest", focused = "other", pane = "dest", inApp = true, external = false))
        val s = PickSentence.of(Want.SAVE, "save", "a copy of a.jpg", asker = null, whatQualifies = "")
        assertEquals("Choose where to save a copy of a.jpg", s.headline)
        assertEquals("Save here", s.confirm)
    }

    @Test
    fun `every pick opens on Home unless its operation names a place`() {
        for (want in Want.entries) assertNull("$want opens on Home", PickSpec("Choose", want).startIn())
        assertNull("a save too", save.startIn())
        assertEquals(photos, save.copy(start = photos).startIn())
    }

    @Test
    fun `a file saved out of an archive starts in the folder the archive is in`() {
        val member = VPath.of("zip", "/storage/emulated/0/Download/notes.zip!/docs/a.txt")
        assertEquals(VPath.of("local", "/storage/emulated/0/Download"), PickSpec.besideArchive(member))
        val top = VPath.of("zip", "/storage/emulated/0/Download/notes.zip!/a.txt")
        assertEquals(VPath.of("local", "/storage/emulated/0/Download"), PickSpec.besideArchive(top))
        assertNull("not in an archive", PickSpec.besideArchive(VPath.of("local", "/storage/emulated/0/a.txt")))
    }
}

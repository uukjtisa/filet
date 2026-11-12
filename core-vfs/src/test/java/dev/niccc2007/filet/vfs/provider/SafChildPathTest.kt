package dev.niccc2007.filet.vfs.provider

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A SAF path is `tree/docId/ownName`, and a copy names its destination by adding a child to the
 * folder's path. Those added names have to be read as children of the folder, not as the folder
 * itself, or every copy into another app's storage is refused as "already exists".
 */
class SafChildPathTest {

    private val folder = VPath("saf", "/content%3A%2F%2Fcom.termux.documents%2Ftree%2Fhome/%2Fhome/home")

    @Test fun `a listed node names itself, with nothing under it`() {
        assertEquals(emptyList<String>(), SafProvider.childSegments(folder))
    }

    @Test fun `a name added for a copy is a child of the folder`() {
        assertEquals(listOf("notes.txt"), SafProvider.childSegments(folder.child("notes.txt")))
    }

    @Test fun `a folder copied in keeps its children under it`() {
        assertEquals(listOf("pics", "a.jpg"), SafProvider.childSegments(folder.child("pics").child("a.jpg")))
    }

    // A file's path is tree/fileId/name, so its parent is the file without its name, and a name
    // added back for a save's hidden copy must not land on the file: that deleted it.

    @Test fun `a listed file's own path names the file`() {
        assertEquals(SafAnchor.SELF, SafProvider.anchorOf(3, "notes.txt", ownName = "notes.txt"))
    }

    @Test fun `the hidden copy beside a file is a sibling, never the file`() {
        assertEquals(SafAnchor.SIBLING, SafProvider.anchorOf(3, ".filet-saving-notes.txt", ownName = "notes.txt"))
    }

    @Test fun `a name beside a file that is gone is still read as beside it`() {
        assertEquals(SafAnchor.SIBLING, SafProvider.anchorOf(3, "notes.txt", ownName = null))
    }

    @Test fun `a path with no name part is the document itself`() {
        assertEquals(SafAnchor.SELF, SafProvider.anchorOf(2, null, ownName = "notes.txt"))
    }

    @Test fun `the top of a tree that gives no name is still the top`() {
        assertEquals(SafAnchor.SELF, SafProvider.anchorOf(3, "home", ownName = null, isRoot = true))
        assertEquals(SafAnchor.SIBLING, SafProvider.anchorOf(3, "elsewhere", ownName = "home", isRoot = true))
    }
}

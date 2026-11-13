package dev.niccc2007.filet.vfs.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which outside changes re-read the folder on screen. */
class FolderChangesTest {

    @Test fun `a change heard in the folder itself always re-reads`() {
        // A file written over in place leaves the folder's time alone, and still changes its row.
        assertTrue(FolderChanges.shouldRelist(fromFolder = true, seenMtime = 1_000, nowMtime = 1_000))
    }

    @Test fun `a media index change re-reads only when the folder's own time moved`() {
        // A screenshot landing in another folder: one stat here, no listing.
        assertFalse(FolderChanges.shouldRelist(fromFolder = false, seenMtime = 1_000, nowMtime = 1_000))
        // A gallery app deleting a photo from this folder, under the mount where inotify cannot hear.
        assertTrue(FolderChanges.shouldRelist(fromFolder = false, seenMtime = 1_000, nowMtime = 2_000))
    }

    @Test fun `a folder whose time cannot be read is re-read, since it may be gone`() {
        assertTrue(FolderChanges.shouldRelist(fromFolder = false, seenMtime = 1_000, nowMtime = 0))
    }
}

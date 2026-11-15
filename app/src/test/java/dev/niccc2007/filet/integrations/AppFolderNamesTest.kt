package dev.niccc2007.filet.integrations

import dev.niccc2007.filet.browser.VolumeInfo
import dev.niccc2007.filet.home.DriveDetails
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a folder lent by an app is called, and what its card and Details say. */
class AppFolderNamesTest {

    @Test
    fun `the app leads and the folder follows`() {
        assertEquals("Termux · home", AppFolderNames.system("Termux", "home"))
        assertEquals("Google Drive · Photos", AppFolderNames.system("Google Drive", "Photos"))
    }

    @Test
    fun `a folder named for its app, or with no name, is called by the app alone`() {
        assertEquals("Termux", AppFolderNames.system("Termux", "termux"))
        assertEquals("Termux", AppFolderNames.system("Termux", "  "))
    }

    @Test
    fun `with no app found it is the folder as before`() {
        assertEquals("home", AppFolderNames.system(null, "home"))
        assertEquals("Granted folder", AppFolderNames.system(null, ""))
    }

    @Test
    fun `the card's big line is the folder, or the name it was given`() {
        assertEquals("home", AppFolderNames.cardValue("Termux", "home", "Termux · home", "Termux · home"))
        assertEquals("Scripts", AppFolderNames.cardValue("Termux", "home", "Scripts", "Termux · home"))
        assertEquals("Termux", AppFolderNames.cardValue("Termux", "", "Termux", "Termux"))
    }

    @Test
    fun `the caption counts what is in it and says whether Filet may write`() {
        assertEquals("142 items · read and write", AppFolderNames.caption(142, writable = true))
        assertEquals("1 item · read only", AppFolderNames.caption(1, writable = false))
        assertEquals("read and write", AppFolderNames.caption(null, writable = true))
    }

    @Test
    fun `Details names the app and the access, and only for a folder from an app`() {
        val node = VNode(VPath("saf", "/content%3A%2F%2Fcom.termux.documents%2Ftree%2Fx/x"), isDir = true, size = 0, mtime = 0, writable = false)
        val termux = FolderApps.App("com.termux", "Termux", "com.termux.documents")
        val lent = VolumeInfo(node, "Termux · home", null, null, systemLabel = "Termux · home", app = termux)
        val rows = DriveDetails.rows(lent) { "$it B" }.toMap()
        assertEquals("Termux", rows["App"])
        assertEquals("Read only", rows["Access"])
        val plain = VolumeInfo(node.copy(writable = true), "Granted folder", null, null)
        assertFalse("App" in DriveDetails.rows(plain) { "$it B" }.toMap())
    }

    @Test
    fun `Termux is recognised by its provider, never by a folder's name`() {
        assertTrue(Termux.isTermuxAuthority("com.termux.documents"))
        assertFalse(Termux.isTermuxAuthority("com.google.android.apps.docs.storage"))
        assertFalse(Termux.isTermuxAuthority(null))
    }
}

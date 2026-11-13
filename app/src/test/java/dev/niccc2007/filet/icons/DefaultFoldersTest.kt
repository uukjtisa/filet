package dev.niccc2007.filet.icons

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultFoldersTest {

    @Test fun a_folder_directly_in_internal_storage_is_at_the_top() {
        assertEquals("Download", DefaultFolders.topLevelName("/storage/emulated/0/Download"))
        assertEquals("Music", DefaultFolders.topLevelName("/storage/emulated/10/Music/"))
    }

    @Test fun a_folder_directly_on_a_card_is_at_the_top() {
        assertEquals("DCIM", DefaultFolders.topLevelName("/storage/1A2B-3C4D/DCIM"))
    }

    @Test fun deeper_folders_and_the_drives_themselves_are_not() {
        assertNull(DefaultFolders.topLevelName("/storage/emulated/0/DCIM/Camera"))
        assertNull(DefaultFolders.topLevelName("/storage/emulated/0/Pictures/Screenshots"))
        assertNull(DefaultFolders.topLevelName("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp"))
        assertNull(DefaultFolders.topLevelName("/storage/emulated/0"))
        assertNull(DefaultFolders.topLevelName("/storage/1A2B-3C4D"))
    }

    @Test fun the_aliases_and_anything_outside_storage_are_not() {
        assertNull(DefaultFolders.topLevelName("/storage/self/primary"))
        assertNull(DefaultFolders.topLevelName("/storage/emulated/legacy"))
        assertNull(DefaultFolders.topLevelName("/sdcard/Music"))
        assertNull(DefaultFolders.topLevelName("/data/Music"))
    }

    @Test fun a_standard_folder_wears_its_look_whatever_its_case() {
        assertEquals(FolderLook("download", "green"), DefaultFolders.lookOf(local("/storage/emulated/0/Download")))
        assertEquals(FolderLook("download", "green"), DefaultFolders.lookOf(local("/storage/emulated/0/download")))
        assertEquals(FolderLook("camera", "amber"), DefaultFolders.lookOf(local("/storage/emulated/0/DCIM")))
    }

    @Test fun the_same_name_deeper_down_or_somewhere_else_gets_nothing() {
        assertNull(DefaultFolders.lookOf(local("/storage/emulated/0/DCIM/Camera")))
        assertNull(DefaultFolders.lookOf(local("/storage/emulated/0/Work/Music")))
        assertNull(DefaultFolders.lookOf(VPath("smb", "/storage/emulated/0/Music")))
        assertNull(DefaultFolders.lookOf(local("/storage/emulated/0/Holiday")))
    }

    private fun local(p: String) = VPath("local", p)
}

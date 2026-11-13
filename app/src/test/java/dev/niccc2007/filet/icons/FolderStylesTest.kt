package dev.niccc2007.filet.icons

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderStylesTest {

    @Test fun a_moved_folder_takes_its_look_along() {
        assertEquals("local:///s/Work2", movedTo("local:///s/Work", "local:///s/Work", "local:///s/Work2"))
    }

    @Test fun so_does_everything_inside_it() {
        assertEquals("local:///t/Work/Receipts/2026", movedTo("local:///s/Work/Receipts/2026", "local:///s/Work", "local:///t/Work"))
    }

    @Test fun a_folder_whose_name_only_starts_the_same_stays() {
        assertNull(movedTo("local:///s/Music2", "local:///s/Music", "local:///s/Songs"))
        assertNull(movedTo("local:///s/Other", "local:///s/Music", "local:///s/Songs"))
    }

    @Test fun a_chosen_look_wins_over_the_standard_one_which_wins_over_plain() {
        val download = VPath("local", "/storage/emulated/0/Download")
        val mine = FolderLook("star", "rose")
        assertEquals(mine, lookFor(mapOf(download.toString() to mine), download))
        assertEquals(FolderLook("download", "green"), lookFor(emptyMap(), download))
        assertEquals(FolderLook.PLAIN, lookFor(emptyMap(), VPath("local", "/storage/emulated/0/Holiday")))
    }
}

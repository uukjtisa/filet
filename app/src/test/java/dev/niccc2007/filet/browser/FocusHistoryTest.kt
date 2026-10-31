package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back steps the tree's focus inside a folder, and only inside it. */
class FocusHistoryTest {

    private val download = VPath.of("local", "/storage/emulated/0/Download")
    private val music = VPath.of("local", "/storage/emulated/0/Music")

    @Test
    fun `an entry for the folder the pane is in is a focus step`() {
        assertTrue(FocusHistory.isFocusStep(true, download, true, download))
    }

    @Test
    fun `an entry for another folder is a move, not a focus step`() {
        assertFalse(FocusHistory.isFocusStep(true, download, true, music))
    }

    @Test
    fun `nothing that is not a folder on both sides is a focus step`() {
        // Home, a category, a special pane: none of them has a tree to focus in.
        assertFalse(FocusHistory.isFocusStep(false, null, true, download))
        assertFalse(FocusHistory.isFocusStep(true, download, false, null))
        assertFalse(FocusHistory.isFocusStep(true, null, true, null))
    }
}

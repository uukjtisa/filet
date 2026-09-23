package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Home opens, and the reminder that fires once in the app's life. */
class HomeTargetTest {

    private val download = VPath.parse("local:///storage/emulated/0/Download")

    @Test
    fun `nothing set means the overview`() {
        assertEquals(HomeTarget.Resolution.Overview, HomeTarget.resolve(null) { true })
    }

    @Test
    fun `a set folder that still exists is what Home opens`() {
        assertEquals(
            HomeTarget.Resolution.Folder(download),
            HomeTarget.resolve(download) { it == download },
        )
    }

    @Test
    fun `a set folder that has gone falls back to the overview`() {
        // Home is the screen that opens on launch. A target that no longer resolves must not
        // put an error there - a file manager whose first screen is a failure reads as broken
        // before it has done anything.
        assertEquals(HomeTarget.Resolution.Overview, HomeTarget.resolve(download) { false })
    }

    @Test
    fun `existence is only asked about when something is set`() {
        var asked = 0
        HomeTarget.resolve(null) { asked++; true }
        assertEquals("the default must not cost a filesystem read", 0, asked)
    }

    // ── the one-time reminder ──────────────────────────────────────────────────────────

    @Test
    fun `the reminder shows the first time a folder is set`() {
        assertTrue(HomeTarget.shouldRemind(alreadyShown = false, settingAFolder = true))
    }

    @Test
    fun `it never shows a second time`() {
        // Once in the whole app's life, which is what was asked for - not once per session and
        // not once per folder.
        assertFalse(HomeTarget.shouldRemind(alreadyShown = true, settingAFolder = true))
    }

    @Test
    fun `resetting Home back to the overview never reminds`() {
        // The reminder exists to say this action is reversible. Showing it while somebody is
        // reversing it is absurd.
        assertFalse(HomeTarget.shouldRemind(alreadyShown = false, settingAFolder = false))
        assertFalse(HomeTarget.shouldRemind(alreadyShown = true, settingAFolder = false))
    }

    @Test
    fun `the reminder says where to undo it`() {
        // A one-time message whose whole job is to be findable later has to name the place.
        assertTrue(HomeTarget.REMINDER_BODY.contains("Settings"))
        assertTrue(HomeTarget.REMINDER_BODY.contains("Home screen"))
        assertTrue(HomeTarget.REMINDER_TITLE.isNotBlank())
    }
}

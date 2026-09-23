package dev.niccc2007.filet.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The long-press menu on the app icon: short, fixed, and never claiming the wrong thing. */
class LauncherShortcutsTest {

    @Test
    fun `the menu never exceeds what the platform will show`() {
        // Beyond the cap the extras are dropped, and WHICH ones is up to the launcher - so a
        // longer list has entries that exist on some phones and not on others.
        assertTrue(LauncherShortcuts.actionsFor(sharing = false).size <= LauncherShortcuts.MAX)
        assertTrue(LauncherShortcuts.actionsFor(sharing = true).size <= LauncherShortcuts.MAX)
    }

    @Test
    fun `it offers to start sharing only when nothing is being shared`() {
        val idle = LauncherShortcuts.actionsFor(sharing = false)
        assertTrue(AppAction.SHARE_NEARBY in idle)
        assertFalse(AppAction.STOP_SHARING in idle)
    }

    @Test
    fun `it offers to stop sharing only while a share is running`() {
        val live = LauncherShortcuts.actionsFor(sharing = true)
        assertTrue(AppAction.STOP_SHARING in live)
        assertFalse(AppAction.SHARE_NEARBY in live)
    }

    @Test
    fun `never both at once`() {
        // Offering both means one of the two is always wrong.
        for (sharing in listOf(true, false)) {
            val menu = LauncherShortcuts.actionsFor(sharing)
            assertFalse(AppAction.SHARE_NEARBY in menu && AppAction.STOP_SHARING in menu)
        }
    }

    @Test
    fun `the list is stable apart from the sharing entry`() {
        // Position is the only thing that makes a long-press menu fast. Anything that moves
        // between presses may as well not be there.
        val idle = LauncherShortcuts.actionsFor(sharing = false)
        val live = LauncherShortcuts.actionsFor(sharing = true)
        assertEquals(idle.size, live.size)
        for (i in idle.indices) {
            val differs = idle[i] != live[i]
            if (differs) {
                assertTrue(
                    "only the sharing slot may change",
                    idle[i] == AppAction.SHARE_NEARBY && live[i] == AppAction.STOP_SHARING,
                )
            }
        }
    }

    @Test
    fun `search is first`() {
        // The first slot is the only position that is the same in every launcher.
        assertEquals(AppAction.SEARCH, LauncherShortcuts.actionsFor(sharing = false).first())
    }

    @Test
    fun `it carries the things that were asked for`() {
        val menu = LauncherShortcuts.actionsFor(sharing = false)
        assertTrue(AppAction.BOOKMARKS in menu)
        assertTrue(AppAction.INDEX_NOW in menu)
    }

    @Test
    fun `no action appears twice`() {
        for (sharing in listOf(true, false)) {
            val menu = LauncherShortcuts.actionsFor(sharing)
            assertEquals(menu.size, menu.toSet().size)
        }
    }

    @Test
    fun `every action in the menu is one the app can still parse`() {
        // A launcher icon outlives the version that made it. An entry whose name no longer
        // parses is a menu item that does nothing when pressed.
        for (sharing in listOf(true, false)) {
            for (a in LauncherShortcuts.actionsFor(sharing)) {
                assertEquals(a, AppAction.parse(a.name))
            }
        }
    }

    @Test
    fun `labels fit the space a launcher gives them`() {
        // Android truncates the short label hard; a label that is cut mid-word says less than
        // a shorter one would.
        for (a in AppAction.entries) {
            assertTrue("${a.label} is too long for a launcher menu", a.label.length <= 16)
        }
    }
}

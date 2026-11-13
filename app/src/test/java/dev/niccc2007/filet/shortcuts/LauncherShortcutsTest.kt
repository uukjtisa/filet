package dev.niccc2007.filet.shortcuts

import dev.niccc2007.filet.browser.TabTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The long-press menu on the app icon: short, in the chosen order, and never claiming the wrong thing. */
class LauncherShortcutsTest {

    /** Menus somebody could have chosen: the default, short ones, one with no share, and everything at once. */
    private val chosenSets = listOf(
        HoldMenu.DEFAULT,
        listOf("tab:playlists", HoldMenu.SHARE),
        listOf(HoldMenu.SEARCH),
        listOf("tab:files", "tab:resume", HoldMenu.INDEX, HoldMenu.SHARE),
        HoldMenu.CHOICES,
    )

    private fun routes(chosen: List<String>, sharing: Boolean) = LauncherShortcuts.menuFor(chosen, sharing).map { it.route }

    @Test
    fun `the menu never exceeds what the platform will show`() {
        // Beyond the cap the extras are dropped, and WHICH ones is up to the launcher - so a
        // longer list has entries that exist on some phones and not on others.
        for (chosen in chosenSets) for (sharing in listOf(true, false)) {
            assertTrue(chosen.toString(), LauncherShortcuts.menuFor(chosen, sharing).size <= LauncherShortcuts.MAX)
        }
    }

    @Test
    fun `it offers to start sharing only when nothing is being shared`() {
        val idle = routes(HoldMenu.DEFAULT, sharing = false)
        assertTrue(AppAction.SHARE_NEARBY.name in idle)
        assertFalse(AppAction.STOP_SHARING.name in idle)
    }

    @Test
    fun `it offers to stop sharing only while a share is running`() {
        val live = routes(HoldMenu.DEFAULT, sharing = true)
        assertTrue(AppAction.STOP_SHARING.name in live)
        assertFalse(AppAction.SHARE_NEARBY.name in live)
    }

    @Test
    fun `never both at once`() {
        // Offering both means one of the two is always wrong.
        for (chosen in chosenSets) for (sharing in listOf(true, false)) {
            val menu = routes(chosen, sharing)
            assertFalse(AppAction.SHARE_NEARBY.name in menu && AppAction.STOP_SHARING.name in menu)
        }
    }

    @Test
    fun `the list is stable apart from the sharing entry`() {
        // Position is the only thing that makes a long-press menu fast. Anything that moves
        // between presses may as well not be there.
        for (chosen in chosenSets) {
            val idle = routes(chosen, sharing = false)
            val live = routes(chosen, sharing = true)
            assertEquals(idle.size, live.size)
            for (i in idle.indices) {
                if (idle[i] != live[i]) {
                    assertTrue(
                        "only the sharing slot may change",
                        idle[i] == AppAction.SHARE_NEARBY.name && live[i] == AppAction.STOP_SHARING.name,
                    )
                }
            }
        }
    }

    @Test
    fun `the chosen order is the published order`() {
        val chosen = listOf("tab:files", "tab:resume", HoldMenu.INDEX, HoldMenu.SHARE)
        assertEquals(chosen, LauncherShortcuts.menuFor(chosen, sharing = false).map { it.id })
    }

    @Test
    fun `nobody who never chose sees search first, then bookmarks, sharing and index`() {
        // The first slot is the only position that is the same in every launcher.
        val menu = routes(HoldMenu.DEFAULT, sharing = false)
        assertEquals(AppAction.SEARCH.name, menu.first())
        assertTrue(TAB_PREFIX + TabTarget.BOOKMARKS.id in menu)
        assertTrue(AppAction.INDEX_NOW.name in menu)
    }

    @Test
    fun `no entry appears twice`() {
        for (chosen in chosenSets) for (sharing in listOf(true, false)) {
            val menu = routes(chosen, sharing)
            assertEquals(menu.size, menu.toSet().size)
        }
    }

    @Test
    fun `every entry opens something the app can still find`() {
        // A launcher icon outlives the version that made it. An entry whose route no longer
        // resolves is a menu item that does nothing when pressed.
        for (sharing in listOf(true, false)) {
            for (e in LauncherShortcuts.menuFor(HoldMenu.CHOICES, sharing) + HoldMenu.CHOICES.mapNotNull { HoldMenu.entryOf(it, sharing) }) {
                val tab = tabIdOrNull(e.route)
                if (tab != null) assertTrue(e.route, TabTarget.byId(tab) != null)
                else assertTrue(e.route, AppAction.parse(e.route) != null)
            }
        }
    }

    @Test
    fun `labels fit the space a launcher gives them`() {
        // Android truncates hard; a label that is cut mid-word says less than a shorter one would.
        for (a in AppAction.entries) {
            assertTrue("${a.label} is too long for a launcher menu", a.label.length <= 16)
        }
        for (id in HoldMenu.CHOICES) {
            val e = HoldMenu.entryOf(id, sharing = false)!!
            assertTrue("${e.long} is longer than a launcher's long label", e.long.length <= 25)
        }
    }
}

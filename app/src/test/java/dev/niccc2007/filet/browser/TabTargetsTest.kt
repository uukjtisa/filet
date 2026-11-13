package dev.niccc2007.filet.browser

import dev.niccc2007.filet.home.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one list of tabs, and everything that has to agree with it.
 *
 * The failure this exists for: Apps, the QR scanner and the six categories could be opened from
 * the side bar and were missing from both the bar picker and the shortcut list, because each of
 * those lists was written by hand. A tab added to the side bar and forgotten here fails below.
 */
class TabTargetsTest {

    @Test
    fun `every side-bar destination is a tab`() {
        for (kind in SpecialPanes.REACHABLE) {
            assertTrue("$kind opens from the side bar but is not a TabTarget", TabTarget.entries.any { it.kind == kind })
        }
    }

    @Test
    fun `every pinned side-bar entry that opens a tab is a tab`() {
        // About is a page about Filet and Shutdown is an action; neither is somewhere to go back to.
        val notTabs = setOf(SpecialPanes.Pinned.ABOUT, SpecialPanes.Pinned.STOP)
        for (p in SpecialPanes.Pinned.entries - notTabs) {
            assertNotNull("${p.label} is not a TabTarget", TabTarget.entries.firstOrNull { it.label == p.label })
        }
    }

    @Test
    fun `every category is a tab`() {
        for (c in MediaCategory.entries) {
            assertTrue("$c has no TabTarget", TabTarget.entries.any { it.category == c })
        }
    }

    @Test
    fun `every tab has a bar entry that opens it`() {
        for (t in TabTarget.entries) {
            assertEquals(t, BarItem.of(t).tab)
        }
    }

    @Test
    fun `a bar entry that is not a tab is an action the picker lists as one`() {
        for (item in BarItem.entries.filter { it.tab == null }) {
            assertTrue("$item is neither a tab nor in BAR_ACTIONS", item in BAR_ACTIONS)
        }
    }

    @Test
    fun `ids are unique`() {
        val ids = TabTarget.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `ids already stored in bars and pinned icons never change`() {
        // A saved bar and every pinned tab icon hold these. Renaming one strands both.
        val stored = mapOf(
            TabTarget.HOME to "home", TabTarget.FILES to "files", TabTarget.BOOKMARKS to "bookmarks",
            TabTarget.OPENED to "recent", TabTarget.NEW_FILES to "newfiles", TabTarget.PLAYLISTS to "playlists",
            TabTarget.CARRY_ON to "resume", TabTarget.REMOTES to "remotes", TabTarget.SHARE to "sharing",
            TabTarget.SCRIPTS to "scripts", TabTarget.SHORTCUTS to "shortcuts", TabTarget.ACTIVITY to "activity",
            TabTarget.SETTINGS to "settings",
        )
        for ((t, id) in stored) assertEquals(id, t.id)
    }

    @Test
    fun `an unknown id is null, not a crash`() {
        assertEquals(null, TabTarget.byId("teleport"))
        assertEquals(null, TabTarget.byId(null))
    }

    @Test
    fun `internal storage is named for the drive it opens`() {
        assertEquals("Internal storage", TabTarget.FILES.label)
        assertEquals("Internal storage", TabTarget.FILES.home)
        assertEquals("Storage", BarItem.FILES.label)
    }

    @Test
    fun `names that could be another app's start with Filet on the home screen`() {
        for (t in listOf(TabTarget.HOME, TabTarget.SETTINGS, TabTarget.SHARE, TabTarget.PHOTOS, TabTarget.SCAN)) {
            assertTrue("${t.home} could be any app's", t.home.startsWith("Filet "))
        }
    }

    @Test
    fun `every tab is in a group the picker draws`() {
        val grouped = TabGroup.entries.flatMap { TabTarget.inGroup(it) }
        assertEquals(TabTarget.entries.toSet(), grouped.toSet())
    }

    @Test
    fun `every tab and every action has its drawn icon`() {
        // An art id the generated table lacks falls back to the Filet icon without a word, so a
        // tab added without its drawing would ship looking like the app instead of itself.
        val launcher = dev.niccc2007.filet.shortcuts.TabIconRes.LAUNCHER
        val art = dev.niccc2007.filet.shortcuts.TabIconRes.ART
        for (t in TabTarget.entries) {
            assertTrue("${t.name} -> ${t.art}", t.art in launcher && t.art in art)
        }
        for (a in dev.niccc2007.filet.shortcuts.AppAction.entries) {
            assertTrue("${a.name} -> ${a.art}", a.art in launcher && a.art in art)
        }
        assertEquals(TabTarget.entries.size, TabTarget.entries.map { it.art }.toSet().size)
    }

    @Test
    fun `a pane finds its tab for the chip, and a folder has none`() {
        assertEquals(TabTarget.PLAYLISTS, dev.niccc2007.filet.shortcuts.tabOfPane(PaneKind.PLAYLISTS, null))
        assertEquals(TabTarget.VIDEOS, dev.niccc2007.filet.shortcuts.tabOfPane(PaneKind.CATEGORY, MediaCategory.VIDEOS))
        assertEquals(null, dev.niccc2007.filet.shortcuts.tabOfPane(PaneKind.FOLDER, null))
        assertEquals(null, dev.niccc2007.filet.shortcuts.tabOfPane(PaneKind.ABOUT, null))
    }
}

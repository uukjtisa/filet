package dev.niccc2007.filet.shortcuts

import dev.niccc2007.filet.browser.TabTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The held icon's menu, chosen: four at most, known entries only, the share entry saying the truth. */
class HoldMenuTest {

    @Test
    fun `nobody who never chose sees a different menu`() {
        val ids = HoldMenu.normalise(null).map { HoldMenu.entryOf(it, sharing = false)!!.long }
        assertEquals(LauncherShortcuts.actionsFor(sharing = false).map { it.label }, ids)
        assertEquals(HoldMenu.DEFAULT, HoldMenu.normalise(""))
    }

    @Test
    fun `unknown and repeated ids are dropped, and the cap holds`() {
        val stored = "tab:cat.images,gone,tab:cat.images,search,tab:nowhere,index,share,tab:audio"
        assertEquals(listOf("tab:cat.images", "search", "index", "share"), HoldMenu.normalise(stored))
        assertEquals(HoldMenu.DEFAULT, HoldMenu.normalise("gone,also-gone"))
    }

    @Test
    fun `the share entry starts or stops by what is running`() {
        assertEquals(AppAction.SHARE_NEARBY.name, HoldMenu.entryOf(HoldMenu.SHARE, sharing = false)!!.route)
        assertEquals(AppAction.STOP_SHARING.name, HoldMenu.entryOf(HoldMenu.SHARE, sharing = true)!!.route)
    }

    @Test
    fun `a tab opens its tab, under its short name and its own drawing`() {
        val e = HoldMenu.entryOf("tab:files", sharing = false)!!
        assertEquals("tab:files", e.route)
        assertEquals("Storage", e.short)
        assertEquals("Internal storage", e.long)
        assertEquals(TabTarget.FILES.art, e.art)
        assertTrue(e.short.length <= 10)
    }

    @Test
    fun `every choice resolves and has a drawing`() {
        for (id in HoldMenu.CHOICES) {
            val e = HoldMenu.entryOf(id, sharing = false)
            assertTrue(id, e != null && e.art in TabIconRes.LAUNCHER)
        }
        assertEquals(TabTarget.entries.size + 3, HoldMenu.CHOICES.size)
    }

    @Test
    fun `adding past four is refused, and the last entry cannot be taken out`() {
        val full = HoldMenu.DEFAULT
        assertEquals(full, HoldMenu.added(full, "tab:cat.images"))
        val three = HoldMenu.removed(full, HoldMenu.INDEX)
        assertEquals(listOf("search", "tab:bookmarks", "share", "tab:cat.images"), HoldMenu.added(three, "tab:cat.images"))
        assertEquals(listOf("search"), HoldMenu.removed(listOf("search"), "search"))
        assertEquals(three, HoldMenu.added(three, "search"))
    }

    @Test
    fun `moving is exact, and out of range does nothing`() {
        assertEquals(listOf("tab:bookmarks", "search", "share", "index"), HoldMenu.moved(HoldMenu.DEFAULT, 0, 1))
        assertEquals(HoldMenu.DEFAULT, HoldMenu.moved(HoldMenu.DEFAULT, 0, 9))
    }

    @Test
    fun `round trip`() {
        val ids = listOf("tab:playlists", "search")
        assertEquals(ids, HoldMenu.normalise(HoldMenu.encode(ids)))
    }
}

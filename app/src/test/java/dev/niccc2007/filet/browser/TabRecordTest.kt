package dev.niccc2007.filet.browser

import dev.niccc2007.filet.index.Reach
import dev.niccc2007.filet.index.VolumeKey
import dev.niccc2007.filet.vfs.VPath
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A tab saved and read back is the tab that was left, field for field. */
class TabRecordTest {

    private fun p(path: String) = VPath.of("local", "/storage/emulated/0/$path")

    private fun roundTrip(r: TabRecord) = TabCodec.decode(JSONObject(TabCodec.encode(r).toString()))

    @Test
    fun `a tree tab comes back with its folds, focus, scroll and search`() {
        val r = TabRecord(
            kind = PaneKind.FOLDER,
            path = p("Download"),
            folds = listOf(p("Download/chemall"), p("Download/ccvoice")),
            focus = p("Download/chemall"),
            scroll = ScrollMemory.Marks(
                list = ScrollMemory.Mark(17, 33, "local:///storage/emulated/0/Download/chemall/x.pdf@1"),
                grid = ScrollMemory.Mark(4, 0),
            ),
            search = SearchRecord("invoice", Reach.Subtree),
        )
        assertEquals(r, roundTrip(r))
    }

    @Test
    fun `a category tab keeps its category, a special tab keeps its own title`() {
        val cat = TabRecord(kind = PaneKind.CATEGORY, title = "Photos", category = "photos")
        assertEquals(cat, roundTrip(cat))
        // Bug identified here: "Opened" came back as "Recent", the enum's name.
        val opened = TabRecord(kind = PaneKind.RECENT, title = "Opened")
        assertEquals("Opened", roundTrip(opened)?.title)
    }

    @Test
    fun `every reach survives, drives by their roots`() {
        val drives = Reach.Drives(setOf(VolumeKey(VPath.of("local", "/storage/emulated/0")), VolumeKey(VPath.of("dav", "/nas/a"))))
        for (reach in listOf(Reach.Folder, Reach.Subtree, Reach.Everywhere, drives)) {
            val r = TabRecord(kind = PaneKind.FOLDER, path = p("Music"), search = SearchRecord("x", reach))
            assertEquals(reach, roundTrip(r)?.search?.reach)
        }
    }

    @Test
    fun `a record from before these fields existed still reads`() {
        // What the last release wrote. Losing a saved tab to a format change is losing the tab.
        val old = JSONObject("""{"kind":"FOLDER","path":"local:///storage/emulated/0/Download","folds":[]}""")
        val r = TabCodec.decode(old)!!
        assertEquals(PaneKind.FOLDER, r.kind)
        assertEquals(p("Download"), r.path)
        assertNull(r.focus)
        assertNull(r.scroll)
        assertNull(r.search)
        assertNull(r.title)
    }

    @Test
    fun `an unknown kind reads as nothing, so the caller opens Home there`() {
        assertNull(TabCodec.decode(JSONObject("""{"kind":"GONE_IN_THIS_BUILD"}""")))
    }

    @Test
    fun `its history, every scroll it filed and each screen's view come back too`() {
        val r = TabRecord(
            kind = PaneKind.SETTINGS,
            title = "Settings",
            places = listOf(
                "local:///storage/emulated/0/Download" to ScrollMemory.Marks(list = ScrollMemory.Mark(40, 12, "k")),
                "settings:CONTROLS" to ScrollMemory.Marks(list = ScrollMemory.Mark(9, 0)),
                "category:images" to ScrollMemory.Marks(grid = ScrollMemory.Mark(120, 4)),
            ),
            kept = mapOf("settings.group" to "CONTROLS", "playlists.open" to "all-music"),
            back = listOf(
                TabRecord(kind = PaneKind.HOME),
                TabRecord(kind = PaneKind.FOLDER, path = p("Download"), folds = listOf(p("Download/a")), focus = p("Download/a")),
            ),
            forward = listOf(TabRecord(kind = PaneKind.APPS, title = "Apps")),
        )
        assertEquals(r, roundTrip(r))
    }

    @Test
    fun `a history entry this build cannot read is dropped, not the whole history`() {
        val o = JSONObject("""{"kind":"HOME","back":[{"kind":"GONE"},{"kind":"FOLDER","path":"local:///storage/emulated/0/Download"}]}""")
        assertEquals(listOf(PaneKind.FOLDER), TabCodec.decode(o)?.back?.map { it.kind })
    }

    @Test
    fun `a step back is saved as the tab it was, and read back as the same place`() {
        val title: (VPath) -> String = { it.name }
        val folder = PaneState(id = 3, kind = PaneKind.FOLDER, cwd = p("Download/a"), title = "a", open = setOf(p("Download/a/b")), active = p("Download/a/b"))
        val back = TabHistory.stateOf(TabHistory.recordOf(folder), 3, title)!!
        assertEquals(folder.kind, back.kind)
        assertEquals(folder.cwd, back.cwd)
        assertEquals(folder.open, back.open)
        assertEquals(folder.active, back.active)
        assertEquals("a", back.title)

        val photos = dev.niccc2007.filet.home.MediaCategory.IMAGES
        val cat = TabHistory.stateOf(TabHistory.recordOf(PaneState(id = 3, kind = PaneKind.CATEGORY, category = photos, title = photos.label)), 3, title)!!
        assertEquals(photos, cat.category)

        val settings = TabHistory.stateOf(TabHistory.recordOf(PaneState(id = 3, kind = PaneKind.SETTINGS, title = "Settings")), 3, title)!!
        assertEquals("Settings", settings.title)
        assertEquals(PaneKind.HOME, TabHistory.stateOf(TabRecord(kind = PaneKind.HOME), 3, title)!!.kind)
    }

    @Test
    fun `a step back to somewhere that cannot be honoured is dropped`() {
        val title: (VPath) -> String = { it.name }
        assertNull("a folder with no folder", TabHistory.stateOf(TabRecord(kind = PaneKind.FOLDER), 1, title))
        assertNull("a category that is gone", TabHistory.stateOf(TabRecord(kind = PaneKind.CATEGORY, category = "gone"), 1, title))
    }

    @Test
    fun `a blank search is not a search`() {
        val o = JSONObject("""{"kind":"FOLDER","path":"","search":{"q":"  ","reach":{"t":"folder"}}}""")
        assertNull(TabCodec.decode(o)?.search)
    }
}

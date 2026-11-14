package dev.niccc2007.filet.shortcuts

import dev.niccc2007.filet.browser.TabTarget
import dev.niccc2007.filet.icons.COLOUR_IDS
import dev.niccc2007.filet.icons.EMBLEM_IDS
import dev.niccc2007.filet.icons.FolderLook
import dev.niccc2007.filet.icons.FolderPaint
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shortcut sheet's model: how a chosen icon is kept on the record and read back, which
 * sources each kind of shortcut is offered, what a sheet opens with, and its wording.
 */
class ShortcutIconTest {

    private fun p(path: String) = VPath.of("local", path)
    private fun file(name: String, picture: Boolean = false) =
        PinSubject.File(VNode(p("/storage/emulated/0/DCIM/$name"), isDir = false, size = 1, mtime = 0), picture)
    private fun folder(name: String) =
        PinSubject.File(VNode(p("/storage/emulated/0/$name"), isDir = true, size = -1, mtime = 0), picture = false)

    private val emblem = EMBLEM_IDS.first()
    private val colour = COLOUR_IDS.last()

    // ── Kept on the record ──

    @Test fun `every icon reads back as itself`() {
        val all = listOf(
            ShortcutIcon.Picture(framed = false),
            ShortcutIcon.Picture(framed = true),
            ShortcutIcon.Type,
            ShortcutIcon.Folder(null),
            ShortcutIcon.Folder(FolderLook(emblem, colour)),
            ShortcutIcon.Folder(FolderLook(null, "steel", cover = "IMG_2040.jpg")),
            ShortcutIcon.Emblem(emblem, colour),
            ShortcutIcon.Emblem(null, "steel"),
            ShortcutIcon.Drawing(TabTarget.FILES.art),
            ShortcutIcon.Filet,
            ShortcutIcon.Simple,
        )
        for (icon in all) assertEquals(icon.encode(), icon, ShortcutIcon.parse(icon.encode()))
    }

    @Test fun `a cover name with a colon in it survives`() {
        val icon = ShortcutIcon.Folder(FolderLook(emblem, colour, cover = "12:30 shot.jpg"))
        assertEquals(icon, ShortcutIcon.parse(icon.encode()))
    }

    @Test fun `an own folder keeps Show emblem and its paint, with a cover after them`() {
        for (look in listOf(
            FolderLook(emblem, colour, showEmblem = false),
            FolderLook(emblem, colour, paint = FolderPaint.WHOLE),
            FolderLook(emblem, colour, cover = "12:30 shot.jpg", showEmblem = false, paint = FolderPaint.EMBLEM),
        )) {
            val icon = ShortcutIcon.Folder(look)
            assertEquals(icon.encode(), icon, ShortcutIcon.parse(icon.encode()))
        }
        // A spec from before the flags reads as it always did.
        assertEquals(
            ShortcutIcon.Folder(FolderLook(emblem, colour, cover = "a.jpg")),
            ShortcutIcon.parse("folder:own:$emblem:$colour:a.jpg"),
        )
    }

    @Test fun `what this build cannot draw reads as nothing, so the kind's own icon is used`() {
        for (raw in listOf(
            null, "", "  ", "sparkles", "picture", "picture:tiled", "type:pdf",
            "folder", "folder:other", "folder:own:no-such-emblem:steel", "folder:own:-:no-such-colour",
            "emblem:no-such-emblem:steel", "emblem:-:no-such-colour", "emblem:-", "emblem:-:steel:extra",
            "art:no-such-drawing", "art", "filet:extra", "simple:extra",
        )) assertNull(raw, ShortcutIcon.parse(raw))
    }

    // ── What each kind is offered ──

    @Test fun `a photo is offered its picture first`() {
        assertEquals(
            listOf(IconSource.PICTURE, IconSource.TYPE, IconSource.EMBLEM, IconSource.DRAWING, IconSource.FILET),
            sourcesFor(file("IMG_2040.jpg", picture = true), simple = false),
        )
    }

    @Test fun `a file with no picture starts on its type`() {
        assertEquals(
            listOf(IconSource.TYPE, IconSource.EMBLEM, IconSource.DRAWING, IconSource.FILET),
            sourcesFor(file("Notes.pdf"), simple = false),
        )
    }

    @Test fun `a folder starts on the folder and has no type`() {
        assertEquals(
            listOf(IconSource.FOLDER, IconSource.EMBLEM, IconSource.DRAWING, IconSource.FILET),
            sourcesFor(folder("Camera"), simple = false),
        )
    }

    @Test fun `a tab, an action and a script start on their drawing`() {
        val want = listOf(IconSource.DRAWING, IconSource.EMBLEM, IconSource.FILET)
        assertEquals(want, sourcesFor(PinSubject.Tab(TabTarget.PLAYLISTS), simple = false))
        assertEquals(want, sourcesFor(PinSubject.Action(AppAction.SEARCH), simple = false))
    }

    @Test fun `Simple is offered to every kind, last, and never first`() {
        for (s in listOf(folder("Camera"), file("Notes.pdf"), file("IMG_2040.jpg", picture = true), PinSubject.Tab(TabTarget.PLAYLISTS), PinSubject.Action(AppAction.SEARCH))) {
            val sources = sourcesFor(s)
            assertEquals(s.toString(), IconSource.SIMPLE, sources.last())
            assertTrue(s.toString(), sources.first() != IconSource.SIMPLE)
        }
        assertFalse(IconSource.SIMPLE in sourcesFor(folder("Camera"), simple = false))
    }

    @Test fun `a new sheet never starts on Simple, an edited one wearing it opens on it`() {
        assertTrue(IconDraft.start(PinSubject.Tab(TabTarget.PLAYLISTS)).source != IconSource.SIMPLE)
        assertEquals(ShortcutIcon.Simple, IconDraft.start(PinSubject.Tab(TabTarget.PLAYLISTS), current = ShortcutIcon.Simple).icon())
    }

    // ── What a sheet opens with ──

    @Test fun `a new sheet starts on the first source, with the subject's own drawing`() {
        val tab = IconDraft.start(PinSubject.Tab(TabTarget.PLAYLISTS))
        assertEquals(IconSource.DRAWING, tab.source)
        assertEquals(ShortcutIcon.Drawing(TabTarget.PLAYLISTS.art), tab.icon())
        assertEquals(ShortcutIcon.Type, IconDraft.start(file("Notes.pdf")).icon())
        assertEquals(ShortcutIcon.Folder(null), IconDraft.start(folder("Camera")).icon())
    }

    @Test fun `a folder's emblem and colour start from the look it wears in Filet`() {
        val look = FolderLook(emblem, colour)
        val d = IconDraft.start(folder("Camera"), folderLook = look)
        assertEquals(look, d.look)
        assertEquals(emblem, d.emblem)
        assertEquals(colour, d.colour)
        assertEquals(TabTarget.FILES.art, d.art)
    }

    @Test fun `a see-through picture starts framed`() {
        assertTrue(IconDraft.start(file("app.png", picture = true), seeThrough = true).framed)
        assertFalse(IconDraft.start(file("IMG_2040.jpg", picture = true)).framed)
    }

    @Test fun `editing opens on the icon the shortcut wears`() {
        val worn = ShortcutIcon.Emblem(emblem, colour)
        val d = IconDraft.start(PinSubject.Tab(TabTarget.PLAYLISTS), current = worn)
        assertEquals(IconSource.EMBLEM, d.source)
        assertEquals(worn, d.icon())
        // The other sources keep what suits the subject, for switching back.
        assertEquals(TabTarget.PLAYLISTS.art, d.art)
    }

    @Test fun `a worn icon the subject is not offered is ignored`() {
        val d = IconDraft.start(file("Notes.pdf"), current = ShortcutIcon.Picture(framed = true))
        assertEquals(ShortcutIcon.Type, d.icon())
    }

    @Test fun `a folder's own look is kept apart from the folder's`() {
        val own = FolderLook(emblem, colour)
        val d = IconDraft.start(folder("Camera"), current = ShortcutIcon.Folder(own))
        assertTrue(d.folderOwn)
        assertEquals(ShortcutIcon.Folder(own), d.icon())
        assertEquals(ShortcutIcon.Folder(null), d.copy(folderOwn = false).icon())
    }

    // ── Wording ──

    @Test fun `a file's folder is named from the top of the phone's storage`() {
        assertEquals("DCIM/Camera", whereOf("/storage/emulated/0/DCIM/Camera"))
        assertEquals("Internal storage", whereOf("/storage/emulated/0"))
        assertEquals("Internal storage", whereOf("/storage/emulated/10/"))
        assertEquals("storage/1234-5678/Music", whereOf("/storage/1234-5678/Music"))
        assertEquals("", whereOf(null))
    }

    @Test fun `the title line says where a file is, or what the thing is`() {
        assertEquals("DCIM/IMG_2040.jpg", sheetSub(file("IMG_2040.jpg"), editing = false, where = "DCIM"))
        assertEquals("Tab · ${TabTarget.PLAYLISTS.home}", sheetSub(PinSubject.Tab(TabTarget.PLAYLISTS), editing = false, where = null))
        assertEquals("On your home screen", sheetSub(PinSubject.Tab(TabTarget.PLAYLISTS), editing = true, where = null))
    }

    @Test fun `the share actions each say what a tap does, since a placed icon cannot flip`() {
        assertEquals("Starts sharing and shows the code.", opensLine(PinSubject.Action(AppAction.SHARE_NEARBY)))
        assertEquals("Stops sharing without opening the tab.", opensLine(PinSubject.Action(AppAction.STOP_SHARING)))
        assertEquals("Opens Filet on ${TabTarget.PLAYLISTS.label}.", opensLine(PinSubject.Tab(TabTarget.PLAYLISTS)))
    }

    @Test fun `a folder has no opener to choose, a file starts on Default`() {
        assertTrue(openersFor(folder("Camera").node).isEmpty())
        val openers = openersFor(file("Notes.pdf").node)
        assertEquals(null to "Default", openers.first())
        assertEquals(openers.size, openers.map { it.first }.distinct().size)
    }

    // ── The chooser ──

    @Test fun `the chooser lists every tab, the actions, and ends on the file and folder rows`() {
        val rows = chooserRows(emptyList())
        val tabs = rows.filterIsInstance<ChooserRow.Pick>().map { it.subject }.filterIsInstance<PinSubject.Tab>()
        assertEquals(TabTarget.entries.toSet(), tabs.map { it.target }.toSet())
        assertTrue(rows.contains(ChooserRow.Heading("Actions")))
        assertFalse("no scripts, no heading", rows.contains(ChooserRow.Heading("Scripts")))
        assertEquals(BROWSE_ROWS, rows.takeLast(BROWSE_ROWS.size))
        assertEquals(ChooserRow.Heading("Files and folders"), rows[rows.size - BROWSE_ROWS.size - 1])
    }

    @Test fun `the Drawing grid leaves out Stop sharing, which only the share flip shows`() {
        assertFalse(AppAction.STOP_SHARING.art in DRAWINGS)
        assertTrue(TabTarget.entries.all { it.art in DRAWINGS })
    }
}

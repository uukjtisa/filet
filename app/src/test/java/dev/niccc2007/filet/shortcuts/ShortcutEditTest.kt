package dev.niccc2007.filet.shortcuts

import dev.niccc2007.filet.browser.TabTarget
import dev.niccc2007.filet.script.Script
import dev.niccc2007.filet.script.ScriptPermissions
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edit on a placed shortcut, for every kind and every record however old.
 *
 * Records kept before the shortcut sheet have no icon, and file records from before the list knew
 * folders from files have no `dir`. Each is read exactly as an older build wrote it.
 */
class ShortcutEditTest {

    private val camera = VPath.of("local", "/storage/emulated/0/DCIM/Camera")
    private val moved = VPath.of("local", "/storage/emulated/0/Pictures/Camera")
    private val report = VPath.of("local", "/storage/emulated/0/Documents/Report.pdf")

    /** One record as an older build stored it: no icon, no dir, no unconfirmed. */
    private fun old(id: String, kind: String, label: String, target: String) = ShortcutStore.decode(
        """[{"id":"$id","kind":"$kind","label":"$label","target":"$target","handler":null,"at":1}]""",
    ).single()

    private val oldFolder = old("id:42", "FILE", "Camera", camera.toString())
    private val oldFile = old("path:$report", "FILE", "Report.pdf", report.toString())
    private val oldTab = old("tab:playlists", "TAB", "Playlists", "playlists")
    private val oldAction = old("action:BOOKMARKS", "ACTION", "Bookmarks", "BOOKMARKS")
    private val oldScript = old("script:s1", "SCRIPT", "Back up DCIM", "s1")

    private val script = Script("s1", "Back up DCIM", "", ScriptPermissions(), "f", 0L)
    private fun node(p: VPath, dir: Boolean) = VNode(p, isDir = dir, size = if (dir) -1 else 1, mtime = 0)

    // ── The record as kept ──

    @Test fun `an old record reads with no icon and no dir`() {
        for (r in listOf(oldFolder, oldFile, oldTab, oldAction, oldScript)) {
            assertNull(r.id, r.icon)
            assertNull(r.id, r.dir)
        }
    }

    @Test fun `a file shortcut's id gives its file id, a path one and every other kind none`() {
        assertEquals(42L, oldFolder.fileId)
        assertNull(oldFile.fileId)
        assertNull(oldTab.fileId)
        assertNull(ShortcutRecord("id:7", ShortcutKind.TAB, "x", "x").fileId)
    }

    // ── Finding the file ──

    @Test fun `a folder moved since it was pinned is found by its file id`() = runBlocking {
        val found = findForEdit(oldFolder, byId = { if (it == 42L) moved else null }) { p ->
            if (p == moved) node(moved, dir = true) else null
        }
        assertEquals(moved, found?.path)
    }

    @Test fun `when the index does not know the id, where it was pinned is looked at`() = runBlocking {
        val found = findForEdit(oldFolder, byId = { null }) { p -> if (p == camera) node(camera, dir = true) else null }
        assertEquals(camera, found?.path)
    }

    @Test fun `a shortcut pinned by path is found at its path`() = runBlocking {
        val asked = ArrayList<Long>()
        val found = findForEdit(oldFile, byId = { asked += it; null }) { p -> if (p == report) node(report, dir = false) else null }
        assertEquals(report, found?.path)
        assertTrue("a path shortcut has no id to look up", asked.isEmpty())
    }

    @Test fun `a file gone from both places is gone`() = runBlocking {
        assertNull(findForEdit(oldFolder, byId = { moved }) { null })
    }

    // ── What the sheet edits ──

    @Test fun `every kind kept by an older build opens the sheet on its own icon`() {
        val folder = PinSubject.File(node(camera, dir = true), picture = false)
        val file = PinSubject.File(node(report, dir = false), picture = false)
        val cases = listOf(
            oldFolder to editSubject(oldFolder, folder) { null },
            oldFile to editSubject(oldFile, file) { null },
            oldTab to editSubject(oldTab, null) { null },
            oldAction to editSubject(oldAction, null) { null },
            oldScript to editSubject(oldScript, null) { if (it == "s1") script else null },
        )
        for ((record, subject) in cases) {
            requireNotNull(subject) { record.id }
            assertEquals(record.id, record.kind, subject.kind)
            // As the sheet starts it for an edit: with the icon the record wears, here none.
            val draft = IconDraft.start(subject, current = ShortcutIcon.parse(record.icon))
            assertEquals(record.id, defaultIcon(subject), draft.icon())
        }
        assertEquals(PinSubject.Tab(TabTarget.PLAYLISTS), editSubject(oldTab, null) { null })
        assertEquals(PinSubject.Action(AppAction.BOOKMARKS), editSubject(oldAction, null) { null })
    }

    @Test fun `what is gone gives no sheet`() {
        assertNull(editSubject(oldFolder, null) { null })
        assertNull(editSubject(oldScript, null) { null })
        assertNull(editSubject(old("tab:no-such-tab", "TAB", "x", "no-such-tab"), null) { null })
        assertNull(editSubject(old("action:NO_SUCH", "ACTION", "x", "NO_SUCH"), null) { null })
    }
}

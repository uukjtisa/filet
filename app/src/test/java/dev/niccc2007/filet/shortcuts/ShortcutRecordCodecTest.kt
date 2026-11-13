package dev.niccc2007.filet.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Shortcuts tab's records as kept, including ones written before a field existed. */
class ShortcutRecordCodecTest {

    @Test fun `a record written before icons were chosen reads with no icon, so it wears its kind's own`() {
        // As a build before the shortcut sheet wrote it: no "icon" field at all.
        val old = """[{"id":"tab:playlists","kind":"TAB","label":"Playlists","target":"playlists","at":1731500000000,"unconfirmed":false}]"""
        val r = ShortcutStore.decode(old).single()
        assertEquals("tab:playlists", r.id)
        assertEquals(ShortcutKind.TAB, r.kind)
        assertEquals("Playlists", r.label)
        assertNull(r.icon)
        assertNull(r.dir)
        assertNull(ShortcutIcon.parse(r.icon))
    }

    @Test fun `a chosen icon is kept and read back`() {
        val r = ShortcutRecord(
            id = "id:42", kind = ShortcutKind.FILE, label = "Camera", target = "local:/storage/emulated/0/DCIM/Camera",
            createdAt = 1L, dir = true, icon = ShortcutIcon.Folder(null).encode(),
        )
        assertEquals(listOf(r), ShortcutStore.decode(ShortcutStore.encode(listOf(r))))
    }

    @Test fun `no icon writes no field`() {
        val r = ShortcutRecord(id = "action:SEARCH", kind = ShortcutKind.ACTION, label = "Search", target = "SEARCH", createdAt = 1L)
        assertEquals(false, ShortcutStore.encode(listOf(r)).contains("\"icon\""))
        assertEquals(listOf(r), ShortcutStore.decode(ShortcutStore.encode(listOf(r))))
    }
}

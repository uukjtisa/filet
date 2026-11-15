package dev.niccc2007.filet.locker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Locker's index: its tree, where things go back to, and what survives a save. */
class LockerIndexTest {

    private fun sample() = LockerIndex().apply {
        put(LockerIndex.Entry("/Trips", dir = true, origin = "local:///storage/emulated/0/DCIM/Trips"))
        put(LockerIndex.Entry("/Trips/a.jpg", dir = false, blob = "b1", size = 10))
        put(LockerIndex.Entry("/Trips/Day 2", dir = true))
        put(LockerIndex.Entry("/Trips/Day 2/b.jpg", dir = false, blob = "b2", size = 20))
        put(LockerIndex.Entry("/note.pdf", dir = false, blob = "b3", size = 5, origin = "local:///storage/emulated/0/Download/note.pdf"))
        put(LockerIndex.Entry("/made here.txt", dir = false, blob = "b4", size = 1))
    }

    @Test fun `a folder lists only its own entries`() {
        val i = sample()
        assertEquals(setOf("Trips", "note.pdf", "made here.txt"), i.children("/").map { it.name }.toSet())
        assertEquals(setOf("a.jpg", "Day 2"), i.children("/Trips").map { it.name }.toSet())
        assertEquals(listOf("b.jpg"), i.children("/Trips/Day 2/").map { it.name })
    }

    @Test fun `a file inside a folder that came in goes back under that folder`() {
        val i = sample()
        assertEquals("local:///storage/emulated/0/DCIM/Trips/Day 2/b.jpg", i.originOf("/Trips/Day 2/b.jpg"))
        assertEquals("local:///storage/emulated/0/Download/note.pdf", i.originOf("/note.pdf"))
        assertNull(i.originOf("/made here.txt"))
    }

    @Test fun `a move takes everything inside along and keeps where it came from`() {
        val i = sample()
        i.put(LockerIndex.Entry("/Old", dir = true))
        i.move("/Trips", "/Old/Trips 2024")
        assertNull(i.get("/Trips/a.jpg"))
        assertEquals("b2", i.get("/Old/Trips 2024/Day 2/b.jpg")?.blob)
        assertEquals("local:///storage/emulated/0/DCIM/Trips/Day 2/b.jpg", i.originOf("/Old/Trips 2024/Day 2/b.jpg"))
    }

    @Test fun `removing a folder hands back every blob inside it`() {
        val i = sample()
        assertEquals(setOf("b1", "b2"), i.remove("/Trips").toSet())
        assertNull(i.get("/Trips/Day 2"))
        assertEquals(6L, i.bytes())
    }

    @Test fun `a folder cannot go inside itself, and nothing lands on something`() {
        val i = sample()
        assertTrue(runCatching { i.move("/Trips", "/Trips/Day 2/Trips") }.isFailure)
        assertTrue(runCatching { i.move("/note.pdf", "/made here.txt") }.isFailure)
        assertTrue(runCatching { i.put(LockerIndex.Entry("/Nowhere/x", dir = false)) }.isFailure)
    }

    @Test fun `what is saved is what is read back, the history included`() {
        val i = sample()
        i.note(LockerIndex.Event("note.pdf", "Download", 7, released = false))
        i.note(LockerIndex.Event("a.jpg", "DCIM/Trips", 9, released = true))
        val back = LockerIndex.decode(i.encode())
        assertEquals(i.files().toSet(), back.files().toSet())
        assertEquals(i.originOf("/Trips/Day 2/b.jpg"), back.originOf("/Trips/Day 2/b.jpg"))
        assertEquals(listOf("a.jpg", "note.pdf"), back.history().map { it.name })
    }

    @Test fun `the history keeps the newest`() {
        val i = LockerIndex()
        repeat(LockerIndex.HISTORY + 5) { i.note(LockerIndex.Event("f$it", "", it.toLong(), false)) }
        assertEquals(LockerIndex.HISTORY, i.history().size)
        assertEquals("f${LockerIndex.HISTORY + 4}", i.history().first().name)
    }
}

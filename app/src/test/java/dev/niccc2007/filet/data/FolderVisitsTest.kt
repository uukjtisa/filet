package dev.niccc2007.filet.data

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderVisitsTest {

    private val a = VPath.of("local", "/storage/emulated/0/A")
    private val b = VPath.of("local", "/storage/emulated/0/B")

    @Test
    fun `a visit adds one and moves the folder to the front`() {
        var v = FolderVisits.bump(emptyList(), a, "A", now = 1)
        v = FolderVisits.bump(v, b, "B", now = 2)
        v = FolderVisits.bump(v, a, "A", now = 3)
        assertEquals(listOf(a, b), v.map { it.path })
        assertEquals(2, v.first().visits)
        assertEquals(3L, v.first().at)
    }

    @Test
    fun `the oldest folder falls off past the cap`() {
        var v = emptyList<RecentEntry>()
        for (i in 1..5) v = FolderVisits.bump(v, VPath.of("local", "/f$i"), "f$i", now = i.toLong(), cap = 3)
        assertEquals(listOf("f5", "f4", "f3"), v.map { it.label })
    }
}

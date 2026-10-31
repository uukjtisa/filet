package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickAccessTest {

    private fun e(name: String, visits: Int = 0, at: Long = 0, added: Long = 0) =
        QuickAccess.Entry(VPath.of("local", "/storage/emulated/0/$name"), name, added, visits, at)

    @Test
    fun `added by hand comes first, in the order added, whatever the counts`() {
        val out = QuickAccess.quick(
            listOf(e("busy", visits = 900), e("second", added = 20), e("first", added = 10)),
        )
        assertEquals(listOf("first", "second", "busy"), out.map { it.label })
    }

    @Test
    fun `the rest are ranked by use, and recency breaks a tie`() {
        val out = QuickAccess.quick(
            listOf(e("older", visits = 3, at = 100), e("newer", visits = 3, at = 900), e("most", visits = 9, at = 1)),
        )
        assertEquals(listOf("most", "newer", "older"), out.map { it.label })
    }

    @Test
    fun `used folders fill the slots and stop there`() {
        val many = (1..30).map { e("f$it", visits = it, at = it.toLong()) }
        val out = QuickAccess.quick(many)
        assertEquals(QuickAccess.SLOTS, out.size)
        assertEquals("f30", out.first().label)
    }

    @Test
    fun `added by hand is never cut, and takes slots from the used ones`() {
        val hand = (1..8).map { e("h$it", added = it.toLong()) }
        val used = (1..5).map { e("u$it", visits = it) }
        assertEquals(hand.map { it.label }, QuickAccess.quick(hand + used).map { it.label })
        val two = QuickAccess.quick(hand.take(2) + used)
        assertEquals(QuickAccess.SLOTS, two.size)
        assertEquals(listOf("h1", "h2", "u5", "u4", "u3", "u2"), two.map { it.label })
    }

    @Test
    fun `an unused bookmark still fills an empty slot, in bookmark order`() {
        val out = QuickAccess.quick(listOf(e("a"), e("b"), e("c")))
        assertEquals(listOf("a", "b", "c"), out.map { it.label })
    }

    @Test
    fun `opening a file counts for its folder, opening a folder counts for nothing`() {
        val photo = VPath.of("local", "/storage/emulated/0/DCIM/Camera/a.jpg")
        assertEquals(VPath.of("local", "/storage/emulated/0/DCIM/Camera"), QuickAccess.usedBy(photo, isDir = false))
        assertNull(QuickAccess.usedBy(VPath.of("local", "/storage/emulated/0/Android"), isDir = true))
    }

    @Test
    fun `nothing inside an archive or an APK is counted`() {
        assertNull(QuickAccess.usedBy(VPath.of("zip", "/a/b.txt"), isDir = false))
        assertNull(QuickAccess.workedIn(VPath.of("apk", "/res")))
        assertEquals(VPath.of("local", "/x"), QuickAccess.workedIn(VPath.of("local", "/x")))
    }

    @Test
    fun `no bookmarks is an empty list`() {
        assertEquals(emptyList<QuickAccess.Entry>(), QuickAccess.quick(emptyList()))
    }
}

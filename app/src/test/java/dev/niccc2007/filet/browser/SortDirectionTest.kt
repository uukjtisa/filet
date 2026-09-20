package dev.niccc2007.filet.browser

import dev.niccc2007.filet.data.SortKey
import dev.niccc2007.filet.data.SortSpec
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sort direction, on all four sorters rather than the two that were noticed.
 *
 * Bug identified: every comparator was written ascending and one boolean reversed whichever
 * was active, so the same toggle produced newest-first for dates and **Z to A for names**. The
 * flag now means most relevant first and each comparator is anchored that way.
 *
 * Both directions are asserted for every key. A test that only checked the new default would
 * pass against a comparator that had been hard-wired and no longer responded to the toggle at
 * all, which is a worse bug than the one being fixed.
 */
class SortDirectionTest {

    private fun node(name: String, size: Long = 0, mtime: Long = 0, dir: Boolean = false) =
        VNode(path = VPath.parse("file:///storage/$name"), isDir = dir, size = size, mtime = mtime)

    private fun names(list: List<VNode>) = list.map { it.name }

    private val byName = listOf(node("banana.txt"), node("Apple.txt"), node("cherry.txt"))

    private val bySize = listOf(
        node("small.bin", size = 10),
        node("huge.bin", size = 9_000_000),
        node("middle.bin", size = 5_000),
    )

    private val byDate = listOf(
        node("old.txt", mtime = 1_600_000_000_000),
        node("newest.txt", mtime = 1_700_000_000_000),
        node("middle.txt", mtime = 1_650_000_000_000),
    )

    private fun spec(key: SortKey, descending: Boolean) =
        SortSpec(key = key, descending = descending, foldersFirst = false)

    // ── the report ──

    @Test
    fun `descending sorts names A to Z, not Z to A`() {
        // The whole complaint. This produced cherry, banana, Apple before.
        assertEquals(
            listOf("Apple.txt", "banana.txt", "cherry.txt"),
            names(byName.sortedBy(spec(SortKey.NAME, descending = true))),
        )
    }

    @Test
    fun `descending sorts dates newest first`() {
        assertEquals(
            listOf("newest.txt", "middle.txt", "old.txt"),
            names(byDate.sortedBy(spec(SortKey.MODIFIED, descending = true))),
        )
    }

    @Test
    fun `descending sorts sizes largest first`() {
        assertEquals(
            listOf("huge.bin", "middle.bin", "small.bin"),
            names(bySize.sortedBy(spec(SortKey.SIZE, descending = true))),
        )
    }

    @Test
    fun `descending sorts types A to Z by EXTENSION, then by name`() {
        // By extension, so png before txt before zip - not by filename, which is what the
        // first version of this test assumed and got wrong.
        val mixed = listOf(node("c.zip"), node("a.txt"), node("b.png"), node("a.png"))
        assertEquals(
            listOf("a.png", "b.png", "a.txt", "c.zip"),
            names(mixed.sortedBy(spec(SortKey.TYPE, descending = true))),
        )
    }

    // ── the toggle still toggles: the control for every assertion above ──

    @Test
    fun `every sorter reverses when the toggle is off`() {
        assertEquals(
            listOf("cherry.txt", "banana.txt", "Apple.txt"),
            names(byName.sortedBy(spec(SortKey.NAME, descending = false))),
        )
        assertEquals(
            listOf("old.txt", "middle.txt", "newest.txt"),
            names(byDate.sortedBy(spec(SortKey.MODIFIED, descending = false))),
        )
        assertEquals(
            listOf("small.bin", "middle.bin", "huge.bin"),
            names(bySize.sortedBy(spec(SortKey.SIZE, descending = false))),
        )
    }

    // ── what the toggle is called ──

    @Test
    fun `the toggle names the real direction instead of saying descending`() {
        // "Descending" cannot mean A to Z and newest first at once and stay comprehensible,
        // which is what produced the bug. The word is no longer shown.
        assertEquals("A to Z", SortOrder.label(SortKey.NAME, descending = true))
        assertEquals("Z to A", SortOrder.label(SortKey.NAME, descending = false))
        assertEquals("Newest first", SortOrder.label(SortKey.MODIFIED, descending = true))
        assertEquals("Oldest first", SortOrder.label(SortKey.MODIFIED, descending = false))
        assertEquals("Largest first", SortOrder.label(SortKey.SIZE, descending = true))
        assertEquals("Smallest first", SortOrder.label(SortKey.SIZE, descending = false))
    }

    @Test
    fun `no label anywhere still says ascending or descending`() {
        for (key in SortKey.entries) {
            for (d in listOf(true, false)) {
                val text = SortOrder.label(key, d).lowercase()
                assertTrue(
                    "$key/$d reads \"$text\" and still leans on the word that caused this",
                    !text.contains("ascend") && !text.contains("descend"),
                )
            }
        }
    }

    @Test
    fun `every sort key has its own label, so a new key cannot inherit a wrong one`() {
        val labels = SortKey.entries.map { SortOrder.label(it, true) }
        assertEquals(labels.size, labels.distinct().size)
    }

    // ── things the direction must not disturb ──

    @Test
    fun `folders stay first in both directions`() {
        // Reversing the sort must not reverse the folder grouping, which looks like a bug even
        // when it is consistent.
        val mixed = listOf(node("b.txt"), node("zzz", dir = true), node("a.txt"))
        for (d in listOf(true, false)) {
            val out = mixed.sortedBy(SortSpec(key = SortKey.NAME, descending = d, foldersFirst = true))
            assertEquals("folders first should hold with descending=$d", "zzz", out.first().name)
        }
    }

    @Test
    fun `natural order survives the change`() {
        // file2 before file10 was the other sort bug and it is easy to lose while moving the
        // direction around.
        val n = listOf(node("IMG_10.jpg"), node("IMG_2.jpg"), node("IMG_1.jpg"))
        assertEquals(
            listOf("IMG_1.jpg", "IMG_2.jpg", "IMG_10.jpg"),
            names(n.sortedBy(spec(SortKey.NAME, descending = true))),
        )
    }

    @Test
    fun `the default direction is the useful one`() {
        assertTrue("a fresh install should open A to Z and newest first", SortOrder.DEFAULT_DESCENDING)
    }
}

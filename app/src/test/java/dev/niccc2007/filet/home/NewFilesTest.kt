package dev.niccc2007.filet.home

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * New files, device-wide: the order, the paging, the cap, and the query's shape.
 *
 * The list is read from the media index in pages; the decisions worth pinning are which rows
 * come back in which order, when the next page is allowed, and that folders and hidden files
 * never become rows.
 */
class NewFilesTest {

    private fun item(name: String, added: Long) = FeedItem(
        node = VNode(path = VPath.of("local", "/storage/emulated/0/Download/$name"), isDir = false, size = 1, mtime = 0),
        at = 0,
        added = added,
    )

    // ── the query ──

    @Test
    fun `newest first, with the id breaking ties so a page boundary is stable`() {
        assertEquals("date_added DESC, _id DESC", CategoryQuery.NEW_ORDER)
    }

    @Test
    fun `folders and hidden files are excluded, parameterised`() {
        val (sel, args) = CategoryQuery.newSelection(before = null)
        assertTrue(sel.contains("mime_type IS NOT NULL"))
        assertTrue(sel.contains("_display_name NOT LIKE ?"))
        assertEquals(listOf(".%"), args.toList())
        assertEquals(args.size, sel.count { it == '?' })
    }

    @Test
    fun `a date jump is a range in seconds, not milliseconds`() {
        // DATE_ADDED is seconds. Comparing it with milliseconds puts every file "before" any
        // date, and the jump would show the newest files instead of the day asked for.
        val (sel, args) = CategoryQuery.newSelection(before = 1_700_000_123_456L)
        assertTrue(sel.endsWith("date_added < ?"))
        assertEquals("1700000123", args.last())
        assertEquals(args.size, sel.count { it == '?' })
    }

    // ── paging ──

    @Test
    fun `the next page starts where the loaded rows end`() {
        assertEquals(0, NewFilesPaging.nextOffset(0, exhausted = false, cap = 10_000))
        assertEquals(2_000, NewFilesPaging.nextOffset(2_000, exhausted = false, cap = 10_000))
    }

    @Test
    fun `no page past the cap, and none once the index has run out`() {
        assertNull(NewFilesPaging.nextOffset(10_000, exhausted = false, cap = 10_000))
        assertNull(NewFilesPaging.nextOffset(1_234, exhausted = true, cap = 10_000))
    }

    @Test
    fun `a merged page keeps the database's order and drops a row seen twice`() {
        // A file that arrives between two pages shifts every offset by one, so the last row of
        // page one comes back as the first of page two.
        val one = listOf(item("c", 3), item("b", 2))
        val two = listOf(item("b", 2), item("a", 1))
        val merged = NewFilesPaging.merge(one, two)
        assertEquals(listOf("c", "b", "a"), merged.map { it.node.name })
    }

    @Test
    fun `merging is not sorting - the order given is the order kept`() {
        // The database orders by DATE_ADDED then _id. Re-sorting here by `added` alone would
        // reorder equal-second rows differently from the query and make rows swap on refresh.
        val one = listOf(item("x", 5), item("y", 5))
        val merged = NewFilesPaging.merge(one, listOf(item("z", 5)))
        assertEquals(listOf("x", "y", "z"), merged.map { it.node.name })
    }

    // ── what the retirement leaves behind ──

    @Test
    fun `every key the tracked-folder feature wrote is on the removal list`() {
        val retired = HomeFeed.RETIRED_KEYS.toSet()
        for (k in listOf("tracked.folders", "tracked.seeded", "tracked.prunedRemotes", "tracked.seededCommon", "home.firstSeen.v4", "home.feed.v1")) {
            assertTrue("$k would be left stranded", k in retired)
        }
    }
}

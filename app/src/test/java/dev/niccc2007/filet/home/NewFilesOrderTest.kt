package dev.niccc2007.filet.home

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The New files tab's two lists - by date added and by date modified - and Filet's memory
 * correcting an arrival date.
 *
 * The fault these exist for: "Last changed" re-sorted the newest ARRIVALS by mtime, so a file
 * that arrived long ago and was edited today never appeared in it at all.
 */
class NewFilesOrderTest {

    @Test
    fun `each list is its own query, ordered by its own column`() {
        assertEquals("date_added DESC, _id DESC", CategoryQuery.orderFor(HistorySort.DATE_ADDED))
        assertEquals("date_modified DESC, _id DESC", CategoryQuery.orderFor(HistorySort.DATE_MODIFIED))
    }

    @Test
    fun `the date jump windows on the column the list is ordered by`() {
        val (added, _) = CategoryQuery.newSelection(1_700_000_000_000L, by = HistorySort.DATE_ADDED)
        val (modified, args) = CategoryQuery.newSelection(1_700_000_000_000L, by = HistorySort.DATE_MODIFIED)
        assertTrue(added.contains("date_added < ?"))
        assertTrue(modified.contains("date_modified < ?"))
        assertTrue("seconds, like the column", args.contains("1700000000"))
    }

    @Test
    fun `the modified list keeps the folder and prefix exclusions`() {
        val (sql, args) = CategoryQuery.newSelection(null, setOf("/a"), setOf("log_"), HistorySort.DATE_MODIFIED)
        assertTrue(sql.contains("NOT LIKE ? ESCAPE"))
        assertTrue(args.any { it.startsWith("/a/") })
    }

    // ── Filet's memory, applied to a row ──

    private fun item(added: Long, at: Long = added, by: HistorySort = HistorySort.DATE_ADDED) =
        FeedItem(VNode(VPath.of("local", "/x/$added"), false, 1, at), at = at, added = added, by = by)

    @Test
    fun `re-saved on the 10th, remembered from the 7th, is listed on the 7th`() {
        val r = NewFilesPaging.remember(item(added = 10), firstSeen = 7)
        assertEquals(7L, r.arrived)
        assertEquals(7L, r.listedAt)
        assertEquals("the query's own date is untouched, so paging continues correctly", 10L, NewFilesPaging.dbTime(r, HistorySort.DATE_ADDED))
    }

    @Test
    fun `a later memory never moves a date - the system already knew it was here`() {
        assertNull(NewFilesPaging.remember(item(added = 5), firstSeen = 9).remembered)
        assertNull(NewFilesPaging.remember(item(added = 5), firstSeen = null).remembered)
        assertNull(NewFilesPaging.remember(item(added = 5), firstSeen = 0).remembered)
    }

    @Test
    fun `the modified list is listed by modification time, never by memory`() {
        val r = item(added = 3, at = 20, by = HistorySort.DATE_MODIFIED).copy(remembered = 1)
        assertEquals(20L, r.listedAt)
        assertEquals(20L, NewFilesPaging.dbTime(r, HistorySort.DATE_MODIFIED))
    }

    @Test
    fun `the Home card shows the newest by the date listed, not a re-dated old file`() {
        val reSaved = NewFilesPaging.remember(item(added = 100), firstSeen = 1)
        val fresh = item(added = 90)
        val card = NewFilesPaging.cardRows(listOf(reSaved, fresh), 1)
        assertEquals(listOf(fresh), card)
    }

    @Test
    fun `the setting stores words, and anything unknown reads as date added`() {
        assertEquals(HistorySort.DATE_MODIFIED, HistorySort.of(HistorySort.DATE_MODIFIED.key))
        assertEquals(HistorySort.DATE_ADDED, HistorySort.of(HistorySort.DATE_ADDED.key))
        assertEquals(HistorySort.DATE_ADDED, HistorySort.of(null))
        assertEquals(HistorySort.DATE_ADDED, HistorySort.of("FIRST_SEEN"))
    }
}

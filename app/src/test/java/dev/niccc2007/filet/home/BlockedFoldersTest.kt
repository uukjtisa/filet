package dev.niccc2007.filet.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocking folders out of New files.
 *
 * The patterns go into SQL inside the media provider, and a path is user data, so the escaping
 * is the part worth testing rather than the plumbing.
 */
class BlockedFoldersTest {

    @Test fun `a folder blocks itself and everything under it`() {
        assertEquals(
            listOf("/storage/emulated/0/DCIM/%"),
            CategoryQuery.blockedPrefixes(setOf("/storage/emulated/0/DCIM")),
        )
    }

    @Test fun `a trailing separator is the same folder, not a second one`() {
        assertEquals(
            listOf("/a/b/%"),
            CategoryQuery.blockedPrefixes(setOf("/a/b/")),
        )
        assertEquals(1, CategoryQuery.blockedPrefixes(setOf("/a/b", "/a/b/")).size)
    }

    @Test fun `a blank or root entry is dropped rather than blocking the device`() {
        // A stored preference is not trustworthy just because the code that writes it is
        // careful. An empty string would become "%" and hide every file on the phone.
        assertTrue(CategoryQuery.blockedPrefixes(setOf("", "   ", "/")).isEmpty())
    }

    @Test fun `wildcards in a real folder name are escaped`() {
        // A folder genuinely called this exists on plenty of phones, and unescaped it would
        // block everything: % matches any run of characters and _ matches any single one.
        assertEquals(
            listOf("/a/100\\%\\_backup/%"),
            CategoryQuery.blockedPrefixes(setOf("/a/100%_backup")),
        )
    }

    @Test fun `the selection carries one clause per blocked entry, parameterised`() {
        val (sql, args) = CategoryQuery.newSelection(null, setOf("/a", "/b"), by = HistorySort.DATE_ADDED)
        // One escaped clause per entry. The base selection also has a `NOT LIKE ?`, for
        // dotfiles, so counting those instead would pass with the entries missing.
        assertEquals(2, Regex("""NOT LIKE \? ESCAPE""").findAll(sql).count())
        // The hidden-file pattern plus two per entry - what is under it, and itself; no path is
        // ever interpolated.
        assertEquals(5, args.size)
        assertTrue(args.contains("/a/%"))
        assertTrue(args.contains("/b/%"))
    }

    @Test fun `an entry naming a file excludes that file, not just a folder of that name`() {
        // Bug identified here: only `path/%` was written, which a file's own row never matches.
        val (sql, args) = CategoryQuery.newSelection(null, setOf("/F/filet.json"), by = HistorySort.DATE_ADDED)
        assertTrue(sql.contains("!= ?"))
        assertTrue(args.contains("/F/filet.json"))
        assertTrue(args.contains("/F/filet.json/%"))
    }

    @Test fun `a category selection is bracketed before anything is added to it`() {
        // An AND after "a OR b" binds to b alone.
        val (sql, args) = CategoryQuery.excluding("mime_type LIKE ? OR mime_type LIKE ?", arrayOf("a/%", "b/%"), setOf("/F/Logs"))
        assertTrue(sql, sql.startsWith("(mime_type LIKE ? OR mime_type LIKE ?) AND ("))
        assertEquals(listOf("a/%", "b/%", "/F/Logs/%", "/F/Logs"), args.toList())
        // Nothing to leave out leaves the selection exactly as it was.
        assertEquals("x = ?", CategoryQuery.excluding("x = ?", arrayOf("1"), emptySet()).first)
    }

    @Test fun `blocking composes with the date jump`() {
        val (sql, args) = CategoryQuery.newSelection(1_700_000_000_000L, setOf("/a"), by = HistorySort.DATE_ADDED)
        assertTrue(sql.contains("date_added < ?"))
        assertTrue(args.contains("1700000000"))
        assertTrue(args.contains("/a/%"))
    }
}

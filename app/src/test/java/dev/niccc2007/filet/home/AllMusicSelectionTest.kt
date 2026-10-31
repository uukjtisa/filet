package dev.niccc2007.filet.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `WHERE` All Music is read with.
 *
 * The decision it is built from lives in `AllMusicFilter` and is tested beside it; this is the
 * SQL, which is the part that can be wrong in a way nobody sees - a filter that is slightly too
 * strict reads as music going missing.
 */
class AllMusicSelectionTest {

    private val primary = "/storage/emulated/0"

    @Test fun `folders are OR-ed and the lengths are AND-ed around them`() {
        val (sql, args) = CategoryQuery.audioSelection(
            listOf("Music", "Download"), primary, 60_000, 600_000,
        )
        assertTrue(sql!!, sql.contains(" OR "))
        // The escape CHARACTER is asserted, not just the word.
        //
        // Bug identified here: the source had a single backslash inside the quotes, which
        // Kotlin reads as an escaped quote - so what reached SQLite was an empty escape
        // expression and every read of All Music failed with "escape expression must be a
        // single character". A test that only looked for the word ESCAPE passed throughout.
        //
        // A raw string is used on purpose: this assertion is about a backslash, and writing it
        // in an ordinary literal is the exact mistake being pinned.
        assertEquals(2, Regex(Regex.escape("""LIKE ? ESCAPE '\'""")).findAll(sql).count())
        assertTrue(sql, sql.contains("duration >= ?"))
        assertTrue(sql, sql.contains("duration <= ?"))
        assertEquals(
            listOf("/storage/emulated/0/Music/%", "/storage/emulated/0/Download/%", "60000", "600000"),
            args.toList(),
        )
    }

    @Test fun `a track whose length is unknown survives both bounds`() {
        // MediaStore reports 0 when it never read the header. Dropping those would hide real
        // albums, which reads as the library being broken rather than as a filter being strict.
        val (sql, _) = CategoryQuery.audioSelection(emptyList(), primary, 60_000, 600_000)
        assertTrue(sql!!, sql.contains("duration <= 0"))
    }

    @Test fun `no folders means no folder restriction, never an empty list`() {
        val (sql, args) = CategoryQuery.audioSelection(emptyList(), primary, 0, 0)
        assertNull(sql)
        assertTrue(args.isEmpty())
    }

    @Test fun `a ceiling dragged below the floor is ignored rather than matching nothing`() {
        val (sql, args) = CategoryQuery.audioSelection(emptyList(), primary, 600_000, 60_000)
        assertTrue(sql!!, sql.contains("duration >= ?"))
        assertTrue(sql, !sql.contains("duration <= ?"))
        assertEquals(listOf("600000"), args.toList())
    }

    @Test fun `a zero ceiling means no ceiling`() {
        val (sql, args) = CategoryQuery.audioSelection(emptyList(), primary, 60_000, 0)
        assertTrue(sql!!, !sql.contains("duration <= ?"))
        assertEquals(listOf("60000"), args.toList())
    }

    @Test fun `wildcards in a folder name are escaped`() {
        val (_, args) = CategoryQuery.audioSelection(listOf("100%_mixes"), primary, 0, 0)
        assertEquals(listOf("""/storage/emulated/0/100\%\_mixes/%"""), args.toList())
    }
}

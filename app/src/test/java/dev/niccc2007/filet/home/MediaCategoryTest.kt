package dev.niccc2007.filet.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The category query's pure parts: the WHERE clause, turning a row into a node, and telling
 * "empty" apart from "refused" and "failed".
 *
 * The defect behind these was a query rejected by the media provider on Android 11 and later,
 * swallowed, and drawn as an empty category. The rejection itself (the LIMIT in the sort order)
 * cannot be reproduced without the provider, so what is pinned here is everything around it
 * that decided what the screen then said.
 */
class MediaCategoryTest {

    private val primary = "/storage/emulated/0"

    // ── the selection ──

    @Test
    fun `media categories select by media type, one placeholder, one argument`() {
        for ((cat, type) in listOf(MediaCategory.IMAGES to "1", MediaCategory.AUDIO to "2", MediaCategory.VIDEOS to "3")) {
            val (sel, args) = CategoryQuery.selectionFor(cat)
            assertEquals("media_type=?", sel)
            assertEquals(listOf(type), args.toList())
        }
    }

    @Test
    fun `extension categories have one placeholder per argument, bracketed`() {
        for (cat in listOf(MediaCategory.APPS, MediaCategory.DOCUMENTS, MediaCategory.ARCHIVES)) {
            val (sel, args) = CategoryQuery.selectionFor(cat)
            assertEquals(args.size, sel.count { it == '?' })
            assertTrue(sel.startsWith("(") && sel.endsWith(")"))
            assertTrue(args.all { it.startsWith("%.") })
            assertTrue(!sel.contains("LIMIT", ignoreCase = true))
        }
    }

    // ── rows ──

    @Test
    fun `a row with DATA keeps its exact path and converts seconds`() {
        val n = CategoryQuery.nodeFor("/storage/emulated/0/DCIM/Camera/a.mp4", "DCIM/Camera/", "external_primary", "a.mp4", 10L, 1_700_000_000L, primary)!!
        assertEquals("/storage/emulated/0/DCIM/Camera/a.mp4", n.path.path)
        assertEquals(1_700_000_000_000L, n.mtime)
    }

    @Test
    fun `a row with null DATA is rebuilt from its relative path rather than dropped`() {
        // The old mapping skipped every such row, which gives a successful query and an empty
        // list - the exact symptom.
        val n = CategoryQuery.nodeFor(null, "Movies/Clips/", "external_primary", "b.mp4", 5L, 0L, primary)
        assertEquals("/storage/emulated/0/Movies/Clips/b.mp4", n!!.path.path)
        assertEquals("local", n.path.scheme)
        val blank = CategoryQuery.nodeFor("  ", "Music/", null, "c.mp3", 5L, 0L, primary)
        assertEquals("/storage/emulated/0/Music/c.mp3", blank!!.path.path)
    }

    @Test
    fun `a row on a removable card rebuilds onto that card`() {
        val n = CategoryQuery.nodeFor(null, "DCIM/", "1a2b-3c4d", "d.jpg", 1L, 0L, primary)
        assertEquals("/storage/1A2B-3C4D/DCIM/d.jpg", n!!.path.path)
    }

    @Test
    fun `a row with no DATA and no relative path is unplaceable, not invented`() {
        assertNull(CategoryQuery.nodeFor(null, null, "external_primary", "e.jpg", 1L, 0L, primary))
        assertNull(CategoryQuery.nodeFor(null, "DCIM/", "external_primary", null, 1L, 0L, primary))
    }

    // ── what the screen is told ──

    @Test
    fun `empty, refused and failed are three answers, not one`() {
        val node = CategoryQuery.nodeFor("/storage/emulated/0/a.jpg", null, null, "a.jpg", 1L, 0L, primary)!!
        assertTrue(CategoryQuery.outcome(listOf(node), 0, permitted = true) is CategoryResult.Found)
        assertEquals(CategoryResult.Empty, CategoryQuery.outcome(emptyList(), 0, permitted = true))
        assertEquals(CategoryResult.Refused, CategoryQuery.outcome(emptyList(), 0, permitted = false))
        assertTrue(CategoryQuery.outcome(emptyList(), 12, permitted = true) is CategoryResult.Failed)
        // Own files are still files: rows found without permission are shown, not refused.
        assertTrue(CategoryQuery.outcome(listOf(node), 0, permitted = false) is CategoryResult.Found)
    }

    // ── music ordering ──

    @Test
    fun `music is ordered by the database, names folding case, ids breaking ties`() {
        assertEquals("_display_name COLLATE NOCASE ASC, _id ASC", CategoryQuery.audioOrder("NAME", false))
        assertEquals("duration DESC, _id DESC", CategoryQuery.audioOrder("DURATION", true))
        assertEquals("date_added DESC, _id DESC", CategoryQuery.audioOrder("ADDED", true))
        assertEquals("date_modified ASC, _id ASC", CategoryQuery.audioOrder("MODIFIED", false))
        assertEquals("_size DESC, _id DESC", CategoryQuery.audioOrder("SIZE", true))
    }

    @Test
    fun `an unknown order key falls back to date added and never reaches SQL as text`() {
        assertEquals("date_added DESC, _id DESC", CategoryQuery.audioOrder("title; DROP TABLE files", true))
        assertEquals("date_added ASC, _id ASC", CategoryQuery.audioOrder("_display_name", false))
    }
}

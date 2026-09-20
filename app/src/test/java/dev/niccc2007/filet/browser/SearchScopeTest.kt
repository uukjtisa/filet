package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What search does on each kind of tab.
 *
 * Bug identified: search was written for the folder pane and every other pane inherited it, so
 * searching from Settings walked the filesystem and returned files - not settings, and not
 * something that pane could display.
 */
class SearchScopeTest {

    @Test
    fun `searching in settings does not search the filesystem`() {
        // The report.
        assertNotEquals(SearchMode.FILESYSTEM, searchPlan(PaneKind.SETTINGS))
        assertEquals(SearchMode.FILTER, searchPlan(PaneKind.SETTINGS))
    }

    @Test
    fun `a folder pane still searches the device`() {
        // The control. A change that stopped the filesystem search everywhere would fix the
        // report and remove the feature.
        assertEquals(SearchMode.FILESYSTEM, searchPlan(PaneKind.FOLDER))
    }

    @Test
    fun `every list-shaped pane narrows its own rows`() {
        for (kind in listOf(
            PaneKind.SCRIPTS, PaneKind.BOOKMARKS, PaneKind.RECENT, PaneKind.HISTORY,
            PaneKind.SHORTCUTS, PaneKind.REMOTES, PaneKind.ACTIVITY, PaneKind.NEARBY,
        )) {
            assertEquals(kind.name, SearchMode.FILTER, searchPlan(kind))
        }
    }

    @Test
    fun `panes where search means nothing do not offer it`() {
        // Home is a few cards and About is prose. A search box on either cannot do anything,
        // which is rule R1.
        assertEquals(SearchMode.NONE, searchPlan(PaneKind.HOME))
        assertEquals(SearchMode.NONE, searchPlan(PaneKind.ABOUT))
        assertFalse(searchOffered(PaneKind.HOME))
        assertFalse(searchOffered(PaneKind.ABOUT))
    }

    @Test
    fun `search is offered everywhere it can do something`() {
        for (kind in PaneKind.entries) {
            assertEquals(
                "$kind disagrees with itself about whether search is offered",
                searchPlan(kind) != SearchMode.NONE,
                searchOffered(kind),
            )
        }
    }

    @Test
    fun `every pane kind has an answer`() {
        // The hole this table exists to close. A kind added without a decision would have
        // inherited a filesystem search it cannot use, which is exactly how this happened.
        for (kind in PaneKind.entries) {
            assertTrue(kind.name, searchPlan(kind) in SearchMode.entries)
        }
    }

    @Test
    fun `the hint says which of the two it will do`() {
        assertTrue(searchHint(PaneKind.FOLDER).contains("device"))
        assertTrue(searchHint(PaneKind.SETTINGS).contains("screen"))
        assertEquals("", searchHint(PaneKind.HOME))
    }

    @Test
    fun `the two hints are different, so the field never lies about its scope`() {
        assertNotEquals(searchHint(PaneKind.FOLDER), searchHint(PaneKind.SETTINGS))
    }
}

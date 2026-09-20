package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cold index must never be allowed to answer "nothing found" on its own.
 *
 * Reported: a file in a subfolder of Download, searched for with Subfolders selected, was not
 * found - and the same search from inside that subfolder found it at once. The pane ran the
 * first source that claimed the request, the index claims Subfolders, and a subtree the crawl
 * had not reached yet simply returned nothing. The walk that would have found it never ran.
 */
class SearchFallbackTest {

    // ── the report ──

    @Test
    fun `subfolders is answered by the walk as well as the index`() {
        // The exact case. Before the fix this was the index alone.
        val plan = sourcePlan(indexUsable = true, scope = SearchScope.SUBFOLDERS, nativeOnly = false)
        assertTrue("the walk has to run, or a cold subtree returns nothing", plan.contains(SourceKind.WALK))
    }

    @Test
    fun `every scope that used to be index-only now also walks`() {
        // Provenance is the exception and it is excluded deliberately - the filesystem does
        // not record where a file came from, so a walk cannot answer it at all.
        for (scope in SearchScope.entries) {
            if (scope == SearchScope.PROVENANCE) continue
            val plan = sourcePlan(indexUsable = true, scope = scope, nativeOnly = false)
            assertTrue("$scope must reach the filesystem", plan.contains(SourceKind.WALK))
        }
    }

    @Test
    fun `the index still answers first, because it is the fast one`() {
        val plan = sourcePlan(indexUsable = true, scope = SearchScope.DEVICE, nativeOnly = false)
        assertEquals(listOf(SourceKind.INDEX, SourceKind.WALK), plan)
    }

    // ── a cold or absent index ──

    @Test
    fun `no index at all still searches`() {
        for (scope in listOf(SearchScope.FOLDER, SearchScope.SUBFOLDERS, SearchScope.DEVICE)) {
            assertEquals("$scope", listOf(SourceKind.WALK), sourcePlan(false, scope, nativeOnly = false))
        }
    }

    @Test
    fun `provenance without an index answers nothing rather than something wrong`() {
        // Better to return no rows than to walk the device and present unrelated files as
        // though they carried provenance.
        assertTrue(sourcePlan(indexUsable = false, scope = SearchScope.PROVENANCE, nativeOnly = false).isEmpty())
    }

    // ── the toggle ──

    @Test
    fun `native search uses the walk and nothing else`() {
        for (scope in listOf(SearchScope.FOLDER, SearchScope.SUBFOLDERS, SearchScope.DEVICE)) {
            assertEquals("$scope", listOf(SourceKind.WALK), sourcePlan(true, scope, nativeOnly = true))
        }
    }

    @Test
    fun `native search cannot turn off provenance, because a walk cannot answer it`() {
        assertEquals(
            listOf(SourceKind.INDEX),
            sourcePlan(indexUsable = true, scope = SearchScope.PROVENANCE, nativeOnly = true),
        )
    }

    // ── shape ──

    @Test
    fun `a plan never repeats a source`() {
        for (u in listOf(true, false)) for (n in listOf(true, false)) for (s in SearchScope.entries) {
            val plan = sourcePlan(u, s, n)
            assertEquals("$u/$s/$n", plan.size, plan.distinct().size)
        }
    }

    @Test
    fun `only provenance may plan nothing at all`() {
        for (u in listOf(true, false)) for (n in listOf(true, false)) for (s in SearchScope.entries) {
            if (s == SearchScope.PROVENANCE) continue
            assertTrue("$u/$s/$n found no source", sourcePlan(u, s, n).isNotEmpty())
        }
    }

    @Test
    fun `the broken set is named, and every member of it is fixed`() {
        // wasIndexOnly records exactly which scopes the old code handed to the index alone.
        assertTrue(wasIndexOnly(SearchScope.SUBFOLDERS))
        assertTrue(wasIndexOnly(SearchScope.DEVICE))
        assertFalse("a single folder always went to the walk, which is why that case worked",
            wasIndexOnly(SearchScope.FOLDER))
    }
}

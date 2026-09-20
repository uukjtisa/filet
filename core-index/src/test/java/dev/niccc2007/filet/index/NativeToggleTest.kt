package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reported twice: the Native search toggle changes nothing.
 *
 * Two separate causes, and only one of them was a fault.
 *
 * The fault: the pane resolved a planned source by POSITION in the list and called it without
 * checking it accepted the request. The index declines a single-folder scope, so with Folder
 * selected the plan asked for an index, was handed one anyway, and called it with a request it
 * had already refused. Turning the toggle on then removed a source that was never legitimately
 * contributing, so the rows did not move.
 *
 * The one that is not a fault: for a single-folder scope the walk is the only source either
 * way, so the toggle genuinely cannot change the answer. That makes it a dead switch for that
 * scope (R1), and the fix is to say so rather than to pretend otherwise.
 */
class NativeToggleTest {

    @Test
    fun `a planned source is only used if it accepts the actual request`() {
        // The shape of the fault, as a property of the plan rather than of the pane: for a
        // single folder the plan may name the index, and the index declines that scope.
        val plan = sourcePlan(indexUsable = true, scope = SearchScope.FOLDER, nativeOnly = false)
        assertTrue("the plan may still name an index", plan.contains(SourceKind.INDEX))
        // So the pane has to filter by handles(req). What this test can assert is the honest
        // consequence: the toggle cannot matter here.
        assertFalse(nativeToggleMatters(indexUsable = true, scope = SearchScope.FOLDER))
    }

    @Test
    fun `the toggle matters wherever the index actually answers`() {
        assertTrue(nativeToggleMatters(true, SearchScope.DEVICE))
        assertTrue(nativeToggleMatters(true, SearchScope.SUBFOLDERS))
    }

    @Test
    fun `with no index at all the toggle cannot matter`() {
        for (scope in SearchScope.entries) {
            assertFalse("$scope", nativeToggleMatters(indexUsable = false, scope = scope))
        }
    }

    @Test
    fun `provenance is not something the toggle can affect`() {
        // The filesystem does not record where a file came from, so a walk cannot answer it
        // and turning the walk on cannot help.
        assertFalse(nativeToggleMatters(true, SearchScope.PROVENANCE))
    }

    @Test
    fun `native only really does plan one source`() {
        for (scope in listOf(SearchScope.FOLDER, SearchScope.SUBFOLDERS, SearchScope.DEVICE)) {
            assertEquals("$scope", listOf(SourceKind.WALK), sourcePlan(true, scope, nativeOnly = true))
        }
    }

    @Test
    fun `where it matters, turning it on removes a source`() {
        val before = sourcePlan(true, SearchScope.DEVICE, nativeOnly = false)
        val after = sourcePlan(true, SearchScope.DEVICE, nativeOnly = true)
        assertTrue("it should have been two", before.size > after.size)
        assertTrue(after.contains(SourceKind.WALK))
    }

    @Test
    fun `the origin line distinguishes every case a reader could be in`() {
        val lines = listOf(
            originLine(emptyList()),
            originLine(listOf(SourceKind.INDEX)),
            originLine(listOf(SourceKind.WALK)),
            originLine(listOf(SourceKind.INDEX, SourceKind.WALK)),
        )
        assertEquals("two cases read the same", lines.size, lines.distinct().size)
    }

    @Test
    fun `the origin line never claims an index that is not running`() {
        assertFalse(originLine(listOf(SourceKind.WALK)).contains("Index"))
    }
}

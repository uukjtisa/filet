package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cold index must never be allowed to answer "nothing found" on its own.
 *
 * Reported: a file in a subfolder of Download, searched for with Subfolders selected, was not
 * found - and the same search from inside that subfolder found it at once. The pane ran the
 * first source that claimed the request, the index claims a subtree, and a subtree the crawl
 * had not reached yet simply returned nothing. The walk that would have found it never ran.
 */
class SearchFallbackTest {

    private val card = Reach.Drives(setOf(VolumeKey(VPath.of("local", "/storage/1A2B"))))
    private val every = listOf(Reach.Folder, Reach.Subtree, card, Reach.Everywhere)

    // ── the report ──

    @Test
    fun `a subtree is answered by the walk as well as the index`() {
        // The exact case. Before the fix this was the index alone.
        val plan = sourcePlan(indexUsable = true, reach = Reach.Subtree, nativeOnly = false)
        assertTrue("the walk has to run, or a cold subtree returns nothing", plan.contains(SourceKind.WALK))
    }

    @Test
    fun `every reach that used to be index-only now also walks`() {
        for (reach in every) {
            val plan = sourcePlan(indexUsable = true, reach = reach, nativeOnly = false)
            assertTrue("$reach must reach the filesystem", plan.contains(SourceKind.WALK))
        }
    }

    @Test
    fun `the index still answers first, because it is the fast one`() {
        val plan = sourcePlan(indexUsable = true, reach = Reach.Everywhere, nativeOnly = false)
        assertEquals(listOf(SourceKind.INDEX, SourceKind.WALK), plan)
    }

    // ── a cold or absent index ──

    @Test
    fun `no index at all still searches`() {
        for (reach in every) {
            assertEquals("$reach", listOf(SourceKind.WALK), sourcePlan(false, reach, nativeOnly = false))
        }
    }

    @Test
    fun `provenance without an index answers nothing rather than something wrong`() {
        // Better to return no rows than to walk the device and present unrelated files as
        // though they carried provenance.
        assertTrue(
            sourcePlan(indexUsable = false, reach = Reach.Everywhere, nativeOnly = false, provenance = true)
                .isEmpty(),
        )
    }

    // ── the toggle ──

    @Test
    fun `native search uses the walk and nothing else`() {
        for (reach in every) {
            assertEquals("$reach", listOf(SourceKind.WALK), sourcePlan(true, reach, nativeOnly = true))
        }
    }

    @Test
    fun `native search cannot turn off provenance, because a walk cannot answer it`() {
        assertEquals(
            listOf(SourceKind.INDEX),
            sourcePlan(indexUsable = true, reach = Reach.Everywhere, nativeOnly = true, provenance = true),
        )
    }

    // ── shape ──

    @Test
    fun `a plan never repeats a source`() {
        for (u in listOf(true, false)) for (n in listOf(true, false)) for (r in every) {
            val plan = sourcePlan(u, r, n)
            assertEquals("$u/$r/$n", plan.size, plan.distinct().size)
        }
    }

    @Test
    fun `only a provenance query may plan nothing at all`() {
        for (u in listOf(true, false)) for (n in listOf(true, false)) for (r in every) {
            assertTrue("$u/$r/$n found no source", sourcePlan(u, r, n).isNotEmpty())
        }
    }

    @Test
    fun `the broken set is named, and every member of it is fixed`() {
        // wasIndexOnly records exactly which reaches the old code handed to the index alone.
        assertTrue(wasIndexOnly(Reach.Subtree))
        assertTrue(wasIndexOnly(Reach.Everywhere))
        assertTrue(wasIndexOnly(card))
        assertTrue(wasIndexOnly(Reach.Folder, provenance = true))
        assertFalse(
            "a single folder always went to the walk, which is why that case worked",
            wasIndexOnly(Reach.Folder),
        )
    }
}

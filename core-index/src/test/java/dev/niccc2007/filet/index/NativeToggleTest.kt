package dev.niccc2007.filet.index

import dev.niccc2007.filet.vfs.VPath
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
 * checking it accepted the request. The index declines a single-folder reach, so with This
 * folder selected the plan asked for an index, was handed one anyway, and called it with a
 * request it had already refused. Turning the toggle on then removed a source that was never
 * legitimately contributing, so the rows did not move.
 *
 * The one that is not a fault: for a single folder the walk is the only source either way, so
 * the toggle genuinely cannot change the answer. That makes it a dead switch there (R1), and
 * the fix is to say so rather than to pretend otherwise.
 */
class NativeToggleTest {

    private val card = Reach.Drives(setOf(VolumeKey(VPath.of("local", "/storage/1A2B"))))

    @Test
    fun `a planned source is only used if it accepts the actual request`() {
        // The shape of the fault, as a property of the plan rather than of the pane: for a
        // single folder the plan may name the index, and the index declines that reach.
        val plan = sourcePlan(indexUsable = true, reach = Reach.Folder, nativeOnly = false)
        assertTrue("the plan may still name an index", plan.contains(SourceKind.INDEX))
        // So the pane has to filter by handles(req). What this test can assert is the honest
        // consequence: the toggle cannot matter here.
        assertFalse(nativeToggleMatters(indexUsable = true, reach = Reach.Folder))
    }

    @Test
    fun `the toggle matters wherever the index actually answers`() {
        assertTrue(nativeToggleMatters(true, Reach.Everywhere))
        assertTrue(nativeToggleMatters(true, Reach.Subtree))
        assertTrue(nativeToggleMatters(true, card))
    }

    @Test
    fun `with no index at all the toggle cannot matter`() {
        for (reach in listOf(Reach.Folder, Reach.Subtree, card, Reach.Everywhere)) {
            assertFalse("$reach", nativeToggleMatters(indexUsable = false, reach = reach))
        }
    }

    @Test
    fun `provenance is not something the toggle can affect`() {
        // The filesystem does not record where a file came from, so a walk cannot answer it
        // and turning the walk on cannot help. A property of the query now rather than of the
        // reach, which is why it is passed beside one.
        assertFalse(nativeToggleMatters(true, Reach.Everywhere, provenance = true))
        assertFalse(nativeToggleMatters(true, card, provenance = true))
    }

    @Test
    fun `native only really does plan one source`() {
        for (reach in listOf(Reach.Folder, Reach.Subtree, card, Reach.Everywhere)) {
            assertEquals("$reach", listOf(SourceKind.WALK), sourcePlan(true, reach, nativeOnly = true))
        }
    }

    @Test
    fun `a provenance query is the one thing native only cannot take away`() {
        // Not a special case for its own sake: a walk returning nothing for `from:youtube` is
        // indistinguishable from there being no such file, which is the answer this whole
        // design refuses to give.
        assertEquals(
            listOf(SourceKind.INDEX),
            sourcePlan(true, Reach.Everywhere, nativeOnly = true, provenance = true),
        )
        // And with no index there is nothing honest to return at all.
        assertEquals(
            emptyList<SourceKind>(),
            sourcePlan(false, Reach.Everywhere, nativeOnly = true, provenance = true),
        )
    }

    @Test
    fun `where it matters, turning it on removes a source`() {
        val before = sourcePlan(true, Reach.Everywhere, nativeOnly = false)
        val after = sourcePlan(true, Reach.Everywhere, nativeOnly = true)
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

package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three things a row can be told about a folder's total.
 *
 * What is pinned is the mapping from state to what the row draws, because that mapping is the
 * whole feature: a figure that is still moving must say so, a figure that has settled must stop
 * saying so, and a folder nothing has measured must not carry a spinner promising an answer that
 * is not on its way.
 */
class FolderTotalTest {

    @Test fun `nothing known has no figure and no spinner`() {
        // The dash breathes, but nothing turns: there is nothing in flight for this row.
        assertNull(FolderTotal.Unknown.figure())
        assertFalse(FolderTotal.Unknown.known())
        assertFalse(FolderTotal.Unknown.busy())
    }

    @Test fun `a figure being confirmed is shown and marked as moving`() {
        val t = FolderTotal.Confirming(4_938_100_000L)
        assertEquals(4_938_100_000L, t.figure())
        assertTrue(t.known())
        assertTrue(t.busy())
    }

    @Test fun `a settled figure is shown and nothing moves`() {
        val t = FolderTotal.Settled(123L)
        assertEquals(123L, t.figure())
        assertTrue(t.known())
        assertFalse(t.busy())
    }

    @Test fun `an empty folder is a settled zero, but only a walk may say so`() {
        // Zero bytes is a real answer for a folder with nothing in it - from a WALK. It is a
        // wrong answer when it stands in for "the crawl never looked here", which is why the
        // index is no longer allowed to produce it at all.
        val t = FolderTotal.Settled(0L)
        assertEquals(0L, t.figure())
        assertTrue(t.known())
        assertFalse(t.isFloor())
    }

    @Test fun `a floor is shown but never reads as a total`() {
        // The defect: 703 MB from a partial index printed as though a crawl had seen the whole
        // of a 39 GB folder. The figure is worth showing; claiming it is the answer is not.
        val t = FolderTotal.AtLeast(703L * 1024 * 1024)
        assertTrue(t.known())
        assertTrue(t.isFloor())
        assertFalse("a floor is not a thing in flight", t.busy())
        assertTrue("a floor still wants a real walk", t.wantsWalk())
    }

    @Test fun `only a finished walk is final`() {
        // The invariant the type exists to carry. Everything else is a floor, and everything
        // that is not already being walked still wants one.
        val states = listOf(
            FolderTotal.Unknown,
            FolderTotal.AtLeast(1L),
            FolderTotal.Confirming(1L),
            FolderTotal.Settled(1L),
        )
        assertEquals(listOf(false, true, true, false), states.map { it.isFloor() })
        assertEquals(listOf(true, true, false, false), states.map { it.wantsWalk() })
    }

    @Test fun `only one state spins`() {
        val all = listOf(
            FolderTotal.Unknown,
            FolderTotal.AtLeast(1L),
            FolderTotal.Confirming(1L),
            FolderTotal.Settled(1L),
        )
        assertEquals(listOf(false, false, true, false), all.map { it.busy() })
    }
}

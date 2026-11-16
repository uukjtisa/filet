package dev.niccc2007.filet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides which surfaces step aside for a camera hole. The numbers are a
 * 1080 x 2400 phone whose punch hole is 72 px across and 96 px deep, centred on the short edge.
 */
class CutoutRuleTest {

    private val wide = 2400

    /** The hole in landscape, mid-way down the left edge. */
    private val leftHole = PxBox(0, 504, 96, 576)

    @Test
    fun `a phone with no hole moves nothing`() {
        assertEquals(0 to 0, CutoutRule.sidePads(PxBox(0, 260, 1200, 1000), emptyList(), wide))
    }

    @Test
    fun `in landscape only the surface beside the hole steps aside, by the hole's depth`() {
        // The body runs past the hole's rows: it steps in.
        assertEquals(96 to 0, CutoutRule.sidePads(PxBox(0, 260, 1200, 1000), listOf(leftHole), wide))
        // The tab strip and the path bar sit above it: they keep the whole width.
        assertEquals(0 to 0, CutoutRule.sidePads(PxBox(0, 96, wide, 170), listOf(leftHole), wide))
        assertEquals(0 to 0, CutoutRule.sidePads(PxBox(0, 180, wide, 260), listOf(leftHole), wide))
    }

    @Test
    fun `a surface away from the edge is not moved, and one partly over the hole moves by the overlap`() {
        assertEquals(0 to 0, CutoutRule.sidePads(PxBox(600, 260, 2400, 1000), listOf(leftHole), wide))
        assertEquals(56 to 0, CutoutRule.sidePads(PxBox(40, 260, 600, 1000), listOf(leftHole), wide))
    }

    @Test
    fun `turned the other way the hole is on the right, and so is the padding`() {
        val rightHole = PxBox(2304, 504, 2400, 576)
        assertEquals(0 to 96, CutoutRule.sidePads(PxBox(1200, 260, 2400, 1000), listOf(rightHole), wide))
    }

    @Test
    fun `in portrait a hole on the top edge never moves anything sideways`() {
        val top = PxBox(504, 0, 576, 96)
        assertEquals(0 to 0, CutoutRule.sidePads(PxBox(0, 96, 1080, 200), listOf(top), 1080))
        // A corner hole in the status bar's band: the rows start below it.
        val corner = PxBox(0, 0, 100, 96)
        assertEquals(0 to 0, CutoutRule.sidePads(PxBox(0, 96, 1080, 200), listOf(corner), 1080))
    }
}

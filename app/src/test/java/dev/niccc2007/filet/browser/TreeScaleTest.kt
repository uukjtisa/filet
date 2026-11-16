package dev.niccc2007.filet.browser

import dev.niccc2007.filet.data.ViewStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeScaleTest {

    private val icon = 24
    private val slop = 8f

    @Test
    fun `the step follows the view size`() {
        assertEquals(12f, TreeScale.of(ViewStep.COMPACT_LIST, 360, icon).indentDp, 0.001f)
        assertEquals(16f, TreeScale.of(ViewStep.LIST, 360, icon).indentDp, 0.001f)
        assertEquals(20f, TreeScale.of(ViewStep.COMFORTABLE, 360, icon).indentDp, 0.001f)
        for (step in ViewStep.entries) assertEquals(TreeScale.START, TreeScale.of(step, 360, icon).startDp, 0.001f)
    }

    @Test
    fun `the step never depends on how deep the tree is open`() {
        // The old squeeze shrank the step as the tree opened. That is what made a deep tree a
        // cramped list, and it is gone: one step per view size, whatever is open.
        val s = TreeScale.of(ViewStep.LIST, 360, icon)
        for (d in 1..minOf(s.levels, TreeRows.MAX_DEPTH)) assertEquals(s.indentDp, s.at(d) - s.at(d - 1), 0.001f)
    }

    @Test
    fun `every step leaves room for a guide line`() {
        // The elbow runs `indent - 6` dp. At six or below the corner and the run are one pixel.
        for (step in ViewStep.entries) assertTrue(step.name, TreeScale.indentFor(step) > 6f)
    }

    @Test
    fun `a tree stops stepping in at two screen widths`() {
        val screen = 360
        for (step in ViewStep.entries) {
            val s = TreeScale.of(step, screen, icon)
            val need = TreeScale.needDp(500, s, icon)
            assertTrue("$step needs $need at depth 500", need <= 2f * screen + 0.001f)
            // And the last level that does step in still fits inside the cap.
            assertTrue("$step last level", TreeScale.needDp(s.levels, s, icon) <= 2f * screen + 0.001f)
            assertEquals("$step past the cap", s.at(s.levels), s.at(s.levels + 7), 0.001f)
        }
    }

    @Test
    fun `thirty levels in List on a 360 dp screen before the cap bites`() {
        assertEquals(30, TreeScale.of(ViewStep.LIST, 360, icon).levels)
    }

    @Test
    fun `a screen too small for even one level still answers`() {
        val s = TreeScale.of(ViewStep.COMFORTABLE, 100, icon)
        assertEquals(0, s.levels)
        assertEquals(TreeScale.START, s.at(9), 0.001f)
        assertEquals(TreeScale.START, s.at(-3), 0.001f)
    }

    @Test
    fun `nothing slides when everything fits`() {
        val s = TreeScale.of(ViewStep.LIST, 360, icon)
        assertEquals(0f, TreeScale.maxSlideDp(0, s, icon, slidingDp = 300f), 0.001f)
        assertEquals(0f, TreeScale.maxSlideDp(3, s, icon, slidingDp = 300f), 0.001f)
    }

    @Test
    fun `a row deeper than the pane slides by exactly what it lacks`() {
        val s = TreeScale.of(ViewStep.LIST, 360, icon)
        val sliding = 280f
        val deep = 12
        val need = TreeScale.START + deep * 16f + TreeScale.CHEVRON + icon + TreeScale.GAP + TreeScale.NAME_MIN
        assertEquals(need, TreeScale.needDp(deep, s, icon), 0.001f)
        assertEquals(need - sliding, TreeScale.maxSlideDp(deep, s, icon, sliding), 0.001f)
    }

    @Test
    fun `the slide stays inside its ends`() {
        assertEquals(0f, TreeScale.slid(10f, -50f, 100f), 0.001f)
        assertEquals(100f, TreeScale.slid(90f, 50f, 100f), 0.001f)
        assertEquals(60f, TreeScale.slid(40f, 20f, 100f), 0.001f)
        assertEquals(0f, TreeScale.slid(40f, 20f, -5f), 0.001f)
    }

    @Test
    fun `nothing is decided inside the slop`() {
        assertEquals(TreeScale.Claim.UNDECIDED, claim(dx = -5f, dy = 2f))
        assertEquals(TreeScale.Claim.UNDECIDED, claim(dx = 0f, dy = 0f))
    }

    @Test
    fun `sideways past the slop slides, either way it can go`() {
        assertEquals(TreeScale.Claim.SLIDE, claim(dx = -12f, dy = 3f, offset = 0f))
        assertEquals(TreeScale.Claim.SLIDE, claim(dx = 12f, dy = 3f, offset = 50f))
    }

    @Test
    fun `a tree at its end leaves the touch for the tab swipe`() {
        assertEquals(TreeScale.Claim.LEAVE, claim(dx = 12f, dy = 0f, offset = 0f))
        assertEquals(TreeScale.Claim.LEAVE, claim(dx = -12f, dy = 0f, offset = 100f, max = 100f))
        // A tree with nothing to show sideways never takes a touch at all.
        assertEquals(TreeScale.Claim.LEAVE, claim(dx = -12f, dy = 0f, offset = 0f, max = 0f))
    }

    @Test
    fun `down first is the list's scroll`() {
        assertEquals(TreeScale.Claim.LEAVE, claim(dx = 2f, dy = 10f))
        assertEquals(TreeScale.Claim.LEAVE, claim(dx = -9f, dy = 9f))
    }

    @Test
    fun `a sloppy sideways drag still slides`() {
        // Past the slop downwards too, but further across: a thumb drags on a slant.
        assertEquals(TreeScale.Claim.SLIDE, claim(dx = -20f, dy = 12f))
    }

    @Test
    fun `a hold or a second finger is somebody else's`() {
        assertEquals(TreeScale.Claim.LEAVE, claim(dx = -20f, dy = 0f, held = true))
        assertEquals(TreeScale.Claim.LEAVE, claim(dx = -20f, dy = 0f, fingers = 2))
        assertEquals(TreeScale.Claim.LEAVE, claim(dx = 0f, dy = 0f, held = true))
    }

    private fun claim(
        dx: Float,
        dy: Float,
        offset: Float = 40f,
        max: Float = 100f,
        held: Boolean = false,
        fingers: Int = 1,
    ) = TreeScale.claim(dx, dy, slop, offset, max, held, fingers)
}

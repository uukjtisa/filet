package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TapDetailTest {

    @Test fun `the three folder actions of the tree each say what sets them apart`() {
        val lines = listOf(TapDo.OPEN, TapDo.FOCUS, TapDo.EXPAND).map { TapPlan.detail(TapView.TREE, true, it) }
        for (l in lines) assertNotNull(l)
        assertEquals("each line is different", 3, lines.toSet().size)
    }

    @Test fun `every action the tree's folder column offers that opens a folder is described`() {
        val opensFolders = setOf(TapDo.OPEN, TapDo.FOCUS, TapDo.EXPAND)
        for (act in TapPlan.choices(TapView.TREE, isDir = true).filter { it in opensFolders }) {
            assertNotNull(act.name, TapPlan.detail(TapView.TREE, true, act))
        }
    }

    @Test fun `files and the other views stay plain`() {
        assertNull(TapPlan.detail(TapView.TREE, false, TapDo.OPEN))
        assertNull(TapPlan.detail(TapView.LISTS, true, TapDo.OPEN))
        assertNull(TapPlan.detail(TapView.TREE, true, TapDo.MENU))
    }
}

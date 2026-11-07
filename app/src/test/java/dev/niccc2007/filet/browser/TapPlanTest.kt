package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one table every view reads for what a tap does (#34). */
class TapPlanTest {

    @Test
    fun `the Filet preset is what each view did before it was configurable`() {
        val lists = TapPlan.default(TapView.LISTS)
        assertEquals(TapDo.OPEN, lists.of(isDir = false, Tap.ONE))
        assertEquals(TapDo.MENU, lists.of(isDir = true, Tap.TWO))
        assertEquals(TapDo.SELECT, lists.of(isDir = false, Tap.HOLD))
        assertEquals(TapDo.PEEK, TapPlan.default(TapView.GALLERY).of(isDir = false, Tap.HOLD))
        assertEquals(TapDo.FOCUS, TapPlan.default(TapView.TREE).of(isDir = true, Tap.ONE))
        assertEquals(TapDo.OPEN, TapPlan.default(TapView.TREE).of(isDir = false, Tap.ONE))
    }

    @Test
    fun `a count set to Nothing is not waited for`() {
        val gallery = TapPlan.default(TapView.GALLERY)
        assertEquals(1, gallery.listensFor(isDir = false))
        assertEquals(2, TapPlan.default(TapView.LISTS).listensFor(isDir = false))
        val three = gallery.with(isDir = false, Tap.THREE, TapDo.DETAILS)
        // Two taps still mean nothing, but a third is listened for, so the second must wait too.
        assertEquals(3, three.listensFor(isDir = false))
    }

    @Test
    fun `a set reads back as its preset until a row is changed, then as Custom`() {
        val windows = TapPlan.preset(TapView.LISTS, TapPreset.WINDOWS)!!
        assertEquals(TapPreset.WINDOWS, TapPlan.presetOf(TapView.LISTS, windows))
        val changed = windows.with(isDir = true, Tap.THREE, TapDo.DETAILS)
        assertEquals(TapPreset.CUSTOM, TapPlan.presetOf(TapView.LISTS, changed))
    }

    @Test
    fun `Xplorer is offered for the tree only`() {
        assertTrue(TapPreset.XPLORER in TapPlan.presets(TapView.TREE))
        assertTrue(TapPreset.XPLORER !in TapPlan.presets(TapView.LISTS))
        assertEquals(null, TapPlan.preset(TapView.GALLERY, TapPreset.XPLORER))
    }

    @Test
    fun `folding choices are the tree's and for folders, contents choices for files`() {
        assertTrue(TapDo.EXPAND in TapPlan.choices(TapView.TREE, isDir = true))
        assertTrue(TapDo.EXPAND !in TapPlan.choices(TapView.TREE, isDir = false))
        assertTrue(TapDo.FOCUS !in TapPlan.choices(TapView.LISTS, isDir = true))
        assertTrue(TapDo.PEEK !in TapPlan.choices(TapView.LISTS, isDir = true))
        assertTrue(TapDo.OPEN_WITH in TapPlan.choices(TapView.GALLERY, isDir = false))
    }

    @Test
    fun `every set survives being stored and read back`() {
        for (view in TapView.entries) for (p in TapPlan.presets(view)) {
            val set = TapPlan.preset(view, p)!!
            assertEquals("$view $p", set, TapPlan.decode(view, TapPlan.encode(set)))
        }
    }

    @Test
    fun `a stored value that is missing, garbled or not allowed falls back row by row`() {
        assertEquals(TapPlan.default(TapView.LISTS), TapPlan.decode(TapView.LISTS, null))
        assertEquals(TapPlan.default(TapView.LISTS), TapPlan.decode(TapView.LISTS, "nonsense"))
        // Expand is a tree folder's; in Lists it is refused and the default row stays.
        val read = TapPlan.decode(TapView.LISTS, "d:ONE=EXPAND,TWO=DETAILS")
        assertEquals(TapDo.OPEN, read.of(isDir = true, Tap.ONE))
        assertEquals(TapDo.DETAILS, read.of(isDir = true, Tap.TWO))
    }

    private fun p(path: String) = VPath.of("local", path)

    @Test
    fun `Xplorer going back up folds the branch it came out of and keeps the rest`() {
        val down = p("/sdcard/Download")
        val trawl = p("/sdcard/Download/trawl")
        val deeper = p("/sdcard/Download/trawl/x")
        val other = p("/sdcard/Download/other")
        val open = setOf(down, trawl, deeper, other)
        assertEquals(setOf(down, other), TapPlan.foldOnTheWay(open, at = down, was = deeper))
    }

    @Test
    fun `tapping a folder that is not above where you are folds nothing`() {
        val music = p("/sdcard/Music")
        val down = p("/sdcard/Download")
        val open = setOf(music, down)
        assertEquals(open, TapPlan.foldOnTheWay(open, at = music, was = down))
        assertEquals(open, TapPlan.foldOnTheWay(open, at = down, was = down))
        assertEquals(open, TapPlan.foldOnTheWay(open, at = down, was = null))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the counter acts at once at the most it listens for, otherwise after the window`() = runTest {
        val got = mutableListOf<Int>()
        val counter = TapCounter(this, window = 300)
        counter.tap("a", max = 2) { got += it }
        counter.tap("a", max = 2) { got += it }
        assertEquals(listOf(2), got)
        counter.tap("a", max = 3) { got += it }
        runCurrent()
        assertEquals(listOf(2), got)
        advanceTimeBy(301)
        assertEquals(listOf(2, 1), got)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a tap on another item settles the last one first`() = runTest {
        val got = mutableListOf<String>()
        val counter = TapCounter(this, window = 300)
        counter.tap("a", max = 2) { got += "a$it" }
        counter.tap("b", max = 2) { got += "b$it" }
        assertEquals(listOf("a1"), got)
        advanceTimeBy(301)
        assertEquals(listOf("a1", "b1"), got)
    }
}

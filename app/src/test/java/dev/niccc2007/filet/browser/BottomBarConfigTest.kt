package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configurable bottom bar.
 *
 * The interesting cases are all about a stored value that no longer matches the code, because
 * preferences outlive releases and the bar is read at startup. Every one of these has a failure
 * mode worse than the thing it prevents: a crash on launch, or a bar with nothing in it and no
 * way to reach the screen that fixes it.
 */
class BottomBarConfigTest {

    @Test
    fun `a round trip survives`() {
        val bar = listOf(BarItem.FILES, BarItem.SHARING, BarItem.SCRIPTS)
        assertEquals(bar, BottomBarConfig.normalise(BottomBarConfig.encode(bar)))
    }

    @Test
    fun `order is kept, because the order is the configuration`() {
        val bar = listOf(BarItem.SETTINGS, BarItem.FILES, BarItem.SEARCH)
        assertEquals(bar, BottomBarConfig.normalise(BottomBarConfig.encode(bar)))
    }

    // ── a stored value written by a different version ──

    @Test
    fun `an id this version no longer has is dropped, not fatal`() {
        val read = BottomBarConfig.normalise("files,teleport,search")
        assertEquals(listOf(BarItem.FILES, BarItem.SEARCH), read)
    }

    @Test
    fun `a stored value of nothing but unknown ids falls back rather than showing an empty bar`() {
        // An empty strip is unreachable-from: Settings is reached through the bar.
        assertEquals(BottomBarConfig.DEFAULT, BottomBarConfig.normalise("teleport,warp"))
    }

    @Test
    fun `never configured gives the bar it always had`() {
        assertEquals(BottomBarConfig.DEFAULT, BottomBarConfig.normalise(null))
        assertEquals(BottomBarConfig.DEFAULT, BottomBarConfig.normalise(""))
    }

    @Test
    fun `duplicates collapse instead of drawing the same button twice`() {
        assertEquals(listOf(BarItem.FILES, BarItem.SEARCH), BottomBarConfig.normalise("files,search,files"))
    }

    @Test
    fun `whitespace and trailing separators in a hand-edited value are tolerated`() {
        assertEquals(listOf(BarItem.FILES, BarItem.SEARCH), BottomBarConfig.normalise(" files , search , "))
    }

    // ── no cap, it scrolls ──

    @Test
    fun `six fit and seven scroll`() {
        assertFalse(BottomBarConfig.scrolls(6))
        assertTrue(BottomBarConfig.scrolls(7))
    }

    @Test
    fun `there is no upper limit on how many can be added`() {
        // The chosen answer over a hard cap: a cap has to decide which entry gets replaced, and
        // that question has no good answer and no good screen.
        var bar = listOf(BarItem.FILES)
        for (item in BarItem.entries) bar = BottomBarConfig.toggled(bar, item)
        assertTrue("every item should be addable, got ${bar.size}", bar.size >= BarItem.entries.size - 1)
        assertTrue(BottomBarConfig.scrolls(bar.size))
    }

    // ── toggling ──

    @Test
    fun `toggling adds at the end and removes in place`() {
        val bar = listOf(BarItem.FILES, BarItem.SEARCH)
        assertEquals(bar + BarItem.SCRIPTS, BottomBarConfig.toggled(bar, BarItem.SCRIPTS))
        assertEquals(listOf(BarItem.FILES), BottomBarConfig.toggled(bar, BarItem.SEARCH))
    }

    @Test
    fun `the last entry cannot be removed`() {
        // Otherwise the bar empties and Settings is only reachable through the bar.
        val one = listOf(BarItem.FILES)
        assertEquals(one, BottomBarConfig.toggled(one, BarItem.FILES))
    }

    @Test
    fun `every item has a distinct id, or a stored bar would resolve wrongly`() {
        val ids = BarItem.entries.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue("ids must be stable and simple", ids.all { it.isNotEmpty() && it.none { c -> c == ',' } })
    }
}

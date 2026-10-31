package dev.niccc2007.filet.pick

import dev.niccc2007.filet.pick.PickSpec.Want
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the pick's card appears, and what a tap on a file adds to it.
 *
 * Both of these were the same defect from two sides. The pick's chosen set used to BE a pane's
 * selection, which made the card a property of that pane: it was drawn there, it counted what was
 * selected there, and the confirm read from there. So choosing a photo from the Photos tab wrote
 * to nothing the confirm could see, the row did not light up, and walking to that tab in the
 * first place left the card - the only thing that could confirm or cancel - behind in another tab.
 */
class PickCardTest {

    private fun file(name: String) =
        VNode(VPath.of("local", "/sdcard/Pictures/$name"), isDir = false, size = 1, mtime = 0)

    private val files = PickSpec("Choose pictures", Want.FILES, PickFilter.PICTURES)
    private val one = PickSpec("Choose a picture", Want.FILE, PickFilter.PICTURES)
    private val folder = PickSpec("Choose a folder", Want.FOLDER, PickFilter.Everything)

    // ── which pane draws it ───────────────────────────────────────────────────────────────

    @Test fun `with nothing being picked there is no card anywhere`() {
        // The regression this test exists for. While the pane test was "the pick's pane is this
        // pane" it answered false by itself with no pick running, because a null pane is no
        // pane. Rewriting it as "the focused pane" lost that, and the focused pane is always
        // SOME pane - so a chooser appeared over the listing with no caller behind it, asking
        // "Choose a file" on behalf of nobody and answering to nothing.
        for (want in Want.entries) {
            for (pane in listOf("a", "b")) {
                assertFalse(
                    "$want in $pane",
                    PickCard.shownIn(want, anchor = null, focused = "a", pane = pane, inApp = false, external = false),
                )
            }
        }
    }


    @Test fun `a file pick follows the pane being looked at`() {
        // THE defect. Started in the tab that opened for it, answered from the Photos tab: the
        // card has to be in the second one, or the pick has no visible confirm and no cancel.
        assertTrue(PickCard.shownIn(Want.FILES, anchor = "start", focused = "photos", pane = "photos", inApp = true, external = false))
        assertFalse(PickCard.shownIn(Want.FILES, anchor = "start", focused = "photos", pane = "start", inApp = true, external = false))
    }

    @Test fun `a folder pick stays in the pane whose folder it is about`() {
        // The opposite, and for a reason rather than for symmetry: the answer to a folder pick
        // IS this pane's location. Drawn beside a different pane it would name one folder and
        // confirm another.
        assertTrue(PickCard.shownIn(Want.FOLDER, anchor = "dest", focused = "other", pane = "dest", inApp = true, external = false))
        assertFalse(PickCard.shownIn(Want.FOLDER, anchor = "dest", focused = "other", pane = "other", inApp = true, external = false))
    }

    @Test fun `a folder pick whose tab was closed follows the eye instead of vanishing`() {
        // Closing the tab does not end the pick - only the card's X does - so with no anchor
        // left the card must still be somewhere. The alternative is an operation running with
        // nothing on screen that can end it.
        assertTrue(PickCard.shownIn(Want.FOLDER, anchor = null, focused = "other", pane = "other", inApp = true, external = false))
        assertFalse(PickCard.shownIn(Want.FOLDER, anchor = null, focused = "other", pane = "gone", inApp = true, external = false))
    }

    @Test fun `exactly one pane draws the card in split view`() {
        // Two cards for one question is worse than none: each would carry its own confirm and
        // only one of them would be the one that answers.
        for (want in Want.entries) {
            val panes = listOf("a", "b")
            val drawn = panes.count {
                PickCard.shownIn(want, anchor = "a", focused = "b", pane = it, inApp = true, external = false)
            }
            assertEquals("$want", 1, drawn)
        }
    }

    @Test fun `another app's pick is drawn wherever it is asked`() {
        // One pane, and it is this one. `PickActivity` hosts the browser itself, so there is no
        // focused side to compare against and nothing to anchor to.
        assertTrue(PickCard.shownIn(Want.FILES, anchor = null, focused = null, pane = "only", inApp = false, external = true))
    }

    // ── what a tap adds ───────────────────────────────────────────────────────────────────

    @Test fun `a multiple pick accumulates and a second tap takes it back out`() {
        val a = file("a.jpg")
        val b = file("b.jpg")
        val after = files.chosenAfter(files.chosenAfter(emptyList(), a), b)
        assertEquals(listOf(a, b), after)
        // Untapping is the only way back out now. The row is no longer a pane selection with a
        // bar offering to clear it.
        assertEquals(listOf(b), files.chosenAfter(after, a))
    }

    @Test fun `the same file tapped twice is not chosen twice`() {
        val a = file("a.jpg")
        assertEquals(emptyList<VNode>(), files.chosenAfter(listOf(a), a))
    }

    @Test fun `a file is matched by path, not by the node it arrived as`() {
        // Every surface builds its own node for the same file - Home from a recents row with no
        // size, a category from the media store, the listing from a stat - so two nodes for one
        // file are never equal. Comparing nodes would let the same photo be chosen once per
        // screen it was tapped on.
        val fromList = file("a.jpg")
        val fromHome = VNode(fromList.path, isDir = false, size = -1, mtime = 99)
        assertEquals(emptyList<VNode>(), files.chosenAfter(listOf(fromList), fromHome))
    }

    @Test fun `a single-file pick replaces rather than accumulates`() {
        val a = file("a.jpg")
        val b = file("b.jpg")
        assertEquals(listOf(b), one.chosenAfter(listOf(a), b))
    }

    @Test fun `a folder pick takes no files`() {
        // Unreachable through the tap handler, which dims every file for a folder pick. Pinned
        // so it stays unreachable: a folder pick that quietly collected a file would confirm a
        // place while holding something else.
        val a = file("a.jpg")
        assertEquals(emptyList<VNode>(), folder.chosenAfter(emptyList(), a))
    }
}

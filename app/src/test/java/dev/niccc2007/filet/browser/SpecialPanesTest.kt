package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialPanesTest {

    @Test
    fun `every pane kind is either reachable or says why not`() {
        // The Apps tab shipped unreachable on a phone: it was added to the wide-screen rail
        // alone, and the rail exists above 720dp and nowhere else. Every check passed. This is
        // the check that would have failed.
        for (kind in PaneKind.entries) {
            val reachable = kind in SpecialPanes.REACHABLE
            val excused = kind in SpecialPanes.OPENED_ELSEWHERE
            assertTrue(
                "$kind is in neither the destination list nor OPENED_ELSEWHERE - it cannot be opened",
                reachable || excused,
            )
            assertTrue(
                "$kind claims both a destination row and an exemption",
                !(reachable && excused),
            )
        }
    }

    @Test
    fun `an excuse has to say something`() {
        for ((kind, why) in SpecialPanes.OPENED_ELSEWHERE) {
            assertTrue("$kind has a blank reason", why.isNotBlank())
        }
    }

    @Test
    fun `stop filet is pinned, last, where both surfaces draw it`() {
        // Both the switcher and the rail iterate PINNED, so being in this list is being on
        // both surfaces - see check-pinned.mjs for the half of that a test cannot see.
        assertEquals(SpecialPanes.Pinned.STOP, SpecialPanes.PINNED.last())
    }

    @Test
    fun `no pinned entry shares a label with a destination`() {
        val pinned = SpecialPanes.PINNED.map { it.label }
        val destinations = SpecialPanes.DESTINATIONS.map { it.label }
        assertEquals(pinned.size, pinned.toSet().size)
        assertTrue(pinned.intersect(destinations.toSet()).isEmpty())
    }

    @Test
    fun `no destination is declared twice`() {
        val kinds = SpecialPanes.DESTINATIONS.map { it.kind }
        assertEquals(kinds.size, kinds.toSet().size)
    }

    @Test
    fun `every destination has a label`() {
        for (d in SpecialPanes.DESTINATIONS) {
            assertTrue("${d.kind} has a blank label", d.label.isNotBlank())
        }
    }

    @Test
    fun `no two destinations share a label`() {
        // Two rows reading the same word in one list is a list somebody has to tap to
        // disambiguate, which is the opposite of what a navigation surface is for.
        val labels = SpecialPanes.DESTINATIONS.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `every destination has a search decision and a refresh plan`() {
        // Both are exhaustive `when`s, so this cannot fail at runtime - it is here to state
        // that the registry does not replace them, and to fail loudly if either ever grows an
        // `else` branch that would let a kind through undecided.
        for (d in SpecialPanes.DESTINATIONS) {
            searchPlan(d.kind)
            assertTrue("${d.kind} refreshes nothing", refreshPlan(d.kind).targets.isNotEmpty())
        }
    }

    // ── the side bar's folds ──

    @Test
    fun `every destination lives in exactly one fold`() {
        val placed = SpecialPanes.Fold.entries.flatMap { SpecialPanes.inFold(it) }
        assertEquals(SpecialPanes.DESTINATIONS.toSet(), placed.toSet())
        assertEquals(SpecialPanes.DESTINATIONS.size, placed.size)
    }

    @Test
    fun `the full lists are a fold's way out, not a second copy of its rows`() {
        // The redundancy the side bar was redesigned to remove: Bookmarks beside the pinned
        // folders, Opened beside the recent files.
        val seeAll = SpecialPanes.DESTINATIONS.filter { it.seeAll != null }.map { it.kind }.toSet()
        assertEquals(setOf(PaneKind.BOOKMARKS, PaneKind.RECENT), seeAll)
        assertEquals(SpecialPanes.Fold.QUICK, SpecialPanes.DESTINATIONS.first { it.kind == PaneKind.BOOKMARKS }.fold)
        assertEquals(SpecialPanes.Fold.RECENT, SpecialPanes.DESTINATIONS.first { it.kind == PaneKind.RECENT }.fold)
    }

    @Test
    fun `finding, adding and hosting a share are tools, not one more drive`() {
        val tools = SpecialPanes.inFold(SpecialPanes.Fold.TOOLS).map { it.kind }
        assertEquals(
            listOf(PaneKind.REMOTES, PaneKind.NEARBY, PaneKind.APPS, PaneKind.SHORTCUTS, PaneKind.SCRIPTS, PaneKind.SCAN),
            tools,
        )
        assertTrue(SpecialPanes.inFold(SpecialPanes.Fold.DRIVES).isEmpty())
    }

    @Test
    fun `Quick access comes before Recent, and the pinned entries are not a fold`() {
        val folds = SpecialPanes.Fold.entries
        assertTrue(folds.indexOf(SpecialPanes.Fold.QUICK) < folds.indexOf(SpecialPanes.Fold.RECENT))
        assertEquals(5, folds.size)
    }

    @Test
    fun `recent rows carry a short time that fits the end of the row`() {
        val now = 10_000_000_000L
        assertEquals("now", shortAgo(now - 5_000, now))
        assertEquals("5m", shortAgo(now - 5 * 60_000, now))
        assertEquals("3h", shortAgo(now - 3 * 3_600_000L, now))
        assertEquals("2d", shortAgo(now - 2 * 86_400_000L, now))
        assertEquals("", shortAgo(0, now))
    }
}

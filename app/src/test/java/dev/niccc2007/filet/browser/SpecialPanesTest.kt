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
}

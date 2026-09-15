package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refresh button, which for seven rounds did nothing on six of the eleven pane kinds.
 *
 * The old code was `if (s.kind == PaneKind.FOLDER) relist()` and an implicit return. Nothing
 * failed, nothing logged, and the button stayed enabled - so the only way to find out was to
 * be somebody pressing it on the Nearby tab and eventually restarting the app.
 */
class RefreshTest {

    @Test fun every_pane_kind_has_a_plan() {
        // The hole this closes: a kind added later with no branch. A `when` over an enum is
        // exhaustive at compile time, so the real risk is a branch that returns nothing.
        for (kind in PaneKind.entries) {
            val plan = refreshPlan(kind)
            assertTrue("$kind refreshes nothing", plan.targets.isNotEmpty())
            assertTrue("$kind has no label", plan.label.isNotBlank())
        }
    }

    @Test fun a_folder_re_lists_itself() {
        assertEquals(setOf(RefreshTarget.LISTING), refreshPlan(PaneKind.FOLDER).targets)
    }

    @Test fun nearby_re_reads_the_server_so_the_sharing_button_stops_lying() {
        // The specific case: connect from another device, come back, and the button still
        // says Start sharing.
        val targets = refreshPlan(PaneKind.NEARBY).targets
        assertTrue(RefreshTarget.NEARBY in targets)
        assertTrue("a stale peer list is the other half of it", RefreshTarget.NEARBY_SCAN in targets)
    }

    @Test fun home_re_reads_everything_it_actually_shows() {
        val targets = refreshPlan(PaneKind.HOME).targets
        for (needed in listOf(
            RefreshTarget.HOME_FEED,
            RefreshTarget.RECENTS,
            RefreshTarget.VOLUMES,
            RefreshTarget.BOOKMARKS,
        )) {
            assertTrue("Home shows $needed and must re-read it", needed in targets)
        }
    }

    @Test fun the_list_panes_each_re_read_their_own_source() {
        assertTrue(RefreshTarget.SHORTCUTS in refreshPlan(PaneKind.SHORTCUTS).targets)
        assertTrue(RefreshTarget.SCRIPTS in refreshPlan(PaneKind.SCRIPTS).targets)
        assertTrue(RefreshTarget.BOOKMARKS in refreshPlan(PaneKind.BOOKMARKS).targets)
        assertTrue(RefreshTarget.RECENTS in refreshPlan(PaneKind.RECENT).targets)
        assertTrue(RefreshTarget.REMOTES in refreshPlan(PaneKind.REMOTES).targets)
        assertTrue(RefreshTarget.JOBS in refreshPlan(PaneKind.ACTIVITY).targets)
    }

    @Test fun settings_re_reads_the_index_readout_it_displays() {
        assertTrue(RefreshTarget.INDEX_STATUS in refreshPlan(PaneKind.SETTINGS).targets)
    }

    @Test fun no_pane_re_lists_a_folder_it_is_not_showing() {
        // Re-listing on a pane with no cwd is a no-op at best and a stray read at worst.
        for (kind in PaneKind.entries) {
            if (kind == PaneKind.FOLDER) continue
            assertTrue(
                "$kind should not ask for a folder listing",
                RefreshTarget.LISTING !in refreshPlan(kind).targets,
            )
        }
    }

    @Test fun every_target_is_reachable_from_some_pane() {
        // A target nothing asks for is dead code pretending to be a feature - the same rule,
        // pointed the other way.
        val asked = PaneKind.entries.flatMapTo(HashSet()) { refreshPlan(it).targets }
        val unused = RefreshTarget.entries.filterNot { it in asked }
        assertTrue("no pane ever refreshes: $unused", unused.isEmpty())
    }

    @Test fun the_label_names_the_pane_so_a_refresh_is_visible_even_when_nothing_changed() {
        assertEquals("Nearby", refreshPlan(PaneKind.NEARBY).label)
        assertEquals("Home", refreshPlan(PaneKind.HOME).label)
        assertEquals("Settings", refreshPlan(PaneKind.SETTINGS).label)
    }
}
